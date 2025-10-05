(ns sched-mcp.resources
  "MCP resource definitions for schedMCP.
   Provides access to project documentation and dynamic project information."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn create-resources-from-config
  "Creates resources from configuration map.
   Takes a config map where keys are resource names and values contain
   :description, :file-path, and optionally :url and :mime-type.
   Returns a seq of resource maps."
  [resource-name {:keys [description file-path url mime-type]}]
  (let [full-path (if (.isAbsolute (io/file file-path))
                       file-path
                       (.getCanonicalPath (io/file "." file-path)))
        actual-mime-type (or mime-type
                             (file-content/mime-type full-path)
                             "text/plain")
        actual-url (or url
                       (str "custom://"
                            (str/lower-case
                             (str/replace resource-name #"[^a-zA-Z0-9]+" "-"))))]
    (when (.exists (io/file full-path))
      (create-file-resource
       actual-url
       resource-name
       description
       actual-mime-type
       full-path))))

(defn make-resources!
  "Creates all resources for the MCP server from config.edn.
   Resources are defined in config.edn under :resources key."
  [config-map]
  (->> (:resources config-map)
       (reduce-kv (fn [res name info] (conj res (create-resources-from-config name info))) [])
       (filterv identity)))
