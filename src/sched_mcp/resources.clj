(ns sched-mcp.resources
  "MCP resource definitions for schedMCP.
   Provides access to project documentation and dynamic project information."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sched-mcp.file-content :as file-content]
            [sched-mcp.schema :as schema]))

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

(defn format-schema-attribute
  "Format a single schema attribute as markdown"
  [attr-name attr-def]
  (let [{:db/keys [cardinality valueType unique doc]} attr-def]
    (str "### `" attr-name "`\n\n"
         (when doc (str doc "\n\n"))
         "- **Type**: `" valueType "`\n"
         "- **Cardinality**: `" cardinality "`\n"
         (when unique (str "- **Unique**: `" unique "`\n"))
         "\n")))

(defn generate-db-schema-doc
  "Generate markdown documentation for the database schema"
  []
  (str
   "# SchedMCP Database Schema Reference\n\n"
   "This document describes the Datahike database schema used by the schedMCP project.\n\n"

   "## Datahike Schema Basics\n\n"
   "Each attribute in the schema is defined with the following properties:\n\n"

   "### `:db/valueType`\n"
   "The data type of the attribute value. Common types:\n"
   "- `:db.type/string` - String values\n"
   "- `:db.type/keyword` - Keyword values\n"
   "- `:db.type/long` - Long integer values\n"
   "- `:db.type/double` - Double precision floating point values\n"
   "- `:db.type/boolean` - Boolean values (true/false)\n"
   "- `:db.type/instant` - Date/time values\n"
   "- `:db.type/ref` - Reference to another entity (creates relationships)\n\n"

   "### `:db/cardinality`\n"
   "How many values this attribute can have:\n"
   "- `:db.cardinality/one` - Single value only\n"
   "- `:db.cardinality/many` - Can have multiple values (stored as a set)\n\n"

   "### `:db/unique`\n"
   "Uniqueness constraint (optional):\n"
   "- `:db.unique/identity` - Value must be unique; can be used to lookup entities\n"
   "- `:db.unique/value` - Value must be unique globally\n\n"

   "### `:db/doc`\n"
   "Human-readable documentation string describing the attribute's purpose.\n\n"

   "---\n\n"

   ;; Include tree structure diagrams
   (try
     (slurp "resources/sched-mcp/db-trees/project-db-tree.md")
     (catch Exception e
       (str "## Project Database Tree Structure\n\n"
            "*Tree diagram not available: " (.getMessage e) "*\n\n")))

   "\n---\n\n"

   (try
     (slurp "resources/sched-mcp/db-trees/system-db-tree.md")
     (catch Exception e
       (str "## System Database Tree Structure\n\n"
            "*Tree diagram not available: " (.getMessage e) "*\n\n")))

   "\n---\n\n"

   "## Project Database Schema Details\n\n"
   "Complete attribute-by-attribute reference for the project database.\n\n"
   (str/join "\n"
             (map (fn [[k v]] (format-schema-attribute k v))
                  (sort-by first schema/db-schema-proj+)))

   "\n## System Database Schema Details\n\n"
   "Complete attribute-by-attribute reference for the system database.\n\n"
   (str/join "\n"
             (map (fn [[k v]] (format-schema-attribute k v))
                  (sort-by first schema/db-schema-sys+)))))

(defn create-db-schema-resource
  "Creates a dynamic resource for the database schema documentation"
  []
  {:url "custom://db-schema"
   :name "DB_SCHEMA.md"
   :description "Datahike database schema reference for project and system databases"
   :mime-type "text/markdown"
   :resource-fn
   (fn [_ _ clj-result-k]
     (try
       (let [schema-doc (generate-db-schema-doc)]
         (clj-result-k [schema-doc]))
       (catch Exception e
         (clj-result-k [(str "Error generating schema documentation: "
                             (ex-message e))]))))})

(defn make-resources!
  "Creates all resources for the MCP server from config.edn.
   Resources are defined in config.edn under :resources key.
   Also includes dynamically generated resources like the DB schema."
  [config-map]
  (let [config-resources (->> (:resources config-map)
                              (reduce-kv (fn [res name info] (conj res (create-resources-from-config name info))) [])
                              (filterv identity))
        dynamic-resources [(create-db-schema-resource)]]
    (into config-resources dynamic-resources)))
