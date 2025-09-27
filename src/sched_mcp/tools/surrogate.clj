(ns sched-mcp.tools.surrogate
  "MCP tools for surrogate expert functionality"
  (:require
   [sched-mcp.surrogate   :as sur]
   [sched-mcp.tool-system :as tool-system]))

;;; Tool definitions for surrogate expert

(def start-surrogate-tool-spec
  {:name "sur_start_expert"
   :description (str "Start an interview with a surrogate expert agent that simulates "
                     "a domain expert in manufacturing. The expert will answer questions "
                     "about their manufacturing processes, challenges, and scheduling needs.")
   :schema
   {:type "object"
    :properties
    {:domain {:type "string"
              :description "Manufacturing domain for the surrogate expert (e.g., craft-beer, plate-glass, metal-fabrication, textiles, food-processing, etc.)"}
     :company_name {:type "string"
                    :description "Name of the simulated company (optional)"}
     :project_name {:type "string"
                    :description "Name for the interview project (optional)"}}
    :required ["domain"]}

   :tool-fn
   (fn [{:keys [domain company_name project_name]}]
     (try
       (let [result (sur/start-surrogate-interview
                     {:domain (keyword domain)
                      :company-name company_name
                      :project-name project_name})]
         ;; Format for display with orange color indication
         {:status "success"
          :message (:message result)
          :project_id (:project-id result)
          :expert_id (:expert-id result)})
       (catch Exception e
         {:status "error"
          :message (.getMessage e)})))})

(def answer-question-tool-spec
  {:name "sur_answer"
   :description (str "Get an answer from the surrogate expert. The expert will respond "
                     "as a domain expert would, providing specific details about their "
                     "manufacturing processes and challenges.")
   :schema
   {:type "object"
    :properties
    {:project_id {:type "string"
                  :description "Project ID from sur_start_expert"}
     :question {:type "string"
                :description "Question to ask the surrogate expert"}}
    :required ["project_id" "question"]}

   :tool-fn
   (fn [{:keys [project_id question]}]
     (try
       (let [result (sur/surrogate-answer-question
                     {:project-id project_id
                      :question question})]
         (if (:error result)
           {:status "error"
            :message (:error result)}
           {:status "success"
            :expert_response (:response result)}))
       (catch Exception e
         {:status "error"
          :message (.getMessage e)})))})

(defmethod tool-system/format-results :sur-start-expert [_ result]
  (if (:error result)
    {:result [(:message result)]
     :error true}
    {:result [(str "Started surrogate expert for " (:domain result)
                   "\nProject ID: " (:project-id result)
                   "\nExpert ID: " (:expert-id result))]
     :error false}))

;;; Collect all surrogate tools
(def tool-specs
  [start-surrogate-tool-spec
   answer-question-tool-spec])
