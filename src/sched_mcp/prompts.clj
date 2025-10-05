(ns sched-mcp.prompts
  "Prompt definitions for the MCP server"
  (:require [clojure.java.io :as io]
            [pogonos.core :as pg]))

(defn create-prompt-from-config
  "Creates a prompt from configuration map.
   Config should have :description, :args, and either :file-path or :content.
   Uses pogonos/mustache templating for the content."
  [prompt-name {:keys [description args file-path content]}]
  (let [;; Prepare arguments structure for MCP
        arguments (mapv (fn [{:keys [name description required?]}]
                          {:name name
                           :description description
                           :required? (boolean required?)})
                        args)
        ;; Get the template content
        template-content (cond
                           content content
                           file-path (let [full-path (if (.isAbsolute (io/file file-path))
                                                       file-path
                                                       (.getCanonicalPath (io/file "." file-path)))]
                                       (when (.exists (io/file full-path))
                                         (slurp full-path)))
                           :else nil)]
    (when template-content
      {:name prompt-name
       :description description
       :arguments arguments
       :prompt-fn (fn [_ request-args clj-result-k]
                    (try
                      ;; Convert string keys to keyword keys for pogonos
                      (let [template-data (into {} (map (fn [[k v]] [(keyword k) v]) request-args))
                            rendered-content (pg/render-string template-content template-data)]
                        (clj-result-k
                         {:description description
                          :messages [{:role :user :content rendered-content}]}))
                      (catch Exception e
                        (clj-result-k
                         {:description (str "Error rendering prompt: " (.getMessage e))
                          :messages [{:role :assistant
                                      :content (str "Failed to render prompt template: " (.getMessage e))}]}))))})))

(defn default-prompts
  "Returns the default prompts as a list."
  [_config-map]
  [;; Put prompts here. See examples/clojure-mcp/src/clojure_mcp/prompts.clj for examples.
   ])

(defn make-prompts
  "Creates all prompts for the MCP server, combining defaults with configuration.
   Config prompts can override defaults by using the same name."
  [config-map]
  (->> config-map
       (:prompts)
       (reduce-kv (fn [res prompt-name info-map]
                     (conj res (create-prompt-from-config prompt-name info-map)))
                  [])
       (filterv identity)
       (into (default-prompts config-map))))
