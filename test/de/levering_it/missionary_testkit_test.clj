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
      (is (mt/idle? (mt/step! sched)))))

  (testing "tick! returns 0 when no microtasks"
    (let [sched (mt/make-scheduler)]
      (is (= 0 (mt/tick! sched)))))

  (testing "step! returns IdleStatus with reason :idle-empty when truly empty"
    (let [sched (mt/make-scheduler)
          result (mt/step! sched)]
      (is (mt/idle? result))
      (is (= ::mt/idle-empty (mt/idle-reason result)))
      (let [details (mt/idle-details result)]
        (is (= 0 (:pending-count details)))
        (is (= 0 (:timer-count details)))
        (is (= 0 (:in-flight-count details)))
        (is (nil? (:blocked-lanes details))))))

  (testing "step! returns IdleStatus with reason :idle-blocked when lanes full"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:cpu-threads 1 :duration-range [100 100]})
            ;; Start two CPU tasks - first occupies lane, second blocked
            _ (mt/start! sched
                         (m/sp (m/? (m/join vector
                                            (m/via m/cpu :first)
                                            (m/via m/cpu :second)))))]
        ;; First step starts the first cpu task
        (mt/step! sched)
        ;; Second step should be blocked
        (let [result (mt/step! sched)]
          (is (mt/idle? result))
          (is (= ::mt/idle-blocked (mt/idle-reason result)))
          (let [details (mt/idle-details result)]
            (is (= 1 (:pending-count details)) "One task waiting")
            (is (= 1 (:in-flight-count details)) "One task in-flight")
            (is (contains? (:blocked-lanes details) :cpu) "CPU lane should be blocked")
            (is (= 100 (:next-time details)) "Next event at 100ms"))))))

  (testing "step! returns IdleStatus with reason :idle-awaiting-time when timers pending"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)
            _ (mt/start! sched (m/sleep 100 :done))]
        (let [result (mt/step! sched)]
          (is (mt/idle? result))
          (is (= ::mt/idle-awaiting-time (mt/idle-reason result)))
          (let [details (mt/idle-details result)]
            (is (= 0 (:pending-count details)))
            (is (= 1 (:timer-count details)))
            (is (= 100 (:next-time details)))))))))

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

  (testing "via-call propagates Cancelled exception (no deadlock)"
    ;; Bug fix: When a via-call thunk throws missionary.Cancelled, the testkit
    ;; must still notify the parent via f(). Previously, it swallowed the exception
    ;; setting done?=true but never calling s or f, causing deadlock.
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})
            task (m/sp
                   (m/? (m/via m/cpu
                               (throw (Cancelled.)))))]
        ;; Should complete (with Cancelled failure) without hanging
        (is (thrown? Cancelled
                     (mt/run sched task {:max-steps 100}))))))

  (testing "via-call propagates Cancelled exception with duration-range"
    ;; Same test but with duration > 0 (run-then-complete path)
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true :duration-range [10 10]})
            task (m/sp
                   (m/? (m/via m/cpu
                               (throw (Cancelled.)))))]
        (is (thrown? Cancelled
                     (mt/run sched task {:max-steps 100})))))))

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

(deftest check-interleaving-requires-determinism-test
  (testing "check-interleaving throws when called outside with-determinism"
    (let [task-fn (fn [] (m/sp :done))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"check-interleaving must be called inside mt/with-determinism body"
           (mt/check-interleaving task-fn {:num-tests 1 :seed 42})))
      ;; Verify the exception has the right kind
      (try
        (mt/check-interleaving task-fn {:num-tests 1})
        (catch clojure.lang.ExceptionInfo e
          (is (= ::mt/not-in-deterministic-mode (:mt/kind (ex-data e))))
          (is (= "check-interleaving" (:fn (ex-data e))))))))

  (testing "check-interleaving works inside with-determinism"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp :done))
            result (mt/check-interleaving task-fn {:num-tests 3 :seed 42})]
        (is (:ok? result))))))

(deftest explore-interleavings-requires-determinism-test
  (testing "explore-interleavings throws when called outside with-determinism"
    (let [task-fn (fn [] (m/sp :done))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"explore-interleavings must be called inside mt/with-determinism body"
           (mt/explore-interleavings task-fn {:num-samples 1 :seed 42})))
      ;; Verify the exception has the right kind
      (try
        (mt/explore-interleavings task-fn {:num-samples 1})
        (catch clojure.lang.ExceptionInfo e
          (is (= ::mt/not-in-deterministic-mode (:mt/kind (ex-data e))))
          (is (= "explore-interleavings" (:fn (ex-data e))))))))

  (testing "explore-interleavings works inside with-determinism"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp :done))
            result (mt/explore-interleavings task-fn {:num-samples 3 :seed 42})]
        (is (= 3 (count (:results result))))))))

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

;; =============================================================================
;; Via Cancellation Tests
;; =============================================================================
;;
;; The testkit patches m/via-call to run on the microtask queue instead of
;; real executors. The via body runs synchronously once started.
;;
;; LIMITATION: In single-threaded deterministic mode, via body cannot be
;; cancelled mid-work. Cancellation can only happen:
;; 1. Before the body starts (at the initial microtask scheduling point)
;; 2. After the body completes (result is discarded)
;;
;; The testkit does NOT use Thread.interrupt() - cancellation is cooperative.

(deftest via-cancellation-test
  (testing "via body runs to completion when not cancelled"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)
            execution-log (atom [])]
        (mt/run sched
                (m/sp
                  (m/? (m/via m/cpu
                         (swap! execution-log conj :step-1)
                         (swap! execution-log conj :step-2)
                         (swap! execution-log conj :step-3)
                         :via-done))))
        (is (= [:step-1 :step-2 :step-3] @execution-log)
            "Via body should run to completion"))))

  (testing "via cancellation before body starts - body never executes"
    ;; When the race winner completes before via's microtask executes,
    ;; the via body never runs
    (mt/with-determinism
      (let [sched (mt/make-scheduler)
            via-body-ran (atom false)]
        (mt/run sched
                (m/sp
                  (m/? (m/race
                         (m/via m/cpu
                           (reset! via-body-ran true)
                           :via-done)
                         ;; Winner completes immediately via m/sp
                         (m/sp :instant-winner)))))
        (is (false? @via-body-ran)
            "Via body should not execute when cancelled before microtask runs")))))

;; =============================================================================
;; Virtual Task Duration Tests
;; =============================================================================

(deftest duration-range-basic-test
  (testing "without duration-range, yields complete at t=0"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)
            times (atom [])]
        (mt/run sched
                (m/sp
                  (swap! times conj (mt/now-ms sched))
                  (m/? (mt/yield))
                  (swap! times conj (mt/now-ms sched))
                  (m/? (mt/yield))
                  (swap! times conj (mt/now-ms sched))
                  :done))
        (is (= [0 0 0] @times)
            "All events should occur at t=0 without duration-range"))))

  (testing "with duration-range, yields advance virtual time"
    ;; Time advances BEFORE a microtask's continuation executes.
    ;; So: yield enqueues continuation → time advances by duration → continuation runs
    ;; When we observe time right after yield returns, time has already passed.
    ;; Note: Only yield and via-call get durations; job completion is infrastructure (0ms).
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [10 10] :seed 42})
            times (atom [])]
        (mt/run sched
                (m/sp
                  (swap! times conj (mt/now-ms sched))  ;; t=0
                  (m/? (mt/yield))                       ;; yield takes 10ms
                  (swap! times conj (mt/now-ms sched))  ;; t=10 (after yield's duration)
                  (m/? (mt/yield))                       ;; second yield takes 10ms
                  (swap! times conj (mt/now-ms sched))  ;; t=20 (after second yield's duration)
                  :done))
        (is (= 0 (first @times)) "First observation at t=0")
        (is (= 10 (second @times)) "Second observation at t=10 (after first yield)")
        (is (= 20 (nth @times 2)) "Third observation at t=20 (after second yield)")
        (is (= 20 (mt/now-ms sched)) "Final time is 20ms (2 yields × 10ms each)"))))

  (testing "duration-range generates varied durations with seed"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [1 100] :seed 42 :trace? true})]
        (mt/run sched
                (m/sp
                  (m/? (mt/yield))
                  (m/? (mt/yield))
                  (m/? (mt/yield))
                  :done))
        (let [trace (mt/trace sched)
              ;; Only yields get durations from duration-range; filter by :kind :yield
              durations (->> trace
                             (filter #(and (= :enqueue-microtask (:event %))
                                           (= :yield (:kind %))))
                             (map :duration-ms))]
          ;; Should have 3 yields with durations in range
          (is (= 3 (count durations)) "Should have exactly 3 yield microtasks")
          (is (every? #(and (>= % 1) (<= % 100)) durations)
              "All yield durations should be within [1, 100]"))))))

(deftest duration-range-timer-promotion-test
  (testing "duration advance promotes due timers"
    ;; This test verifies that when a microtask's duration causes time to pass
    ;; a timer deadline, that timer gets promoted to the microtask queue
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [20 20] :seed 42 :trace? true})
            events (atom [])]
        (mt/run sched
                (m/sp
                  (m/? (m/join vector
                         ;; Worker: yields with 20ms duration
                         (m/sp
                           (swap! events conj {:who :worker :event :start :t (mt/now-ms sched)})
                           (m/? (mt/yield))
                           (swap! events conj {:who :worker :event :done :t (mt/now-ms sched)})
                           :worker-done)
                         ;; Timer: fires at 15ms
                         (m/sp
                           (swap! events conj {:who :timer :event :waiting :t (mt/now-ms sched)})
                           (m/? (m/sleep 15))
                           (swap! events conj {:who :timer :event :fired :t (mt/now-ms sched)})
                           :timer-fired)))))
        ;; Verify timer was promoted during worker's duration advance
        (let [trace (mt/trace sched)
              promote-events (filter #(= :promote-timers (:event %)) trace)]
          (is (seq promote-events) "Should have timer promotion events"))
        ;; Timer should fire at or after its scheduled time
        (let [timer-fired (first (filter #(= :fired (:event %)) @events))]
          (is (some? timer-fired))
          (is (>= (:t timer-fired) 15) "Timer should fire at or after 15ms"))))))

(deftest duration-range-timeout-race-test
  (testing "timeout beats work when work runs on separate lane (m/blk)"
    ;; When work runs on m/blk (separate lane), the timeout timer can fire
    ;; and deliver its result without being blocked by the work.
    ;; Real Missionary: (m/? (m/timeout (m/via m/blk (Thread/sleep 100) :work) 50 :timeout)) -> :timeout
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [100 100] :seed 2})]
        (is (= :timed-out
               (mt/run sched
                       (m/sp
                         (m/? (m/timeout
                                (mt/via-call m/blk (fn [] :work-completed)) ;; 100ms on :blk lane
                                50 ;; 50ms timeout
                                :timed-out)))))
            "Timeout wins when work (100ms on blk) > timeout (50ms)"))))

  (testing "work beats timeout when work blocks trampoline (m/sp)"
    ;; When work runs via m/sp with blocking (yield), it occupies the trampoline.
    ;; The timeout timer fires but can't deliver until trampoline is free.
    ;; By then, work has completed and wins the race.
    ;; Real Missionary: (m/? (m/timeout (m/sp (Thread/sleep 100) :work) 50 :timeout)) -> :work
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [100 100] :seed 42})]
        (is (= :work-completed
               (mt/run sched
                       (m/sp
                         (m/? (m/timeout
                                (m/sp
                                  (m/? (mt/yield)) ;; 100ms blocking the trampoline
                                  :work-completed)
                                50 ;; 50ms timeout - fires but blocked
                                :timed-out)))))
            "Work wins when it blocks the trampoline, even if timeout is shorter"))))

  (testing "work beats timeout when work duration is less than timeout"
    ;; When work duration < timeout, work completes before timeout is due.
    ;; The timer is never promoted, so work always wins.
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [10 10] :seed 42})]
        (is (= :work-completed
               (mt/run sched
                       (m/sp
                         (m/? (m/timeout
                                (m/sp
                                  (m/? (mt/yield)) ;; 10ms duration
                                  :work-completed)
                                50 ;; 50ms timeout
                                :timed-out)))))
            "Work should complete when work duration (10ms) < timeout (50ms)"))))

  (testing "without duration-range, work always beats timeout"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (is (= :work-completed
               (mt/run sched
                       (m/sp
                         (m/? (m/timeout
                                (m/sp
                                  (m/? (mt/yield)) ;; 0ms duration
                                  :work-completed)
                                1 ;; 1ms timeout (even tiny timeout loses)
                                :timed-out)))))
            "Without duration-range, work (0ms) always beats any timeout")))))

(deftest duration-range-explore-interleavings-test
  (testing "explore-interleavings with duration-range finds both timeout outcomes"
    ;; Use mt/via-call m/blk so work runs on separate lane and timeout can win
    (mt/with-determinism
      (let [result (mt/explore-interleavings
                     (fn []
                       (m/timeout
                         (mt/via-call m/blk (fn [] :work))
                         50
                         :timeout))
                     {:num-samples 30
                      :seed 42
                      :duration-range [30 70]})] ;; 30-70ms work vs 50ms timeout
        (is (= 2 (:unique-results result))
            "Should find both :work and :timeout outcomes")
        (let [outcomes (frequencies (map :result (:results result)))]
          (is (pos? (:work outcomes 0)) "Some runs should complete work")
          (is (pos? (:timeout outcomes 0)) "Some runs should timeout")))))

  (testing "check-interleaving with duration-range can find timeout failures"
    ;; Use mt/via-call m/blk so work runs on separate lane and timeout can win
    (mt/with-determinism
      (let [result (mt/check-interleaving
                     (fn []
                       (m/timeout
                         (mt/via-call m/blk (fn [] :work))
                         50
                         :timeout))
                     {:num-tests 50
                      :seed 42
                      :duration-range [60 80] ;; Always > 50ms timeout
                      :property (fn [r] (= r :work))})] ;; Expect work to complete
        (is (not (:ok? result)) "Should find failure (timeout beats work)")
        (is (= :property-failed (:kind result)))
        (is (= :timeout (:value result)) "Failed value should be :timeout")))))

(deftest duration-range-trace-events-test
  (testing "trace includes duration information"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [5 15] :seed 42 :trace? true})]
        (mt/run sched
                (m/sp
                  (m/? (mt/yield))
                  (m/? (mt/yield))
                  :done))
        (let [trace (mt/trace sched)]
          ;; Check enqueue-microtask events have duration-ms
          (let [enqueue-events (filter #(= :enqueue-microtask (:event %)) trace)]
            (is (every? #(contains? % :duration-ms) enqueue-events)
                "All enqueue events should have :duration-ms"))
          ;; Check task-start events (replaces duration-advance in split execution model)
          (let [start-events (filter #(= :task-start (:event %)) trace)]
            (is (seq start-events) "Should have task-start events for tasks with duration")
            (is (every? #(and (contains? % :duration-ms)
                              (contains? % :end-ms)
                              (contains? % :lane)) start-events)
                "Start events should have duration-ms, end-ms, and lane"))
          ;; Check task-complete events
          (let [complete-events (filter #(= :task-complete (:event %)) trace)]
            (is (seq complete-events) "Should have task-complete events")
            (is (every? #(contains? % :lane) complete-events)
                "Complete events should have lane")))))))

(deftest yield-duration-fn-test
  (testing "yield with :duration-fn overrides scheduler duration-range"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [1 1] :seed 42})
            times (atom [])]
        (mt/run sched
                (m/sp
                  (swap! times conj (mt/now-ms sched))
                  ;; This yield uses duration-fn = 50ms, overriding the 1ms from duration-range
                  (m/? (mt/yield :a {:duration-fn (constantly 50)}))
                  (swap! times conj (mt/now-ms sched))
                  ;; This yield uses the scheduler's duration-range (1ms)
                  (m/? (mt/yield :b))
                  (swap! times conj (mt/now-ms sched))
                  :done))
        (is (= 0 (nth @times 0)) "First observation at t=0")
        (is (= 50 (nth @times 1)) "Second observation at t=50 (after 50ms yield)")
        (is (= 51 (nth @times 2)) "Third observation at t=51 (after 1ms yield)"))))

  (testing "yield with :duration-fn works without scheduler duration-range"
    (mt/with-determinism
      (let [sched (mt/make-scheduler) ;; no duration-range
            times (atom [])]
        (mt/run sched
                (m/sp
                  (swap! times conj (mt/now-ms sched))
                  ;; This yield uses duration-fn = 100ms
                  (m/? (mt/yield :a {:duration-fn (constantly 100)}))
                  (swap! times conj (mt/now-ms sched))
                  ;; This yield has no duration (0ms default)
                  (m/? (mt/yield :b))
                  (swap! times conj (mt/now-ms sched))
                  :done))
        (is (= 0 (nth @times 0)) "First observation at t=0")
        (is (= 100 (nth @times 1)) "Second observation at t=100 (after 100ms yield)")
        (is (= 100 (nth @times 2)) "Third observation still at t=100 (0ms yield)"))))

  (testing "yield :duration-fn is called at enqueue time"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)
            call-count (atom 0)
            duration-fn (fn []
                          (swap! call-count inc)
                          (* @call-count 10))] ;; returns 10, 20, 30, ...
        (mt/run sched
                (m/sp
                  (m/? (mt/yield :a {:duration-fn duration-fn}))
                  (m/? (mt/yield :b {:duration-fn duration-fn}))
                  (m/? (mt/yield :c {:duration-fn duration-fn}))
                  :done))
        (is (= 3 @call-count) "duration-fn called 3 times")
        ;; Time progression: 10 + 20 + 30 = 60, plus job/complete (0ms)
        (is (= 60 (mt/now-ms sched)) "Final time is 60ms (10 + 20 + 30)"))))

  (testing "yield returns value correctly with opts"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (is (= :my-value
               (mt/run sched
                       (m/sp
                         (m/? (mt/yield :my-value {:duration-fn (constantly 10)})))))
            "yield with opts should return the value")))))

;; -----------------------------------------------------------------------------
;; Cancel-microtask tests
;; -----------------------------------------------------------------------------

(deftest cancel-microtask-test
  (testing "cancel-microtask! returns false when task not found"
    (mt/with-determinism
      (let [sched (mt/make-scheduler)]
        (is (false? (mt/cancel-microtask! sched 999)) "Should return false for unknown ID"))))

  (testing "yield cancellation removes from queue"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})
            result (atom nil)
            cancel-fn (atom nil)]
        ;; Need to bind scheduler for yield to work
        (binding [mt/*scheduler* sched]
          ;; Start a yield but capture the cancel function
          (reset! cancel-fn
                  ((mt/yield :should-not-see)
                   (fn [x] (reset! result x))
                   (fn [e] (reset! result [:error e])))))
        ;; Cancel it
        (@cancel-fn)
        ;; Step should be idle - yield was removed from queue
        (is (mt/idle? (mt/step! sched))
            "Queue should be empty after yield cancellation")
        ;; Result should be the cancelled error
        (is (vector? @result) "Should have error result")
        (is (= :error (first @result)))
        ;; Trace should have cancel-microtask event
        (let [cancel-events (filter #(= :cancel-microtask (:event %)) (mt/trace sched))]
          (is (= 1 (count cancel-events)) "Should have one cancel event")))))

  (testing "via-call cancellation removes from queue"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true})
            executed (atom false)
            cancel-fn (atom nil)]
        ;; Need to bind scheduler for via-call to work
        (binding [mt/*scheduler* sched]
          ;; Start a via-call but capture the cancel function
          (reset! cancel-fn
                  ((mt/via-call m/cpu (fn [] (reset! executed true) :result))
                   (fn [x] nil)
                   (fn [e] nil))))
        ;; Cancel it
        (@cancel-fn)
        ;; Step should be idle - via-call was removed from queue
        (is (mt/idle? (mt/step! sched))
            "Queue should be empty after via-call cancellation")
        (is (false? @executed) "Thunk should not have executed")
        ;; Trace should have cancel-microtask event
        (let [cancel-events (filter #(= :cancel-microtask (:event %)) (mt/trace sched))]
          (is (= 1 (count cancel-events)) "Should have one cancel event"))))))

;; -----------------------------------------------------------------------------
;; Timer-policy tests
;; -----------------------------------------------------------------------------

(deftest timer-policy-test
  (testing ":microtasks-first ensures microtask drains before timer promotion"
    ;; The :microtasks-first policy should drain all microtasks before promoting
    ;; timers. We test this by having a yield (microtask) and sleep 0 (timer at t=0)
    ;; running in parallel. With :microtasks-first, yield should complete before
    ;; the sleep timer is promoted.
    (mt/with-determinism
      ;; With :microtasks-first, microtask drains before timer is promoted
      (let [sched (mt/make-scheduler {:timer-policy :microtasks-first})
            order (atom [])
            result (mt/run sched
                           (m/sp
                             (m/? (m/join  ;; Need m/? to await the join
                                    vector
                                    ;; sleep 0 = timer due immediately, but won't be promoted
                                    ;; until microtask queue is empty
                                    (m/sp (m/? (m/sleep 0)) (swap! order conj :timer) :a)
                                    ;; yield = microtask, runs first
                                    (m/sp (m/? (mt/yield)) (swap! order conj :micro) :b)))))]
        ;; With :microtasks-first, the yield completes before sleep timer is promoted
        ;; Note: m/join adds its own microtasks, but the key ordering we're testing
        ;; is that the yield's effect happens before the sleep timer's effect
        (is (= [:micro :timer] @order)
            "Microtask should complete before timer with :microtasks-first"))))

  (testing ":timer-policy included in failure bundles"
    (mt/with-determinism
      ;; Check that timer-policy is passed through check-interleaving
      (let [result (mt/check-interleaving
                     (fn []
                       (let [order (atom [])]
                         (m/sp
                           (m/? (m/join  ;; Need m/? to await the join
                                  vector
                                  (m/sp (m/? (m/sleep 0)) (swap! order conj :timer) :a)
                                  (m/sp (m/? (mt/yield)) (swap! order conj :micro) :b)))
                           @order)))
                     {:num-tests 10
                      :seed 42
                      :timer-policy :microtasks-first
                      :property (fn [r] (= [:micro :timer] r))})]
        ;; With :microtasks-first, the order should always be [:micro :timer]
        (is (:ok? result)
            "With :microtasks-first, order should always be [:micro :timer]")))))

;; -----------------------------------------------------------------------------
;; Split RNG streams tests
;; -----------------------------------------------------------------------------

(deftest split-rng-streams-test
  (testing "duration-range doesn't affect interleaving order"
    (mt/with-determinism
      ;; Run same seed with and without duration-range, compare interleaving
      (let [make-task (fn []
                        (let [result (atom [])]
                          (m/sp
                            (m/join
                              vector
                              (m/sp (swap! result conj :a) (m/? (mt/yield)) (swap! result conj :a2))
                              (m/sp (swap! result conj :b) (m/? (mt/yield)) (swap! result conj :b2)))
                            @result)))
            ;; Without duration-range
            sched1 (mt/make-scheduler {:seed 42 :trace? true})
            result1 (mt/run sched1 (make-task))
            schedule1 (mt/trace->schedule (mt/trace sched1))
            ;; With duration-range
            sched2 (mt/make-scheduler {:seed 42 :duration-range [10 100] :trace? true})
            result2 (mt/run sched2 (make-task))
            schedule2 (mt/trace->schedule (mt/trace sched2))]
        ;; The interleaving order should be identical
        (is (= schedule1 schedule2)
            "Same seed should produce same interleaving with or without duration-range")
        (is (= result1 result2)
            "Same seed should produce same result with or without duration-range"))))

  (testing "different duration-range values don't change interleaving"
    (mt/with-determinism
      (let [make-task (fn []
                        (m/sp
                          (m/join vector
                                  (m/sp (m/? (mt/yield)) :a)
                                  (m/sp (m/? (mt/yield)) :b)
                                  (m/sp (m/? (mt/yield)) :c))))
            sched1 (mt/make-scheduler {:seed 123 :duration-range [1 10] :trace? true})
            _ (mt/run sched1 (make-task))
            schedule1 (mt/trace->schedule (mt/trace sched1))
            sched2 (mt/make-scheduler {:seed 123 :duration-range [100 1000] :trace? true})
            _ (mt/run sched2 (make-task))
            schedule2 (mt/trace->schedule (mt/trace sched2))]
        (is (= schedule1 schedule2)
            "Different duration-range should produce same interleaving")))))

;; -----------------------------------------------------------------------------
;; Thread pool tests
;; -----------------------------------------------------------------------------

(deftest cpu-thread-pool-test
  (testing "cpu-threads limits concurrent CPU tasks"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:cpu-threads 2 :duration-range [100 100] :trace? true})
            timeline (atom [])]
        (mt/run sched
                (m/sp
                  (m/? (m/join vector
                         (m/sp (swap! timeline conj [:cpu1-start (mt/now-ms sched)])
                               (m/? (m/via m/cpu :cpu1))
                               (swap! timeline conj [:cpu1-end (mt/now-ms sched)]))
                         (m/sp (swap! timeline conj [:cpu2-start (mt/now-ms sched)])
                               (m/? (m/via m/cpu :cpu2))
                               (swap! timeline conj [:cpu2-end (mt/now-ms sched)]))
                         (m/sp (swap! timeline conj [:cpu3-start (mt/now-ms sched)])
                               (m/? (m/via m/cpu :cpu3))
                               (swap! timeline conj [:cpu3-end (mt/now-ms sched)]))))))
        ;; With cpu-threads=2 and 100ms duration each:
        ;; - cpu1 and cpu2 start at 0, complete at 100
        ;; - cpu3 can't start until 100 (pool full), completes at 200
        (let [ends (filter #(= :end (second (first %))) (partition 2 @timeline))]
          ;; First two complete at 100ms
          (is (= 100 (second (first (filter #(#{:cpu1-end :cpu2-end} (first %)) @timeline))))
              "First CPU task should complete at 100ms")
          ;; Third completes at 200ms
          (is (= 200 (second (first (filter #(= :cpu3-end (first %)) @timeline))))
              "Third CPU task should complete at 200ms (had to wait for pool slot)")))))

  (testing "default cpu-threads is 8"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [100 100] :trace? true})]
        (mt/run sched
                (m/sp
                  ;; Start 10 CPU tasks - only 8 should be concurrent
                  (m/? (apply m/join vector
                              (for [i (range 10)]
                                (m/sp (m/? (m/via m/cpu i))))))))
        ;; Count max concurrent task-starts at any given time
        (let [trace (mt/trace sched)
              starts (filter #(and (= :task-start (:event %))
                                   (= :cpu (:lane %))) trace)
              ;; Group by start time
              starts-by-time (group-by :now-ms starts)]
          ;; At time 0, should have at most 8 starts
          (is (<= (count (get starts-by-time 0 [])) 8)
              "Should start at most 8 CPU tasks at time 0"))))))

(deftest blk-unlimited-threads-test
  (testing "blk lane has unlimited threads"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [100 100] :trace? true})
            timeline (atom [])]
        (mt/run sched
                (m/sp
                  (m/? (apply m/join vector
                              (for [i (range 20)]
                                (m/sp
                                  (swap! timeline conj [:start i (mt/now-ms sched)])
                                  (m/? (m/via m/blk i))
                                  (swap! timeline conj [:end i (mt/now-ms sched)])))))))
        ;; All 20 BLK tasks should start at t=0 and complete at t=100
        (let [starts (filter #(= :start (first %)) @timeline)
              ends (filter #(= :end (first %)) @timeline)]
          (is (every? #(= 0 (nth % 2)) starts)
              "All BLK tasks should start at t=0")
          (is (every? #(= 100 (nth % 2)) ends)
              "All BLK tasks should complete at t=100"))))))

(deftest default-lane-single-threaded-test
  (testing "default lane is single-threaded"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:duration-range [100 100] :trace? true})
            timeline (atom [])]
        (mt/run sched
                (m/sp
                  (m/? (m/join vector
                         (m/sp (swap! timeline conj [:a-start (mt/now-ms sched)])
                               (m/? (mt/yield))
                               (swap! timeline conj [:a-end (mt/now-ms sched)]))
                         (m/sp (swap! timeline conj [:b-start (mt/now-ms sched)])
                               (m/? (mt/yield))
                               (swap! timeline conj [:b-end (mt/now-ms sched)]))))))
        ;; Default lane is single-threaded, so tasks run sequentially
        ;; Both start at t=0 (enqueued), but execute one at a time
        ;; First yield completes at t=100, second at t=200
        (let [ends (->> @timeline
                        (filter #(#{:a-end :b-end} (first %)))
                        (sort-by second))]
          (is (= 100 (second (first ends)))
              "First task should complete at 100ms")
          (is (= 200 (second (second ends)))
              "Second task should complete at 200ms (sequential execution)"))))))

(deftest mixed-lanes-concurrent-test
  (testing "different lanes run concurrently"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:cpu-threads 2 :duration-range [100 100] :trace? true})
            timeline (atom [])]
        (mt/run sched
                (m/sp
                  (m/? (m/join vector
                         ;; CPU task
                         (m/sp (swap! timeline conj [:cpu-start (mt/now-ms sched)])
                               (m/? (m/via m/cpu :cpu))
                               (swap! timeline conj [:cpu-end (mt/now-ms sched)]))
                         ;; BLK task
                         (m/sp (swap! timeline conj [:blk-start (mt/now-ms sched)])
                               (m/? (m/via m/blk :blk))
                               (swap! timeline conj [:blk-end (mt/now-ms sched)]))
                         ;; Another CPU task
                         (m/sp (swap! timeline conj [:cpu2-start (mt/now-ms sched)])
                               (m/? (m/via m/cpu :cpu2))
                               (swap! timeline conj [:cpu2-end (mt/now-ms sched)]))))))
        ;; All three should run concurrently (different lanes or same lane with capacity)
        ;; and complete at 100ms
        (let [ends (filter #(#{:cpu-end :blk-end :cpu2-end} (first %)) @timeline)]
          (is (every? #(= 100 (second %)) ends)
              "All tasks from different lanes should complete at 100ms"))))))

(deftest complex-mixed-lanes-exploration-test
  (testing "explore interleavings with mixed CPU/BLK/default lanes"
    ;; This test explores how CPU pool limits, BLK unlimited threads, and default lane
    ;; interact to create different possible execution orderings while maintaining correctness
    (mt/with-determinism
      (let [make-task (fn []
                        (let [results (atom {})]
                          (m/sp
                            (m/? (m/join vector
                                   ;; Two CPU tasks competing for pool slots
                                   (m/sp
                                     (let [v (m/? (m/via m/cpu (* 2 21)))]
                                       (swap! results assoc :cpu1 v)
                                       v))
                                   (m/sp
                                     (let [v (m/? (m/via m/cpu (* 3 14)))]
                                       (swap! results assoc :cpu2 v)
                                       v))
                                   ;; Three BLK tasks (should all run concurrently)
                                   (m/sp
                                     (let [v (m/? (m/via m/blk :io-1))]
                                       (swap! results assoc :blk1 v)
                                       v))
                                   (m/sp
                                     (let [v (m/? (m/via m/blk :io-2))]
                                       (swap! results assoc :blk2 v)
                                       v))
                                   (m/sp
                                     (let [v (m/? (m/via m/blk :io-3))]
                                       (swap! results assoc :blk3 v)
                                       v))
                                   ;; Two yield tasks on default lane
                                   (m/sp
                                     (let [v (m/? (mt/yield :micro-1))]
                                       (swap! results assoc :yield1 v)
                                       v))
                                   (m/sp
                                     (let [v (m/? (mt/yield :micro-2))]
                                       (swap! results assoc :yield2 v)
                                       v))))
                            @results)))

            ;; Use check-interleaving to verify correctness across many orderings
            result (mt/check-interleaving
                     make-task
                     {:num-tests 100
                      :seed 54321
                      :duration-range [10 50]
                      :property (fn [r]
                                  ;; All computations must produce correct results
                                  (and (= 42 (:cpu1 r))
                                       (= 42 (:cpu2 r))
                                       (= :io-1 (:blk1 r))
                                       (= :io-2 (:blk2 r))
                                       (= :io-3 (:blk3 r))
                                       (= :micro-1 (:yield1 r))
                                       (= :micro-2 (:yield2 r))))})]
        (is (:ok? result)
            (str "All interleavings should produce correct results. Failure: "
                 (when-not (:ok? result) (:value result))))
        (is (= 100 (:iterations-run result))
            "Should complete all iterations"))))

  (testing "explore-interleavings reveals different orderings with mixed lanes"
    (mt/with-determinism
      (let [make-task (fn []
                        (let [order (atom [])]
                          (m/sp
                            (m/? (m/join vector
                                   (m/sp (m/? (m/via m/cpu (swap! order conj :cpu))) :cpu)
                                   (m/sp (m/? (m/via m/blk (swap! order conj :blk))) :blk)
                                   (m/sp (m/? (mt/yield (swap! order conj :yield))) :yield)))
                            @order)))

            exploration (mt/explore-interleavings
                          make-task
                          {:num-samples 50
                           :seed 99999
                           :duration-range [10 30]})]

        ;; Should see multiple unique orderings due to concurrent execution
        (is (> (:unique-results exploration) 1)
            "Should observe multiple unique execution orderings")

        ;; All results should have exactly 3 elements
        (is (every? #(= 3 (count (:result %))) (:results exploration))
            "Each result should capture exactly 3 operations")

        ;; Each result should contain all three operations
        (is (every? #(= #{:cpu :blk :yield} (set (:result %))) (:results exploration))
            "Each result should contain :cpu, :blk, and :yield"))))

  (testing "CPU pool saturation causes sequential execution for excess tasks"
    (mt/with-determinism
      ;; With cpu-threads=2 and duration=100, 4 tasks run in batches:
      ;; First batch (2 tasks): ends at 100ms
      ;; Second batch (2 tasks): ends at 200ms
      (let [sched (mt/make-scheduler {:cpu-threads 2 :duration-range [100 100] :seed 11111})
            timeline (atom [])
            _ (mt/run sched
                (m/sp
                  (m/? (m/join vector
                         (m/sp
                           (swap! timeline conj [:t1-start (mt/clock)])
                           (m/? (m/via m/cpu :t1))
                           (swap! timeline conj [:t1-end (mt/clock)]))
                         (m/sp
                           (swap! timeline conj [:t2-start (mt/clock)])
                           (m/? (m/via m/cpu :t2))
                           (swap! timeline conj [:t2-end (mt/clock)]))
                         (m/sp
                           (swap! timeline conj [:t3-start (mt/clock)])
                           (m/? (m/via m/cpu :t3))
                           (swap! timeline conj [:t3-end (mt/clock)]))
                         (m/sp
                           (swap! timeline conj [:t4-start (mt/clock)])
                           (m/? (m/via m/cpu :t4))
                           (swap! timeline conj [:t4-end (mt/clock)]))))))
            ends (->> @timeline
                      (filter #(#{:t1-end :t2-end :t3-end :t4-end} (first %)))
                      (map second)
                      sort
                      vec)]
        ;; First two tasks end at 100, next two at 200
        (is (= [100 100 200 200] ends)
            (str "CPU pool should cause batched execution. Got: " ends))))))

;; -----------------------------------------------------------------------------
;; Timeout Linearization Tests
;; -----------------------------------------------------------------------------
;;
;; These tests document the timeout linearization behavior and verify that the
;; testkit correctly models real Missionary timeout semantics.
;;
;; BACKGROUND:
;; In real Missionary, timeout races are determined by which callback executes
;; first. The testkit models this through microtask scheduling with lane-based
;; thread pool simulation.
;;
;; KEY INSIGHT:
;; Timer callbacks run on scheduler threads (not user thread pools). When using
;; proper lane separation (m/blk for blocking work, default lane for timers),
;; timers fire at their scheduled time without being blocked by user work.
;;
;; The testkit correctly handles three scenarios:
;; 1. Work completes BEFORE timeout: work wins (deterministic)
;; 2. Timeout fires BEFORE work completes: timeout wins (deterministic)
;; 3. Work and timeout complete at SAME instant: either can win (explores both)
;;
;; This third case is intentional - in real systems, simultaneous completion
;; would have non-deterministic outcomes based on thread scheduling.

(deftest timeout-linearization-real-missionary-parity-test
  ;; These tests verify the testkit produces results matching real Missionary.
  ;; Real Missionary scenarios tested:
  ;;   (m/? (m/race (m/timeout (m/via m/blk (Thread/sleep 500)) 200 :timeout)
  ;;               (m/sleep 250 :sleep))) ; -> :timeout
  ;;   (m/? (m/race (m/sp (Thread/sleep 400) :sleep1)
  ;;               (m/sleep 250 :sleep2))) ; -> :sleep2
  ;;   (m/? (m/join vector (m/sp (Thread/sleep 400) :sleep1)
  ;;               (m/sleep 250 :sleep2))) ; -> [:sleep1 :sleep2]

  (testing "timeout fires before blocking work completes - timeout wins"
    ;; Real Missionary: (m/? (m/race (m/timeout (m/via m/blk (Thread/sleep 500)) 200 :timeout)
    ;;                              (m/sleep 250 :sleep))) -> :timeout
    ;;
    ;; Work takes 500ms on m/blk, timeout is 200ms. Timer fires at t=200,
    ;; while work is still running. Timeout should always win.
    (mt/with-determinism
      (doseq [seed (range 1 6)]
        (let [sched (mt/make-scheduler {:seed seed :duration-range [500 500]})
              result (mt/run sched
                             (m/race
                              (m/timeout
                               (mt/via-call m/blk (fn [] :work-done))
                               200 :timeout)
                              (m/sleep 250 :sleep)))]
          (is (= :timeout result)
              (str "Seed " seed ": timeout (200ms) should beat work (500ms)"))))))

  (testing "blocking work beats sleep in race due to trampoline blocking"
    ;; Real Missionary: (m/? (m/race (m/sp (Thread/sleep 400) :sleep1)
    ;;                              (m/sleep 250 :sleep2))) -> :sleep1
    ;;
    ;; IMPORTANT: Thread/sleep inside m/sp blocks the trampoline thread.
    ;; The sleep timer fires at 250ms (on scheduler thread), but its
    ;; continuation cannot run until the trampoline is free at 400ms.
    ;; By then, the sp has already completed and delivered :sleep1.
    ;;
    ;; This is different from m/via which runs work on a separate executor.
    (mt/with-determinism
      (doseq [seed (range 1 6)]
        (let [sched (mt/make-scheduler {:seed seed :duration-range [400 400]})
              result (mt/run sched
                             (m/race
                              (m/sp (m/? (mt/yield)) :sleep1)
                              (m/sleep 250 :sleep2)))]
          (is (= :sleep1 result)
              (str "Seed " seed ": sp with blocking work (400ms) wins because trampoline is blocked")))))))

(deftest timeout-linearization-lane-separation-test
  ;; These tests verify that proper lane separation is critical for realistic
  ;; timeout behavior. When blocking work runs on m/blk and timers on default
  ;; lane, they don't block each other.

  (testing "timer fires at scheduled time when work is on separate lane"
    ;; With m/blk (unlimited threads), the timer isn't blocked by work.
    ;; Timer fires exactly at its scheduled time.
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:trace? true :seed 42 :duration-range [500 500]})
            _ (mt/run sched
                (m/timeout
                  (mt/via-call m/blk (fn [] :work))
                  200 :timeout))
            trace (mt/trace sched)
            timer-run (first (filter #(and (= :run-microtask (:event %))
                                           (= :timeout/timer (:kind %)))
                                     trace))]
        (is (= 200 (:now-ms timer-run))
            "Timer should run at exactly t=200, not blocked by m/blk work")))))

(deftest timeout-linearization-simultaneous-completion-test
  ;; When work duration EQUALS timeout duration, both complete at the same
  ;; virtual time. In our model, in-flight task completion is processed
  ;; before queued microtasks (timer callbacks), so work wins deterministically.
  ;;
  ;; This differs slightly from real systems where truly simultaneous completion
  ;; would have non-deterministic outcomes. However, the testkit correctly models
  ;; that work runs on a separate lane (m/blk) and completes independently.

  (testing "simultaneous completion: work wins because in-flight completes before queue"
    ;; When work duration equals timeout, both are ready at the same time.
    ;; In-flight task completion runs before queued timer callback, so work wins.
    (mt/with-determinism
      (doseq [seed (range 1 6)]
        (let [sched (mt/make-scheduler {:seed seed :duration-range [100 100]})
              result (mt/run sched
                       (m/timeout
                         (mt/via-call m/blk (fn [] :work))
                         100 :timeout))]
          (is (= :work result)
              (str "Seed " seed ": work wins simultaneous completion")))))))

(deftest timeout-linearization-deterministic-outcomes-test
  ;; When work and timeout complete at DIFFERENT times, the outcome should
  ;; be deterministic regardless of seed.

  (testing "work always wins when it completes before timeout"
    ;; Work: 50ms, Timeout: 200ms -> work wins at t=50
    (mt/with-determinism
      (doseq [seed (range 1 11)]
        (let [sched (mt/make-scheduler {:seed seed :duration-range [50 50]})
              result (mt/run sched
                       (m/timeout
                         (mt/via-call m/blk (fn [] :work))
                         200 :timeout))]
          (is (= :work result)
              (str "Seed " seed ": work (50ms) should always beat timeout (200ms)"))))))

  (testing "timeout always wins when it fires before work completes"
    ;; Work: 500ms, Timeout: 100ms -> timeout wins at t=100
    (mt/with-determinism
      (doseq [seed (range 1 11)]
        (let [sched (mt/make-scheduler {:seed seed :duration-range [500 500]})
              result (mt/run sched
                       (m/timeout
                         (mt/via-call m/blk (fn [] :work))
                         100 :timeout))]
          (is (= :timeout result)
              (str "Seed " seed ": timeout (100ms) should always beat work (500ms)")))))))

;; -----------------------------------------------------------------------------
;; amb= Flow Tests
;; -----------------------------------------------------------------------------
;;
;; These tests verify that m/amb= flows work correctly with the testkit,
;; matching real Missionary behavior for concurrent branch evaluation.
;;
;; Real Missionary scenarios tested:
;;   (m/? (m/reduce conj [] (m/ap (m/amb=
;;                                  (m/? (m/sleep 400 :sleep1))
;;                                  (m/? (m/sleep 300 :sleep2)))))) ; -> [:sleep2 :sleep1]
;;
;;   (m/? (m/reduce conj [] (m/ap (m/amb=
;;                                  (m/? (m/sp (m/? (m/via m/blk (Thread/sleep 50)))
;;                                             :sleep1))
;;                                  (m/? (m/sleep 10 :sleep2)))))) ; -> [:sleep2 :sleep1]

(deftest amb=-flow-timing-test
  (testing "amb= with two m/sleep timers - shorter timer wins"
    ;; Real Missionary: (m/? (m/reduce conj [] (m/ap (m/amb=
    ;;                                                (m/? (m/sleep 400 :sleep1))
    ;;                                                (m/? (m/sleep 300 :sleep2))))))
    ;; Result: [:sleep2 :sleep1]
    ;;
    ;; Both branches run concurrently. The 300ms timer fires before the 400ms timer,
    ;; so :sleep2 is emitted first, then :sleep1.
    (mt/with-determinism
      (doseq [seed (range 1 6)]
        (let [sched (mt/make-scheduler {:seed seed})
              result (mt/run sched
                       (m/reduce conj []
                         (m/ap (m/amb=
                                 (m/? (m/sleep 400 :sleep1))
                                 (m/? (m/sleep 300 :sleep2))))))]
          (is (= [:sleep2 :sleep1] result)
              (str "Seed " seed ": 300ms timer should complete before 400ms timer"))))))

  (testing "amb= with m/via m/blk vs m/sleep - timer beats blocking work"
    ;; Real Missionary: (m/? (m/reduce conj [] (m/ap (m/amb=
    ;;                                                (m/? (m/sp (m/? (m/via m/blk (Thread/sleep 50)))
    ;;                                                           :sleep1))
    ;;                                                (m/? (m/sleep 10 :sleep2))))))
    ;; Result: [:sleep2 :sleep1]
    ;;
    ;; First branch: blocking work (50ms) on m/blk lane, then returns :sleep1
    ;; Second branch: timer at 10ms, returns :sleep2
    ;; The 10ms timer fires before the 50ms blocking work completes.
    (mt/with-determinism
      (doseq [seed (range 1 6)]
        (let [sched (mt/make-scheduler {:seed seed :duration-range [50 50]})
              result (mt/run sched
                       (m/reduce conj []
                         (m/ap (m/amb=
                                 (m/? (m/sp (m/? (mt/via-call m/blk (fn [] nil)))
                                            :sleep1))
                                 (m/? (m/sleep 10 :sleep2))))))]
          (is (= [:sleep2 :sleep1] result)
              (str "Seed " seed ": 10ms timer should beat 50ms blocking work"))))))

  (testing "amb= with blocking work vs m/sleep - blocking completes first"
    ;; Real Missionary: (m/? (m/reduce conj [] (m/ap (m/amb=
    ;;                                                (m/? (m/sp (Thread/sleep 400) :sleep1))
    ;;                                                (m/? (m/sleep 300 :sleep2))))))
    ;; Result: [:sleep1 :sleep2]
    ;;
    ;; KEY INSIGHT: Thread/sleep inside m/sp blocks the trampoline thread.
    ;; Both branches start concurrently (m/? suspends, triggering amb= fork),
    ;; but the sleep timer's continuation cannot run while the trampoline
    ;; is blocked by Thread/sleep in the first branch.
    ;;
    ;; Timeline:
    ;; - t=0: Both branches start (amb= forks on m/? suspension)
    ;; - t=0: Branch 1's sp starts blocking (Thread/sleep)
    ;; - t=0: Branch 2's sleep timer scheduled for t=300
    ;; - t=300: Sleep timer fires, but continuation blocked (trampoline busy)
    ;; - t=400: Thread/sleep completes, sp returns :sleep1
    ;; - t=400: Sleep continuation can now run, returns :sleep2
    ;;
    ;; Model with mt/yield: yield's duration blocks the :default lane,
    ;; preventing sleep timer callbacks from running until yield completes.
    (mt/with-determinism
      (doseq [seed (range 1 6)]
        (let [sched (mt/make-scheduler {:seed seed})
              result (mt/run sched
                       (m/reduce conj []
                         (m/ap (m/amb=
                                (m/? (m/sp (m/? (mt/yield :sleep1 {:duration-fn (constantly 400)}))))
                                (m/? (m/sleep 300 :sleep2))))))]
          (is (= [:sleep1 :sleep2] result)
              (str "Seed " seed ": blocking work completes first because trampoline is blocked")))))))
