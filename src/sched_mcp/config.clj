(ns sched-mcp.config
  "NOTE: This should probably not be maintained. With the next upgrade of clojure-mcp, use the one it defines.
   Instead just add :sched-mcp/config to the clojure-mcp config."                                              ; <================
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [sched-mcp.config.schema :as schema]
   [sched-mcp.util :refer [log!]]
   [clojure.edn :as edn]))

(defn- relative-to [dir path]
  (try
    (let [f (io/file path)]
      (if (.isAbsolute f)
        (.getCanonicalPath f)
        (.getCanonicalPath (io/file dir path))))
    (catch Exception e
      (log! :warn (str "Bad file paths " (pr-str [dir path]) " " e))
      nil)))

#_(defn- load-config-file
  "Loads a single config file from the given path. Returns empty map if file doesn't exist."
  [config-file-path]
  (let [config-file (io/file config-file-path)]
    (if (.exists config-file)
      (try
        (edn/read-string (slurp config-file))
        (catch Exception e
          (log! :warn  (str "Failed to read config file:" (.getPath config-file) " " e))
          {}))
      {})))

(defn get-home-config-path
  "Returns the path to the user home config file."
  []
  (io/file (System/getProperty "user.home") ".clojure-mcp" "config.edn"))

#_(defn- load-home-config
  "Loads configuration from ~/.clojure-mcp/config.edn.
   Returns empty map if the file doesn't exist."
  []
  (let [home-config-file (get-home-config-path)]
    (load-config-file (.getPath home-config-file))))

(defn- deep-merge
  "Deeply merges maps, with the second map taking precedence.
   For non-map values, the second value wins."
  [m1 m2]
  (cond
    (and (map? m1) (map? m2))   (merge-with deep-merge m1 m2)
    :else                       m2))

(defn- merge-configs
  "Merges user home config (defaults) with project config (overrides).
   Project config takes precedence over user config."
  [user-config project-config]
  (deep-merge user-config project-config))

(defn validate-configs
  "Validates a sequence of config files with their file paths.
   Takes a sequence of maps with :config and :file-path keys.
   Validates each config sequentially and throws on the first error found.

   Each map should have:
   - :config    - The configuration map to validate
   - :file-path - The path to the config file (for error reporting)

   Throws ExceptionInfo with:
   - :type      ::schema-error
   - :errors    - Validation errors from Malli
   - :config    - The invalid config
   - :file-path - Path to the file with errors (canonical path)"
  [config-files]
  (doseq [{:keys [config file-path]} config-files]
    (when (seq config)
      (when-let [errors (schema/explain-config config)]
        (let [canonical-path (try
                               (.getCanonicalPath (io/file file-path))
                               (catch Exception _ file-path))] ; fallback to original if error
          (throw (ex-info (str "Configuration validation failed: " canonical-path)
                          {:type ::schema-error
                           :errors errors
                           :model schema/Config
                           :config config
                           :file-path canonical-path})))))))

(defn process-config
  "Add some clojure-mcp properties to the merged config file."
  [{:keys [allowed-directories _emacs-notify write-file-guard _cljfmt _bash-over-nrepl _nrepl-env-type] :as config}]
  (let [user-dir (System/getProperty "user.home")
        ud (io/file user-dir)]
    (assert (and (.isAbsolute ud) (.isDirectory ud)))
    (when (some? write-file-guard)
      (when-not (contains? #{:full-read :partial-read false} write-file-guard)
        (log! :warn (str "Invalid write-file-guard value: " write-file-guard " - using default :partial-read"))
        (throw (ex-info (str "Invalid Config: write-file-guard value:  " write-file-guard
                             "- must be one of (:full-read, :partial-read, false)")
                        {:write-file-guard write-file-guard}))))
    (cond-> config
      user-dir (assoc :nrepl-user-dir (.getCanonicalPath ud))
      true
      (assoc :allowed-directories
             (->> (cons user-dir allowed-directories)
                  (keep #(relative-to user-dir %))
                  distinct
                  vec))
      (some? (:emacs-notify config))
      (assoc :emacs-notify (boolean (:emacs-notify config)))
      (some? (:cljfmt config))
      (assoc :cljfmt (boolean (:cljfmt config)))
      (some? (:bash-over-nrepl config))
      (assoc :bash-over-nrepl (boolean (:bash-over-nrepl config)))
      (some? (:nrepl-env-type config))
      (assoc :nrepl-env-type (:nrepl-env-type config)))))

(defn load-and-validate-configs
  "Load config from project directory, validate it, and return processed config.
   Simplified version that doesn't use dialects - just loads config.edn from project root.

   There are possibly two config files used here: one at ~/.clojure-mcp/config.edn, and one at ./config.edn (project root).
   Unlike how this is used with clojure-mcp. I think of the one at project root as being about SchedMCP, and
   the one in the home dir as being about clojure-mcp. Nonetheless, they get merged, so I suppose you can override anything
   in the home directory config with the one in schedMCP."
  [project-dir]
  (let [cmcp-config-path (get-home-config-path)
        cmcp-config-map (if (.exists cmcp-config-path)
                          (-> cmcp-config-path slurp edn/read-string)
                          {:allowed-directories [project-dir]
                           :write-file-guard false})]
    ;; Use canonical paths for consistent error reporting
    (validate-configs
     (cond-> []
       ;; Only validate home config if it exists and has content
       (seq cmcp-config-map)                 (conj {:config cmcp-config-map
                                                    :file-path (.getCanonicalPath cmcp-config-path)})))
    (let [processed-config (process-config cmcp-config-map)]
    ;; Logging for debugging
      (log! :info (str "Final processed config: " (with-out-str (pprint processed-config))))
      processed-config)))

(defn get-config [nrepl-client-map k]
  (get-in nrepl-client-map [::config k]))

#_(defn get-allowed-directories [nrepl-client-map]
  (get-config nrepl-client-map :allowed-directories))

#_(defn get-emacs-notify [nrepl-client-map]
  (get-config nrepl-client-map :emacs-notify))

(defn get-nrepl-user-dir [nrepl-client-map]
  (get-config nrepl-client-map :nrepl-user-dir))

#_(defn get-cljfmt [nrepl-client-map]
  (let [value (get-config nrepl-client-map :cljfmt)]
    (if (nil? value)
      true ; Default to true when not specified
      value)))

#_(defn get-write-file-guard [nrepl-client-map]
  (let [value (get-config nrepl-client-map :write-file-guard)]
    ;; Validate the value and default to :partial-read if invalid
    (cond
      ;; nil means not configured, use default
      (nil? value) :partial-read
      ;; Valid values (including false)
      (contains? #{:full-read :partial-read false} value) value
      ;; Invalid values
      :else (do
              (log! :warn (str "Invalid write-file-guard value: " value " - using default :partial-read"))
              :partial-read))))

#_(defn get-nrepl-env-type
  "Returns the nREPL environment type.
   Defaults to :clj if not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :nrepl-env-type)]
    (if (nil? value)
      :clj ; Default to :clj when not specified
      value)))

#_(defn get-bash-over-nrepl
  "Returns whether bash commands should be executed over nREPL.
   Defaults to true for compatibility."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :bash-over-nrepl)
        nrepl-env-type (get-nrepl-env-type nrepl-client-map)]
    ;; XXX hack so that bash still works in other environments
    (if (nil? value)
      ;; default to the capability
      (dialects/handle-bash-over-nrepl? nrepl-env-type)
      ;; respect configured value
      (boolean value))))

#_(defn clojure-env?
  "Returns true if the nREPL environment is a Clojure environment."
  [nrepl-client-map]
  (= :clj (get-nrepl-env-type nrepl-client-map)))

#_(defn write-guard?
  "Returns true if write-file-guard is enabled (not false).
   This means file timestamp checking is active."
  [nrepl-client-map]
  (not= false (get-write-file-guard nrepl-client-map)))

#_(defn get-scratch-pad-load
  "Returns whether scratch pad persistence is enabled.
   Defaults to false when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :scratch-pad-load)]
    (if (nil? value)
      false ; Default to false when not specified
      (boolean value))))

#_(defn get-scratch-pad-file
  "Returns the scratch pad filename.
   Defaults to 'scratch_pad.edn' when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :scratch-pad-file)]
    (if (nil? value)
      "scratch_pad.edn" ; Default filename
      value)))

#_(defn get-models
  "Returns the models configuration map.
   Defaults to empty map when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :models)]
    (if (nil? value)
      {} ; Default to empty map
      value)))

#_(defn get-tools-config
  "Returns the tools configuration map.
   Defaults to empty map when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :tools-config)]
    (if (nil? value)
      {} ; Default to empty map
      value)))

#_(defn get-tool-config
  "Returns configuration for a specific tool.
   Tool ID can be a keyword or string.
   Returns nil if no configuration exists for the tool."
  [nrepl-client-map tool-id]
  (let [tools-config (get-tools-config nrepl-client-map)
        ;; Normalize tool-id to keyword
        tool-key (keyword tool-id)]
    (get tools-config tool-key)))

#_(defn get-agents-config
  "Returns the agents configuration vector.
   Defaults to empty vector when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :agents)]
    (if (nil? value)
      []
      value)))

#_(defn get-agent-config
  "Returns configuration for a specific agent by ID.
   Agent ID can be a keyword or string.
   Returns nil if no agent with that ID exists."
  [nrepl-client-map agent-id]
  (let [agents (get-agents-config nrepl-client-map)
        ;; Normalize agent-id to keyword
        agent-key (keyword agent-id)]
    (first (filter #(= (:id %) agent-key) agents))))

#_(defn get-mcp-client-hint [nrepl-client-map]
  (get-config nrepl-client-map :mcp-client))

#_(defn get-dispatch-agent-context
  "Returns dispatch agent context configuration.
   Can be:
   - true/false (boolean) - whether to use default code index
   - list of file paths - specific files to load into context
   Defaults to true for backward compatibility."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :dispatch-agent-context)
        user-dir (get-nrepl-user-dir nrepl-client-map)]
    (cond
      (nil? value)
      true ;; Default to true to maintain existing behavior

      (boolean? value)
      value

      (sequential? value)
      ;; Process file paths
      (->> value
           (map (fn [path]
                  (let [file (io/file path)]
                    (if (.isAbsolute file)
                      (.getCanonicalPath file)
                      (.getCanonicalPath (io/file user-dir path))))))
           (filter #(.exists (io/file %)))
           vec)

      :else
      (do
        (log! :warn "Invalid :dispatch-agent-context value, defaulting to true")
        true))))

#_(defn get-enable-tools [nrepl-client-map]
  (get-config nrepl-client-map :enable-tools))

#_(defn get-disable-tools [nrepl-client-map]
  (get-config nrepl-client-map :disable-tools))

#_(defn tool-id-enabled?
  "Check if a tool should be enabled based on :enable-tools and :disable-tools config.

   Logic:
   - If :enable-tools is nil, all tools are enabled (unless in :disable-tools)
   - If :enable-tools is [], no tools are enabled
   - If :enable-tools is provided, only those tools are enabled
   - :disable-tools is then applied to remove tools from the enabled set

   Both config lists can contain strings or keywords - they are normalized to keywords."
  [nrepl-client-map tool-id]
  (let [enable-tools (get-enable-tools nrepl-client-map)
        disable-tools (get-disable-tools nrepl-client-map)
        ;; Normalize tool-id to keyword
        tool-id (keyword tool-id)
        enable-set (when enable-tools (set (map keyword enable-tools)))
        disable-set (when disable-tools (set (map keyword disable-tools)))]
    (cond
      ;; If enable is empty list [], nothing is enabled
      (and (some? enable-tools) (empty? enable-tools)) false

      ;; If enable is nil, all are enabled (unless in disable list)
      (nil? enable-tools) (not (contains? disable-set tool-id))

      ;; If enable is provided, check if tool is in enable list AND not in disable list
      :else (and (contains? enable-set tool-id)
                 (not (contains? disable-set tool-id))))))

#_(defn get-enable-prompts [nrepl-client-map]
  (get-config nrepl-client-map :enable-prompts))

#_(defn get-disable-prompts [nrepl-client-map]
  (get-config nrepl-client-map :disable-prompts))

#_(defn prompt-name-enabled?
  "Check if a prompt should be enabled based on :enable-prompts and :disable-prompts config.

   Logic:
   - If :enable-prompts is nil, all prompts are enabled (unless in :disable-prompts)
   - If :enable-prompts is [], no prompts are enabled
   - If :enable-prompts is provided, only those prompts are enabled
   - :disable-prompts is then applied to remove prompts from the enabled set

   Prompt names are converted to keywords for comparison.
   Both config lists can contain strings or keywords."
  [nrepl-client-map prompt-name]
  (let [enable-prompts (get-enable-prompts nrepl-client-map)
        disable-prompts (get-disable-prompts nrepl-client-map)
        enable-set (when enable-prompts (set enable-prompts))
        disable-set (when disable-prompts (set disable-prompts))]
    (cond
      ;; If enable is empty list [], nothing is enabled
      (and (some? enable-prompts) (empty? enable-prompts)) false

      ;; If enable is nil, all are enabled (unless in disable list)
      (nil? enable-prompts) (not (contains? disable-set prompt-name))

      ;; If enable is provided, check if prompt is in enable list AND not in disable list
      :else (and (contains? enable-set prompt-name)
                 (not (contains? disable-set prompt-name))))))

#_(defn get-enable-resources [nrepl-client-map]
  (get-config nrepl-client-map :enable-resources))

#_(defn get-disable-resources [nrepl-client-map]
  (get-config nrepl-client-map :disable-resources))

#_(defn resource-name-enabled?
  "Check if a resource should be enabled based on :enable-resources and :disable-resources config.

   Logic:
   - If :enable-resources is nil, all resources are enabled (unless in :disable-resources)
   - If :enable-resources is [], no resources are enabled
   - If :enable-resources is provided, only those resources are enabled
   - :disable-resources is then applied to remove resources from the enabled set

   Resource names are used as strings for comparison.
   Both config lists should contain strings."
  [nrepl-client-map resource-name]
  (let [enable-resources (get-enable-resources nrepl-client-map)
        disable-resources (get-disable-resources nrepl-client-map)
        enable-set (when enable-resources (set enable-resources))
        disable-set (when disable-resources (set disable-resources))]
    (cond
      ;; If enable is empty list [], nothing is enabled
      (and (some? enable-resources) (empty? enable-resources)) false

      ;; If enable is nil, all are enabled (unless in disable list)
      (nil? enable-resources) (not (contains? disable-set resource-name))

      ;; If enable is provided, check if resource is in enable list AND not in disable list
      :else (and (contains? enable-set resource-name)
                 (not (contains? disable-set resource-name))))))

(defn get-resources
  "Get the resources configuration map from config"
  [nrepl-client-map]
  (get-config nrepl-client-map :resources))

#_(defn get-prompts
  "Get the prompts configuration map from config"
  [nrepl-client-map]
  (get-config nrepl-client-map :prompts))

#_(defn set-config*
  "Sets a config value in a map. Returns the updated map.
   This is the core function that set-config! uses."
  [nrepl-client-map k v]
  (assoc-in nrepl-client-map [::config k] v))

#_(defn set-config!
  "Sets a config value in an atom containing an nrepl-client map.
   Uses set-config* to perform the actual update."
  [nrepl-client-atom k v]
  (swap! nrepl-client-atom set-config* k v))
