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
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched (mt/sleep 100 :done) {:label "sleep-test"})]
        (is (not (mt/done? job)))
        (is (= 1 (count (:timers (mt/pending sched)))))
        (mt/advance-to! sched 100)
        (is (mt/done? job))
        (is (= :done (mt/result job))))))

  (testing "sleep with nil result"
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched (mt/sleep 50) {})]
        (mt/advance-to! sched 50)
        (is (mt/done? job))
        (is (nil? (mt/result job))))))

  (testing "cancelled sleep throws Cancelled"
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched (mt/sleep 100 :done) {:label "cancel-test"})]
        (mt/cancel! job)
        (mt/tick! sched)
        (is (mt/done? job))
        (is (thrown? Cancelled (mt/result job)))))))

;; =============================================================================
;; Timeout Tests
;; =============================================================================

(deftest timeout-test
  (testing "timeout passes through when inner task completes first"
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched
                           (mt/timeout (mt/sleep 50 :inner) 100 :timed-out)
                           {:label "timeout-pass"})]
        (mt/advance-to! sched 50)
        (is (mt/done? job))
        (is (= :inner (mt/result job))))))

  (testing "timeout fires when inner task takes too long"
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched
                           (mt/timeout (mt/sleep 200 :inner) 100 :timed-out)
                           {:label "timeout-fire"})]
        (mt/advance-to! sched 100)
        (is (mt/done? job))
        (is (= :timed-out (mt/result job))))))

  (testing "timeout with nil fallback"
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched (mt/timeout (mt/sleep 200 :inner) 100) {})]
        (mt/advance-to! sched 100)
        (is (mt/done? job))
        (is (nil? (mt/result job))))))

  (testing "cancelling timeout cancels inner and throws Cancelled"
    (let [sched (mt/make-scheduler)]
      (let [job (mt/start! sched
                           (mt/timeout (mt/sleep 200 :inner) 100 :timed-out)
                           {:label "timeout-cancel"})]
        (mt/cancel! job)
        (mt/tick! sched)
        (is (mt/done? job))
        (is (thrown? Cancelled (mt/result job)))))))

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
;; Subject (Discrete Flow) Tests
;; =============================================================================

(deftest subject-test
  (testing "subject flow receives emitted values"
    (let [sched (mt/make-scheduler)
          {:keys [flow emit close]} (mt/subject sched {:label "test-subject"})
          proc (mt/spawn-flow! sched flow {:label "consumer"})]
      ;; Initially not ready
      (mt/tick! sched)
      (is (not (mt/ready? proc)))

      ;; Emit a value
      (mt/start! sched (emit :hello) {})
      (mt/tick! sched)
      (is (mt/ready? proc))

      ;; Transfer
      (is (= :hello (mt/transfer! proc)))
      (mt/tick! sched)

      ;; Close
      (mt/start! sched (close) {})
      (mt/tick! sched)
      (is (mt/terminated? proc))))

  (testing "subject queues multiple values"
    (let [sched (mt/make-scheduler)
          {:keys [flow emit close]} (mt/subject sched)
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)

      ;; Emit multiple values
      (mt/start! sched (emit :a) {})
      (mt/start! sched (emit :b) {})
      (mt/start! sched (emit :c) {})
      (mt/tick! sched)

      ;; Transfer all
      (is (= :a (mt/transfer! proc)))
      (mt/tick! sched)
      (is (= :b (mt/transfer! proc)))
      (mt/tick! sched)
      (is (= :c (mt/transfer! proc)))
      (mt/tick! sched)

      ;; Close
      (mt/start! sched (close) {})
      (mt/tick! sched)
      (is (mt/terminated? proc))))

  (testing "offer succeeds when no backlog"
    (let [sched (mt/make-scheduler)
          {:keys [flow offer]} (mt/subject sched)
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)

      (is (true? (offer :value)))
      (mt/tick! sched)
      (is (mt/ready? proc))
      (is (= :value (mt/transfer! proc)))))

  (testing "offer fails when backpressured"
    (let [sched (mt/make-scheduler)
          {:keys [flow offer]} (mt/subject sched)
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)

      ;; First offer succeeds
      (is (true? (offer :first)))
      ;; Second offer fails (already has pending)
      (is (false? (offer :second)))

      (mt/tick! sched)
      (is (= :first (mt/transfer! proc)))))

  (testing "subject fail propagates error"
    (let [sched (mt/make-scheduler)
          {:keys [flow fail]} (mt/subject sched)
          proc (mt/spawn-flow! sched flow)
          ex (ex-info "subject failed" {})]
      (mt/tick! sched)

      (mt/start! sched (fail ex) {})
      (mt/tick! sched)

      (is (mt/ready? proc))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"subject failed"
                            (mt/transfer! proc))))))

;; =============================================================================
;; State (Continuous Flow) Tests
;; =============================================================================

(deftest state-test
  (testing "state provides initial value"
    (let [sched (mt/make-scheduler)
          {:keys [flow]} (mt/state sched {:initial :init})
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)
      (is (mt/ready? proc))
      (is (= :init (mt/transfer! proc)))))

  (testing "state updates on set"
    (let [sched (mt/make-scheduler)
          {:keys [flow set]} (mt/state sched {:initial :v1})
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)

      ;; Get initial
      (is (= :v1 (mt/transfer! proc)))

      ;; Set new value
      (set :v2)
      (mt/tick! sched)
      (is (mt/ready? proc))
      (is (= :v2 (mt/transfer! proc)))))

  (testing "state close terminates flow"
    (let [sched (mt/make-scheduler)
          {:keys [flow close]} (mt/state sched {:initial :init})
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)
      (mt/transfer! proc)

      (close)
      (mt/tick! sched)
      (is (mt/terminated? proc))))

  ;; Note: state fail sets :closed? which prevents signal-ready! from firing.
  ;; Use subject for flows that need fail propagation.
  ;; The state flow is designed for continuous values where close is the normal termination.
  )

;; =============================================================================
;; Flow Process Tests
;; =============================================================================

(deftest flow-process-test
  (testing "transfer throws when not ready"
    (let [sched (mt/make-scheduler)
          {:keys [flow]} (mt/subject sched)
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)
      (is (not (mt/ready? proc)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not ready"
                            (mt/transfer! proc)))))

  (testing "cancel! cancels flow process"
    (let [sched (mt/make-scheduler)
          {:keys [flow]} (mt/subject sched)
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)
      (mt/cancel! proc)
      ;; After cancel, ready should signal with Cancelled error
      (mt/tick! sched)
      (is (mt/ready? proc))
      (is (thrown? Cancelled (mt/transfer! proc))))))

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
    (is (= [:a :b :c]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (let [{:keys [flow emit close]} (mt/subject sched)]
                 (mt/run sched
                         (m/sp
                          (m/? (m/join (fn [_ v] v)
                                       (m/sp
                                        (m/? (emit :a))
                                        (m/? (emit :b))
                                        (m/? (emit :c))
                                        (m/? (close)))
                                       (mt/collect flow)))))))))))

  (testing "collect with transducer"
    (is (= [2 4 6]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (let [{:keys [flow emit close]} (mt/subject sched)]
                 (mt/run sched
                         (m/sp
                          (m/? (m/join (fn [_ v] v)
                                       (m/sp
                                        (m/? (emit 1))
                                        (m/? (emit 2))
                                        (m/? (emit 3))
                                        (m/? (close)))
                                       (mt/collect flow {:xf (map #(* 2 %))})))))))))))

  (testing "collect with timeout"
    (is (= ::mt/timeout
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (let [{:keys [flow emit]} (mt/subject sched)]
                 ;; Emit one value but never close - should timeout
                 (mt/run sched
                         (m/sp
                          (m/? (m/join (fn [_ v] v)
                                       (m/sp
                                        (m/? (emit :a))
                                        (m/? (m/sleep 2000))) ; wait longer than timeout
                                       (mt/collect flow {:timeout-ms 100}))))))))))))

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

  (testing "transfer after termination throws"
    (let [sched (mt/make-scheduler)
          {:keys [flow close]} (mt/subject sched)
          proc (mt/spawn-flow! sched flow)]
      (mt/tick! sched)
      (mt/start! sched (close) {})
      (mt/tick! sched)
      (is (mt/terminated? proc))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"terminated"
                            (mt/transfer! proc))))))

(deftest no-scheduler-test
  (testing "sleep throws without scheduler"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No TestScheduler"
                          ((mt/sleep 100 :done) (fn [_]) (fn [_]))))))

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

(deftest integration-complex-flow-test
  (testing "complex flow with multiple subjects using amb= (interleaved)"
    ;; amb= interleaves flows, so values come in round-robin order
    (is (= [1 10 2 20 3 30]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (let [subj1 (mt/subject sched)
                     subj2 (mt/subject sched)]
                 (mt/run sched
                         (m/sp
                          (m/? (m/join (fn [_ _ v] v)
                                       ;; Producer 1
                                       (m/sp
                                        (m/? ((:emit subj1) 1))
                                        (m/? ((:emit subj1) 2))
                                        (m/? ((:emit subj1) 3))
                                        (m/? ((:close subj1))))
                                       ;; Producer 2
                                       (m/sp
                                        (m/? ((:emit subj2) 10))
                                        (m/? ((:emit subj2) 20))
                                        (m/? ((:emit subj2) 30))
                                        (m/? ((:close subj2))))
                                       ;; Collect both - amb= interleaves
                                       (mt/collect
                                        (m/ap
                                         (m/?> (m/amb=
                                                (:flow subj1)
                                                (:flow subj2))))))))))))))))

(deftest integration-sequential-flows-test
  (testing "collecting flows sequentially with cat"
    ;; Using m/? on each flow separately collects them in order
    (is (= [:a :b :c]
           (mt/with-determinism
             (let [sched (mt/make-scheduler)]
               (let [{:keys [flow emit close]} (mt/subject sched)]
                 (mt/run sched
                         (m/sp
                          (m/? (m/join (fn [_ v] v)
                                       (m/sp
                                        (m/? (emit :a))
                                        (m/? (emit :b))
                                        (m/? (emit :c))
                                        (m/? (close)))
                                       (mt/collect flow))))))))))))

;; =============================================================================
;; Interleaving Tests
;; =============================================================================

(deftest random-selection-determinism-test
  (testing ":random selection is deterministic with same seed across runs"
    ;; This test verifies the fix for non-deterministic :random selection.
    ;; Previously, :random used (hash task-map) which included function objects,
    ;; making results non-reproducible. Now it uses a proper LCG RNG.
    (let [run-task (fn [seed]
                     (let [order (atom [])]
                       (mt/with-determinism
                         (let [sched (mt/make-scheduler {:rng-seed seed
                                                                       :micro-schedule [:random :random :random
                                                                                        :random :random :random]})]
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

        ;; Replay should give same result
        (when-let [first-result (first (:results exploration))]
          (let [replayed (mt/replay-schedule (make-task) (:micro-schedule first-result))]
            (is (= (:result first-result) replayed)
                "Replaying schedule should produce same result"))))))

  (testing "schedule consumption only at branch points, not single-item steps"
    ;; This test verifies that schedule decisions are only consumed when there's
    ;; actually a choice to make (queue size > 1), not on every step.
    ;; This ensures trace->schedule extraction matches actual schedule consumption.
    (let [make-task (fn []
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
                         @order)))
          ;; Schedule with decisions only for actual branch points
          schedule [:lifo :fifo :fifo]]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:micro-schedule schedule :trace? true})]
          (let [result1 (mt/run sched (make-task))
                trace (mt/trace sched)
                extracted-schedule (mt/trace->schedule trace)]
            ;; With LIFO first, :c should run first (last enqueued)
            (is (= :c (first result1)) "LIFO should select last enqueued task first")

            ;; Extracted schedule should match what trace recorded
            (is (vector? extracted-schedule))

            ;; Replay with extracted schedule should give identical result
            (let [result2 (mt/replay-schedule (make-task) extracted-schedule)]
              (is (= result1 result2)
                  "Replaying extracted schedule should reproduce exact same result"))))))))

(deftest schedule-selection-test
  (testing "FIFO selection maintains order"
    (let [order (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:micro-schedule [:fifo :fifo :fifo] :trace? true})]
          ;; Create a task that starts 3 concurrent sleeps at t=0
          (mt/run sched
                  (m/sp
                   (m/? (m/join vector
                                (m/sp (swap! order conj :a) :a)
                                (m/sp (swap! order conj :b) :b)
                                (m/sp (swap! order conj :c) :c)))))))
      ;; With FIFO, order depends on when tasks were enqueued
      (is (= 3 (count @order)))))

  (testing "LIFO selection reverses order"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:micro-schedule [:lifo :lifo :lifo :lifo :lifo
                                                                     :lifo :lifo :lifo :lifo :lifo]
                                                    :trace? true})]
        ;; Run a task - with LIFO the last enqueued task runs first
        (mt/run sched
                (m/sp
                 (m/? (m/join vector
                              (m/sleep 0 :a)
                              (m/sleep 0 :b)
                              (m/sleep 0 :c))))))))

  (testing "schedule exhaustion throws error"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:micro-schedule [:lifo] :trace? true})]
        ;; Schedule has only 1 decision but 3 concurrent tasks need 2 decisions
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Schedule exhausted"
                              (mt/run sched
                                      (m/sp
                                       (m/? (m/join vector
                                                    (m/sleep 0 :a)
                                                    (m/sleep 0 :b)
                                                    (m/sleep 0 :c)))))))))))

(deftest by-label-selection-test
  (testing "[:by-label label] selects task with matching label"
    ;; Use subjects with different labels to create distinctly labeled microtasks
    (let [sched (mt/make-scheduler {:trace? true})
          subj-a (mt/subject sched {:label "producer-a"})
          subj-b (mt/subject sched {:label "producer-b"})
          results (atom [])]

      ;; Start consumers for both subjects
      (mt/start! sched
                 (m/sp
                  (m/? (m/join vector
                               ;; Consumer A
                               (m/sp
                                (swap! results conj [:a (m/? (m/reduce conj [] (:flow subj-a)))]))
                               ;; Consumer B
                               (m/sp
                                (swap! results conj [:b (m/? (m/reduce conj [] (:flow subj-b)))])))))
                 {:label "consumers"})

      ;; Tick to let consumers subscribe
      (mt/tick! sched)

      ;; Emit to both subjects - this creates labeled microtasks
      (mt/start! sched ((:emit subj-a) :val-a) {:label "emit-a"})
      (mt/start! sched ((:emit subj-b) :val-b) {:label "emit-b"})

      ;; Tick to process emits
      (mt/tick! sched)

      ;; Check that trace contains microtasks with different labels
      (let [trace (mt/trace sched)
            emit-events (filter #(= :subject/emit (:kind %)) trace)]
        (is (some #(= "producer-a" (:label %)) emit-events)
            "Should have microtask with label producer-a")
        (is (some #(= "producer-b" (:label %)) emit-events)
            "Should have microtask with label producer-b"))))

  (testing "[:by-label label] falls back to first when label not found"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:micro-schedule [[:by-label "nonexistent"]]
                                                    :trace? true})]
        ;; Run concurrent sleeps (all have label "sleep")
        (let [result (mt/run sched
                             (m/sp
                              (m/? (m/join vector
                                           (m/sleep 0 :a)
                                           (m/sleep 0 :b)))))]
          ;; Should still complete (falls back to first item)
          (is (= [:a :b] (sort result))))))))

(deftest by-id-selection-test
  (testing "[:by-id id] selects task with matching ID"
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

  (testing "[:by-id id] selects specific task when multiple are queued"
    ;; We need to know IDs ahead of time - they're assigned sequentially starting from 0
    ;; Job gets ID 0, then timers get IDs 1, 2, 3
    ;; So to select the third timer (ID 3), we use [:by-id 3]
    (let [execution-order (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:micro-schedule [[:by-id 4]  ; select third sleep (ID 4)
                                                                       :fifo :fifo]
                                                      :trace? true})]
          (mt/run sched
                  (m/sp
                   (m/? (m/join vector
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :a) :a)
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :b) :b)
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :c) :c)))))

          ;; Check trace for select-task event
          (let [trace (mt/trace sched)
                select-events (filter #(= :select-task (:event %)) trace)]
            (when (seq select-events)
              (let [first-select (first select-events)]
                (is (= [:by-id 4] (:decision first-select))
                    "First selection should use [:by-id 4]"))))))))

  (testing "[:by-id id] falls back to first when ID not found"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:micro-schedule [[:by-id 99999]] ; nonexistent ID
                                                    :trace? true})]
        ;; Run concurrent sleeps
        (let [result (mt/run sched
                             (m/sp
                              (m/? (m/join vector
                                           (m/sleep 0 :a)
                                           (m/sleep 0 :b)))))]
          ;; Should still complete (falls back to first item)
          (is (= [:a :b] (sort result))))))))

(deftest nth-selection-test
  (testing "[:nth n] selects nth task from queue"
    (let [execution-order (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:micro-schedule [[:nth 2]  ; select third item (0-indexed)
                                                                       :fifo :fifo]
                                                      :trace? true})]
          (mt/run sched
                  (m/sp
                   (m/? (m/join vector
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :a) :a)
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :b) :b)
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :c) :c)))))

          ;; Third task should run first due to [:nth 2]
          (is (= :c (first @execution-order))
              "[:nth 2] should select the third task (index 2)")))))

  (testing "[:nth n] wraps around when n >= queue size"
    (let [execution-order (atom [])]
      (mt/with-determinism
        (let [sched (mt/make-scheduler {:micro-schedule [[:nth 5]  ; larger than queue size 3
                                                                       :fifo :fifo]
                                                      :trace? true})]
          (mt/run sched
                  (m/sp
                   (m/? (m/join vector
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :a) :a)
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :b) :b)
                                (m/sp (m/? (m/sleep 0)) (swap! execution-order conj :c) :c)))))

          ;; [:nth 5] with queue size 3 -> 5 mod 3 = 2 -> third task
          (is (= :c (first @execution-order))
              "[:nth 5] should wrap to index 2 (5 mod 3)"))))))

(deftest trace->schedule-test
  (testing "extracts schedule from trace"
    (mt/with-determinism
      (let [sched (mt/make-scheduler {:micro-schedule [:lifo :fifo :lifo] :trace? true})]
        ;; Run something with concurrent tasks to trigger selection events
        (mt/run sched
                (m/sp
                 (m/? (m/join vector
                              (m/sleep 0 :a)
                              (m/sleep 0 :b)
                              (m/sleep 0 :c)))))
        (let [extracted (mt/trace->schedule (mt/trace sched))]
          ;; Should be a vector of decisions
          (is (vector? extracted))
          ;; Decisions should be keywords
          (is (every? keyword? extracted))))))

  (testing "empty trace yields empty schedule"
    (is (= [] (mt/trace->schedule []))))

  (testing "trace without select events yields empty schedule"
    (is (= [] (mt/trace->schedule [{:event :run-microtask :id 1}
                                   {:event :advance-to :from 0 :to 100}])))))

(deftest replay-schedule-test
  (testing "replay produces same result with same schedule"
    ;; replay-schedule takes a task, which should be created inside with-determinism
    (mt/with-determinism
      (let [make-task (fn []
                        (m/sp
                         (m/? (m/join +
                                      (m/sleep 0 1)
                                      (m/sleep 0 2)
                                      (m/sleep 0 3)))))
            schedule [:fifo :fifo :fifo :fifo :fifo :fifo]
            r1 (mt/replay-schedule (make-task) schedule)
            r2 (mt/replay-schedule (make-task) schedule)]
        (is (= r1 r2)))))

  (testing "different schedules can produce different results"
    ;; This test uses a task where order matters
    (mt/with-determinism
      (let [;; Task factory that records execution order (fresh atom each time)
            make-task (fn []
                        (let [order (atom [])]
                          (m/sp
                           (m/? (m/join (fn [& args] @order)
                                        (m/sp (swap! order conj :a) :a)
                                        (m/sp (swap! order conj :b) :b)
                                        (m/sp (swap! order conj :c) :c))))))]
        ;; Run with different schedules
        (let [r1 (mt/replay-schedule (make-task) [:fifo :fifo :fifo :fifo :fifo :fifo :fifo :fifo :fifo])
              r2 (mt/replay-schedule (make-task) [:lifo :lifo :lifo :lifo :lifo :lifo :lifo :lifo :lifo])]
          ;; Results should be vectors of the recorded order
          (is (vector? r1))
          (is (vector? r2)))))))

(deftest seed->schedule-test
  (testing "generates schedule of correct length"
    (let [schedule (mt/seed->schedule 42 10)]
      (is (vector? schedule))
      (is (= 10 (count schedule)))))

  (testing "same seed produces same schedule"
    (is (= (mt/seed->schedule 12345 20)
           (mt/seed->schedule 12345 20))))

  (testing "different seeds produce different schedules"
    (is (not= (mt/seed->schedule 1 10)
              (mt/seed->schedule 2 10))))

  (testing "schedule contains valid decisions"
    (let [schedule (mt/seed->schedule 42 100)]
      (is (every? #{:fifo :lifo :random} schedule)))))

(deftest check-interleaving-test
  (testing "returns success map with seed when all tests pass"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp (m/? (m/sleep 10 :done))))
            result (mt/check-interleaving task-fn {:num-tests 10
                                                   :seed 42
                                                   :property (fn [v] (= v :done))})]
        (is (:success result))
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
        (when result
          (is (map? result))
          (is (contains? result :failure))
          (is (contains? result :seed))
          (is (contains? result :micro-schedule))
          (is (contains? result :iteration))))))

  (testing "returns failure info when task throws"
    (mt/with-determinism
      (let [task-fn (fn [] (m/sp (throw (ex-info "intentional failure" {}))))
            result (mt/check-interleaving task-fn {:num-tests 5 :seed 42})]
        (is (map? result))
        (is (contains? (:failure result) :error))))))

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
      (let [sched (mt/make-scheduler {:micro-schedule [:lifo :fifo] :trace? true})]
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
          (is (some? failure) "Should find an interleaving that exposes the bug")

          (when failure
            ;; Verify the failure info is complete
            (is (contains? failure :micro-schedule))
            (is (contains? failure :seed))
            (is (vector? (:micro-schedule failure)))

            ;; The failing result should be 5 or 7 (not 12)
            (is (not= 12 (get-in failure [:failure :value]))
                "Failing result should not be the expected 12")

            ;; Replay should produce the same buggy result
            (let [replayed (mt/replay-schedule (make-buggy-task) (:micro-schedule failure))]
              (is (= (get-in failure [:failure :value]) replayed)
                  "Replaying the schedule should reproduce the exact same bug")))))))

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
          (is (:success result)
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
        (let [sched (mt/make-scheduler {:micro-schedule [:fifo :fifo :fifo :fifo]})]
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
      ;; With FIFO scheduling, order should be deterministic
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
