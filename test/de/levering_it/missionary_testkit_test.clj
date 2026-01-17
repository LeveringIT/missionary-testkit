(ns de.levering-it.missionary-testkit-test
  (:require [clojure.test :refer [deftest is testing]]
            [de.levering-it.missionary-testkit :as mt]
            [missionary.core :as m])
  (:import (missionary Cancelled)))

;; =============================================================================
;; Scheduler Creation and Basic Operations
;; =============================================================================

(deftest make-scheduler-test
  (testing "creates scheduler with default options"
    (let [sched (mt/make-scheduler)]
      (is (some? sched))
      (is (= 0 (mt/now-ms sched)))
      (is (nil? (mt/trace sched)))
      (is (= {:microtasks [] :timers []} (mt/pending sched)))))

  (testing "creates scheduler with custom initial time"
    (let [sched (mt/make-scheduler {:initial-ms 1000})]
      (is (= 1000 (mt/now-ms sched)))))

  (testing "creates scheduler with tracing enabled"
    (let [sched (mt/make-scheduler {:trace? true})]
      (is (vector? (mt/trace sched)))
      (is (empty? (mt/trace sched))))))

(deftest step-and-tick-test
  (testing "step! returns idle when no microtasks"
    (let [sched (mt/make-scheduler)]
      (is (= ::mt/idle (mt/step! sched)))))

  (testing "tick! returns 0 when no microtasks"
    (let [sched (mt/make-scheduler)]
      (is (= 0 (mt/tick! sched))))))

(deftest next-event-test
  (testing "returns nil when idle"
    (let [sched (mt/make-scheduler)]
      (is (nil? (mt/next-event sched)))))

  (testing "returns microtask info when microtask queued"
    (let [sched (mt/make-scheduler)]
      (mt/start! sched (mt/yield :done) {:label "test-yield"})
      (let [ev (mt/next-event sched)]
        (is (= :microtask (:type ev)))
        (is (some? (:kind ev)))
        (is (some? (:id ev))))))

  (testing "returns timer info when only timer pending"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (mt/start! sched (mt/sleep 100 :done) {:label "test-sleep"})
        (let [ev (mt/next-event sched)]
          (is (= :timer (:type ev)))
          (is (= :sleep (:kind ev)))
          (is (= 100 (:at-ms ev)))))))

  (testing "prefers microtask over timer"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        ;; Start a sleep then add a yield - both pending
        (mt/start! sched (mt/sleep 100 :done) {})
        (mt/start! sched (mt/yield :immediate) {})
        ;; Should show microtask (yield), not timer
        (let [ev (mt/next-event sched)]
          (is (= :microtask (:type ev))))))))

(deftest advance-test
  (testing "advance-to! moves time forward"
    (let [sched (mt/make-scheduler)]
      (mt/advance-to! sched 100)
      (is (= 100 (mt/now-ms sched)))))

  (testing "advance! adds to current time"
    (let [sched (mt/make-scheduler {:initial-ms 50})]
      (mt/advance! sched 100)
      (is (= 150 (mt/now-ms sched)))))

  (testing "advance-to! throws on negative time jump"
    (let [sched (mt/make-scheduler {:initial-ms 100})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"advance-to! requires t >= now"
                            (mt/advance-to! sched 50)))))

  (testing "advance! throws on negative delta"
    (let [sched (mt/make-scheduler)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-negative"
                            (mt/advance! sched -10))))))

;; =============================================================================
;; Sleep Tests
;; =============================================================================

(deftest sleep-test
  (testing "sleep completes after virtual time advances"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched (mt/sleep 100 :done) {:label "sleep-test"})]
          (is (not (mt/done? job)))
          (is (= 1 (count (:timers (mt/pending sched)))))
          (mt/advance-to! sched 100)
          (is (mt/done? job))
          (is (= :done (mt/result job)))))))

  (testing "sleep with nil result"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched (mt/sleep 50) {})]
          (mt/advance-to! sched 50)
          (is (mt/done? job))
          (is (nil? (mt/result job)))))))

  (testing "cancelled sleep throws Cancelled"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched (mt/sleep 100 :done) {:label "cancel-test"})]
          (mt/cancel! job)
          (mt/tick! sched)
          (is (mt/done? job))
          (is (thrown? Cancelled (mt/result job))))))))

;; =============================================================================
;; Timeout Tests
;; =============================================================================

(deftest timeout-test
  (testing "timeout passes through when inner task completes first"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched
                             (mt/timeout (mt/sleep 50 :inner) 100 :timed-out)
                             {:label "timeout-pass"})]
          (mt/advance-to! sched 50)
          (is (mt/done? job))
          (is (= :inner (mt/result job)))))))

  (testing "timeout fires when inner task takes too long"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched
                             (mt/timeout (mt/sleep 200 :inner) 100 :timed-out)
                             {:label "timeout-fire"})]
          (mt/advance-to! sched 100)
          (is (mt/done? job))
          (is (= :timed-out (mt/result job)))))))

  (testing "timeout with nil fallback"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched (mt/timeout (mt/sleep 200 :inner) 100) {})]
          (mt/advance-to! sched 100)
          (is (mt/done? job))
          (is (nil? (mt/result job)))))))

  (testing "cancelling timeout cancels inner and throws Cancelled"
    (binding [mt/*is-deterministic* true]
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched
                             (mt/timeout (mt/sleep 200 :inner) 100 :timed-out)
                             {:label "timeout-cancel"})]
          (mt/cancel! job)
          (mt/tick! sched)
          (is (mt/done? job))
          (is (thrown? Cancelled (mt/result job))))))))

;; =============================================================================
;; Job Management Tests
;; =============================================================================

(deftest job-lifecycle-test
  (testing "job starts pending"
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched (mt/sleep 100 :done) {})]
        (is (not (mt/done? job)))
        (is (= ::mt/pending (mt/result job))))))

  (testing "job success"
    (let [sched (mt/make-scheduler)
          job (mt/start! sched
                         (fn [s _f] (s :immediate) (fn [] nil))
                         {:label "immediate"})]
      (mt/tick! sched)
      (is (mt/done? job))
      (is (= :immediate (mt/result job)))))

  (testing "job failure"
    (let [sched (mt/make-scheduler)
          ex (ex-info "test error" {:type :test})
          job (mt/start! sched
                         (fn [_s f] (f ex) (fn [] nil))
                         {:label "failure"})]
      (mt/tick! sched)
      (is (mt/done? job))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"test error"
                            (mt/result job))))))



;; =============================================================================
;; with-determinism Macro Tests
;; =============================================================================

(deftest with-determinism-test
  (testing "*is-deterministic* is false outside with-determinism"
    (is (false? mt/*is-deterministic*)))

  (testing "*is-deterministic* is true inside with-determinism"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (is (true? mt/*is-deterministic*)))))

  (testing "rebinds m/sleep to virtual sleep"
    (is (= :done
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp (m/? (m/sleep 100 :done)))))))))

  (testing "rebinds m/timeout to virtual timeout"
    (is (= :timed-out
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp (m/? (m/timeout (m/sleep 200 :inner) 100 :timed-out)))))))))

  (testing "concurrent sleeps with join"
    (is (= [:a :b :c]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp
                        (m/? (m/join vector
                                     (m/sleep 100 :a)
                                     (m/sleep 200 :b)
                                     (m/sleep 150 :c))))))))))

  (testing "sequential sleeps accumulate time"
    (let [times (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler)]
          (mt/run sched
                  (m/sp
                   (swap! times conj (mt/now-ms sched))
                   (m/? (m/sleep 100))
                   (swap! times conj (mt/now-ms sched))
                   (m/? (m/sleep 50))
                   (swap! times conj (mt/now-ms sched))))))
      (is (= [0 100 150] @times)))))

;; =============================================================================
;; Run Function Tests
;; =============================================================================

(deftest run-test
  (testing "run auto-advances time"
    (is (= :done
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched (m/sp (m/? (m/sleep 1000 :done)))))))))

  (testing "run detects deadlock without auto-advance"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Deadlock"
                          (mt/with-determinism
                            (let [sched (mt/make-scheduler)]
                              (mt/run sched
                                      (m/sp (m/? (m/sleep 100)))
                                      {:auto-advance? false}))))))

  (testing "run enforces step budget"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Step budget exceeded"
                          (mt/with-determinism
                            (let [sched (mt/make-scheduler)]
                              (mt/run sched
                                      (m/sp
                                        ;; Create many microtasks
                                        (loop [i 0]
                                          (when (< i 1000)
                                            (m/? (m/sleep 0))
                                            (recur (inc i)))))
                                      {:max-steps 100}))))))

  (testing "run enforces time budget"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Time budget exceeded"
                          (mt/with-determinism
                            (let [sched (mt/make-scheduler)]
                              (mt/run sched
                                      (m/sp (m/? (m/sleep 10000 :done)))
                                      {:max-time-ms 1000})))))))

;; =============================================================================
;; Collect Tests
;; =============================================================================

(deftest collect-test
  (testing "collect gathers flow values into vector"
    (is (= [1 2 3]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp
                        (m/? (mt/collect (m/seed [1 2 3]))))))))))

  (testing "collect with transducer"
    (is (= [2 4 6]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp
                        (m/? (mt/collect (m/seed [1 2 3]) {:xf (map #(* 2 %))}))))))))))

;; =============================================================================
;; Trace Tests
;; =============================================================================

(deftest trace-test
  (testing "trace records events when enabled"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        (mt/run sched (m/sp (m/? (m/sleep 100 :done))))
        (let [trace (mt/trace sched)]
          (is (vector? trace))
          (is (pos? (count trace)))
          (is (some #(= :enqueue-timer (:event %)) trace))
          (is (some #(= :run-microtask (:event %)) trace))))))

  (testing "trace is nil when disabled"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? false})]
        (mt/run sched (m/sp (m/? (m/sleep 100 :done))))
        (is (nil? (mt/trace sched)))))))

;; =============================================================================
;; Executor Tests (JVM only)
;; =============================================================================

(deftest executor-test
  (testing "executor runs runnables as microtasks"
    (let [sched (mt/make-scheduler)]
      (binding [mt/*scheduler* sched]
        (let [result (atom nil)
              exec (mt/executor)]
          (.execute exec (fn [] (reset! result :executed)))
          (is (nil? @result))
          (mt/tick! sched)
          (is (= :executed @result))))))

  (testing "cpu-executor runs on cpu lane"
    (let [sched (mt/make-scheduler {:trace? true})]
      (binding [mt/*scheduler* sched]
        (let [result (atom nil)
              exec (mt/cpu-executor)]
          (.execute exec (fn [] (reset! result :cpu-done)))
          (mt/tick! sched)
          (is (= :cpu-done @result))
          (is (some #(= :cpu (:lane %)) (mt/trace sched)))))))

  (testing "blk-executor runs on blk lane"
    (let [sched (mt/make-scheduler {:trace? true})]
      (binding [mt/*scheduler* sched]
        (let [result (atom nil)
              exec (mt/blk-executor)]
          (.execute exec (fn [] (reset! result :blk-done)))
          (mt/tick! sched)
          (is (= :blk-done @result))
          (is (some #(= :blk (:lane %)) (mt/trace sched))))))))

;; =============================================================================
;; m/via with Virtualized Executors
;; =============================================================================

(deftest via-with-virtualized-executors-test
  (testing "m/via m/cpu runs deterministically on driver thread"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        (let [thread-id (atom nil)
              driver-thread (Thread/currentThread)
              result (mt/run sched
                             (m/sp
                              (m/? (m/via m/cpu
                                          (reset! thread-id (Thread/currentThread))
                                          (+ 1 2 3)))))]
          (is (= 6 result))
          (is (= driver-thread @thread-id)
              "via body should run on driver thread")))))

  (testing "m/via m/blk runs deterministically"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        (let [result (mt/run sched
                             (m/sp
                              (m/? (m/via m/blk
                                          (* 2 3 4)))))]
          (is (= 24 result))))))

  (testing "cancelled via before tick sees interrupt flag"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (let [saw-interrupt (atom false)
              job (mt/start! sched
                            (m/via m/cpu
                                   (reset! saw-interrupt (.isInterrupted (Thread/currentThread)))
                                   :done)
                            {})]
          (mt/cancel! job)
          (mt/tick! sched)
          (is @saw-interrupt "via body should see interrupt flag when cancelled before tick")))))

  (testing "scheduler remains usable after cancelled via"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (let [job1 (mt/start! sched (m/via m/cpu :done1) {})]
          (mt/cancel! job1)
          (mt/tick! sched)
          ;; Start another job - should work fine
          (let [job2 (mt/start! sched (m/via m/cpu :done2) {})]
            (mt/tick! sched)
            (is (= :done2 (mt/result job2)))
            (is (not (.isInterrupted (Thread/currentThread)))
                "driver thread should not be left interrupted"))))))

  (testing "via with blocking call throws InterruptedException when cancelled"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched
                            (m/via m/cpu
                                   (Thread/sleep 100)
                                   :done)
                            {})]
          (mt/cancel! job)
          (mt/tick! sched)
          (is (mt/done? job))
          (is (thrown? InterruptedException (mt/result job))))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest error-handling-test
  (testing "task exception propagates"
    (let [sched (mt/make-scheduler)
          job (mt/start! sched
                         (fn [_s _f]
                           (throw (ex-info "boom" {})))
                         {})]
      (mt/tick! sched)
      (is (mt/done? job))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                            (mt/result job)))))

)

(deftest no-scheduler-test
  (testing "sleep falls back to real sleep when *is-deterministic* is false"
    ;; Outside with-determinism, mt/sleep should delegate to m/sleep (real time)
    (let [result (atom nil)]
      ;; Use a short sleep to test fallback behavior
      ((mt/sleep 10 :done) (fn [v] (reset! result v)) (fn [_]))
      ;; Give it time to complete (real async)
      (Thread/sleep 50)
      (is (= :done @result))))

  (testing "sleep throws in deterministic mode without scheduler"
    ;; With *is-deterministic* true but no *scheduler*, should throw
    (binding [mt/*is-deterministic* true]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No TestScheduler"
                            ((mt/sleep 100 :done) (fn [_]) (fn [_])))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest integration-race-test
  (testing "race completes with first result"
    (is (= :fast
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp
                        (m/? (m/race
                              (m/sleep 50 :fast)
                              (m/sleep 100 :slow)))))))))))

;; Note: m/amb requires forking which is not supported in sequential tasks.
;; Use m/race instead for choosing between alternatives.

(deftest integration-amb-test
  (testing "amb= interleaves discrete flows"
    ;; Using m/seed to create discrete flows
    (is (= [:x :a :y :b :z :c]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp
                        (m/? (mt/collect
                              (m/ap
                               (m/?> (m/amb=
                                      (m/seed [:a :b :c])
                                      (m/seed [:x :y :z]))))))))))))))

;; =============================================================================
;; Interleaving Tests
;; =============================================================================

(deftest random-selection-determinism-test
  (testing ":random selection is deterministic with same seed across runs"
    ;; This test verifies that seeded random selection produces deterministic results.
    ;; Same seed should always produce the same execution order.
    (let [run-task (fn [seed]
                     (let [order (atom [])]
                       (mt/with-determinism
                         (let [sched (mt/make-scheduler {:seed seed})]
                           (mt/run sched
                                   (m/sp
                                    (m/? (m/join vector
                                                 (m/sp (m/? (m/sleep 0)) (swap! order conj :a) :a)
                                                 (m/sp (m/? (m/sleep 0)) (swap! order conj :b) :b)
                                                 (m/sp (m/? (m/sleep 0)) (swap! order conj :c) :c)))))))
                       @order))]
      ;; Same seed should produce same order every time
      (let [result1 (run-task 42)
            result2 (run-task 42)
            result3 (run-task 42)]
        (is (= result1 result2) "Same seed should produce same execution order")
        (is (= result2 result3) "Same seed should produce same execution order"))

      ;; Different seeds should (likely) produce different orders
      (let [results (set (map run-task (range 10)))]
        (is (> (count results) 1)
            "Different seeds should produce different execution orders"))))

  (testing ":random selection is deterministic in check-interleaving"
    ;; Verify that check-interleaving replay works correctly with :random
    (mt/with-determinism
      (let [make-task (fn []
                        (let [order (atom [])]
                          (m/sp
                           (m/? (m/join (fn [& _] @order)
                                        (m/sp (m/? (m/sleep 0)) (swap! order conj :a))
                                        (m/sp (m/? (m/sleep 0)) (swap! order conj :b))
                                        (m/sp (m/? (m/sleep 0)) (swap! order conj :c)))))))
            ;; Run with a seed that generates :random decisions
            exploration (mt/explore-interleavings make-task {:num-samples 20
                                                              :seed 12345})]
        ;; Should produce deterministic results - same seeds always give same outcomes
        (is (pos? (:unique-results exploration)))

        ;; Replay should give same result - schedule is now [id1 id2 ...] format
        (when-let [first-result (first (:results exploration))]
          (let [replayed (mt/replay-schedule (make-task) (:micro-schedule first-result))]
            (is (= (:result first-result) replayed)
                "Replaying schedule should produce same result"))))))

  (testing "schedule consumption only at branch points, not single-item steps"
    ;; This test verifies that schedule decisions are only consumed when there's
    ;; actually a choice to make (queue size > 1), not on every step.
    ;; This ensures trace->schedule extraction matches actual schedule consumption.
    (mt/with-determinism
      ;; First run to discover IDs
      (let [sched1 (mt/make-scheduler {:trace? true})
            make-task (fn []
                        (let [order (atom [])]
                          (m/sp
                           ;; Single sequential sleep (no branch, no schedule decision)
                           (m/? (m/sleep 10))
                           ;; Branch point: 3 concurrent tasks (needs schedule decisions)
                           (m/? (m/join vector
                                        (m/sp (m/? (m/sleep 0)) (swap! order conj :a))
                                        (m/sp (m/? (m/sleep 0)) (swap! order conj :b))
                                        (m/sp (m/? (m/sleep 0)) (swap! order conj :c))))
                           ;; Another single sleep (no branch)
                           (m/? (m/sleep 10))
                           @order)))]
        (let [result1 (mt/run sched1 (make-task))
              trace (mt/trace sched1)
              extracted-schedule (mt/trace->schedule trace)]
          ;; Extracted schedule should be task IDs (integers)
          (is (vector? extracted-schedule))
          (is (every? integer? extracted-schedule) "Schedule should contain task IDs")

          ;; Replay with extracted schedule should give identical result
          (let [result2 (mt/replay-schedule (make-task) extracted-schedule)]
            (is (= result1 result2)
                "Replaying extracted schedule should reproduce exact same result")))))))

(deftest schedule-selection-test
  (testing "default FIFO selection maintains order"
    (let [order (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:trace? true})]
          ;; Create a task that starts 3 concurrent yields
          (mt/run sched
                  (m/sp
                   (m/? (m/join vector
                                (m/sp (m/? (mt/yield)) (swap! order conj :a) :a)
                                (m/sp (m/? (mt/yield)) (swap! order conj :b) :b)
                                (m/sp (m/? (mt/yield)) (swap! order conj :c) :c)))))))
      ;; With FIFO (default), :a completes first since its yield was enqueued first
      (is (= [:a :b :c] @order))))

  (testing "task ID schedule reverses order"
    ;; First run to discover IDs, then replay in reverse
    (mt/with-determinism
      (let [sched1 (mt/make-scheduler {:trace? true})
            order1 (atom [])
            make-task (fn [order]
                        (m/sp
                         (m/? (m/join vector
                                      (m/sp (m/? (mt/yield)) (swap! order conj :a) :a)
                                      (m/sp (m/? (mt/yield)) (swap! order conj :b) :b)
                                      (m/sp (m/? (mt/yield)) (swap! order conj :c) :c)))))]
        ;; Run to get task IDs
        (mt/run sched1 (make-task order1))
        (let [trace (mt/trace sched1)
              select-events (filter #(= :select-task (:event %)) trace)]
          (when (>= (count select-events) 2)
            ;; Get the IDs that were selected
            (let [first-sel (first select-events)
                  selected-id (:selected-id first-sel)
                  alt-ids (:alternatives first-sel)
                  ;; Reverse the order for replay: pick last alternative first
                  reversed-schedule (vec (concat [(last alt-ids)] [(first alt-ids)]))]
              (let [order2 (atom [])
                    sched2 (mt/make-scheduler {:micro-schedule reversed-schedule :trace? true})]
                (mt/run sched2 (make-task order2))
                ;; Order should be different from FIFO
                (is (not= @order1 @order2) "Reversed schedule should produce different order"))))))))

  (testing "schedule exhaustion throws error"
    (mt/with-determinism
      ;; First discover what IDs we need
      (let [sched1 (mt/make-scheduler {:trace? true})]
        (mt/run sched1
                (m/sp
                 (m/? (m/join vector
                              (m/sleep 0 :a)
                              (m/sleep 0 :b)
                              (m/sleep 0 :c)))))
        ;; Get one ID from trace
        (let [select-events (filter #(= :select-task (:event %)) (mt/trace sched1))]
          (when-let [first-id (:selected-id (first select-events))]
            ;; Schedule has only 1 decision but 3 concurrent tasks need 2 decisions
            (let [sched2 (mt/make-scheduler {:micro-schedule [first-id] :trace? true})]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    #"Schedule exhausted"
                                    (mt/run sched2
                                            (m/sp
                                             (m/? (m/join vector
                                                          (m/sleep 0 :a)
                                                          (m/sleep 0 :b)
                                                          (m/sleep 0 :c))))))))))))))

;; NOTE: by-label selection was removed - schedules now use task IDs only

(deftest by-id-selection-test
  (testing "bare integer ID selects task with matching ID"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        ;; First, run to see what IDs get assigned
        (mt/run sched
                (m/sp
                 (m/? (m/join vector
                              (m/sleep 0 :a)
                              (m/sleep 0 :b)
                              (m/sleep 0 :c)))))

        ;; Extract timer IDs from trace
        (let [trace (mt/trace sched)
              timer-events (filter #(= :enqueue-timer (:event %)) trace)
              timer-ids (mapv :id timer-events)]
          ;; Should have 3 timers
          (is (= 3 (count timer-ids)))))))

  (testing "task ID selects specific task when multiple are queued"
    ;; First run to discover IDs, then replay with reversed order
    (mt/with-determinism
      (let [sched1 (mt/make-scheduler {:trace? true})
            execution-order1 (atom [])]
        ;; Run first to discover IDs
        (mt/run sched1
                (m/sp
                 (m/? (m/join vector
                              (m/sp (m/? (m/sleep 0)) (swap! execution-order1 conj :a) :a)
                              (m/sp (m/? (m/sleep 0)) (swap! execution-order1 conj :b) :b)
                              (m/sp (m/? (m/sleep 0)) (swap! execution-order1 conj :c) :c)))))

        ;; Get full schedule from trace to replay with reversed order
        (let [original-schedule (mt/trace->schedule (mt/trace sched1))]
          (when (>= (count original-schedule) 2)
            ;; Reverse the schedule to get a different order
            (let [reversed-schedule (vec (reverse original-schedule))
                  execution-order2 (atom [])
                  sched2 (mt/make-scheduler {:micro-schedule reversed-schedule :trace? true})]
              ;; This will run with reversed order
              (mt/run sched2
                      (m/sp
                       (m/? (m/join vector
                                    (m/sp (m/? (m/sleep 0)) (swap! execution-order2 conj :a) :a)
                                    (m/sp (m/? (m/sleep 0)) (swap! execution-order2 conj :b) :b)
                                    (m/sp (m/? (m/sleep 0)) (swap! execution-order2 conj :c) :c)))))

              ;; Execution orders should differ
              (is (not= @execution-order1 @execution-order2)
                  "Different schedules should produce different execution orders")))))))
  (testing "task ID not found throws error"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:micro-schedule [99999] :trace? true})] ; nonexistent ID
        ;; Run concurrent sleeps - should throw because ID not found
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Task ID 99999 not found"
                              (mt/run sched
                                      (m/sp
                                       (m/? (m/join vector
                                                    (m/sleep 0 :a)
                                                    (m/sleep 0 :b))))))))))

  (testing "[:by-id id] format still works (backward compatible)"
    (mt/with-determinism
      (let [sched1 (mt/make-scheduler {:trace? true})]
        ;; First run to get IDs
        (mt/run sched1
                (m/sp
                 (m/? (m/join vector
                              (m/sleep 0 :a)
                              (m/sleep 0 :b)))))
        (let [schedule (mt/trace->schedule (mt/trace sched1))]
          (when (seq schedule)
            (let [first-id (first schedule)
                  ;; Use [:by-id id] format
                  sched2 (mt/make-scheduler {:micro-schedule [[:by-id first-id]] :trace? true})]
              (mt/run sched2
                      (m/sp
                       (m/? (m/join vector
                                    (m/sleep 0 :a)
                                    (m/sleep 0 :b)))))
              ;; Should complete successfully
              (is true))))))))

;; NOTE: nth-selection was removed - schedules now use task IDs only

(deftest trace->schedule-test
  (testing "extracts task IDs from trace"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        ;; Run something with concurrent tasks to trigger selection events
        (mt/run sched
                (m/sp
                 (m/? (m/join vector
                              (m/sleep 0 :a)
                              (m/sleep 0 :b)
                              (m/sleep 0 :c)))))
        (let [extracted (mt/trace->schedule (mt/trace sched))]
          ;; Should be a vector of task IDs
          (is (vector? extracted))
          ;; IDs should be integers
          (is (every? integer? extracted))))))

  (testing "empty trace yields empty schedule"
    (is (= [] (mt/trace->schedule []))))

  (testing "trace without select events yields empty schedule"
    (is (= [] (mt/trace->schedule [{:event :run-microtask :id 1}
                                   {:event :advance-to :from 0 :to 100}])))))

(deftest replay-schedule-test
  (testing "replay produces same result with same schedule (using IDs)"
    ;; replay-schedule takes a task, which should be created inside with-determinism
    ;; First run to discover task IDs, then replay
    (mt/with-determinism
      (let [make-task (fn []
                        (m/sp
                         (m/? (m/join +
                                      (m/sleep 0 1)
                                      (m/sleep 0 2)
                                      (m/sleep 0 3)))))
            ;; First run to get schedule
            sched1 (mt/make-scheduler {:trace? true})
            _ (mt/run sched1 (make-task))
            schedule (mt/trace->schedule (mt/trace sched1))
            ;; Replay with same schedule
            r1 (mt/replay-schedule (make-task) schedule)
            r2 (mt/replay-schedule (make-task) schedule)]
        (is (= r1 r2)))))

  (testing "different ID schedules can produce different results"
    ;; This test uses a task where order matters
    (mt/with-determinism
      (let [;; Task factory that records execution order (fresh atom each time)
            make-task (fn []
                        (let [order (atom [])]
                          (m/sp
                           (m/? (m/join (fn [& args] @order)
                                        (m/sp (swap! order conj :a) :a)
                                        (m/sp (swap! order conj :b) :b)
                                        (m/sp (swap! order conj :c) :c))))))
            ;; First run to discover IDs
            sched1 (mt/make-scheduler {:trace? true})
            _ (mt/run sched1 (make-task))
            schedule1 (mt/trace->schedule (mt/trace sched1))]
        ;; Get select events to find alternative orderings
        (let [select-events (filter #(= :select-task (:event %)) (mt/trace sched1))]
          (when (seq select-events)
            (let [first-sel (first select-events)
                  alt-ids (:alternatives first-sel)
                  ;; Create reversed schedule if we have alternatives
                  schedule2 (when (seq alt-ids)
                              (vec (concat [(last alt-ids)] (rest schedule1))))]
              (when schedule2
                (let [r1 (mt/replay-schedule (make-task) schedule1)
                      r2 (mt/replay-schedule (make-task) schedule2)]
                  ;; Results should be vectors of the recorded order
                  (is (vector? r1))
                  (is (vector? r2)))))))))))

(deftest replay-test
  (testing "replay accepts failure bundle directly"
    (mt/with-determinism
      (let [make-task (fn []
                        (let [counter (atom 0)]
                          (m/sp
                           (m/? (m/join
                                 (fn [_ _] @counter)
                                 (m/sp
                                  (let [v @counter]
                                    (m/? (m/sleep 0))
                                    (reset! counter (+ v 5))))
                                 (m/sp
                                  (let [v @counter]
                                    (m/? (m/sleep 0))
                                    (reset! counter (+ v 7)))))))))
            failure (mt/check-interleaving make-task
                                           {:num-tests 100
                                            :seed 99999
                                            :property (fn [v] (= 12 v))})]
        ;; Should find a failure
        (is (false? (:ok? failure)))

        ;; replay should reproduce the same result
        (let [replayed (mt/replay make-task failure)]
          (is (= (:value failure) replayed))))))

  (testing "replay throws on missing schedule"
    (mt/with-determinism
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"missing :schedule"
                            (mt/replay (fn [] (m/sp :done)) {:ok? false}))))))


(deftest check-interleaving-test
  (testing "returns success map with seed when all tests pass"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp (m/? (m/sleep 10 :done))))
            result (mt/check-interleaving task-fn {:num-tests 10
                                                   :seed 42
                                                   :property (fn [v] (= v :done))})]
        (is (:ok? result))
        (is (= 42 (:seed result)))
        (is (= 10 (:iterations-run result))))))

  (testing "returns failure info when property fails"
    (mt/with-determinism
      ;; Task factory creates fresh atom each time
      (let [make-task (fn []
                        (let [counter (atom 0)]
                          (m/sp
                           (swap! counter inc)
                           @counter)))
            ;; Property that always fails (counter will be 1)
            result (mt/check-interleaving make-task {:num-tests 10
                                                     :seed 42
                                                     :property (fn [v] (= v 0))})] ; fails: counter is 1
        (is (false? (:ok? result)))
        (is (= :property-failed (:kind result)))
        (is (= 1 (:value result)))
        (is (contains? result :seed))
        (is (contains? result :schedule))
        (is (contains? result :iteration)))))

  (testing "returns failure info when task throws"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp (throw (ex-info "intentional failure" {}))))
            result (mt/check-interleaving task-fn {:num-tests 5 :seed 42})]
        (is (false? (:ok? result)))
        (is (= :exception (:kind result)))
        (is (some? (:error result)))))))

(deftest explore-interleavings-test
  (testing "explores multiple schedules"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp (m/? (m/sleep 10 :done))))
            result (mt/explore-interleavings task-fn {:num-samples 5
                                                      :seed 42})]
        (is (map? result))
        (is (contains? result :unique-results))
        (is (contains? result :results))
        (is (= 5 (count (:results result)))))))

  (testing "counts unique results correctly"
    (mt/with-determinism
      ;; Task factory that always returns same value
      (let [task-fn (fn [] (m/sp :constant))
            result (mt/explore-interleavings task-fn {:num-samples 10
                                                      :seed 42})]
        (is (= 1 (:unique-results result))))))

  (testing "each result includes schedule"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp (m/? (m/sleep 0 :done))))
            result (mt/explore-interleavings task-fn {:num-samples 3 :seed 42})]
        (is (every? #(contains? % :micro-schedule) (:results result)))
        (is (every? #(contains? % :result) (:results result)))))))

(deftest select-task-trace-event-test
  (testing "select-task events are traced when queue has multiple items"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        ;; Run concurrent tasks that will queue up
        (mt/run sched
                (m/sp
                 (m/? (m/join vector
                              (m/sleep 0 :a)
                              (m/sleep 0 :b)))))
        (let [trace (mt/trace sched)
              select-events (filter #(= :select-task (:event %)) trace)]
          ;; Should have select events when queue had choices
          (when (seq select-events)
            (let [ev (first select-events)]
              (is (contains? ev :decision))
              (is (contains? ev :queue-size))
              (is (contains? ev :selected-id))
              (is (contains? ev :alternatives))))))))

  (testing "no select-task events when queue always has single item"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        ;; Run sequential tasks - queue never has multiple items
        (mt/run sched
                (m/sp
                 (m/? (m/sleep 10))
                 (m/? (m/sleep 10))
                 :done))
        (let [trace (mt/trace sched)
              select-events (filter #(= :select-task (:event %)) trace)]
          (is (empty? select-events)))))))

;; =============================================================================
;; Interleaving Bug Detection Demo
;; =============================================================================

(deftest interleaving-reveals-bug-test
  (testing "lost update bug: concurrent read-modify-write without synchronization"
    ;; Classic concurrency bug: two tasks read a value, modify it, then write back.
    ;; Both reads happen before any yield, so one update is ALWAYS lost.
    ;;
    ;; Timeline 1 (A writes last):
    ;;   1. A reads counter (0)
    ;;   2. B reads counter (0)
    ;;   3. A yields, B yields
    ;;   4. B writes counter (0 + 20 = 20)
    ;;   5. A writes counter (0 + 10 = 10)  <- B's update is lost!
    ;;   Result: 10
    ;;
    ;; Timeline 2 (B writes last):
    ;;   1. A reads counter (0)
    ;;   2. B reads counter (0)
    ;;   3. A yields, B yields
    ;;   4. A writes counter (0 + 10 = 10)
    ;;   5. B writes counter (0 + 20 = 20)  <- A's update is lost!
    ;;   Result: 20

    (let [;; A buggy "bank transfer" simulation
          make-buggy-task
          (fn []
            (let [account (atom 0)]
              (m/sp
               (m/? (m/join
                     (fn [_ _] @account)
                       ;; Task A: deposit 10
                     (m/sp
                      (let [balance @account] ; read
                        (m/? (m/sleep 0)) ; yield point - allows interleaving
                        (reset! account (+ balance 10)))) ; write
                       ;; Task B: deposit 20
                     (m/sp
                      (let [balance @account] ; read
                        (m/? (m/sleep 0)) ; yield point
                        (reset! account (+ balance 20)))))))))

          ;; Property: final balance should always be 30 (10 + 20)
          valid? (fn [result] (= 30 result))]

      ;; Explore interleavings to find the bug
      (mt/with-determinism
        (let [exploration (mt/explore-interleavings make-buggy-task
                                                    {:num-samples 50
                                                     :seed 12345})]
          ;; Should find multiple unique results (bug manifests as different outcomes)
          (is (> (:unique-results exploration) 1)
              "Bug should cause different outcomes under different interleavings")

          ;; Extract the actual results seen
          ;; Note: 30 is not possible because both reads happen before any yield point.
          ;; The bug manifests as either 10 or 20 depending on which write happens last.
          (let [results (set (map :result (:results exploration)))]
            (is (or (contains? results 10)
                    (contains? results 20))
                "Result 10 or 20 should be possible (lost update)")
            (is (= #{10 20} results)
                "Both lost update outcomes should be observed"))))))

  (testing "check-interleaving finds the bug and provides reproducible schedule"
    (let [make-buggy-task
          (fn []
            (let [counter (atom 0)]
              (m/sp
               (m/? (m/join
                     (fn [_ _] @counter)
                     (m/sp
                      (let [v @counter]
                        (m/? (m/sleep 0))
                        (reset! counter (+ v 5))))
                     (m/sp
                      (let [v @counter]
                        (m/? (m/sleep 0))
                        (reset! counter (+ v 7)))))))))

          valid? (fn [result] (= 12 result))]

      (mt/with-determinism
        (let [failure (mt/check-interleaving make-buggy-task
                                             {:num-tests 100
                                              :seed 99999
                                              :property valid?})]
          ;; Should find a failing case
          (is (false? (:ok? failure)) "Should find an interleaving that exposes the bug")
          (is (= :property-failed (:kind failure)))

          ;; Verify the failure info is complete
          (is (contains? failure :schedule))
          (is (contains? failure :seed))
          (is (vector? (:schedule failure)))

          ;; The failing result should be 5 or 7 (not 12)
          (is (not= 12 (:value failure))
              "Failing result should not be the expected 12")

          ;; Replay should produce the same buggy result
          (let [replayed (mt/replay make-buggy-task failure)]
            (is (= (:value failure) replayed)
                "Replaying the failure bundle should reproduce the exact same bug"))))))

  (testing "fixed version passes all interleavings"
    ;; The fix: use swap! for atomic read-modify-write
    (let [make-fixed-task
          (fn []
            (let [counter (atom 0)]
              (m/sp
               (m/? (m/join
                     (fn [_ _] @counter)
                     (m/sp
                      (m/? (m/sleep 0))
                      (swap! counter + 5)) ; atomic!
                     (m/sp
                      (m/? (m/sleep 0))
                      (swap! counter + 7)))) ; atomic!
               )))

          valid? (fn [result] (= 12 result))]

      (mt/with-determinism
        (let [result (mt/check-interleaving make-fixed-task
                                            {:num-tests 100
                                             :seed 99999
                                             :property valid?})]
          ;; Fixed version should pass all interleavings
          (is (:ok? result)
              "Fixed version should pass all interleavings"))))))

;; =============================================================================
;; Yield Tests
;; =============================================================================

(deftest yield-test
  (testing "yield completes immediately in production (no scheduler)"
    ;; Without a scheduler bound, yield should complete synchronously
    (let [result (atom nil)
          task (mt/yield :done)]
      (task (fn [v] (reset! result v)) (fn [_]))
      (is (= :done @result))))

  (testing "yield with nil result in production"
    (let [result (atom :not-set)
          task (mt/yield)]
      (task (fn [v] (reset! result v)) (fn [_]))
      (is (nil? @result))))

  (testing "yield creates scheduling point in test mode"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched (mt/yield :done) {:label "yield-test"})]
          ;; Job should not be done yet - needs tick to process microtask
          (is (not (mt/done? job)))
          (is (= 1 (count (:microtasks (mt/pending sched)))))
          (mt/tick! sched)
          (is (mt/done? job))
          (is (= :done (mt/result job)))))))

  (testing "yield with nil result in test mode"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched (mt/yield) {})]
          (mt/tick! sched)
          (is (mt/done? job))
          (is (nil? (mt/result job)))))))

  (testing "cancelled yield throws Cancelled"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (let [job (mt/start! sched (mt/yield :done) {:label "cancel-test"})]
          (mt/cancel! job)
          (mt/tick! sched)
          (is (mt/done? job))
          (is (thrown? Cancelled (mt/result job)))))))

  (testing "yield works with with-determinism macro"
    (is (= :done
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (mt/run sched
                       (m/sp (m/? (mt/yield :done)))))))))

  (testing "multiple yields create interleaving opportunities"
    (let [order (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:trace? true})]
          (mt/run sched
                  (m/sp
                   (m/? (m/join vector
                                (m/sp
                                 (swap! order conj :a1)
                                 (m/? (mt/yield))
                                 (swap! order conj :a2))
                                (m/sp
                                 (swap! order conj :b1)
                                 (m/? (mt/yield))
                                 (swap! order conj :b2))))))))
      ;; With FIFO scheduling (default), order should be deterministic
      (is (= 4 (count @order)))))

  (testing "yield enables interleaving exploration"
    ;; This test demonstrates that yield creates real scheduling points
    ;; that check-interleaving can explore
    (mt/with-determinism
      (let [make-task (fn []
                        (let [order (atom [])]
                          (m/sp
                           (m/? (m/join (fn [_ _] @order)
                                        (m/sp
                                         (swap! order conj :a)
                                         (m/? (mt/yield))
                                         (swap! order conj :a2))
                                        (m/sp
                                         (swap! order conj :b)
                                         (m/? (mt/yield))
                                         (swap! order conj :b2)))))))
            exploration (mt/explore-interleavings make-task
                                                  {:num-samples 50
                                                   :seed 12345})]
        ;; Should find multiple unique orderings due to yield points
        (is (> (:unique-results exploration) 1)
            "Yield should create multiple possible execution orders"))))

  (testing "yield is traced when tracing enabled"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        (mt/run sched (m/sp (m/? (mt/yield :done))))
        (let [trace (mt/trace sched)]
          (is (vector? trace))
          (is (some #(= :yield (:kind %)) trace)
              "Trace should contain yield events"))))))

;; =============================================================================
;; Clock Tests
;; =============================================================================

(deftest clock-test
  (testing "clock returns real time when no scheduler bound"
    (let [before (System/currentTimeMillis)
          clock-time (mt/clock)
          after (System/currentTimeMillis)]
      (is (<= before clock-time after))
      (is (pos? clock-time))))

  (testing "clock returns virtual time within with-determinism"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:initial-ms 1000})]
        (binding [mt/*scheduler* sched]
          (is (= 1000 (mt/clock))))
        (mt/run sched
                (m/sp
                 (is (= 1000 (mt/clock)))
                 (m/? (m/sleep 500))
                 (is (= 1500 (mt/clock)))
                 (m/? (m/sleep 200))
                 (is (= 1700 (mt/clock))))))))

  (testing "clock tracks time across multiple sleeps"
    (let [times (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler)]
          (mt/run sched
                  (m/sp
                   (swap! times conj (mt/clock))
                   (m/? (m/sleep 100))
                   (swap! times conj (mt/clock))
                   (m/? (m/sleep 250))
                   (swap! times conj (mt/clock))))))
      (is (= [0 100 350] @times))))

  (testing "clock returns consistent time within concurrent tasks"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (let [result (mt/run sched
                             (m/sp
                              (m/? (m/join vector
                                           (m/sp
                                            (m/? (m/sleep 100))
                                            (mt/clock))
                                           (m/sp
                                            (m/? (m/sleep 200))
                                            (mt/clock))))))]
          (is (= [100 200] result))))))

  (testing "clock is useful for timestamping in production-like code"
    ;; Simulate production code that uses clock for timestamps
    (let [record-event (fn [event] {:time (mt/clock) :event event})]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:initial-ms 1000})]
          (let [events (mt/run sched
                               (m/sp
                                (let [e1 (record-event :start)]
                                  (m/? (m/sleep 5000))
                                  (let [e2 (record-event :end)]
                                    [e1 e2]))))]
            (is (= {:time 1000 :event :start} (first events)))
            (is (= {:time 6000 :event :end} (second events)))
            (is (= 5000 (- (:time (second events)) (:time (first events)))))))))))

(deftest clock-vs-now-ms-test
  (testing "clock equals now-ms when scheduler bound"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:initial-ms 42})]
        (binding [mt/*scheduler* sched]
          (is (= (mt/now-ms sched) (mt/clock)))
          (mt/advance! sched 100)
          (is (= (mt/now-ms sched) (mt/clock)))
          (is (= 142 (mt/clock))))))))

;; =============================================================================
;; Manual Stepping Tests (next-tasks and step! with task-id)
;; =============================================================================

(deftest next-tasks-test
  (testing "returns empty vector when no microtasks"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (is (= [] (mt/next-tasks sched))))))

  (testing "returns available tasks with IDs"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (mt/start! sched
                   (m/sp
                    (m/? (m/join vector
                                 (mt/yield :a)
                                 (mt/yield :b)
                                 (mt/yield :c)))))
        ;; After starting, there should be 3 yield tasks in queue
        (let [tasks (mt/next-tasks sched)]
          (is (= 3 (count tasks)))
          (is (every? :id tasks))
          (is (every? #(= :yield (:kind %)) tasks))))))

  (testing "task IDs can be used with step!"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)
            order (atom [])]
        (mt/start! sched
                   (m/sp
                    (m/? (m/join vector
                                 (m/sp (m/? (mt/yield)) (swap! order conj :a) :a)
                                 (m/sp (m/? (mt/yield)) (swap! order conj :b) :b)
                                 (m/sp (m/? (mt/yield)) (swap! order conj :c) :c)))))
        ;; Get available tasks
        (let [tasks (mt/next-tasks sched)
              ;; Pick the last task first to reverse order
              last-task-id (:id (last tasks))]
          ;; Step the last task first
          (mt/step! sched last-task-id)
          ;; Then step the rest with default (FIFO)
          (mt/tick! sched)
          ;; First completion should be :c (since we stepped it first)
          (is (= :c (first @order))))))))

(deftest step-with-task-id-test
  (testing "step! with task-id runs specific task"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})
            order (atom [])]
        (mt/start! sched
                   (m/sp
                    (m/? (m/join vector
                                 (m/sp (m/? (mt/yield)) (swap! order conj :a))
                                 (m/sp (m/? (mt/yield)) (swap! order conj :b))))))
        ;; Get task IDs
        (let [tasks (mt/next-tasks sched)
              id-a (:id (first tasks))
              id-b (:id (second tasks))]
          ;; Step task B first
          (mt/step! sched id-b)
          (is (= [:b] @order))
          ;; Then step task A
          (mt/step! sched id-a)
          (is (= [:b :a] @order))))))

  (testing "step! with nonexistent task-id throws"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (mt/start! sched (m/sp (m/? (mt/yield))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Task ID 99999 not found"
                              (mt/step! sched 99999))))))

  (testing "step! with task-id records decision in trace"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})]
        (mt/start! sched
                   (m/sp
                    (m/? (m/join vector
                                 (mt/yield :a)
                                 (mt/yield :b)))))
        (let [tasks (mt/next-tasks sched)
              second-id (:id (second tasks))]
          ;; Step specific task
          (mt/step! sched second-id)
          ;; Check trace has the selection
          (let [select-events (filter #(= :select-task (:event %)) (mt/trace sched))]
            (when (seq select-events)
              (let [ev (first select-events)]
                (is (= second-id (:decision ev)))
                (is (= second-id (:selected-id ev)))))))))))

;; =============================================================================
;; Concurrent Emit Interleaving Test
;; =============================================================================

(deftest concurrent-emit-interleaving-test
  (testing "explore-interleavings finds all 6 permutations of concurrent emits"
    ;; When 3 concurrent tasks emit events at the same virtual time,
    ;; the scheduler can interleave them in any order, producing all 3! = 6 permutations.
    (letfn [(make-event-emitter []
              (let [!cb (atom nil)]
                {:flow  (m/observe (fn [cb]
                                     (reset! !cb cb)
                                     #(reset! !cb nil)))
                 :emit! (fn [v] (when-let [cb @!cb] (cb v)))}))]
      (let [results (->> (mt/with-determinism
                           (mt/explore-interleavings
                             #(m/sp
                                (let [{:keys [flow emit!]} (make-event-emitter)]
                                  (m/? (m/race
                                         ;; Consumer: collect 3 events from discrete flow
                                         (m/reduce (fn [acc event]
                                                     (let [acc (conj acc event)]
                                                       (if (>= (count acc) 3) (reduced acc) acc)))
                                                   [] flow)
                                         ;; Producer: emit at controlled points via concurrent tasks
                                         (m/sp
                                           (m/?
                                             (m/join vector
                                                     (m/sp (m/? (m/sleep 10)) (emit! :click))
                                                     (m/sp (m/? (m/sleep 10)) (emit! :scroll))
                                                     (m/sp (m/? (m/sleep 10)) (emit! :keypress))))
                                           (m/? (m/sleep 1000)))))))
                             {:num-samples 100 :seed 123}))
                         :results
                         (map :result)
                         distinct
                         set)]
        ;; Should find exactly 6 unique permutations
        (is (= 6 (count results))
            "Should find all 6 permutations of [:click :scroll :keypress]")
        ;; All results should be permutations of the 3 events
        (is (every? #(= (set %) #{:click :scroll :keypress}) results)
            "Each result should contain exactly the 3 events")))))
