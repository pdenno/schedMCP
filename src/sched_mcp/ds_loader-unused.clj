(ns sched-mcp.ds-loader
  "Discovery Schema loader - loads DS templates from JSON files"
  (:require
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [sched-mcp.util :as util :refer [alog!]]))

(def ^:diag diag (atom nil))

(def ds-base-path "resources/discovery-schemas/")

(defn list-json-files
  "List all JSON files in a directory"
  [dir-path]
  (let [dir (io/file dir-path)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".json"))
           (map #(.getName %))
           sort))))

(defn load-json-file
  "Load and parse a JSON file"
  [file-path]
  (try
    (with-open [reader (io/reader file-path)]
      (json/read reader :key-fn keyword))
    (catch Exception e
      (alog! (str "Failed to load JSON file: " file-path " - " (.getMessage e)) {:level :error})
      nil)))

(defn ds-id-from-filename
  "Convert filename to DS ID keyword
   e.g. 'warm-up-with-challenges.json' -> :process/warm-up-with-challenges"
  [domain filename]
  (keyword domain (-> filename
                      (str/replace ".json" "")
                      (str/replace "_" "-"))))

(defn load-ds-from-file
  "Load a Discovery Schema from a JSON file
   Returns the parsed DS with added metadata"
  [domain filename]
  (let [file-path (str ds-base-path domain "/" filename)
        ds-data (load-json-file file-path)]
    (when ds-data
      (assoc ds-data
             :ds-id (ds-id-from-filename domain filename)
             :source-file file-path
             :domain (keyword domain)))))

(defn list-available-ds
  "List all available Discovery Schemas organized by domain
   Returns a map of domain -> list of DS info"
  []
  (let [domains ["process" "data" "resources" "optimality"]]
    (reduce (fn [acc domain]
              (let [files (list-json-files (str ds-base-path domain))]
                (if (seq files)
                  (assoc acc (keyword domain)
                         (mapv (fn [filename]
                                 {:filename filename
                                  :ds-id (ds-id-from-filename domain filename)
                                  :path (str ds-base-path domain "/" filename)})
                               files))
                  acc)))
            {}
            domains)))

(defn get-ds-by-id
  "Get a Discovery Schema by its ID
   e.g. :process/warm-up-with-challenges"
  [ds-id]
  (let [domain (namespace ds-id)
        name-part (name ds-id)
        filename (str name-part ".json")]
    (load-ds-from-file domain filename)))

(defn get-all-ds
  "Load all Discovery Schemas from all domains
   Returns a map of ds-id -> ds-data"
  []
  (let [available (list-available-ds)]
    (reduce (fn [acc [domain ds-list]]
              (reduce (fn [acc2 ds-info]
                        (if-let [ds-data (load-ds-from-file (name domain) (:filename ds-info))]
                          (assoc acc2 (:ds-id ds-info) ds-data)
                          acc2))
                      acc
                      ds-list))
            {}
            available)))

;; Quick test function
(defn test-loader
  "Test the DS loader with warm-up schema"
  []
  (println "\n=== Testing DS Loader ===\n")

  ;; List available
  (println "Available Discovery Schemas:")
  (let [available (list-available-ds)]
    (doseq [[domain schemas] available]
      (println (str "\n" (name domain) ":"))
      (doseq [schema schemas]
        (println (str "  - " (:ds-id schema) " (" (:filename schema) ")")))))

  ;; Load specific DS
  (println "\n\nLoading :process/warm-up-with-challenges:")
  (when-let [ds (get-ds-by-id :process/warm-up-with-challenges)]
    (println "  Message Type:" (:message-type ds))
    (println "  Interview Objective:" (subs (:interview-objective ds) 0
                                            (min 100 (count (:interview-objective ds)))) "...")
    (println "  EADS structure keys:" (keys (:EADS ds))))

  (println "\n=== Test Complete ==="))

;; Example usage:
;; (require '[sched-mcp.ds-loader :as ds])
;; (ds/test-loader)
;; (ds/list-available-ds)
;; (ds/get-ds-by-id :process/flow-shop)
