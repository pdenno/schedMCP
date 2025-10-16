(ns sched-mcp.lg-example
  "Simple LangGraph4j example translated from Java to Clojure.
   Based on 'Your First Graph' example from https://github.com/langgraph4j/langgraph4j

   This version uses idiomatic Clojure with helper functions that hide Java interop."
  (:require
   [sched-mcp.sutil])
  (:import [org.bsc.langgraph4j StateGraph]
           [org.bsc.langgraph4j.state AgentState Channels]
           [org.bsc.langgraph4j.action NodeAction AsyncNodeAction]
           [java.util ArrayList]))

;;; ============================================================================
;;; Utility Functions - Hide Java Interop
;;; ============================================================================

(defn make-appender-channel
  "Create a LangGraph channel that appends values to a list."
  []
  (Channels/appender #(ArrayList.)))

(defn make-schema
  "Create a Java Map schema from a Clojure map.
   Values should be Channel instances."
  [clj-map]
  (java.util.Map/copyOf clj-map))

(defn get-state-value
  "Get a value from agent state, returning a Clojure data structure.
   Returns default-val if key not found."
  ([^AgentState state k]
   (get-state-value state k nil))
  ([^AgentState state k default-val]
   (-> (.value state (name k))
       (.orElse default-val)
       vec))) ; Convert Java list to Clojure vector

(defn make-node-action
  "Wrap a Clojure function as a NodeAction.
   The function receives state as the first argument and should return
   a Clojure map of updates."
  [node-fn]
  (reify NodeAction
    (apply [_ state]
      (node-fn state))))

(defn make-async-node
  "Wrap a Clojure function as an async NodeAction."
  [node-fn]
  (AsyncNodeAction/node_async (make-node-action node-fn)))

(defn make-state-factory
  "Create an AgentStateFactory from a Clojure function.
   The function receives init-data (Java Map) and should return an AgentState."
  [factory-fn]
  (reify org.bsc.langgraph4j.state.AgentStateFactory
    (apply [_ init-data]
      (factory-fn init-data))))

(defn build-graph
  "Build a state graph using Clojure-friendly syntax.
  The StateGraph is the primary class you'll use to define the structure of your application.
  It's where you add nodes and edges to create your graph. It is parameterized by an AgentState.
  ('Parameterized by an AgentState' means that StateGraph is a generic class (in Java terms) where
  the type parameter is AgentState (or a subclass of it)).

  In Java, this would look something like:
    public class StateGraph<S extends AgentState> {
        // ...  }

   Options:
   - :schema - Schema map (will be converted to Java Map)
   - :state-factory - Function to create state from init-data
   - :nodes - Vector of [name node-fn] pairs
   - :edges - Vector of [from to] pairs (use :start and :end for START/END)"
  [{:keys [schema state-factory nodes edges]}]
  (let [java-schema (make-schema schema)
        factory (make-state-factory state-factory)
        graph (StateGraph. java-schema factory)]

    ;; Add nodes
    (doseq [[node-name node-fn] nodes]
      (.addNode graph (name node-name) (make-async-node node-fn)))

    ;; Add edges
    (doseq [[from to] edges]
      (let [from-str (if (= from :start) StateGraph/START (name from))
            to-str (if (= to :end) StateGraph/END (name to))]
        (.addEdge graph from-str to-str)))

    (.compile graph)))

;;; ============================================================================
;;; State Definition - Now Pure Clojure
;;; ============================================================================

(def messages-key :messages)

(def simple-state-schema
  "Schema for the simple state - defines a messages channel that appends values."
  {(name messages-key) (make-appender-channel)})

(defn create-simple-state
  "Create a SimpleState instance from initial data map.
   This is the boundary where we create the Java object."
  [init-data]
  (proxy [AgentState] [init-data]))

;;; ============================================================================
;;; Node Definitions - Pure Clojure Functions
;;; ============================================================================

(defn greeter-node
  "Node that adds a greeting message to the state.
   Pure Clojure function - takes state, returns update map."
  [state]
  (let [current-messages (get-state-value state messages-key)]
    (println "GreeterNode executing. Current messages:" current-messages)
    {(name messages-key) "Hello from GreeterNode!"}))

(defn responder-node
  "Node that responds to the greeting message.
   Pure Clojure function - takes state, returns update map."
  [state]
  (let [current-messages (get-state-value state messages-key)]
    (println "ResponderNode executing. Current messages:" current-messages)
    (if (some #(= % "Hello from GreeterNode!") current-messages)
      {(name messages-key) "Acknowledged greeting!"}
      {(name messages-key) "No greeting found."})))

;;; ============================================================================
;;; Graph Construction and Execution - Idiomatic Clojure
;;; ============================================================================

(defn run-simple-graph
  "Build and run the simple graph example using idiomatic Clojure."
  []
  (let [compiled-graph (build-graph
                        {:schema simple-state-schema
                         :state-factory create-simple-state
                         :nodes [[:greeter greeter-node]
                                 [:responder responder-node]]
                         :edges [[:start :greeter]
                                 [:greeter :responder]
                                 [:responder :end]]})
        initial-state {}]

    (println "\n=== Starting Simple Graph Execution ===\n")

    ;; Stream through the graph
    (doseq [item (.stream compiled-graph initial-state)]
      (println "\nNode:" (.node item))
      (println "State:" (.state item)))

    (println "\n=== Graph Execution Complete ===\n")))

(comment
  ;; Run the example
  (run-simple-graph))
