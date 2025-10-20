(ns sched-mcp.tools.surrogate.core
  "MCP tools for surrogate expert functionality"
  (:require
   [clojure.data.json :as json]
   [sched-mcp.tools.surrogate.sur-util :as suru]
   [sched-mcp.tool-system :as tool-system]
   [sched-mcp.util :refer [alog!]]))

;;; Factory functions for creating tool configurations

(defn create-start-surrogate-tool
  "Creates the tool for starting a surrogate expert interview"
  []
  {:tool-type :start-surrogate})

(defn create-answer-question-tool
  "Creates the tool for getting answers from surrogate expert"
  []
  {:tool-type :answer-question})

;;; Start Surrogate Tool

(defmethod tool-system/tool-name :start-surrogate [_]
  "sur_start_expert")

(defmethod tool-system/tool-description :start-surrogate [_]
  "Start an interview with a surrogate expert agent that simulates a domain expert in manufacturing. The expert will answer questions about their manufacturing processes, challenges, and scheduling needs.")

(defmethod tool-system/tool-schema :start-surrogate [_]
  {:type "object"
   :properties {:domain {:type "string"
                         :description "Manufacturing domain for the surrogate expert (e.g., craft-beer, plate-glass, metal-fabrication, textiles, food-processing, etc.)"}
                :company_name {:type "string"
                               :description "Name of the simulated company (optional)"}
                :project_name {:type "string"
                               :description "Name for the interview project (optional)"}}
   :required ["domain"]})

(defmethod tool-system/validate-inputs :start-surrogate [_ inputs]
  (tool-system/validate-required-params inputs [:domain]))

(defmethod tool-system/execute-tool :start-surrogate
  [_ {:keys [domain company_name project_name]}]
  (alog! (str "sur_start_expert domain=" domain))
  (try
    (let [result (suru/start-surrogate-interview
                  {:domain (keyword domain)
                   :company-name company_name
                   :project-name project_name})]
      {:status "success"
       :message (:message result)
       :project_id (:project-id result)
       :expert_id (:expert-id result)
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

;;; Answer Question Tool

(defmethod tool-system/tool-name :answer-question [_]
  "sur_answer")

(defmethod tool-system/tool-description :answer-question [_]
  "Get an answer from the surrogate expert. The expert will respond as a domain expert would, providing specific details about their manufacturing processes and challenges.")

(defmethod tool-system/tool-schema :answer-question [_]
  {:type "object"
   :properties {:project_id {:type "string"
                             :description "Project ID from sur_start_expert"}
                :question {:type "string"
                           :description "Question to ask the surrogate expert"}}
   :required ["project_id" "question"]})

(defmethod tool-system/validate-inputs :answer-question [_ inputs]
  (tool-system/validate-required-params inputs [:project_id :question]))

(defmethod tool-system/execute-tool :answer-question
  [_ {:keys [project_id question]}]
  (let [pid project_id]
    (alog! (str "sur_answer project_id=" pid))
    (try
      (let [result (suru/surrogate-answer-question
                    {:project-id pid
                     :question question})]
        (if (:error result)
          {:status "error"
           :message (:error result)}
          {:status "success"
           :expert_response (:response result)
           :project_id pid}))
      (catch Exception e
        {:status "error"
         :message (.getMessage e)}))))

(defmethod tool-system/format-results :answer-question [_ result]
  (cond
    (= "error" (:status result))
    {:result [(str "Error: " (:message result))]
     :error true}

    (:expert_response result)
    {:result [(json/write-str
               {:message-type "surrogate-response"
                :expert_response (:expert_response result)
                :project_id (:project_id result)})]
     :error false}

    :else
    {:result [(json/write-str result)]
     :error false}))

;;; Helper to create all surrogate tools

(defn create-sur-tools
  "Create all surrogate tools"
  []
  [(create-start-surrogate-tool)
   (create-answer-question-tool)])
