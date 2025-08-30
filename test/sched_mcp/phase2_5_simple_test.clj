(ns sched-mcp.phase2-5-simple-test
  "Simple test for Phase 2.5 - Core DS Flow without mount dependencies"
  (:require
   [clojure.edn :as edn]
   [sched-mcp.ds-combine :as combine]
   [sched-mcp.sutil :refer [connect-atm]]
   [datahike.api :as d]))

(defn test-scr-storage
  "Test just the SCR storage and ASCR aggregation"
  []
  (println "\n=== Testing SCR Storage and ASCR Aggregation ===\n")

  ;; 1. Test merge logic for warm-up DS
  (println "1. Testing warm-up DS combine logic...")
  (let [scr1 {:product-or-service-name "craft beer"
              :scheduling-challenges [:tank-capacity :seasonal-demand]}
        scr2 {:scheduling-challenges [:equipment-maintenance]
              :one-more-thing "We struggle with changeover times"}
        scr3 {:scheduling-challenges [:skilled-labor]}]

    ;; Simulate what combine-ds! does
    (let [scrs [scr1 scr2 scr3]
          ;; Use the actual merge logic from warm-up
          ascr (reduce (fn [acc scr]
                         (merge-with (fn [old new]
                                       (cond
                                         ;; Append scheduling challenges
                                         (= :scheduling-challenges (first (keys {old new})))
                                         (distinct (concat (if (coll? old) old [old])
                                                           (if (coll? new) new [new])))
                                         ;; Otherwise take latest
                                         :else new))
                                     acc scr))
                       {}
                       scrs)]
      (println "   SCR 1:" scr1)
      (println "   SCR 2:" scr2)
      (println "   SCR 3:" scr3)
      (println "   ASCR:" ascr)
      (println "   Complete?" (and (seq (:scheduling-challenges ascr))
                                   (some? (:product-or-service-name ascr))
                                   (some? (:one-more-thing ascr))))))

  ;; 2. Test scheduling-problem-type DS
  (println "\n2. Testing scheduling-problem-type DS combine logic...")
  (let [scr1 {:principal-problem-type "FLOW-SHOP-SCHEDULING-PROBLEM"}
        scr2 {:problem-components ["FLOW-SHOP-SCHEDULING-PROBLEM" "TIMETABLING-PROBLEM"]
              :continuous? false}
        scr3 {:cyclical? false}]

    (let [scrs [scr1 scr2 scr3]
          merged (reduce merge {} scrs)
          ;; Apply the DS-specific logic
          ascr (-> merged
                   (update :principal-problem-type #(when % (keyword %)))
                   (update :problem-components
                           (fn [_]
                             (distinct
                              (mapcat (fn [scr]
                                        (let [c (:problem-components scr)]
                                          (cond
                                            (sequential? c) (map keyword c)
                                            (some? c) [(keyword c)]
                                            :else [])))
                                      scrs)))))]
      (println "   SCR 1:" scr1)
      (println "   SCR 2:" scr2)
      (println "   SCR 3:" scr3)
      (println "   ASCR:" ascr)
      (println "   Complete?" (and (some? (:principal-problem-type ascr))
                                   (contains? ascr :continuous?)
                                   (contains? ascr :cyclical?)))))

  (println "\n=== Test Complete ==="))

(comment
  ;; Run the simple test
  (test-scr-storage))