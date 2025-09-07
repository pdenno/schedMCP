(ns sched-mcp.tools.registry
  "Central registry for all schedMCP tools"
  (:require
   ;; Existing tools
   [sched-mcp.tools.iviewr-tools :as itools]
   ;; New tools
   [sched-mcp.sutil :as sutil :refer [connect-atm]]
   [sched-mcp.tools.iviewr.core :as interviewer]
   [sched-mcp.tools.orchestrator.core :as orchestrator]
   [sched-mcp.tools.surrogate :as surrogate]
   [sched-mcp.tool-system :as toolsys]
   [sched-mcp.util :refer [log!]]
   [datahike.api :as d]))

;;; System atom for sharing state between tools
(def system-atom (atom {}))

;;; Helper to convert our tool configs to MCP specs
(defn tool-config->spec
  "Convert our tool configuration to MCP tool spec"
  [tool-config]
  {:name (toolsys/tool-name tool-config)
   :description (toolsys/tool-description tool-config)
   :schema (toolsys/tool-schema tool-config)
   :tool-fn #(toolsys/execute-tool-safe tool-config %)})

;;; Wrap original tools to add DS schema
#_(defn wrap-start-interview
  "Wrap start-interview to ensure DS schema"
  [original-fn]
  (fn [params]
    (let [result (original-fn params)]
      (when (:project_id result)
        (ensure-ds-schema! (keyword (:project_id result))))
      result)))

;;; Build complete tool registry
(defn build-tool-registry
  "Build the complete set of tools for schedMCP"
  []
  (let [;; Create new tools
        interviewer-tools (interviewer/create-interviewer-tools system-atom)
        orchestrator-tools (orchestrator/create-orchestrator-tools system-atom)

        ;; Convert to specs
        new-tool-specs (mapv tool-config->spec
                             (concat interviewer-tools
                                     orchestrator-tools))

        ;; Get original tools and wrap start-interview
        ;;original-specs (mapv (fn [spec]
        ;;                       (if (= (:name spec) "start_interview")
        ;;                        (update spec :tool-fn wrap-start-interview)
        ;;                         spec))
        ;;                     itools/tool-specs)]
        ]
    ;; Combine all tools
    (vec (concat #_original-specs
                 new-tool-specs
                 surrogate/tool-specs))))

;;; Main registry
(def tool-specs
  "All tool specifications for schedMCP"
  (build-tool-registry))
