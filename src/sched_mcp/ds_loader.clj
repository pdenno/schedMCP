(ns sched-mcp.ds-loader
  "Enhanced Discovery Schema loader - loads both JSON and CLJ files
   This will eventually replace ds-loader.clj"
  (:require
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [sched-mcp.util :as util :refer [log!]]))

(def ^:diag diag (atom nil))

(def ds-base-path "resources/discovery-schemas/")
(def ds-impl-base-path "examples/schedulingTBD/src/server/scheduling_tbd/iviewr/domain/")

;;; JSON loading (from original ds-loader)

(defn load-json-file
  "Load and parse a JSON file"
  [file-path]
  (try
    (with-open [reader (io/reader file-path)]
      (json/read reader :key-fn keyword))
    (catch Exception e
      (log! :info (str "Failed to load JSON file: " file-path " - " (.getMessage e)) {:level :error})
      nil)))

(defn ds-id-from-filename
  "Convert filename to DS ID keyword"
  [domain filename]
  (keyword domain (-> filename
                      (str/replace ".json" "")
                      (str/replace "_" "-"))))

;;; Clojure implementation loading

(defn find-impl-file
  "Find the matching .clj implementation file for a DS"
  [domain ds-name]
  (let [;; Try exact match first
        exact-path (str ds-impl-base-path domain "/" ds-name ".clj")
        ;; Also try with underscores
        underscore-path (str ds-impl-base-path domain "/"
                             (str/replace ds-name "-" "_") ".clj")]
    (cond
      (.exists (io/file exact-path)) exact-path
      (.exists (io/file underscore-path)) underscore-path
      :else nil)))

(defn load-impl-namespace
  "Dynamically load the namespace for a DS implementation"
  [domain ds-name]
  (when-let [impl-file (find-impl-file domain ds-name)]
    (let [;; Derive namespace from path
          ns-name (str "scheduling-tbd.iviewr.domain."
                       domain "."
                       (str/replace ds-name "-" "_"))
          ns-sym (symbol ns-name)]
      (try
        ;; Check if already loaded
        (when-not (find-ns ns-sym)
          (log! :info (str "Loading namespace: " ns-sym " from " impl-file))
          ;; This is tricky - we'd need to ensure the namespace is on classpath
          ;; For now, we'll document this limitation
          nil)
        {:namespace ns-sym
         :source-file impl-file}
        (catch Exception e
          (log! :info (str "Failed to load implementation: " (.getMessage e)) {:level :error})
          nil)))))

(defn extract-combine-fn
  "Extract the combine-ds! multimethod for a specific DS"
  [ds-id impl-data]
  ;; This would require the namespace to be loaded
  ;; For now, return a placeholder
  (fn [tag pid]
    (log! :info (str "TODO: Implement combine-ds! for " ds-id))
    {}))

(defn extract-complete-fn
  "Extract the ds-complete? multimethod for a specific DS"
  [ds-id impl-data]
  ;; This would require the namespace to be loaded
  ;; For now, return a placeholder
  (fn [tag pid]
    (log! :info (str "TODO: Implement ds-complete? for " ds-id))
    false))

;;; Unified DS loading

(defn load-discovery-schema
  "Load a complete Discovery Schema with both JSON and CLJ components"
  [domain ds-name]
  (let [json-path (str ds-base-path domain "/" ds-name ".json")
        json-data (load-json-file json-path)
        ds-id (ds-id-from-filename domain ds-name)
        ;; Note: Implementation loading is limited without proper namespace loading
        impl-data (load-impl-namespace domain ds-name)]
    (when json-data
      {:ds-id ds-id
       :domain (keyword domain)
       :source-file json-path
       :impl-file (:source-file impl-data)
       ;; JSON data
       :message-type (:message-type json-data)
       :interview-objective (:interview-objective json-data)
       :eads (:EADS json-data)
       ;; Implementation functions (placeholders for now)
       :combine-fn (extract-combine-fn ds-id impl-data)
       :complete-fn (extract-complete-fn ds-id impl-data)})))

(defn get-ds-by-id
  "Get a complete Discovery Schema by ID"
  [ds-id]
  (let [domain (namespace ds-id)
        ds-name (name ds-id)]
    (load-discovery-schema domain ds-name)))

;;; DS metadata and listing

(defn list-json-files
  "List all JSON files in a directory"
  [dir-path]
  (let [dir (io/file dir-path)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".json"))
           (map #(.getName %))
           sort))))

(defn list-available-ds
  "List all available Discovery Schemas organized by domain"
  []
  (let [domains ["process" "data" "resources" "optimality"]]
    (reduce (fn [acc domain]
              (let [files (list-json-files (str ds-base-path domain))]
                (if (seq files)
                  (assoc acc (keyword domain)
                         (mapv (fn [filename]
                                 {:filename filename
                                  :ds-id (ds-id-from-filename domain filename)
                                  :path (str ds-base-path domain "/" filename)
                                  :has-impl (boolean (find-impl-file
                                                      domain
                                                      (str/replace filename ".json" "")))})
                               files))
                  acc)))
            {}
            domains)))

;;; Caching

(def ds-cache (atom {}))

(defn get-cached-ds
  "Get a DS from cache or load it"
  [ds-id]
  (if-let [cached (get @ds-cache ds-id)]
    cached
    (when-let [ds (get-ds-by-id ds-id)]
      (swap! ds-cache assoc ds-id ds)
      ds)))

(defn clear-ds-cache!
  "Clear the DS cache"
  []
  (reset! ds-cache {})
  (log! :info "DS cache cleared"))

;;; For backwards compatibility during transition

(defn load-ds-from-file
  "Legacy function - loads just JSON"
  [domain filename]
  (let [file-path (str ds-base-path domain "/" filename)
        ds-data (load-json-file file-path)]
    (when ds-data
      (assoc ds-data
             :ds-id (ds-id-from-filename domain filename)
             :source-file file-path
             :domain (keyword domain)))))

;; Example usage
(comment
  (list-available-ds)
  (get-ds-by-id :process/warm-up-with-challenges)
  (get-cached-ds :data/orm))
