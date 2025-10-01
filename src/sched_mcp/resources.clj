(ns sched-mcp.resources
  "MCP resource definitions for schedMCP.
   Provides access to project documentation and dynamic project information."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sched-mcp.config :as config]
            [sched-mcp.file-content :as file-content]))

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

(def default-resources
  "Map of default resources keyed by name.
   Each resource has :url, :description, and :file-path.
   The mime-type will be auto-detected when creating the actual resource."
  {"PROJECT_SUMMARY.md" {:url "custom://project-summary"
                         :description "SchedMCP project summary document providing LLM context about the manufacturing scheduling interview system and its MCP components"
                         :file-path "PROJECT_SUMMARY.md"}

   "README.md" {:url "custom://readme"
                :description "README document for the schedMCP project explaining the manufacturing scheduling interview system architecture and usage"
                :file-path "README.md"}

   "CLAUDE.md" {:url "custom://claude"
                :description "AI coding copilot instructions for working on the schedMCP Clojure project, including coding rules and system design decisions"
                :file-path "CLAUDE.md"}

   "LLM_CODE_STYLE.md" {:url "custom://llm-code-style"
                        :description "Clojure code style guidelines and best practices for LLM-generated code in the schedMCP project"
                        :file-path "LLM_CODE_STYLE.md"}})

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
      ;; If project inspection fails, return nil (no resource)
      nil)))

(defn make-resources
  "Creates all resources for the MCP server, combining defaults with configuration.
   Config resources can override defaults by using the same name."
  [nrepl-client-atom]
  (let [;; Get working directory - fallback to hardcoded if config not available
        working-dir (try
                      (config/get-nrepl-user-dir @nrepl-client-atom)
                      (catch Exception _
                        "/home/pdenno/Documents/git/schedMCP"))

        ;; Create default resources
        default-resources-list (create-resources-from-config
                                default-resources
                                working-dir)

        ;; Get configured resources from config (if any)
        config-resources (try
                           (config/get-resources @nrepl-client-atom)
                           (catch Exception _ nil))
        config-resources-list (when config-resources
                                (create-resources-from-config
                                 config-resources
                                 working-dir))

        ;; Merge resources, with config overriding defaults by name
        all-resources (if config-resources-list
                        (let [config-names (set (map :name config-resources-list))
                              filtered-defaults (remove #(contains? config-names (:name %))
                                                        default-resources-list)]
                          (concat filtered-defaults config-resources-list))
                        default-resources-list)

        ;; Add dynamic project-info resource
        project-info-resource (create-dynamic-project-info nrepl-client-atom)]

    (keep identity (conj (vec all-resources) project-info-resource))))
