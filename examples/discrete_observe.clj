(ns examples.discrete-observe
  "Demonstrates testing discrete flows with m/observe using missionary-testkit.

  Key insight: m/observe can be tested deterministically when the callback
  invocations happen from INSIDE the controlled task (via scheduler microtasks
  or timers), not from external threads."
  (:require [missionary.core :as m]
            [de.levering-it.missionary-testkit :as mt]))

;; =============================================================================
;; Pattern 1: Callback-driven discrete flow with controlled emissions
;; =============================================================================

(defn make-event-emitter
  "Creates an event emitter that can be controlled from within tests.
  Returns {:flow <discrete-flow> :emit! <fn> :close! <fn>}

  The flow is discrete (forked with m/?>) and emits values when emit! is called."
  []
  (let [!cb (atom nil)
        flow (m/observe (fn [cb]
                          (reset! !cb cb)
                          ;; cleanup: clear callback on termination
                          #(reset! !cb nil)))]
    {:flow   flow
     :emit!  (fn [v] (when-let [cb @!cb] (cb v)))
     :close! (fn []  (when-let [cb @!cb] (cb)))}))

;; Example: Testing discrete event processing
(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)
          {:keys [flow emit!]} (make-event-emitter)]
      (mt/run sched
        (m/sp
          (m/? (m/race
                 ;; Consumer: collect 3 events from discrete flow
                 (m/reduce (fn [acc event]
                             (let [acc (conj acc {:received event})]
                               (if (>= (count acc) 3)
                                 (reduced acc)
                                 acc)))
                           []
                           flow)

                 ;; Producer: emit events at controlled points
                 (m/sp
                   (m/? (m/sleep 10))
                   (emit! :click)
                   (m/? (m/sleep 10))
                   (emit! :scroll)
                   (m/? (m/sleep 10))
                   (emit! :keypress)
                   ;; Give consumer time to process
                   (m/? (m/sleep 1000)))))))))
  ;; => [{:received :click} {:received :scroll} {:received :keypress}]
  )

;; =============================================================================
;; Pattern 2: Simulating external event source (like DOM events)
;; =============================================================================

(defn button-clicks
  "Simulates a button click event stream.
  Returns {:flow <discrete-flow> :click! <fn>}"
  []
  (let [!cb (atom nil)
        flow (m/observe (fn [cb]
                          (reset! !cb cb)
                          #(reset! !cb nil)))]
    {:flow   flow
     :click! (fn [] (when-let [cb @!cb] (cb {:type :click :ts (mt/clock)})))}))

(defn throttled-clicks
  "Throttles a click flow to at most one click per interval-ms."
  [click-flow interval-ms]
  (m/ap
    (let [click (m/?> click-flow)]
      (m/? (m/sleep interval-ms))
      click)))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)
          {:keys [flow click!]} (button-clicks)]
      (mt/run sched
        (m/sp
          (m/? (m/race
                 ;; Consumer: collect throttled clicks
                 (m/reduce conj [] (throttled-clicks flow 100))

                 ;; Producer: rapid clicks (some will be throttled)
                 (m/sp
                   (m/? (m/sleep 10))
                   (click!)
                   (m/? (m/sleep 50))  ; within throttle window
                   (click!)
                   (m/? (m/sleep 150)) ; after throttle window
                   (click!)
                   (m/? (m/sleep 500)))))))))
  ;; Throttled: only clicks spaced by 100ms+ get through
  )

;; =============================================================================
;; Pattern 3: Pub/Sub style discrete messaging
;; =============================================================================

(defn make-channel
  "Creates a simple channel for discrete message passing.
  Returns {:flow <discrete-flow> :put! <fn> :close! <fn>}"
  []
  (let [!state (atom {:cb nil :closed? false})
        flow (m/observe
               (fn [cb]
                 (swap! !state assoc :cb cb)
                 #(swap! !state assoc :cb nil)))]
    {:flow   flow
     :put!   (fn [v]
               (let [{:keys [cb closed?]} @!state]
                 (when (and cb (not closed?))
                   (cb v))))
     :close! (fn []
               (swap! !state assoc :closed? true))}))

(defn merge-channels
  "Merges multiple discrete channel flows into one."
  [& channels]
  (m/ap
    (let [ch (m/?= (m/seed channels))]
      (m/?> (:flow ch)))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)
          ch1 (make-channel)
          ch2 (make-channel)]
      (mt/run sched
        (m/sp
          (m/? (m/race
                 ;; Consumer: merge and collect from both channels
                 (m/reduce conj []
                           (merge-channels ch1 ch2))

                 ;; Producer: interleave messages on both channels
                 (m/sp
                   (m/? (m/sleep 10))
                   ((:put! ch1) {:from :ch1 :msg "hello"})
                   (m/? (m/sleep 10))
                   ((:put! ch2) {:from :ch2 :msg "world"})
                   (m/? (m/sleep 10))
                   ((:put! ch1) {:from :ch1 :msg "foo"})
                   (m/? (m/sleep 500)))))))))
  ;; => [{:from :ch1 :msg "hello"} {:from :ch2 :msg "world"} {:from :ch1 :msg "foo"}]
  )

;; =============================================================================
;; Pattern 4: Simple discrete observe with timer-driven emissions
;; =============================================================================

(defn timer-driven-events-task
  "Demonstrates timer-driven discrete events using m/observe.
  Events are emitted at specific times, collected by consumer."
  []
  (let [{:keys [flow emit!]} (make-event-emitter)]
    (m/sp
      (m/? (m/race
             ;; Consumer: collect 3 events
             (m/reduce (fn [acc event]
                         (let [acc (conj acc {:event event :at (mt/clock)})]
                           (if (>= (count acc) 3)
                             (reduced acc)
                             acc)))
                       []
                       flow)

             ;; Producer: emit at specific times
             (m/sp
               (m/? (m/sleep 100))
               (emit! :first)
               (m/? (m/sleep 100))
               (emit! :second)
               (m/? (m/sleep 100))
               (emit! :third)
               (m/? (m/sleep 1000))
               :timeout))))))

(comment
  ;; Run single test
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (timer-driven-events-task))))
  ;; => [{:event :first :at 100} {:event :second :at 200} {:event :third :at 300}]

  ;; Test is deterministic - same result every time
  (mt/with-determinism
    (mt/check-interleaving
      timer-driven-events-task
      {:num-tests 10
       :seed 42
       :property (fn [result]
                   ;; Should always get 3 events in order
                   (and (= 3 (count result))
                        (= [:first :second :third] (mapv :event result))))}))
  ;; => {:ok? true ...}
  )

;; =============================================================================
;; RUNNING THE EXAMPLES
;; =============================================================================

(defn run-examples []
  (println "=== Example 1: Basic event emitter ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)
                       {:keys [flow emit!]} (make-event-emitter)]
                   (mt/run sched
                     (m/sp
                       (m/? (m/race
                              ;; Consumer: collect 3 events using reduced
                              (m/reduce (fn [acc event]
                                          (let [acc (conj acc {:received event})]
                                            (if (>= (count acc) 3)
                                              (reduced acc)
                                              acc)))
                                        []
                                        flow)
                              ;; Producer
                              (m/sp
                                (m/? (m/sleep 10))
                                (emit! :click)
                                (m/? (m/sleep 10))
                                (emit! :scroll)
                                (m/? (m/sleep 10))
                                (emit! :keypress)
                                (m/? (m/sleep 1000)))))))))]
    (println "Result:" result))

  (println "\n=== Example 2: Pub/Sub channels ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)
                       ch1 (make-channel)
                       ch2 (make-channel)]
                   (mt/run sched
                     (m/sp
                       (m/? (m/race
                              ;; Consumer: collect 3 messages using reduced
                              (m/reduce (fn [acc msg]
                                          (let [acc (conj acc msg)]
                                            (if (>= (count acc) 3)
                                              (reduced acc)
                                              acc)))
                                        []
                                        (merge-channels ch1 ch2))
                              ;; Producer
                              (m/sp
                                (m/? (m/sleep 10))
                                ((:put! ch1) {:ch 1 :v "a"})
                                (m/? (m/sleep 10))
                                ((:put! ch2) {:ch 2 :v "b"})
                                (m/? (m/sleep 10))
                                ((:put! ch1) {:ch 1 :v "c"})
                                (m/? (m/sleep 500)))))))))]
    (println "Result:" result))

  (println "\n=== Example 3: Timer-driven discrete events ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (timer-driven-events-task))))]
    (println "Result:" result)
    (println "Events collected at virtual times:" (mapv :at result))))

(comment
  (run-examples))
