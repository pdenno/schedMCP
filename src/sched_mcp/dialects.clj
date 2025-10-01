(ns sched-mcp.dialects
  "Handles environment-specific behavior for different nREPL dialects.

   Supports different Clojure-like environments by providing expressions
   and initialization sequences specific to each dialect."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure-mcp.nrepl :as nrepl]))

(defn handle-bash-over-nrepl? [nrepl-env-type]
  (boolean (#{:clj :bb} nrepl-env-type)))

;; Multimethod for getting the expression to fetch project directory
(defmulti fetch-project-directory-exp
  "Returns an expression (string) to evaluate for getting the project directory.
   Dispatches on :nrepl-env-type from config."
  (fn [nrepl-env-type] nrepl-env-type))

(defmethod fetch-project-directory-exp :clj
  [_]
  "(System/getProperty \"user.dir\")")

(defmethod fetch-project-directory-exp :bb
  [_]
  "(System/getProperty \"user.dir\")")

(defmethod fetch-project-directory-exp :basilisp
  [_]
  "(import os)\n(os/getcwd)")

(defmethod fetch-project-directory-exp :default
  [_]
  nil)

;; Multimethod for environment initialization
(defmulti initialize-environment-exp
  "Returns a vector of expressions (strings) to evaluate for initializing
   the environment. These set up necessary namespaces and helpers."
  (fn [nrepl-env-type] nrepl-env-type))

(defmethod initialize-environment-exp :clj
  [_]
  ["(require '[clojure.repl :as repl])"
   "(require 'nrepl.util.print)"])

(defmethod initialize-environment-exp :bb
  [_]
  ["(require '[clojure.repl :as repl])"])

(defmethod initialize-environment-exp :basilisp
  [_]
  ["(require '[basilisp.repl :as repl])"])

(defmethod initialize-environment-exp :default
  [_]
  [])

;; Helper to load REPL helpers - might vary by environment
(defmulti load-repl-helpers-exp
  "Returns expressions for loading REPL helper functions.
   Some environments might not support all helpers."
  (fn [nrepl-env-type] nrepl-env-type))

(defmethod load-repl-helpers-exp :clj
  [_]
  ;; For Clojure, we load the helpers from resources
  [(slurp (io/resource "clojure-mcp/repl_helpers.clj"))
   "(in-ns 'user)"])

(defmethod load-repl-helpers-exp :default
  [_]
  [])

(defmulti fetch-project-directory-helper (fn [nrepl-env-type _] nrepl-env-type))

(defmethod fetch-project-directory-helper :default [nrepl-env-type nrepl-client-map]
  ;; default to fetching from the nrepl
  (when-let [exp (fetch-project-directory-exp nrepl-env-type)]
    (try
      (edn/read-string
       (nrepl/tool-eval-code nrepl-client-map exp))
      (catch Exception e
        (log/warn e "Failed to fetch project directory")
        nil))))

(defmethod fetch-project-directory-helper :scittle [_ nrepl-client-map]
  (when-let [desc (nrepl/describe nrepl-client-map)]
    (some-> desc :aux :cwd io/file (.getCanonicalPath))))

(defn fetch-project-directory
  "Fetches the project directory for the given nREPL client.
   If project-dir is provided in opts, returns it directly.
   Otherwise, evaluates environment-specific expression to get it."
  [nrepl-client-map nrepl-env-type project-dir]
  (if project-dir
    (.getCanonicalPath (io/file project-dir))
    (fetch-project-directory-helper nrepl-env-type nrepl-client-map)))

;; High-level wrapper functions that execute the expressions

(defn initialize-environment
  "Initializes the environment by evaluating dialect-specific expressions.
   Returns the nREPL client map unchanged."
  [nrepl-client-map nrepl-env-type]
  (log/debug "Initializing Clojure environment")
  (when-let [init-exps (not-empty (initialize-environment-exp nrepl-env-type))]
    (doseq [exp init-exps]
      (nrepl/eval-code nrepl-client-map exp identity)))
  nrepl-client-map)

(defn load-repl-helpers
  "Loads REPL helper functions appropriate for the environment."
  [nrepl-client-map nrepl-env-type]
  (when-let [helper-exps (not-empty (load-repl-helpers-exp nrepl-env-type))]
    (doseq [exp helper-exps]
      (nrepl/tool-eval-code nrepl-client-map exp)))
  nrepl-client-map)

(defn detect-nrepl-env-type [nrepl-client-map]
  (when-let [{:keys [versions]} (nrepl/describe nrepl-client-map)]
    (cond
      (get versions :clojure) :clj
      (get versions :babashka) :bb
      (get versions :basilisp) :basilisp
      (get versions :sci-nrepl) :scittle
      :else :unknown)))

;; Future dialect support placeholders
(comment
  ;; ClojureScript on Node
  (defmethod fetch-project-directory-exp :cljs-node
    [_]
    "js/process.cwd()")

  ;; Jank might have C++ interop
  (defmethod fetch-project-directory-exp :jank
    [_]
    "(jank.native/cwd)"))
