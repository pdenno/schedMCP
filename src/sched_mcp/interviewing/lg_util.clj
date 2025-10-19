(ns sched-mcp.interviewing.lg-util
  "Utility functions for working with LangGraph4j from Clojure.
   These functions hide Java interop and provide idiomatic Clojure interfaces."
  (:require [sched-mcp.interviewing.domains]) ; For mount
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

(defn make-reducer-channel
  "Create a LangGraph channel that reduces/merges values.
   reducer-fn should be a 2-arg function: (current-val new-val) -> merged-val

   If the result is a Clojure map, it's converted to java.util.HashMap for LangGraph4j compatibility.
   Other values (keywords, strings, numbers, booleans) are returned as-is.

   Example: (make-reducer-channel merge) for merging maps"
  [reducer-fn]
  (Channels/base
   (reify org.bsc.langgraph4j.state.Reducer
     (apply [_ current-val new-val]
       (let [result (reducer-fn current-val new-val)]
         (if (map? result)
           (let [hmap (java.util.HashMap.)]
             (.putAll hmap result)
             hmap)
           result))))))

(defn make-last-write-channel
  "Create a LangGraph channel that keeps only the last written value.
   This is implemented as a reducer that ignores the current value."
  []
  (make-reducer-channel (fn [_current new-val] new-val)))

(defn make-schema
  "Create a Java Map schema from a Clojure map.
   Converts keyword keys to strings for Java interop.
   Values should be Channel instances.
   Returns a mutable HashMap required by StateGraph constructor."
  [clj-map]
  (let [hmap (java.util.HashMap.)]
    (doseq [[k v] clj-map]
      (.put hmap (name k) v))
    hmap))

(defn get-state-value
  "Get a value from agent state, returning a Clojure data structure.
   Returns default-val if key not found.
   Converts Java ArrayList to Clojure vector for appender channels.
   Recursively converts string keys to keywords for idiomatic Clojure."
  ([^AgentState state k]
   (get-state-value state k nil))
  ([^AgentState state k default-val]
   (letfn [(keywordize-keys [x]
             (cond
               ;; Handle both Clojure maps and Java Maps (including HashMap)
               (or (map? x) (instance? java.util.Map x))
               (into {} (map (fn [[k v]]
                              [(if (string? k) (keyword k) k)
                               (keywordize-keys v)])
                            x))
               (vector? x) (mapv keywordize-keys x)
               (instance? java.util.List x) (mapv keywordize-keys x)
               :else x))]
     (let [val (-> (.value state (name k))
                   (.orElse default-val))]
       (keywordize-keys val)))))

(defn make-node-action
  "Wrap a Clojure function as a NodeAction.
   The function receives state as the first argument and should return
   a Clojure map of updates with keyword keys.
   Recursively converts keyword keys to strings for Java interop."
  [node-fn]
  (letfn [(stringify-keys [x]
            (cond
              (map? x) (into {} (map (fn [[k v]]
                                       [(if (keyword? k) (name k) k)
                                        (stringify-keys v)])
                                     x))
              (vector? x) (mapv stringify-keys x)
              :else x))]
    (reify NodeAction
      (apply [_ state]
        (let [result (node-fn state)]
          ;; Recursively convert keyword keys to string keys for Java
          (stringify-keys result))))))

(defn make-async-node
  "Wrap a Clojure function as an async NodeAction."
  [node-fn]
  (AsyncNodeAction/node_async (make-node-action node-fn)))

(defn make-edge-action
  "Wrap a Clojure function as an EdgeAction.
   The function receives state and should return a string node name."
  [edge-fn]
  (reify org.bsc.langgraph4j.action.EdgeAction
    (apply [_ state]
      (edge-fn state))))

(defn make-async-edge
  "Wrap a Clojure function as an async EdgeAction."
  [edge-fn]
  (org.bsc.langgraph4j.action.AsyncEdgeAction/edge_async
   (make-edge-action edge-fn)))

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
