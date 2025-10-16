(ns sched-mcp.interviewing.lg-util-test
  "Tests for LangGraph utility functions using the simple greeter/responder example."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sched-mcp.interviewing.lg-util :as lg])
  (:import [org.bsc.langgraph4j.state AgentState]))

;;; ============================================================================
;;; Test State and Nodes
;;; ============================================================================

(def messages-key :messages)

(def simple-state-schema
  "Schema for the simple state - defines a messages channel that appends values."
  {messages-key (lg/make-appender-channel)})

(defn create-simple-state
  "Create a SimpleState instance from initial data map."
  [init-data]
  (proxy [AgentState] [init-data]))

(defn greeter-node
  "Node that adds a greeting message to the state."
  [state]
  (let [current-messages (lg/get-state-value state messages-key)]
    {messages-key "Hello from GreeterNode!"}))

(defn responder-node
  "Node that responds to the greeting message."
  [state]
  (let [current-messages (lg/get-state-value state messages-key)]
    (if (some #(= % "Hello from GreeterNode!") current-messages)
      {messages-key "Acknowledged greeting!"}
      {messages-key "No greeting found."})))

;;; ============================================================================
;;; Tests
;;; ============================================================================

(deftest simple-graph-test
  (testing "Simple greeter/responder graph execution"
    (let [compiled-graph (lg/build-graph
                          {:schema simple-state-schema
                           :state-factory create-simple-state
                           :nodes [[:greeter greeter-node]
                                   [:responder responder-node]]
                           :edges [[:start :greeter]
                                   [:greeter :responder]
                                   [:responder :end]]})
          initial-state {}
          results (vec (.stream compiled-graph initial-state))
          final-state (last results)]

      ;; Should have 4 results: START, greeter, responder, END
      (is (= 4 (count results))
          "Graph should execute all 4 nodes")

      ;; Check node names
      (is (= "__START__" (.node (nth results 0))))
      (is (= "greeter" (.node (nth results 1))))
      (is (= "responder" (.node (nth results 2))))
      (is (= "__END__" (.node (nth results 3))))

      ;; Check final state contains both messages
      (let [final-messages (lg/get-state-value (.state final-state) messages-key)]
        (is (= 2 (count final-messages))
            "Final state should have 2 messages")
        (is (= "Hello from GreeterNode!" (first final-messages))
            "First message should be greeting")
        (is (= "Acknowledged greeting!" (second final-messages))
            "Second message should be acknowledgment")))))

(deftest node-functions-test
  (testing "Individual node functions"
    (testing "greeter-node returns greeting"
      (let [state (create-simple-state {})
            result (greeter-node state)]
        (is (= "Hello from GreeterNode!" (get result messages-key))
            "Greeter should return greeting message")))

    (testing "responder-node acknowledges greeting"
      (let [state (create-simple-state {"messages" ["Hello from GreeterNode!"]})
            result (responder-node state)]
        (is (= "Acknowledged greeting!" (get result messages-key))
            "Responder should acknowledge when greeting present")))

    (testing "responder-node handles missing greeting"
      (let [state (create-simple-state {})
            result (responder-node state)]
        (is (= "No greeting found." (get result messages-key))
            "Responder should indicate when greeting not found")))))
