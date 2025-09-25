(ns sched-mcp.tools.registry
  "Central registry for all schedMCP tools"
  (:require
   [mount.core :as mount         :refer [defstate]]
   [sched-mcp.tools.iviewr.core  :as iviewr]
   [sched-mcp.tools.orch.core    :as orch]
   [sched-mcp.tools.surrogate    :as surrogate]
   [sched-mcp.tool-system        :as toolsys]
   [sched-mcp.util :as util :refer [alog! log!]]))

;;; System atom for sharing state between tools
(def system-atom (atom {}))

;;; nREPL client atom for clojure-mcp tools
(def nrepl-client-atom (atom nil))

;;; Track which tool categories are enabled
(def ^:admin enabled-tools (atom #{:schedmcp :clojure-mcp-all}))

(defmacro with-redirected-out [_diag-num & body]
  `(do
     ;(println "diag-num = " ~diag-num)
     (binding [*out* *err*]
       ~@body)))

;;; Helper to convert our tool configs to MCP specs
(defn tool-config->spec
  "Convert our tool configuration to MCP tool spec"
  [tool-config]
  {:name (toolsys/tool-name tool-config)
   :description (toolsys/tool-description tool-config)
   :schema (toolsys/tool-schema tool-config)
   :tool-fn #(toolsys/execute-tool-safe tool-config %)})

;;; Lazy loading of clojure-mcp tools
(defn ^:admin load-clojure-mcp-tools []
  (try
    (require '[clojure-mcp.tools :as cmcp-tools]
             '[clojure-mcp.nrepl :as cmcp-nrepl])
    ;; Initialize nREPL connection if needed
    (with-redirected-out 1
      (when-not @nrepl-client-atom
        (reset! nrepl-client-atom
                ((resolve 'clojure-mcp.nrepl/create)
                 {:port 7888
                  :clojure-mcp.config/config {:nrepl-user-dir (System/getProperty "user.dir")}}))))
    true
    (catch Exception e
      (log! :error (str "Failed to load clojure-mcp:" (.getMessage e)))
      false)))

(defn ^:admin load-clojure-mcp-prompts []
  (try
    (with-redirected-out 2
      (require '[clojure-mcp.prompts :as cmcp-prompts])
      ((resolve 'cmcp-prompts/make-prompts) nrepl-client-atom))
    (catch Exception e
      (alog! (str "Failed to load clojure-mcp prompts: " (.getMessage e)))
      [])))

(defn ^:admin load-clojure-mcp-resources []
  (try
    (with-redirected-out 3
      (require '[clojure-mcp.resources :as cmcp-resources])
      ((resolve 'cmcp-resources/make-resources nrepl-client-atom)))
    (catch Exception e
      (alog! (str "Failed to load clojure-mcp resources: " (.getMessage e)))
      [])))

;;; Get clojure-mcp tools by category
(defn ^:admin get-clojure-mcp-tools [category]
  (when (load-clojure-mcp-tools)
    (try
      (case category
        :read-only ((resolve 'clojure-mcp.tools/build-read-only-tools) nrepl-client-atom)
        :eval ((resolve 'clojure-mcp.tools/build-eval-tools) nrepl-client-atom)
        :editing ((resolve 'clojure-mcp.tools/build-editing-tools) nrepl-client-atom)
        :agent ((resolve 'clojure-mcp.tools/build-agent-tools) nrepl-client-atom)
        :all ((resolve 'clojure-mcp.tools/build-all-tools) nrepl-client-atom)
        [])
      (catch Exception e
        (log! :error (str "Failed to get clojure-mcp tools:" (.getMessage e)))
        []))))

;;; Build complete tool registry based on enabled categories
(defn build-tool-registry
  "Build the complete set of tools for schedMCP"
  []
  (alog! (str "Building tool registry with enabled-tools: " @enabled-tools))
  (let [;; Always include schedMCP tools
        interviewer-tools (iviewr/create-interviewer-tools system-atom)
        orchestrator-tools (orch/create-orchestrator-tools system-atom)

        ;; Convert to specs
        sched-tool-specs (mapv tool-config->spec
                              (concat interviewer-tools
                                      orchestrator-tools))

        ;; Get enabled clojure-mcp tools
        clojure-mcp-specs (with-redirected-out 4
                            (if (contains? @enabled-tools :clojure-mcp-all)
                              (get-clojure-mcp-tools :all)
                              (vec (concat
                                 (when (contains? @enabled-tools :clojure-mcp-read-only)
                                   (get-clojure-mcp-tools :read-only))
                                 (when (contains? @enabled-tools :clojure-mcp-eval)
                                   (get-clojure-mcp-tools :eval))
                                 (when (contains? @enabled-tools :clojure-mcp-editing)
                                   (get-clojure-mcp-tools :editing))
                                 (when (contains? @enabled-tools :clojure-mcp-agent)
                                   (get-clojure-mcp-tools :agent))))))
        all-tools (vec (concat sched-tool-specs surrogate/tool-specs clojure-mcp-specs))]
    (alog! (str "Total tools registered: " (count all-tools)))
    all-tools))

;;; Admin functions to manage tools
(defn ^:admin enable-clojure-mcp-tools!
  "Enable clojure-mcp tools. Options: :all, :read-only, :eval, :editing, :agent"
  [category]
  (swap! enabled-tools conj (keyword "clojure-mcp" (name category)))
  (log! :info (str "Enabled clojure-mcp " category " tools")))

(defn ^:admin disable-clojure-mcp-tools!
  "Disable clojure-mcp tools"
  [category]
  (swap! enabled-tools disj (keyword "clojure-mcp" (name category)))
  (log! :info (str  "Disabled clojure-mcp " category " tools")))

(defn ^:admin list-enabled-tools []
  @enabled-tools)

;;; Main registry
(def tool-specs
  "All tool specifications for schedMCP"
  (build-tool-registry))

(defn ^:admin refresh-tools!
  "Rebuild the tool registry - call this after enabling/disabling tools"
  []
  (let [new-tools (build-tool-registry)]
    (alog! (str "Refreshing tools. Old count: " (count tool-specs)
               ", new count: " (count new-tools)))
    (alter-var-root #'tool-specs (constantly new-tools))
    (alog! (str "After refresh, tool-specs count: " (count tool-specs)))
    (count tool-specs)))

;;; ----------- Starting and stopping
(defn mount-load-clojure-mcp! []
  (when (contains? @enabled-tools :clojure-mcp-all)
    (load-clojure-mcp-tools))
  (build-tool-registry))

(defstate tool-registry
  :start (mount-load-clojure-mcp!))
