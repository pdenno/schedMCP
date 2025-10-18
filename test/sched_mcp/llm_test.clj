(ns sched-mcp.llm-test
  "Test LLM integration"
  (:require
   [clojure.test :refer [deftest testing is]]
   [sched-mcp.llm :as llm]))

(defn rchat-basic []
  (testing "Whether I can use llm/query-llm for simple interactions with NIST rchat."
    (llm/query-llm
     [{:role "system" :content "You are a helpful assistant."}
      {:role "user"   :content "How many 'r's are there in 'raspberry'?"}]
     :llm-provider :meta)))

(defn rchat-basic-2 []
  (testing "Whether I can use llm/query-llm for simple interactions with NIST rchat."
    (llm/query-llm
     [{:role "system" :content "You are a helpful assistant."}
      {:role "user"   :content (str "Describe a plan for counting the numer of 'r's in the word 'raspberry',\n"
                                    "then execute that plan.")}]
     :llm-provider :meta)))

(defn openai-basic []
  (testing "Whether I can use llm/query-llm for simple interactions with NIST rchat."
    (llm/query-llm
     [{:role "system" :content "You are a helpful assistant."}
      {:role "user"   :content "How many 'r's are there in 'raspberry'?"}]
     :llm-provider :openai)))

(defn openai-basic-2 []
  (testing "Whether I can use llm/query-llm for simple interactions with NIST rchat."
    (llm/query-llm
     [{:role "system" :content "You are a helpful assistant."}
      {:role "user"   :content (str "Describe a plan for counting the numer of 'r's in the word 'raspberry',\n"
                                    "then execute that plan.")}]
     :llm-provider :openai)))

(defn llm-query
  [model-provider text]
  (llm/query-llm
   [{:role "system" :content "You are a helpful assistant."}
    {:role "user"   :content text}]
   :llm-provider model-provider))

;;; NIST Shutdown. Can't test these. ToDo: Write ^:admin function that tests for availability.
;;;(llm-query :meta "How many 'r's are there in 'raspberry'?")
;;;(llm-query :meta "Describe a plan for counting the numer of 'r's in the word 'raspberry', then execute that plan.")
