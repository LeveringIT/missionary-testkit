(ns examples.manual-schedule
  "Demonstrates manual schedule modification for deterministic replay.

  This example shows Use Case 3: Run a task, inspect the schedule (as task IDs),
  manually edit the order, and replay with the modified schedule.

  Key functions:
  - mt/trace->schedule - extracts task IDs from trace
  - mt/replay-schedule - replays with a given schedule
  - mt/next-tasks      - shows available tasks for manual stepping
  - (mt/step! sched id) - executes specific task by ID"
  (:require [missionary.core :as m]
            [de.levering-it.missionary-testkit :as mt]))

;; =============================================================================
;; The Task Under Test
;; =============================================================================

;; A simple concurrent task where 3 workers update a shared result atom.
;; The final result depends on the order of execution.

(defn make-task
  "Returns a task where 3 workers concurrently update a shared result.
  Different interleavings produce different results."
  []
  (let [result (atom [])]
    (m/sp
      (m/? (m/join vector
                   ;; Worker A: yields twice
                   (m/sp
                     (swap! result conj :a1)
                     (m/? (mt/yield))
                     (swap! result conj :a2)
                     :a-done)
                   ;; Worker B: yields twice
                   (m/sp
                     (swap! result conj :b1)
                     (m/? (mt/yield))
                     (swap! result conj :b2)
                     :b-done)
                   ;; Worker C: yields twice
                   (m/sp
                     (swap! result conj :c1)
                     (m/? (mt/yield))
                     (swap! result conj :c2)
                     :c-done)))
      {:final-order @result
       :length (count @result)})))

;; =============================================================================
;; Use Case 3: Run → Inspect Schedule → Edit → Replay
;; =============================================================================

(defn demo-run-inspect-edit-replay []
  (println "=== Use Case 3: Run → Inspect → Edit → Replay ===\n")

  (mt/with-determinism
    ;; Step 1: Run with tracing enabled, use a seed for random interleaving
    (println "Step 1: Run with random interleaving (seed 42)")
    (let [sched (mt/make-scheduler {:trace? true :seed 42})]
      (let [result (mt/run sched (make-task))]
        (println "Result:" result)
        (println "Final order:" (:final-order result))

        ;; Step 2: Extract the schedule (just task IDs)
        (let [original-schedule (mt/trace->schedule (mt/trace sched))]
          (println "\nStep 2: Extracted schedule (task IDs):" original-schedule)

          ;; Step 3: Manually modify the schedule
          ;; Let's reverse the order to see a different interleaving
          (let [modified-schedule (vec (reverse original-schedule))]
            (println "\nStep 3: Modified schedule (reversed):" modified-schedule)

            ;; Step 4: Replay with the modified schedule
            (println "\nStep 4: Replay with modified schedule")
            (let [replay-result (mt/replay-schedule (make-task) modified-schedule)]
              (println "Replay result:" replay-result)
              (println "Replay final order:" (:final-order replay-result))

              ;; Show the difference
              (println "\n--- Comparison ---")
              (println "Original order:" (:final-order result))
              (println "Modified order:" (:final-order replay-result))
              (println "Same result?" (= (:final-order result) (:final-order replay-result))))))))))

;; =============================================================================
;; Use Case 2: Manual Stepping with next-tasks and step!
;; =============================================================================

(defn demo-manual-stepping []
  (println "\n=== Use Case 2: Manual Stepping ===\n")

  (mt/with-determinism
    (let [sched (mt/make-scheduler {:trace? true})
          result (atom [])]

      ;; Start a concurrent task
      (mt/start! sched
                 (m/sp
                   (m/? (m/join vector
                                (m/sp
                                  (swap! result conj :a1)
                                  (m/? (mt/yield))
                                  (swap! result conj :a2)
                                  :a)
                                (m/sp
                                  (swap! result conj :b1)
                                  (m/? (mt/yield))
                                  (swap! result conj :b2)
                                  :b)))))

      ;; Step 1: See what tasks are available
      (println "Initial state, available tasks:")
      (let [tasks (mt/next-tasks sched)]
        (println "  Tasks:" (mapv #(select-keys % [:id :kind]) tasks)))

      ;; Step 2: Execute first available (FIFO)
      (println "\nStep through all tasks, choosing last available each time:")
      (loop [step-num 1]
        (let [tasks (mt/next-tasks sched)]
          (when (seq tasks)
            ;; Pick the LAST task (non-FIFO order)
            (let [chosen-task (last tasks)
                  chosen-id (:id chosen-task)]
              (println (str "  Step " step-num ": Available IDs " (mapv :id tasks)
                            " → choosing " chosen-id))
              (mt/step! sched chosen-id)
              (recur (inc step-num))))))

      ;; Show result
      (println "\nFinal result:" @result)

      ;; Extract the schedule we created
      (let [schedule (mt/trace->schedule (mt/trace sched))]
        (println "Schedule we created:" schedule)))))

;; =============================================================================
;; Example: Find All Unique Interleavings
;; =============================================================================

(defn demo-explore-and-pick []
  (println "\n=== Explore Interleavings and Pick One ===\n")

  (mt/with-determinism
    ;; Explore different interleavings
    (let [exploration (mt/explore-interleavings make-task
                                                 {:num-samples 20
                                                  :seed 1})]
      (println "Found" (:unique-results exploration) "unique results from 20 samples")

      ;; Group by result
      (let [by-result (group-by #(get-in % [:result :final-order]) (:results exploration))]
        (println "\nUnique outcomes:")
        (doseq [[order samples] (take 3 by-result)]
          (println "  " order)
          (println "    First schedule:" (:micro-schedule (first samples))))

        ;; Pick an interesting schedule and replay it
        (when-let [[order samples] (first by-result)]
          (let [schedule (:micro-schedule (first samples))]
            (println "\nReplaying first unique outcome:")
            (println "  Schedule:" schedule)
            (let [replayed (mt/replay-schedule (make-task) schedule)]
              (println "  Result:" (:final-order replayed)))))))))

;; =============================================================================
;; Example: Custom Schedule Construction
;; =============================================================================

(defn demo-custom-schedule []
  (println "\n=== Custom Schedule Construction ===\n")

  (mt/with-determinism
    ;; First, run once to discover what task IDs exist
    (println "Step 1: Discovery run (FIFO) to find task IDs")
    (let [sched (mt/make-scheduler {:trace? true})]
      (mt/run sched (make-task))
      (let [fifo-schedule (mt/trace->schedule (mt/trace sched))]
        (println "  FIFO schedule:" fifo-schedule)

        ;; Now construct a custom schedule
        ;; (In real use, you'd inspect the trace to understand which ID does what)
        (println "\nStep 2: Create custom schedules")

        ;; Schedule A: Execute in FIFO order (same as default)
        (let [schedule-a fifo-schedule
              result-a (mt/replay-schedule (make-task) schedule-a)]
          (println "  Schedule A (FIFO):" schedule-a "→" (:final-order result-a)))

        ;; Schedule B: Reverse order
        (let [schedule-b (vec (reverse fifo-schedule))
              result-b (mt/replay-schedule (make-task) schedule-b)]
          (println "  Schedule B (reversed):" schedule-b "→" (:final-order result-b)))

        ;; Schedule C: Interleaved (take every other, then remaining)
        (let [evens (take-nth 2 fifo-schedule)
              odds (take-nth 2 (rest fifo-schedule))
              schedule-c (vec (concat evens odds))]
          (when (= (count schedule-c) (count fifo-schedule))
            (let [result-c (mt/replay-schedule (make-task) schedule-c)]
              (println "  Schedule C (interleaved):" schedule-c "→" (:final-order result-c)))))))))

;; =============================================================================
;; Run All Examples
;; =============================================================================

(defn run-examples []
  (demo-run-inspect-edit-replay)
  (demo-manual-stepping)
  (demo-explore-and-pick)
  (demo-custom-schedule))

(comment
  ;; Run all examples
  (run-examples)

  ;; Or run individually
  (demo-run-inspect-edit-replay)
  (demo-manual-stepping)
  (demo-explore-and-pick)
  (demo-custom-schedule)

  ;; Quick REPL exploration
  (mt/with-determinism
    (let [sched (mt/make-scheduler {:trace? true :seed 123})]
      (mt/run sched (make-task))
      (mt/trace->schedule (mt/trace sched))))
  ;; => [2 4 6 3 5 7]  ; example output - actual IDs depend on implementation
  )
