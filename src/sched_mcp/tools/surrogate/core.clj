(ns sched-mcp.tools.surrogate.core
  "MCP tools for surrogate expert functionality"
  (:require
   [sched-mcp.tools.surrogate.sur-util :as suru]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.util :refer [log!]]))

;;;----- Start Surrogate Tool-----

(defmethod tool-system/tool-name :start-surrogate [_]
  "start_surrogate_expert")

(defmethod tool-system/tool-description :start-surrogate [_]
  "Start an interview with a surrogate expert agent that simulates a domain expert in manufacturing. The expert will answer questions about their manufacturing processes, challenges, and scheduling needs.")

(defmethod tool-system/tool-schema :start-surrogate [_]
  {:type "object"
   :properties {:domain {:type "string"
                         :description "Manufacturing domain for the surrogate expert (e.g., craft-beer, plate-glass, metal-fabrication, textiles, food-processing, etc.)"}
                :project_name {:type "string"
                               :description "Name for the interview project (optional)"}}
   :required ["domain"]})

(defmethod tool-system/validate-inputs :start-surrogate [_ inputs]
  (tool-system/validate-required-params inputs [:domain]))

(defmethod tool-system/execute-tool :start-surrogate
  [_ {:keys [domain project_name]}]
  (log! :info (str "start_surrogate_expert, domain=" domain))
  (try
    (let [result (suru/start-surrogate-interview {:domain domain :project-name project_name})]
      {:status "success"
       :message (:message result)
       :project_id (:pid result)
       :domain domain})
    (catch Exception e
      {:status "error"
       :message (.getMessage e)})))

(defmethod tool-system/format-results :start-surrogate [_ result]
  (if (= "error" (:status result))
    {:result [(:message result)]
     :error true}
    {:result [(str "Started surrogate expert for " (:domain result)
                   "\nProject ID: " (:project_id result)
                   "\nExpert ID: " (:expert_id result))]
     :error false}))

(defn create-sur-tools
  "Return the tool configuration for each tool in this file."
  []
  [{:tool-type :start-surrogate}])
