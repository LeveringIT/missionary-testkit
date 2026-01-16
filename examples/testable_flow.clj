(ns examples.testable-flow
  "Demonstrates how to test flows deterministically with missionary-testkit.

  Key insight: m/watch and m/signal work deterministically when atom mutations
  happen inside the controlled task, not from external threads."
  (:require [missionary.core :as m]
            [de.levering-it.missionary-testkit :as mt]))

;; =============================================================================
;; Testing with m/watch - Internal Mutations are Deterministic
;; =============================================================================

;; When you modify atoms from within the scheduled task, signal propagation
;; is synchronous and deterministic.

(defn counter-display
  "Displays counter value from a continuous flow."
  [counter-flow]
  (m/ap
    (let [n (m/?< counter-flow)]
      (str "Count: " n))))

;; Test usage - modify atom inside the controlled task:
(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)
          !counter (atom 0)]
      (mt/run sched
        (m/sp
          (m/? (m/race
                 ;; Consumer collects 3 values then stops
                 (m/reduce (fn [acc v]
                             (let [acc (conj acc v)]
                               (if (= 3 (count acc))
                                 (reduced acc)
                                 acc)))
                           [] (counter-display (m/watch !counter)))
                 ;; Producer - mutations happen at controlled yield points
                 (m/sp
                   (m/? (m/sleep 100))
                   (swap! !counter inc)  ; signal propagates synchronously
                   (m/? (m/sleep 100))
                   (swap! !counter inc)
                   (m/? (m/sleep 100))))))))))
  ;; => ["Count: 0" "Count: 1" "Count: 2"]

;; =============================================================================
;; Testing with m/signal - DAG propagation is synchronous
;; =============================================================================

(defn doubled-counter
  "Creates a signal that doubles the input signal."
  [input-signal]
  (m/signal (m/latest #(* 2 %) input-signal)))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)
          !input (atom 1)]
      (mt/run sched
        (m/sp
          (let [<x (m/signal (m/watch !input))
                <doubled (doubled-counter <x)
                results (atom [])]
            (m/? (m/race
                   ;; Consumer
                   (m/reduce (fn [_ v]
                               (swap! results conj v)
                               (when (= 3 (count @results))
                                 (reduced @results)))
                             nil <doubled)
                   ;; Producer
                   (m/sp
                     (m/? (m/sleep 100))
                     (swap! !input inc)
                     (m/? (m/sleep 100))
                     (swap! !input inc)
                     (m/? (m/sleep 100)))))))))))
  ;; => [2 4 6]

;; =============================================================================
;; Testing discrete flows with m/seed
;; =============================================================================

(defn event-processor
  "Processes events from a discrete flow."
  [event-flow]
  (m/ap
    (let [event (m/?> event-flow)]
      {:processed true :event event})))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched
        (m/sp
          (m/? (mt/collect
                 (event-processor
                   (m/seed [:click :scroll :keypress])))))))))
  ;; => [{:processed true :event :click}
  ;;     {:processed true :event :scroll}
  ;;     {:processed true :event :keypress}]

;; =============================================================================
;; RUNNING THE EXAMPLES
;; =============================================================================

(defn run-examples []
  (println "=== Example 1: counter-display with m/watch ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)
                       !counter (atom 0)]
                   (mt/run sched
                     (m/sp
                       (m/? (m/race
                              ;; Consumer collects 3 values
                              (m/reduce (fn [acc v]
                                          (let [acc (conj acc v)]
                                            (if (= 3 (count acc))
                                              (reduced acc)
                                              acc)))
                                        [] (counter-display (m/watch !counter)))
                              ;; Producer
                              (m/sp
                                (m/? (m/sleep 100))
                                (swap! !counter inc)
                                (m/? (m/sleep 100))
                                (swap! !counter inc)
                                (m/? (m/sleep 100)))))))))]
    (println "Result:" result))

  (println "\n=== Example 2: event-processor with m/seed ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched
                     (m/sp
                       (m/? (mt/collect
                              (event-processor
                                (m/seed [:click :scroll :keypress]))))))))]
    (println "Result:" result)))

(comment
  (run-examples))
