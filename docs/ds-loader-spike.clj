(ns sched-mcp.ds.loader
  "Quick spike to test loading Discovery Schemas from JSON files"
  (:require
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [clojure.string :as str]))

(defn load-ds-from-file
  "Load a single DS from a JSON file"
  [file-path]
  (try
    (-> file-path
        io/resource
        slurp
        json/read-str
        ;; Convert JSON keys to keywords
        (update "EADS" #(clojure.walk/keywordize-keys %)))
    (catch Exception e
      (println (str "Error loading DS from " file-path ": " (.getMessage e)))
      nil)))

(defn list-available-ds
  "List all available DS files in a given category"
  [category]
  (let [dir-path (str "discovery-schemas/" category)
        dir (io/resource dir-path)]
    (when dir
      (->> (file-seq (io/file (.getPath dir)))
           (filter #(.endsWith (.getName %) ".json"))
           (map #(.getName %))
           (map #(str/replace % ".json" ""))))))

(defn get-ds-by-id
  "Get a specific DS by its ID (e.g., 'process/warm-up-with-challenges')"
  [ds-id]
  (let [[category ds-name] (str/split ds-id #"/")
        file-path (str "discovery-schemas/" category "/" ds-name ".json")]
    (load-ds-from-file file-path)))

;; Test it
(comment
  ;; List all process DS
  (list-available-ds "process")

  ;; Load the warm-up DS
  (get-ds-by-id "process/warm-up-with-challenges")

  ;; Check the structure
  (-> (get-ds-by-id "process/warm-up-with-challenges")
      (get "EADS")
      keys))
