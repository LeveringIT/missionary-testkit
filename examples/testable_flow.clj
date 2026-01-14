(ns examples.testable-flow
  "Demonstrates how to design flows for testability with missionary-testkit.

  The key pattern: accept flows as parameters rather than creating them internally.
  This allows injecting mt/state in tests instead of m/watch on real atoms."
  (:require [missionary.core :as m]
            [de.levering-it.missionary-testkit :as mt]))

;; =============================================================================
;; THE PROBLEM: Hard-to-test code
;; =============================================================================

;; This component creates the watch internally - difficult to test deterministically
(defn counter-display-bad [counter-atom]
  (m/ap
    (let [n (m/?< (m/watch counter-atom))]  ; watch is created inside
      (str "Count: " n))))

;; To test this, you'd need real atoms and real timing:
;; (def a (atom 0))
;; (future (Thread/sleep 100) (reset! a 1))  ; non-deterministic!

;; =============================================================================
;; SOLUTION 1: Accept the flow as a parameter
;; =============================================================================

(defn counter-display
  "Displays counter value. Accepts any continuous flow."
  [counter-flow]
  (m/ap
    (let [n (m/?< counter-flow)]
      (str "Count: " n))))

;; Production usage:
(comment
  (def app-state (atom 0))
  (counter-display (m/watch app-state)))

;; Test usage - fully deterministic:
(comment
  (mt/with-determinism [sched (mt/make-scheduler)]
    (let [{:keys [flow set close]} (mt/state sched {:initial 0})]
      (mt/run sched
        (m/sp
          (m/? (m/join (fn [_ v] v)
                 ;; Simulate state changes at controlled times
                 (m/sp
                   (m/? (m/sleep 100))
                   (set 1)
                   (m/? (m/sleep 100))
                   (set 2)
                   (m/? (m/sleep 100))
                   (close))
                 ;; Test the component
                 (mt/collect (counter-display flow)))))))))
  ;; => ["Count: 0" "Count: 1" "Count: 2"]

;; =============================================================================
;; SOLUTION 2: Accept a state source map
;; =============================================================================

;; mt/state returns {:flow ... :set ... :close ... :fail ...}
;; Design components to accept this shape directly

(defn stateful-counter
  "A counter component that can increment its own state.
  Accepts a state source with :flow and :set keys."
  [{:keys [flow set]}]
  (m/ap
    (let [n (m/?< flow)]
      ;; Component can both read and write state
      {:value n
       :increment! (fn [] (set (inc n)))})))

;; Production: wrap an atom to match the interface
(comment
  (defn atom->state-source [a]
    {:flow (m/watch a)
     :set (fn [v] (reset! a v))})

  (def app-state (atom 0))
  (stateful-counter (atom->state-source app-state)))

;; Test: mt/state already returns the right shape!
(comment
  (mt/with-determinism [sched (mt/make-scheduler)]
    (let [state (mt/state sched {:initial 0})]
      (mt/run sched
        (m/sp
          (m/? (m/join (fn [_ v] v)
                 (m/sp
                   (m/? (m/sleep 100))
                   ((:set state) 42)
                   (m/? (m/sleep 100))
                   ((:close state)))
                 (mt/collect (m/eduction (map :value) (stateful-counter state))))))))))
  ;; => [0 42]

;; =============================================================================
;; SOLUTION 3: Event streams with mt/subject
;; =============================================================================

;; Same pattern applies to discrete flows (m/observe -> mt/subject)

(defn event-logger
  "Logs events from a discrete flow."
  [event-flow]
  (m/ap
    (let [event (m/?> event-flow)]
      (println "Event:" event)
      event)))

;; Production usage:
(comment
  (defn dom-clicks [element]
    (m/observe (fn [emit!]
                 (.addEventListener element "click" emit!)
                 #(.removeEventListener element "click" emit!))))

  (event-logger (dom-clicks my-button)))

;; Test usage:
(comment
  (mt/with-determinism [sched (mt/make-scheduler)]
    (let [{:keys [flow emit close]} (mt/subject sched)]
      (mt/run sched
        (m/sp
          (m/? (m/join (fn [_ v] v)
                 ;; Simulate click events
                 (m/sp
                   (m/? (emit {:type :click :x 100 :y 200}))
                   (m/? (emit {:type :click :x 150 :y 250}))
                   (m/? (close)))
                 ;; Test the logger
                 (mt/collect (event-logger flow)))))))))
  ;; => [{:type :click :x 100 :y 200} {:type :click :x 150 :y 250}]

;; =============================================================================
;; RUNNING THE EXAMPLES
;; =============================================================================

(defn run-examples []
  (println "=== Example 1: counter-display with mt/state ===")
  (let [result (mt/with-determinism [sched (mt/make-scheduler)]
                 (let [{:keys [flow set close]} (mt/state sched {:initial 0})]
                   (mt/run sched
                     (m/sp
                       (m/? (m/join (fn [_ v] v)
                              (m/sp
                                (m/? (m/sleep 100))
                                (set 1)
                                (m/? (m/sleep 100))
                                (set 2)
                                (m/? (m/sleep 100))
                                (close))
                              (mt/collect (counter-display flow))))))))]
    (println "Result:" result))

  (println "\n=== Example 2: event-logger with mt/subject ===")
  (let [result (mt/with-determinism [sched (mt/make-scheduler)]
                 (let [{:keys [flow emit close]} (mt/subject sched)]
                   (mt/run sched
                     (m/sp
                       (m/? (m/join (fn [_ v] v)
                              (m/sp
                                (m/? (emit :click))
                                (m/? (emit :scroll))
                                (m/? (emit :keypress))
                                (m/? (close)))
                              (mt/collect (event-logger flow))))))))]
    (println "Result:" result)))

(comment
  (run-examples))
