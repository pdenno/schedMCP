(ns sched-mcp.resources
  "MCP resource definitions for schedMCP.
   Provides access to project documentation and dynamic project information."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sched-mcp.config :as config]
            [sched-mcp.file-content :as file-content]
            [sched-mcp.util :refer [log!]]))

(defn read-file
  "Read a file and return its contents as a string.
   Throws ex-info if file doesn't exist or can't be read."
  [full-path]
  (let [file (io/file full-path)]
    (if (.exists file)
      (try
        (slurp file)
        (catch Exception e
          (throw (ex-info (str "Error reading file: " full-path
                               "\nException: " (.getMessage e))
                          {:path full-path}
                          e))))
      (throw (ex-info (str "File not found: " full-path
                           "\nAbsolute path: " (.getAbsolutePath file))
                      {:path full-path})))))

(defn create-file-resource
  "Creates a resource specification for serving a file.
   Takes a full file path resolved with the correct working directory."
  [url name description mime-type full-path]
  {:url url
   :name name
   :description description
   :mime-type mime-type
   :resource-fn
   (fn [_ _ clj-result-k]
     (try
       (let [result (read-file full-path)]
         (clj-result-k [result]))
       (catch Exception e
         (clj-result-k [(str "Error in resource function: "
                             (ex-message e)
                             "\nFor file: " full-path)]))))})

(defn create-string-resource
  "Creates a resource specification for serving dynamic string content.

   :contents should be a vector of strings"
  [url name description mime-type contents]
  {:url url
   :name name
   :description description
   :mime-type mime-type
   :resource-fn (fn [_ _ clj-result-k]
                  (clj-result-k contents))})

(defn create-resources-from-config
  "Creates resources from configuration map.
   Takes a config map where keys are resource names and values contain
   :description, :file-path, and optionally :url and :mime-type.
   Returns a seq of resource maps."
  [resources-config working-dir]
  (keep
   (fn [[resource-name {:keys [description file-path url mime-type]}]]
     (let [full-path (if (.isAbsolute (io/file file-path))
                       file-path
                       (.getCanonicalPath (io/file working-dir file-path)))
           file (io/file full-path)]
       (when (.exists file)
         (let [actual-mime-type (or mime-type
                                    (file-content/mime-type full-path)
                                    "text/plain")
               actual-url (or url
                              (str "custom://"
                                   (str/lower-case
                                    (str/replace resource-name #"[^a-zA-Z0-9]+" "-"))))]
           (create-file-resource
            actual-url
            resource-name
            description
            actual-mime-type
            full-path)))))
   resources-config))

(defn create-dynamic-project-info
  "Creates a dynamic resource containing Clojure project information.
   Uses clojure_inspect_project tool if available via nREPL."
  [nrepl-client-atom]
  (try
    (require '[clojure-mcp.tools.project.core :as project])
    (let [{:keys [outputs error]} ((resolve 'project/inspect-project) nrepl-client-atom)]
      (when-not error
        (create-string-resource
         "custom://project-info"
         "Clojure Project Info"
         "Information about the schedMCP Clojure project structure, REPL environment, dependencies, and available namespaces"
         "text/markdown"
         outputs)))
    (catch Exception e
      (log! :error (str "create-dynamic-project-info " (.getMessage e)))
      nil)))

(defn make-resources
  "Creates all resources for the MCP server from config.edn.

   Resources are defined in config.edn under :resources key.
   Additionally, adds a dynamic 'Clojure Project Info' resource."
  [nrepl-client-atom]
  (let [;; Get working directory - fallback to hardcoded if config not available
        working-dir (try
                      (config/get-nrepl-user-dir @nrepl-client-atom)
                      (catch Exception _
                        "/home/pdenno/Documents/git/schedMCP"))

        ;; Get resources from config.edn
        config-resources (try
                           (config/get-resources @nrepl-client-atom)
                           (catch Exception e
                             (println "Warning: Failed to get resources from config:" (.getMessage e))
                             nil))

        ;; Create resources from config
        resource-list (if config-resources
                        (create-resources-from-config config-resources working-dir)
                        [])

        ;; Add dynamic project-info resource
        project-info-resource (create-dynamic-project-info nrepl-client-atom)]

    (keep identity (conj (vec resource-list) project-info-resource))))
