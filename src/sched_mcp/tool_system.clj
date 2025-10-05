(ns sched-mcp.tool-system
  "Core system for defining and registering MCP tools for scheduling domain.
   Adapted from clojure-mcp.tool-system but independent implementation."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]))

;; Core multimethods for tool behavior

(defmulti tool-name
  "Returns the name of the tool as a string. Dispatches on :tool-type."
  :tool-type)

(defmethod tool-name :default [tool-config]
  (-> tool-config
      :tool-type
      name
      (str/replace "-" "_")))

(defmulti tool-id :tool-type)

(defmethod tool-id :default [tool-config]
  (keyword (tool-name tool-config)))

(defmulti tool-description
  "Returns the description of the tool as a string. Dispatches on :tool-type."
  :tool-type)

(defmulti tool-schema
  "Returns the parameter validation schema for the tool. Dispatches on :tool-type."
  :tool-type)

(defmulti validate-inputs
  "Validates inputs against the schema and returns validated/coerced inputs.
   Throws exceptions for invalid inputs.
   Dispatches on :tool-type in the tool-config."
  (fn [tool-config _inputs] (:tool-type tool-config)))

(defmulti execute-tool
  "Executes the tool with the validated inputs and returns the result.
   Dispatches on :tool-type in the tool-config."
  (fn [tool-config _inputs] (:tool-type tool-config)))

(defmulti format-results
  "Formats the results from tool execution into the expected MCP response format.
   Must return a map with :result (a vector or sequence of strings) and :error (boolean).
   The MCP protocol requires that results are always provided as a sequence of strings,
   never as a single string.

   This standardized format is then used by the tool-fn to call the callback with:
   (callback (:result formatted) (:error formatted))

   Dispatches on :tool-type in the tool-config."
  (fn [tool-config _result] (:tool-type tool-config)))

;; Multimethod to assemble the registration map

(defmulti registration-map
  "Creates the MCP registration map for a tool.
   Dispatches on :tool-type."
  :tool-type)

;; Function to handle java.util.Map and other collection types before keywordizing
(defn convert-java-collections
  "Converts Java collection types to their Clojure equivalents recursively."
  [x]
  (clojure.walk/prewalk
   (fn [node]
     (cond
       (instance? java.util.Map node) (into {} node)
       (instance? java.util.List node) (into [] node)
       (instance? java.util.Set node) (into #{} node)
       :else node))
   x))

;; Helper function to keywordize map keys while preserving underscores
(defn keywordize-keys-preserve-underscores
  "Recursively transforms string map keys into keywords.
   Unlike clojure.walk/keywordize-keys, this preserves underscores.
   Works with Java collection types by converting them first."
  [m]
  (walk/keywordize-keys (convert-java-collections m)))

;; Default implementation for registration-map
(defmethod registration-map :default [tool-config]
  {:name (tool-name tool-config)
   :id (tool-id tool-config)
   :description (tool-description tool-config)
   :schema (tool-schema tool-config)
   :tool-fn (fn [_ params callback]
              (try
                (let [keywordized-params (keywordize-keys-preserve-underscores params)
                      validated (validate-inputs tool-config keywordized-params)
                      result (execute-tool tool-config validated)
                      formatted (format-results tool-config result)]
                  (callback (:result formatted) (:error formatted)))
                (catch Exception e
                  (log/error e)
                  ;; On error, create a sequence of error messages
                  (let [error-msg (or (ex-message e) "Unknown error")
                        data (ex-data e)
                        ;; Construct error messages sequence
                        error-msgs (cond-> [error-msg]
                                     ;; Add any error-details from ex-data if available
                                     (and data (:error-details data))
                                     (concat (if (sequential? (:error-details data))
                                               (:error-details data)
                                               [(:error-details data)])))]
                    (callback error-msgs true)))))})

;;; Common schemas
(def project-id-schema
  {:type "string"
   :description "The project ID returned from start_interview"})

(def conversation-id-schema
  {:type "string"
   :description "The conversation ID for this interview session"})

(def ds-id-schema
  {:type "string"
   :description "Discovery Schema ID (e.g., 'process/warm-up-with-challenges')"})

;;; Stuff particularly for sched-mcp
(defn validate-required-params
  "Check that all required parameters are present"
  [inputs required-params]
  (doseq [param required-params]
    (when-not (get inputs param)
      (throw (ex-info (str "Missing required parameter: " (name param))
                      {:missing-param param
                       :inputs inputs}))))
  inputs)
