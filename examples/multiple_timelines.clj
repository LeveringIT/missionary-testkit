(ns examples.multiple-timelines
  "Demonstrates testing concurrent tasks with multiple timelines,
   random scheduling to explore interleavings, and replay functionality
   to reproduce specific execution orders."
  (:require [de.levering-it.missionary-testkit :as mt]
            [missionary.core :as m]))

;; =============================================================================
;; Example: Read-Modify-Write Race Condition
;; =============================================================================
;; This demonstrates a classic concurrency bug where multiple tasks read a value,
;; compute a new value, then write it back. Without proper synchronization,
;; updates can be lost depending on the interleaving.

(defn make-buggy-counter-task
  "Creates a task with three concurrent timelines that increment a shared counter.
   Each timeline reads the counter, yields (allowing other tasks to run), then writes.
   This read-modify-write pattern is prone to lost updates."
  []
  (let [counter (atom 0)]
    (m/sp
      ;; Three concurrent timelines running in parallel
      (m/? (m/join vector
             ;; Timeline 1: Add 10
             (m/sp
               (let [v @counter]           ; Read current value
                 (m/? (mt/yield))          ; Scheduling point - can be interrupted!
                 (reset! counter (+ v 10)))) ; Write (possibly stale) value

             ;; Timeline 2: Add 20
             (m/sp
               (let [v @counter]
                 (m/? (mt/yield))
                 (reset! counter (+ v 20))))

             ;; Timeline 3: Add 30
             (m/sp
               (let [v @counter]
                 (m/? (mt/yield))
                 (reset! counter (+ v 30))))))

      ;; Return final counter value
      ;; Expected: 60 (10+20+30), but race conditions cause lost updates
      @counter)))

;; =============================================================================
;; Step 1: Explore Different Interleavings
;; =============================================================================
;; Use explore-interleavings to see all possible outcomes

(comment
  ;; Run this to see all unique outcomes
  (mt/with-determinism
    (let [result (mt/explore-interleavings make-buggy-counter-task
                   {:num-samples 100
                    :seed 42})]  ; Fixed seed for reproducibility
      (println "Unique results:" (:unique-results result))
      (println "Seed used:" (:seed result))
      (println "\nAll unique values found:")
      (->> (:results result)
           (map :result)
           distinct
           sort
           (run! #(println "  -" %)))))

  ;; Output:
  ;; Unique results: 3
  ;; Seed used: 42
  ;; All unique values found:
  ;;   - 10   (Timeline 3 wins - all others' writes lost)
  ;;   - 20   (Timeline 2 wins)
  ;;   - 30   (Timeline 1 wins)
  ;;
  ;; Note: 60 is never produced because all three timelines read 0 before any writes!
  )

;; =============================================================================
;; Step 2: Find a Failing Interleaving with check-interleaving
;; =============================================================================
;; Use check-interleaving to find schedules that violate a property

(comment
  ;; Find a schedule where the counter doesn't equal 60
  (def failure-result
    (mt/with-determinism
      (mt/check-interleaving make-buggy-counter-task
        {:num-tests 100
         :seed 42
         :property (fn [v] (= v 60))})))  ; This will fail!

  ;; Examine the failure
  (println "Bug found!")
  (println "Actual value:" (get-in failure-result [:failure :value]))
  (println "Seed:" (:seed failure-result))
  (println "Schedule:" (:micro-schedule failure-result))
  (println "Iteration:" (:iteration failure-result))

  ;; The trace shows exactly what happened
  (println "\nExecution trace:")
  (doseq [event (:trace failure-result)]
    (when (#{:select-task :run-microtask} (:event event))
      (println " " (:event event) "-" (select-keys event [:id :decision :kind]))))
  )

;; =============================================================================
;; Step 3: Replay the Exact Failing Schedule
;; =============================================================================
;; Use replay-schedule to reproduce the exact bug

(comment
  ;; Replay the failing schedule to reproduce the bug deterministically
  (let [failing-schedule (:micro-schedule failure-result)]
    (println "Replaying schedule:" failing-schedule)

    ;; This will always produce the same result
    (mt/with-determinism
      (let [result (mt/replay-schedule (make-buggy-counter-task)
                                       failing-schedule)]
        (println "Reproduced result:" result))))

  ;; You can also replay with tracing to debug
  (mt/with-determinism
    (let [sched (mt/make-scheduler
                  {:micro-schedule (:micro-schedule failure-result)
                   :trace? true})
          result (mt/run sched (make-buggy-counter-task))]
      (println "\nResult:" result)
      (println "\nDetailed trace:")
      (doseq [event (mt/trace sched)]
        (println " " event))))
  )

;; =============================================================================
;; Step 4: Verify the Fix
;; =============================================================================
;; Use swap! instead of read-then-write to fix the race condition

(defn make-fixed-counter-task
  "Fixed version using swap! for atomic read-modify-write."
  []
  (let [counter (atom 0)]
    (m/sp
      (m/? (m/join vector
             (m/sp (m/? (mt/yield)) (swap! counter + 10))
             (m/sp (m/? (mt/yield)) (swap! counter + 20))
             (m/sp (m/? (mt/yield)) (swap! counter + 30))))
      @counter)))

(comment
  ;; Verify the fix works for all interleavings
  (mt/with-determinism
    (let [result (mt/explore-interleavings make-fixed-counter-task
                   {:num-samples 100
                    :seed 42})]
      (println "Unique results:" (:unique-results result))
      (println "Values:" (->> (:results result) (map :result) distinct))))

  ;; Output:
  ;; Unique results: 1
  ;; Values: (60)
  ;;
  ;; All interleavings now produce the correct result!

  ;; Confirm with check-interleaving
  (mt/with-determinism
    (let [result (mt/check-interleaving make-fixed-counter-task
                   {:num-tests 100
                    :seed 42
                    :property (fn [v] (= v 60))})]
      (if (:success result)
        (println "All" (:iterations-run result) "tests passed!")
        (println "Bug found:" (:failure result)))))

  ;; Output: All 100 tests passed!
  )

;; =============================================================================
;; Example: Multiple Timelines with Time-Based Scheduling
;; =============================================================================
;; This example shows concurrent tasks with both yield points and sleeps

(defn make-producer-consumer-task
  "A producer-consumer scenario with multiple timelines."
  []
  (let [buffer (atom [])
        done (atom false)]
    (m/sp
      (m/? (m/join vector
             ;; Producer timeline 1: fast producer
             (m/sp
               (dotimes [i 3]
                 (m/? (m/sleep 10))       ; Small delay
                 (swap! buffer conj [:fast i])
                 (m/? (mt/yield))))       ; Allow consumer to run

             ;; Producer timeline 2: slow producer
             (m/sp
               (dotimes [i 2]
                 (m/? (m/sleep 25))       ; Larger delay
                 (swap! buffer conj [:slow i])
                 (m/? (mt/yield))))

             ;; Consumer timeline
             (m/sp
               (m/? (m/sleep 100))        ; Wait for producers
               (reset! done true))))

      {:buffer @buffer :done @done})))

(comment
  ;; Explore different orderings
  (mt/with-determinism
    (let [result (mt/explore-interleavings make-producer-consumer-task
                   {:num-samples 50
                    :seed 123})]
      (println "Unique buffer orderings:" (:unique-results result))
      (doseq [{:keys [result]} (take 5 (:results result))]
        (println "  Buffer:" (:buffer result)))))
  )

;; =============================================================================
;; Summary
;; =============================================================================
;;
;; Key APIs demonstrated:
;;
;; 1. mt/explore-interleavings - Discover all possible outcomes
;;    - Returns {:unique-results n :results [...] :seed s}
;;    - Always specify :seed for reproducibility
;;
;; 2. mt/check-interleaving - Find schedules that violate a property
;;    - Returns {:success true :seed s} or {:failure {...} :seed s :micro-schedule [...]}
;;    - The :micro-schedule can be used with replay-schedule
;;
;; 3. mt/replay-schedule - Reproduce a specific execution order
;;    - Must be called inside with-determinism
;;    - Takes a task (not task-fn) created inside with-determinism
;;
;; 4. mt/yield - Create scheduling points without time delays
;;    - No-op in production, only affects test scheduling
;;    - Essential for testing concurrent code with interleaving
;;
;; 5. mt/make-scheduler + :trace? true - Debug execution order
;;    - See exactly which tasks ran in what order
;;    - Understand why a particular outcome occurred
