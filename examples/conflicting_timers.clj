(ns conflicting_timers
  "show how conflicting timers (timeout and sleeps)can produce different results."
  (:require [de.levering-it.missionary-testkit :as mt]
            [missionary.core :as m]))



(defn make-demo-task []
  (m/sp
   (let [!cfg     (atom 2)
         !metrics (atom {:events 0 :alerts 0 :sum 0})
         <cfg    (m/signal (m/watch !cfg))
         >raw (m/stream
               (m/ap
                (loop [i 0]
                  (m/? (m/sleep 50))
                  (m/amb
                   i
                   (recur (inc i))))))

         >annotated (m/stream
                     (m/sample
                      (fn [mult  e]
                        (println "guessed time" (* 50 (inc e)) mult)
                        (let [scaled (* mult e)]
                          scaled))
                      <cfg
                      >raw))

         >metric-snapshots
         (->> >annotated
              (m/eduction)
              (m/reductions + 0)
              (m/eduction (drop 1) (take 14))) ; ignore init, take 25 snapshots

         t-metrics
         (m/reduce (fn [_ snap]
                     (reset! !metrics snap))
                   nil
                   >metric-snapshots)

         t-wiggle
         (m/sp
          (doseq [cfg [3 4 5 6 7]]
            (reset! !cfg cfg)
            (m/? (m/sleep 100))) ; a value of 101 should produce 587 (timing order is well defined)
            ;however, in missionary you can  get different values depending on the execution
            ;speed of each branch. results may very on subsequent runs in missionary
          :wiggle-done)]

     (m/? (m/join vector
                  t-wiggle
                  t-metrics)))))


(comment
  (-> (mt/with-determinism
        (mt/explore-interleavings make-demo-task
                                  {:num-samples 50
                                   :seed 123})))
  )