(ns sched-mcp.interviewing.checkpoint
  "Checkpoint persistence for LangGraph interview debugging and replay."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [sched-mcp.util :refer [log! now]]))

(def enable-checkpoints?
  "Atom controlling whether checkpoints are saved.
   Set via REPL: (reset! checkpoint/enable-checkpoints? true)
   Or via system property: -Dcheckpoint.enabled=true"
  (atom (Boolean/parseBoolean (System/getProperty "checkpoint.enabled" "false"))))

(defprotocol Checkpointer
  "Protocol for checkpoint persistence operations."
  (save-checkpoint! [this checkpoint-map]
    "Save a checkpoint to storage. Returns file path or nil.")
  (list-checkpoints [this project-id ds-id]
    "List checkpoint files for a project/DS. Returns seq of file paths.")
  (load-checkpoints [this project-id ds-id]
    "Load all checkpoints for a project/DS. Returns seq of checkpoint maps sorted by timestamp."))

(defn- format-timestamp
  "Format Date to ISO-8601-ish filename-safe string."
  [^java.util.Date date]
  (-> (.toInstant date)
      (.toString)
      (str/replace #":" "-")
      (str/replace #"\." "-")))

(defn- mk-filename
  "Generate checkpoint filename path from checkpoint map.
   Format: data/checkpoints/{project-id}/{ds-id}/{timestamp}-{id}.edn"
  [root-dir {:keys [checkpoint/project-id checkpoint/ds-id
                    checkpoint/timestamp checkpoint/id]}]
  (let [ts-str (format-timestamp timestamp)
        short-id (subs (str id) 0 8)]
    (io/file root-dir
             (name project-id)
             (name ds-id)
             (str ts-str "-" short-id ".edn"))))

(defn- serialize-edn
  "Serialize checkpoint map to EDN string with pretty printing."
  [checkpoint-map]
  (binding [*print-namespace-maps* true
            *print-level* nil
            *print-length* nil]
    (with-out-str
      (pp/pprint checkpoint-map))))

(defn- parse-edn
  "Parse EDN string to checkpoint map."
  [edn-str]
  (edn/read-string edn-str))

(deftype FileCheckpointer [root-dir]
  Checkpointer

  (save-checkpoint! [_this cp]
    (if @enable-checkpoints?
      (try
        (let [file (mk-filename root-dir cp)
              data (serialize-edn cp)]
          (io/make-parents file)
          (spit file data)
          (log! :debug (str "Checkpoint saved: " (.getPath file)))
          (.getPath file))
        (catch Exception e
          (log! :error (str "Failed to save checkpoint: " (.getMessage e)))
          nil))
      nil))

  (list-checkpoints [_this project-id ds-id]
    (let [dir (io/file root-dir (name project-id) (name ds-id))]
      (if (.exists dir)
        (->> (file-seq dir)
             (filter #(and (.isFile %) (str/ends-with? (.getName %) ".edn")))
             (sort-by #(.getName %))
             (mapv #(.getPath %)))
        [])))

  (load-checkpoints [this project-id ds-id]
    (let [files (list-checkpoints this project-id ds-id)]
      (mapv (fn [file-path]
              (try
                (-> file-path slurp parse-edn)
                (catch Exception e
                  (log! :error (str "Failed to load checkpoint " file-path ": " (.getMessage e)))
                  nil)))
            files))))

(defn file-checkpointer
  "Create a file-based checkpointer.
   Respects the enable-checkpoints? atom.
   root-dir defaults to data/checkpoints"
  ([] (file-checkpointer "data/checkpoints"))
  ([root-dir] (FileCheckpointer. root-dir)))

(defn make-checkpoint
  "Create a checkpoint map from interview state and metadata.

   Required keys:
   - :state - InterviewState or state map
   - :project-id - Project ID
   - :ds-id - Discovery Schema ID
   - :iteration - Iteration number
   - :thread-id - Thread ID for LangGraph

   Optional keys:
   - :parent-id - Parent checkpoint ID
   - :metadata - Additional metadata map"
  [{:keys [state project-id ds-id iteration thread-id parent-id metadata]}]
  (let [id (str (java.util.UUID/randomUUID))
        timestamp (now)
        process-id (.pid (ProcessHandle/current))]
    (cond-> {:checkpoint/id id
             :checkpoint/timestamp timestamp
             :checkpoint/project-id project-id
             :checkpoint/ds-id ds-id
             :checkpoint/iteration iteration
             :checkpoint/thread-id thread-id
             :checkpoint/process-id process-id
             :checkpoint/state state}
      parent-id (assoc :checkpoint/parent-id parent-id)
      metadata (assoc :checkpoint/metadata metadata))))

(defn latest-checkpoint
  "Get the most recent checkpoint from a seq of checkpoints."
  [checkpoints]
  (when (seq checkpoints)
    (->> checkpoints
         (sort-by :checkpoint/timestamp)
         last)))

(defn checkpoint-at-iteration
  "Get checkpoint at specific iteration number."
  [checkpoints iteration]
  (->> checkpoints
       (filter #(= iteration (:checkpoint/iteration %)))
       first))

(defn ^:diag inspect-checkpoint
  "Pretty-print a checkpoint for debugging.
   Options:
   - :show-state? - Whether to show full state (default false)"
  ([checkpoint] (inspect-checkpoint checkpoint {}))
  ([checkpoint {:keys [show-state?] :or {show-state? false}}]
   (let [summary (dissoc checkpoint :checkpoint/state)]
     (println "=== Checkpoint Summary ===")
     (pp/pprint summary)
     (when show-state?
       (println "\n=== Full State ===")
       (pp/pprint (:checkpoint/state checkpoint))))))

(defn ^:diag enable-checkpointing!
  "Enable checkpoint saving. Convenience function for REPL use."
  []
  (reset! enable-checkpoints? true)
  (log! :info "Checkpointing ENABLED"))

(defn ^:diag disable-checkpointing!
  "Disable checkpoint saving. Convenience function for REPL use."
  []
  (reset! enable-checkpoints? false)
  (log! :info "Checkpointing DISABLED"))
