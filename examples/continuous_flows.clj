(ns examples.continuous-flows
  "Demonstrates testing continuous flows (signals) with missionary-testkit.

  Key concepts:
  - Continuous flows represent time-varying values (like spreadsheet cells)
  - m/signal creates a continuous flow from a discrete flow
  - m/watch creates a continuous flow from an atom
  - m/latest combines multiple continuous flows
  - m/sample samples continuous flows when discrete events occur

  Diamond-shaped DAGs:
  - When a signal is used by multiple downstream computations that later merge,
    you get a 'diamond' topology: A -> B, A -> C, B + C -> D
  - This tests glitch-free propagation: D should see consistent B and C values"
  (:require [missionary.core :as m]
            [de.levering-it.missionary-testkit :as mt]))

;; =============================================================================
;; Basic Continuous Flow: Signal from Watch
;; =============================================================================

(defn counter-signal-example
  "Basic example: atom -> watch -> signal -> derived signals.
  Note: Because signals are sampled at discrete points, we may see
  intermediate states during propagation. The key insight is that
  all values are from the same consistent moment in time."
  []
  (let [!counter (atom 0)
        ;; Continuous flow of counter value
        <counter (m/signal (m/watch !counter))
        ;; Derived continuous flow: doubled
        <doubled (m/signal (m/latest #(* 2 %) <counter))
        results (atom [])]
    (m/sp
      (m/? (m/race
             ;; Consumer: observe the doubled signal directly
             ;; This shows the derived value tracking the source
             (m/reduce (fn [acc d]
                         (let [acc (conj acc d)]
                           (if (>= (count acc) 4)
                             (reduced acc)
                             acc)))
                       []
                       <doubled)
             ;; Producer: increment counter
             (m/sp
               (m/? (m/sleep 100))
               (swap! !counter inc)
               (m/? (m/sleep 100))
               (swap! !counter inc)
               (m/? (m/sleep 100))
               (swap! !counter inc)
               (m/? (m/sleep 1000))))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (counter-signal-example))))
  ;; => [0 2 4 6]
  ;; The doubled signal produces 0, 2, 4, 6 as counter goes 0, 1, 2, 3
  )

;; =============================================================================
;; Diamond-Shaped DAG: Single Source, Multiple Paths, Merged Result
;; =============================================================================
;;
;;       !input (atom)
;;          |
;;       <input (signal)
;;        /   \
;;    <left   <right    (two derived signals)
;;        \   /
;;       <merged        (combined result)
;;
;; This topology tests that when !input changes, the merged result sees
;; consistent values from both branches (no "glitches" where one branch
;; has the new value and the other has the old value).

(defn diamond-dag-example
  "Diamond-shaped DAG: one source, two derived signals, merged output.
  Tests glitch-free propagation."
  []
  (let [!input (atom 1)
        ;; Source signal
        <input (m/signal (m/watch !input))
        ;; Left branch: multiply by 2
        <left (m/signal (m/latest #(* 2 %) <input))
        ;; Right branch: add 10
        <right (m/signal (m/latest #(+ 10 %) <input))
        ;; Merge: combine both branches
        <merged (m/signal (m/latest (fn [l r] {:left l :right r :sum (+ l r)})
                                    <left <right))
        results (atom [])]
    (m/sp
      (m/? (m/race
             ;; Consumer
             (m/reduce (fn [_ v]
                         (swap! results conj v)
                         (when (>= (count @results) 4)
                           (reduced @results)))
                       nil
                       <merged)
             ;; Producer
             (m/sp
               (m/? (m/sleep 50))
               (reset! !input 2)
               (m/? (m/sleep 50))
               (reset! !input 5)
               (m/? (m/sleep 50))
               (reset! !input 10)
               (m/? (m/sleep 1000))))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (diamond-dag-example))))
  ;; Each result should be consistent:
  ;; - When input=1: left=2, right=11, sum=13
  ;; - When input=2: left=4, right=12, sum=16
  ;; - When input=5: left=10, right=15, sum=25
  ;; - When input=10: left=20, right=20, sum=40
  ;;
  ;; Glitch-free means we never see inconsistent states like
  ;; {:left 4 :right 11 :sum 15} (left updated but right didn't)
  )

;; =============================================================================
;; Complex Diamond: Multiple Sources Converging
;; =============================================================================
;;
;;    !a        !b
;;     |         |
;;    <a        <b
;;     |    \  / |
;;    <a2    <ab  <b2
;;      \    |   /
;;       <result
;;
;; Three inputs, multiple intermediate nodes, single output.

(defn complex-diamond-example
  "Complex diamond with multiple sources and intermediate nodes."
  []
  (let [!a (atom 1)
        !b (atom 10)
        ;; Source signals
        <a (m/signal (m/watch !a))
        <b (m/signal (m/watch !b))
        ;; Intermediate: a squared
        <a2 (m/signal (m/latest #(* % %) <a))
        ;; Intermediate: a * b
        <ab (m/signal (m/latest * <a <b))
        ;; Intermediate: b squared
        <b2 (m/signal (m/latest #(* % %) <b))
        ;; Result: combine all
        <result (m/signal (m/latest (fn [a2 ab b2]
                                      {:a2 a2 :ab ab :b2 b2 :total (+ a2 ab b2)})
                                    <a2 <ab <b2))
        results (atom [])]
    (m/sp
      (m/? (m/race
             ;; Consumer
             (m/reduce (fn [_ v]
                         (swap! results conj v)
                         (when (>= (count @results) 5)
                           (reduced @results)))
                       nil
                       <result)
             ;; Producer: modify both sources
             (m/sp
               (m/? (m/sleep 50))
               (reset! !a 2)      ; a=2, b=10 -> a2=4, ab=20, b2=100, total=124
               (m/? (m/sleep 50))
               (reset! !b 5)      ; a=2, b=5 -> a2=4, ab=10, b2=25, total=39
               (m/? (m/sleep 50))
               (reset! !a 3)      ; a=3, b=5 -> a2=9, ab=15, b2=25, total=49
               (m/? (m/sleep 50))
               (reset! !b 4)      ; a=3, b=4 -> a2=9, ab=12, b2=16, total=37
               (m/? (m/sleep 1000))))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (complex-diamond-example))))
  ;; Each snapshot should be internally consistent:
  ;; - a2 = a*a, ab = a*b, b2 = b*b, total = a2 + ab + b2
  )

;; =============================================================================
;; Verifying Glitch-Free Behavior
;; =============================================================================
;; A "glitch" would be seeing an intermediate state where one branch
;; updated but another didn't. We can verify this doesn't happen.

(defn verify-glitch-free
  "Verifies that diamond DAG never produces inconsistent states."
  []
  (let [!input (atom 1)
        <input (m/signal (m/watch !input))
        ;; Two derived signals that should stay consistent
        <times2 (m/signal (m/latest #(* 2 %) <input))
        <plus1  (m/signal (m/latest inc <input))
        ;; Combined: if glitch-free, (times2 / 2) should equal (plus1 - 1)
        <check (m/signal (m/latest (fn [t2 p1]
                                     (let [from-t2 (/ t2 2)
                                           from-p1 (dec p1)]
                                       {:times2 t2
                                        :plus1 p1
                                        :consistent? (= from-t2 from-p1)
                                        :input-from-t2 from-t2
                                        :input-from-p1 from-p1}))
                                   <times2 <plus1))
        results (atom [])]
    (m/sp
      (m/? (m/race
             ;; Consumer: collect all observations
             (m/reduce (fn [_ v]
                         (swap! results conj v)
                         (when (>= (count @results) 6)
                           (reduced @results)))
                       nil
                       <check)
             ;; Producer: rapid changes
             (m/sp
               (dotimes [i 5]
                 (m/? (m/sleep 20))
                 (reset! !input (inc i)))
               (m/? (m/sleep 1000))))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)
          results (mt/run sched (verify-glitch-free))]
      ;; Verify all results were consistent
      (println "All consistent?" (every? :consistent? results))
      (doseq [r results]
        (println r))))
  ;; Should print:
  ;; All consistent? true
  ;; Each result shows :consistent? true
  )

;; =============================================================================
;; Sampling: Discrete Events from Continuous Signals
;; =============================================================================
;; m/sample creates a discrete flow that emits whenever the first (trigger)
;; flow emits, using current values from continuous flows.

(defn sampling-example
  "Demonstrates m/sample: discrete trigger sampling continuous signals.
  Uses m/ap with m/amb to create a discrete event stream that emits
  at controlled times, allowing the continuous signals to update between samples."
  []
  (let [!temperature (atom 20)
        !humidity (atom 50)
        <temp (m/signal (m/watch !temperature))
        <humid (m/signal (m/watch !humidity))

        ;; Create a discrete tick stream using ap/amb
        ;; Each tick happens after a delay, allowing signal updates between
        >ticks (m/ap
                 (let [tick (m/?> (m/seed [1 2 3 4]))]
                   (m/? (m/sleep 50))  ; Wait between ticks
                   tick))

        ;; Sample both continuous signals when tick occurs
        >readings (m/sample (fn [tick temp humid]
                              {:tick tick :temp temp :humid humid})
                            >ticks <temp <humid)]
    (m/sp
      (m/? (m/race
             ;; Consumer: collect sampled readings
             (m/reduce conj [] >readings)

             ;; Producer: change values at specific times
             (m/sp
               (m/? (m/sleep 75))  ; After tick 1
               (reset! !temperature 22)
               (m/? (m/sleep 50))   ; After tick 2
               (reset! !humidity 55)
               (m/? (m/sleep 50))   ; After tick 3
               (reset! !temperature 25)
               (reset! !humidity 60)
               (m/? (m/sleep 1000)))))))  )

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (sampling-example))))
  ;; Each reading captures the signal values at the moment of that tick:
  ;; tick 1 @ 50ms:  temp=20, humid=50 (initial)
  ;; tick 2 @ 100ms: temp=22, humid=50 (temp updated at 75ms)
  ;; tick 3 @ 150ms: temp=22, humid=55 (humid updated at 125ms)
  ;; tick 4 @ 200ms: temp=25, humid=60 (both updated at 175ms)
  )

;; =============================================================================
;; Diamond with Sampling: Complex Real-World Pattern
;; =============================================================================
;; Combining diamond DAG with sampling for a more realistic scenario.
;; Example: A dashboard that computes metrics and samples them on user refresh.

(defn dashboard-example
  "Dashboard with computed metrics - a diamond-shaped DAG where sales and costs
  feed into both profit and margin calculations, all combined into a dashboard view.

  Diamond topology:
        !sales        !costs
           \\         /   \\
            <sales    <costs
             / \\     /    \\
       <profit  <margin    |
             \\    |       /
              <dashboard"
  []
  (let [;; Raw data sources
        !sales (atom 100)
        !costs (atom 60)

        ;; Continuous flows from raw data
        <sales (m/signal (m/watch !sales))
        <costs (m/signal (m/watch !costs))

        ;; Derived metrics (diamond pattern)
        <profit (m/signal (m/latest - <sales <costs))
        <margin (m/signal (m/latest (fn [sales costs]
                                      (if (zero? sales)
                                        0
                                        (* 100.0 (/ (- sales costs) sales))))
                                    <sales <costs))

        ;; Combined dashboard state - merges all branches
        <dashboard (m/signal (m/latest (fn [sales costs profit margin]
                                         {:sales sales
                                          :costs costs
                                          :profit profit
                                          :margin-pct margin})
                                       <sales <costs <profit <margin))
        results (atom [])]
    (m/sp
      (m/? (m/race
             ;; Consumer: observe dashboard changes
             (m/reduce (fn [_ snapshot]
                         (swap! results conj snapshot)
                         (when (>= (count @results) 4)
                           (reduced @results)))
                       nil
                       <dashboard)

             ;; Producer: update the data sources
             (m/sp
               (m/? (m/sleep 50))
               (reset! !sales 150)
               (reset! !costs 80)
               ;; sales=150, costs=80: profit=70, margin=46.67%
               (m/? (m/sleep 50))
               (reset! !sales 200)
               (reset! !costs 100)
               ;; sales=200, costs=100: profit=100, margin=50%
               (m/? (m/sleep 50))
               (reset! !sales 300)
               (reset! !costs 120)
               ;; sales=300, costs=120: profit=180, margin=60%
               (m/? (m/sleep 1000))))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (dashboard-example))))
  ;; Each snapshot is internally consistent:
  ;; profit = sales - costs
  ;; margin = 100 * (sales - costs) / sales
  )

;; =============================================================================
;; Testing Continuous Flows with check-interleaving
;; =============================================================================
;; Even continuous flows can have interesting interleavings when combined
;; with concurrent updates.

(defn make-interleaving-test-task
  "Task factory for interleaving tests."
  []
  (let [!a (atom 0)
        !b (atom 0)
        <a (m/signal (m/watch !a))
        <b (m/signal (m/watch !b))
        <sum (m/signal (m/latest + <a <b))
        results (atom [])]
    (m/sp
      (m/? (m/race
             ;; Consumer
             (m/reduce (fn [_ sum]
                         (swap! results conj sum)
                         (when (>= (count @results) 5)
                           (reduced @results)))
                       nil
                       <sum)

             ;; Two concurrent producers
             (m/join vector
                     (m/sp
                       (m/? (mt/yield))
                       (reset! !a 10)
                       (m/? (mt/yield))
                       (reset! !a 20))
                     (m/sp
                       (m/? (mt/yield))
                       (reset! !b 5)
                       (m/? (mt/yield))
                       (reset! !b 15))))))))

(comment
  ;; Explore different orderings
  (mt/with-determinism
    (let [result (mt/explore-interleavings make-interleaving-test-task
                                           {:num-samples 50
                                            :seed 42})]
      (println "Unique result sequences:" (:unique-results result))
      (doseq [{:keys [result]} (take 5 (:results result))]
        (println "  " result))))
  ;; Different interleavings produce different sequences of observed sums,
  ;; but each individual observation is always consistent (no glitches).

  ;; Verify that final sum is always 35 (20 + 15)
  (mt/with-determinism
    (mt/check-interleaving
      make-interleaving-test-task
      {:num-tests 100
       :seed 42
       :property (fn [results]
                   ;; Last observed sum should be 35
                   (= 35 (last results)))}))
  ;; => {:ok? true ...}
  )

;; =============================================================================
;; Nested Diamond: Three-Level DAG
;; =============================================================================
;;
;;           !input
;;              |
;;           <input
;;           /     \
;;        <a        <b
;;       /   \    /   \
;;     <a1   <a2 <b1   <b2
;;       \    |   |    /
;;        \   +---+   /
;;         \    |    /
;;          <result
;;
;; Tests deeper DAG propagation.

(defn nested-diamond-example
  "Three-level nested diamond DAG."
  []
  (let [!input (atom 2)
        <input (m/signal (m/watch !input))

        ;; Level 1: two branches
        <a (m/signal (m/latest #(* 2 %) <input))   ; 2x
        <b (m/signal (m/latest #(+ 1 %) <input))   ; +1

        ;; Level 2: four branches
        <a1 (m/signal (m/latest #(* % %) <a))      ; a^2
        <a2 (m/signal (m/latest #(+ 5 %) <a))      ; a+5
        <b1 (m/signal (m/latest #(* 3 %) <b))      ; 3b
        <b2 (m/signal (m/latest dec <b))           ; b-1

        ;; Level 3: merge all
        <result (m/signal (m/latest (fn [a1 a2 b1 b2]
                                      {:a1 a1 :a2 a2 :b1 b1 :b2 b2
                                       :total (+ a1 a2 b1 b2)})
                                    <a1 <a2 <b1 <b2))
        results (atom [])]
    (m/sp
      (m/? (m/race
             (m/reduce (fn [_ v]
                         (swap! results conj v)
                         (when (>= (count @results) 4)
                           (reduced @results)))
                       nil
                       <result)
             (m/sp
               (m/? (m/sleep 50))
               (reset! !input 3)
               ;; input=3: a=6, b=4
               ;; a1=36, a2=11, b1=12, b2=3 -> total=62
               (m/? (m/sleep 50))
               (reset! !input 5)
               ;; input=5: a=10, b=6
               ;; a1=100, a2=15, b1=18, b2=5 -> total=138
               (m/? (m/sleep 50))
               (reset! !input 1)
               ;; input=1: a=2, b=2
               ;; a1=4, a2=7, b1=6, b2=1 -> total=18
               (m/? (m/sleep 1000))))))))

(comment
  (mt/with-determinism
    (let [sched (mt/make-scheduler)]
      (mt/run sched (nested-diamond-example))))
  ;; Each result is consistent with the input at that moment
  )

;; =============================================================================
;; RUNNING THE EXAMPLES
;; =============================================================================

(defn run-examples []
  (println "=== Example 1: Basic Counter Signal ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (counter-signal-example))))]
    (println "Doubled values observed:" result)
    (println "Expected: [0 2 4 6] as counter goes 0 -> 1 -> 2 -> 3"))

  (println "\n=== Example 2: Diamond DAG ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (diamond-dag-example))))]
    (println "Results:" result)
    (println "Verifying consistency:")
    (doseq [r result]
      (let [input (/ (:left r) 2)]
        (println "  input=" input
                 "left=" (:left r) "(2*input)"
                 "right=" (:right r) "(input+10)"
                 "sum=" (:sum r)))))

  (println "\n=== Example 3: Glitch-Free Verification ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (verify-glitch-free))))]
    (println "All observations consistent?" (every? :consistent? result))
    (doseq [r result]
      (println "  " r)))

  (println "\n=== Example 4: Complex Diamond ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (complex-diamond-example))))]
    (println "Results:" result))

  (println "\n=== Example 5: Dashboard with Sampling ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (dashboard-example))))]
    (println "Dashboard snapshots:" result)
    (when (seq result)
      (println "Verifying: profit = sales - costs, margin = 100*(profit/sales)")
      (doseq [{:keys [sales costs profit margin-pct]} result]
        (println "  sales=" sales "costs=" costs "profit=" profit "margin=" margin-pct "%"))))

  (println "\n=== Example 6: Nested Diamond ===")
  (let [result (mt/with-determinism
                 (let [sched (mt/make-scheduler)]
                   (mt/run sched (nested-diamond-example))))]
    (println "Nested diamond results:" result)))

(comment
  (run-examples))
