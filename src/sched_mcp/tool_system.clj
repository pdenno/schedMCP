(ns sched-mcp.tool-system
  "Core system for defining and registering MCP tools for scheduling domain.
   Adapted from clojure-mcp.tool-system but independent implementation."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [sched-mcp.util :as util :refer [alog!]]))

;;; Tool definition multimethods
;;; Each tool implementation must provide these methods

(defmulti tool-name
  "Return the MCP tool name (string) for this tool configuration"
  :tool-type)

(defmethod tool-name :default [tool-config]
  (throw (ex-info "No tool-name implementation for tool type"
                  {:tool-type (:tool-type tool-config)})))

(defmulti tool-description
  "Return the tool description for MCP"
  :tool-type)

(defmulti tool-schema
  "Return the JSON schema for tool parameters"
  :tool-type)

(defmulti validate-inputs
  "Validate and transform tool inputs. Return validated inputs or throw."
  :tool-type)

(defmethod validate-inputs :default [tool-config inputs]
  ;; Default: just return inputs as-is
  inputs)

(defmulti execute-tool
  "Execute the tool with validated inputs. Return result map."
  :tool-type)

(defmulti format-results
  "Format tool results for MCP response. Return formatted map."
  :tool-type)

(defmethod format-results :default [tool-config results]
  ;; Default: return results as-is
  results)

;;; Helper functions

(defn validate-required-params
  "Check that all required parameters are present"
  [inputs required-params]
  (doseq [param required-params]
    (when-not (get inputs param)
      (throw (ex-info (str "Missing required parameter: " (name param))
                      {:missing-param param
                       :inputs inputs}))))
  inputs)

(defn keywordize-keys
  "Recursively convert map keys to keywords, handling underscores"
  [m]
  (cond
    (map? m) (into {} (map (fn [[k v]]
                             [(keyword (str/replace (name k) "_" "-"))
                              (keywordize-keys v)])
                           m))
    (sequential? m) (mapv keywordize-keys m)
    :else m))

;;; Tool execution wrapper

(defn execute-tool-safe
  "Execute a tool with error handling and logging"
  [tool-config inputs]
  (try
    (alog! (str "Executing tool: " (tool-name tool-config)
                " with inputs: " (pr-str inputs)))
    (let [;; Convert underscore keys to hyphenated keywords
          inputs (keywordize-keys inputs)
          ;; Validate
          validated (validate-inputs tool-config inputs)
          ;; Execute
          results (execute-tool tool-config validated)
          ;; Format
          formatted (format-results tool-config results)]
      (alog! (str "Tool " (tool-name tool-config) " completed successfully"))
      formatted)
    (catch Exception e
      (alog! (str "Tool " (tool-name tool-config) " failed: " (.getMessage e))
             {:level :error})
      {:error (.getMessage e)
       :tool-type (:tool-type tool-config)})))

;;; Tool registration

(defn create-tool-spec
  "Create an MCP tool specification from a tool configuration"
  [tool-config]
  {:name (tool-name tool-config)
   :description (tool-description tool-config)
   :schema (tool-schema tool-config)
   :handler (fn [inputs] (execute-tool-safe tool-config inputs))})

(defn register-tool
  "Register a tool configuration for use with MCP"
  [tool-config]
  (let [spec (create-tool-spec tool-config)]
    (alog! (str "Registered tool: " (:name spec)))
    spec))

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
