(ns de.levering-it.missionary-testkit
  (:require [missionary.core :as m])
  #?(:clj
     (:import (java.util.concurrent Executor)
              (missionary Cancelled))))

;; missionary/missionary-testkit
;;
;; Primary namespace: missionary.testkit  (suggested alias: mt)
;;
;; NOTE on macros (ClojureScript):
;; - `with-determinism` is a macro defined on the CLJ (macro) side.
;;   In CLJS you typically require it like:
;;     (ns your.test
;;       (:require [missionary.testkit :as mt])
;;       (:require-macros [missionary.testkit :refer [with-determinism]]))
;;

;; -----------------------------------------------------------------------------
;; Public keywords / kinds
;; -----------------------------------------------------------------------------

(def ^:const idle ::idle)

(def ^:const deadlock ::deadlock)
(def ^:const budget-exceeded ::budget-exceeded)
(def ^:const off-scheduler-callback ::off-scheduler-callback)

(def ^:const illegal-blocking-emission ::illegal-blocking-emission)
(def ^:const illegal-transfer ::illegal-transfer)

;; -----------------------------------------------------------------------------
;; Dynamic scheduler binding
;; -----------------------------------------------------------------------------

(def ^:dynamic *scheduler*
  "Dynamically bound to the current TestScheduler in deterministic tests."
  nil)

(def ^:dynamic *in-scheduler*
  "True while executing scheduler-driven work (microtasks, transfers, etc)."
  false)

(defn- require-scheduler!
  ([] (require-scheduler! "No TestScheduler bound. Use mt/with-determinism or bind mt/*scheduler*."))
  ([msg]
   (or *scheduler*
       (throw (ex-info msg {:mt/kind ::no-scheduler})))))


;; -----------------------------------------------------------------------------
;; Cross-platform queue helpers
;; -----------------------------------------------------------------------------

(def ^:private empty-queue
  #?(:clj  clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

(defn- q-empty? [q] (empty? q))
(defn- q-peek  [q] (peek q))
(defn- q-pop   [q] (pop q))
(defn- q-conj  [q x] (conj q x))

;; -----------------------------------------------------------------------------
;; Scheduler
;; -----------------------------------------------------------------------------

(defrecord TestScheduler
           [state policy seed strict? trace?])

(defn- maybe-trace-state
  "If tracing enabled (state has non-nil :trace vector), append event."
  [s event]
  (if (some? (:trace s))
    (update s :trace conj event)
    s))

(defn make-scheduler
  "Create a deterministic TestScheduler.

  Options:
  {:initial-ms 0
   :policy     :fifo | :seeded
   :seed       42
   :strict?    true|false
   :trace?     true|false}"
  ([]
   (make-scheduler {}))
  ([{:keys [initial-ms policy seed strict? trace?]
     :or   {initial-ms 0
            policy     :fifo
            seed       0
            strict?    false
            trace?     false}}]
   (->TestScheduler
    (atom {:now-ms        (long initial-ms)
           :next-id       0
           :micro-q       empty-queue
           ;; timers: sorted-map keyed by [at-ms tie order id] -> timer-map
           :timers        (sorted-map)
           ;; trace is either nil or vector
           :trace         (when trace? [])
           ;; JVM-only "driver thread" captured lazily under strict mode
           :driver-thread #?(:clj nil :cljs ::na)})
    policy (long seed) (boolean strict?) (boolean trace?))))

(defn now-ms
  "Current virtual time in milliseconds."
  [^TestScheduler sched]
  (:now-ms @(:state sched)))

(defn trace
  "Vector of trace events if enabled, else nil."
  [^TestScheduler sched]
  (:trace @(:state sched)))

(defn pending
  "Stable, printable data describing queued microtasks and timers."
  [^TestScheduler sched]
  (let [{:keys [micro-q timers]} @(:state sched)]
    {:microtasks
     (mapv (fn [mt] (select-keys mt [:id :kind :label :lane :enq-ms :from]))
           (seq micro-q))
     :timers
     (mapv (fn [[_ t]] (select-keys t [:id :kind :label :at-ms :lane]))
           timers)}))

(defn- diag
  ([sched] (diag sched nil))
  ([^TestScheduler sched label]
   (cond-> {:mt/now-ms  (now-ms sched)
            :mt/pending (pending sched)}
     (:trace? sched) (assoc :mt/trace (trace sched))
     (some? label)   (assoc :mt/label label))))

(defn- mt-ex
  ([kind sched msg]
   (mt-ex kind sched msg nil nil))
  ([kind sched msg {:keys [label] :as extra}]
   (mt-ex kind sched msg extra nil))
  ([kind ^TestScheduler sched msg extra cause]
   (let [data (merge {:mt/kind kind}
                     (diag sched (:label extra))
                     (dissoc extra :label))]
     #?(:clj  (if (some? cause) (ex-info msg data cause) (ex-info msg data))
        :cljs (ex-info msg data)))))

(defn- seeded-tie
  "Deterministic tie-break value for :seeded policy.
  Uses arithmetic kept well within 2^53 for CLJS stability."
  [seed order]
  (let [x (+ (* 1664525 (long order)) (long seed))]
    (long (mod x 4294967296)))) ; 2^32

(defn- next-id!
  [^TestScheduler sched]
  (:next-id (swap! (:state sched) update :next-id inc)))

#?(:clj
   (defn- ensure-driver-thread!
     "In strict mode, enforce that driving operations are performed by a single thread.
      Enqueues from other threads are allowed; only *driving* (step/tick/advance/run/transfer) is checked."
     [^TestScheduler sched op-label]
     (when (:strict? sched)
       (let [state-atom (:state sched)
             this-thread (Thread/currentThread)]
         (loop []
           (let [s @state-atom
                 owner (:driver-thread s)]
             (cond
               (nil? owner)
               (if (compare-and-set! state-atom s (assoc s :driver-thread this-thread))
                 true
                 (recur))

               (= owner this-thread)
               true

               :else
               (throw (mt-ex off-scheduler-callback sched
                             (str "Scheduler driven from multiple threads (" op-label ").")
                             {:label op-label})))))))))

#?(:cljs
   (defn- ensure-driver-thread! [_ _] true))

(defn- enqueue-microtask!
  ([^TestScheduler sched f] (enqueue-microtask! sched f {}))
  ([^TestScheduler sched f {:keys [label kind lane]
                            :or   {kind :microtask
                                   lane :default}}]
   (let [id (next-id! sched)]
     (swap! (:state sched)
            (fn [s]
              (let [now (:now-ms s)
                    mt  {:id     id
                         :kind   kind
                         :label  label
                         :lane   lane
                         :enq-ms now
                         :from   :micro
                         :f      f}]
                (-> s
                    (update :micro-q q-conj mt)
                    (maybe-trace-state {:event  :enqueue-microtask
                                        :id     id
                                        :kind   kind
                                        :label  label
                                        :lane   lane
                                        :now-ms now})))))
     id)))

(defn- schedule-timer!
  "Schedule f to run at now+delay-ms as a timer; returns a cancellation token."
  ([^TestScheduler sched delay-ms f] (schedule-timer! sched delay-ms f {}))
  ([^TestScheduler sched delay-ms f {:keys [label kind lane]
                                     :or   {kind :timer
                                            lane :default}}]
   (let [id (next-id! sched)
         token (atom nil)]
     (swap! (:state sched)
            (fn [s]
              (let [now   (:now-ms s)
                    at-ms (+ now (long delay-ms))
                    order id
                    tie   (case (:policy sched)
                            :seeded (seeded-tie (:seed sched) order)
                            ;; default/fallback
                            order)
                    k     [at-ms tie order id]
                    t     {:id    id
                           :kind  kind
                           :label label
                           :lane  lane
                           :at-ms at-ms
                           :key   k
                           :f     f}]
                (reset! token k)
                (-> s
                    (update :timers assoc k t)
                    (maybe-trace-state {:event  :enqueue-timer
                                        :id     id
                                        :at-ms  at-ms
                                        :kind   kind
                                        :label  label
                                        :lane   lane
                                        :now-ms now})))))
     @token)))

(defn- cancel-timer!
  [^TestScheduler sched timer-token]
  (when timer-token
    (swap! (:state sched)
           (fn [s]
             (if-let [t (get-in s [:timers timer-token])]
               (-> s
                   (update :timers dissoc timer-token)
                   (maybe-trace-state {:event  :cancel-timer
                                       :id     (:id t)
                                       :at-ms  (:at-ms t)
                                       :label  (:label t)
                                       :now-ms (:now-ms s)}))
               s))))
  nil)

(defn- promote-due-timers-in-state
  "Move all timers with at-ms <= now-ms into the microtask queue, in timer order."
  [^TestScheduler sched s]
  (let [now (:now-ms s)]
    (loop [timers (:timers s)
           micro  (:micro-q s)
           ids    []]
      (if-let [[k t] (first timers)]
        (if (<= (:at-ms t) now)
          (recur (dissoc timers k)
                 (q-conj micro (assoc t :from :timer))
                 (conj ids (:id t)))
          (cond-> (assoc s :timers timers :micro-q micro)
            (seq ids)
            (maybe-trace-state {:event  :promote-timers
                                :ids    ids
                                :count  (count ids)
                                :now-ms now})))
        (cond-> (assoc s :timers timers :micro-q micro)
          (seq ids)
          (maybe-trace-state {:event  :promote-timers
                              :ids    ids
                              :count  (count ids)
                              :now-ms now}))))))

(defn- next-timer-time
  [^TestScheduler sched]
  (when-let [[_ t] (first (:timers @(:state sched)))]
    (:at-ms t)))

(defn step!
  "Run exactly 1 microtask. Returns ::idle if no microtasks."
  [^TestScheduler sched]
  (ensure-driver-thread! sched "step!")
  (let [state-atom (:state sched)]
    (loop []
      (let [s @state-atom
            q (:micro-q s)]
        (if (q-empty? q)
          idle
          (let [mt (q-peek q)
                q' (q-pop q)
                now (:now-ms s)
                s' (-> s
                       (assoc :micro-q q')
                       (maybe-trace-state {:event  :run-microtask
                                           :id     (:id mt)
                                           :kind   (:kind mt)
                                           :label  (:label mt)
                                           :lane   (:lane mt)
                                           :now-ms now}))]
            (if (compare-and-set! state-atom s s')
              (do
                (binding [*scheduler* sched
                          *in-scheduler* true]
                  (try
                    ((:f mt))
                    (catch #?(:clj Throwable :cljs :default) e
                      ;; Re-throw user exceptions untouched (so tests can assert on them).
                      (throw e))))
                (dissoc mt :f))
              (recur))))))))

(defn tick!
  "Drain all microtasks at current virtual time. Returns number of microtasks executed."
  [^TestScheduler sched]
  (ensure-driver-thread! sched "tick!")
  (loop [n 0]
    (let [r (step! sched)]
      (if (= r idle)
        n
        (recur (inc n))))))

(defn advance-to!
  "Set time to t (>= now), enqueue due timers, then tick. Returns number of microtasks executed by tick."
  [^TestScheduler sched t]
  (ensure-driver-thread! sched "advance-to!")
  (let [t (long t)]
    (swap! (:state sched)
           (fn [s]
             (let [now (:now-ms s)]
               (when (< t now)
                 (throw (mt-ex illegal-transfer sched
                               (str "advance-to! requires t >= now (" t " < " now ").")
                               {:label "advance-to!"})))
               (-> s
                   (assoc :now-ms t)
                   (maybe-trace-state {:event :advance-to :from now :to t :now-ms now})
                   (->> (promote-due-timers-in-state sched))))))
    (tick! sched)))

(defn advance!
  "Advance virtual time by dt-ms (>=0), enqueue due timers, then tick."
  [^TestScheduler sched dt-ms]
  (ensure-driver-thread! sched "advance!")
  (let [dt (long dt-ms)]
    (when (neg? dt)
      (throw (mt-ex illegal-transfer sched
                    (str "advance! requires non-negative dt-ms, got " dt-ms ".")
                    {:label "advance!"})))
    (advance-to! sched (+ (now-ms sched) dt))))


;; -----------------------------------------------------------------------------
;; Jobs (task driving)
;; -----------------------------------------------------------------------------

(defprotocol ICancellable
  (-cancel! [x]))

(defrecord Job
           [^TestScheduler sched id label state cancel-thunk]
  ICancellable
  (-cancel! [_]
    ;; cancel-thunk may be nil or already invoked; cancellation is cooperative.
    (when-let [c @cancel-thunk]
      (try
        (c)
        (catch #?(:clj Throwable :cljs :default) _e
          ;; cancellation thunks should not throw; ignore to keep tests moving
          nil)))
    nil))

(defn start!
  "Start a Missionary task under the scheduler and return a Job handle.

  (def job (mt/start! sched task {:label \"optional\"}))"
  ([^TestScheduler sched task]
   (start! sched task {}))
  ([^TestScheduler sched task {:keys [label] :as _opts}]
   (ensure-driver-thread! sched "start!")
   (let [id          (next-id! sched)
         job-state   (atom {:status :pending})
         cancel-cell (atom nil)

         ;; Complete job via scheduler microtask, so completions become part of deterministic order.
         complete! (fn [status v]
                     (enqueue-microtask!
                      sched
                      (fn []
                        (swap! job-state
                               (fn [st]
                                 (if (= :pending (:status st))
                                   (assoc st :status status
                                          (if (= status :success) :value :error) v)
                                   st))))
                      {:label label
                       :kind  :job/complete
                       :lane  :default}))]

     ;; Start task immediately (but its completion is always delivered through scheduler microtasks).
     (binding [*scheduler* sched]
       (try
         (let [cancel
               (task
                (fn [v]
                  ;; strict: detect off-driver-thread callback (JVM only)
                  #?(:clj
                     (if (and (:strict? sched)
                              (let [owner (:driver-thread @(:state sched))]
                                (and owner (not= owner (Thread/currentThread)))))
                       (complete! :failure
                                  (mt-ex off-scheduler-callback sched
                                         "Task success callback invoked off scheduler thread."
                                         {:label label}))
                       (complete! :success v))
                     :cljs
                     (complete! :success v)))
                (fn [e]
                  #?(:clj
                     (if (and (:strict? sched)
                              (let [owner (:driver-thread @(:state sched))]
                                (and owner (not= owner (Thread/currentThread)))))
                       (complete! :failure
                                  (mt-ex off-scheduler-callback sched
                                         "Task failure callback invoked off scheduler thread."
                                         {:label label
                                          :mt/original-error (pr-str e)}))
                       (complete! :failure e))
                     :cljs
                     (complete! :failure e))))]
           (reset! cancel-cell (or cancel (fn [] nil))))
         (catch #?(:clj Throwable :cljs :default) e
           (complete! :failure e)
           (reset! cancel-cell (fn [] nil)))))

     (->Job sched id label job-state cancel-cell))))

(defn done?
  "Has the job completed (success or failure)?"
  [^Job job]
  (not= :pending (:status @(:state job))))

(defn result
  "Returns job value, throws job failure, or ::pending."
  [^Job job]
  (let [{:keys [status value error]} @(:state job)]
    (case status
      :pending ::pending
      :success value
      :failure (throw error)
      ;; fallback
      ::pending)))

(defn cancel!
  "Cancel a Job handle or a flow handle produced by spawn-flow!."
  [x]
  (-cancel! x))

;; -----------------------------------------------------------------------------
;; Virtual time primitives (replacements for Missionary)
;; -----------------------------------------------------------------------------

(defn- cancelled-ex []
  #?(:clj  (Cancelled.)
     :cljs (Cancelled.)))

(defn sleep
  "Virtual sleep task.

  (mt/sleep ms)
  (mt/sleep ms x)

  Semantics:
  - completes after delay with x (or nil)
  - cancelling fails immediately with missionary.Cancelled"
  ([ms] (sleep ms nil))
  ([ms x]
   (fn [s f]
     (let [sched (require-scheduler!)
           done? (atom false)
           tok   (schedule-timer!
                  sched (long ms)
                  (fn []
                    (when (compare-and-set! done? false true)
                      (s x)))
                  {:kind  :sleep
                   :label "sleep"})]
       (fn cancel
         []
         (when (compare-and-set! done? false true)
           (cancel-timer! sched tok)
           ;; fail via microtask (deterministic, prompt)
           (enqueue-microtask! sched (fn [] (f (cancelled-ex)))
                               {:kind :sleep/cancel
                                :label "sleep-cancel"}))
         nil)))))

(defn timeout
  "Virtual timeout wrapper task.

  (mt/timeout task ms)
  (mt/timeout task ms x)

  Semantics:
  - if input completes before ms, propagate success/failure
  - else, cancel input task and succeed with x (default nil)
  - cancelling the timeout task fails with missionary.Cancelled (and cancels input)."
  ([task ms] (timeout task ms nil))
  ([task ms x]
   (fn [s f]
     (let [sched (require-scheduler!)
           done? (atom false)
           cancel-child (atom nil)
           timer-token (atom nil)

           finish! (fn [status v]
                     (when (compare-and-set! done? false true)
                       ;; stop timer
                       (cancel-timer! sched @timer-token)
                       ;; deliver outcome
                       (case status
                         :success (s v)
                         :failure (f v))))]

       ;; Start child task immediately
       (try
         (reset! cancel-child
                 (task
                  (fn [v]
                    ;; if child succeeds first, succeed
                    (enqueue-microtask! sched (fn [] (finish! :success v))
                                        {:kind :timeout/child-success
                                         :label "timeout-child-success"}))
                  (fn [e]
                    ;; if child fails first, fail
                    (enqueue-microtask! sched (fn [] (finish! :failure e))
                                        {:kind :timeout/child-failure
                                         :label "timeout-child-failure"}))))
         (catch #?(:clj Throwable :cljs :default) e
           (finish! :failure e)
           (reset! cancel-child (fn [] nil))))

       ;; Start timer
       (reset! timer-token
               (schedule-timer!
                sched (long ms)
                (fn []
                  (when (compare-and-set! done? false true)
                    ;; timeout fired first -> cancel child and succeed with fallback
                    (when-let [c @cancel-child]
                      (try (c) (catch #?(:clj Throwable :cljs :default) _ nil)))
                    (s x)))
                {:kind  :timeout/timer
                 :label "timeout-timer"}))

       ;; cancellation thunk
       (fn cancel []
         (when (compare-and-set! done? false true)
           (cancel-timer! sched @timer-token)
           (when-let [c @cancel-child]
             (try (c) (catch #?(:clj Throwable :cljs :default) _ nil)))
           (enqueue-microtask! sched (fn [] (f (cancelled-ex)))
                               {:kind :timeout/cancel
                                :label "timeout-cancel"}))
         nil)))))

;; -----------------------------------------------------------------------------
;; mt/run
;; -----------------------------------------------------------------------------

(defn- run*
  [^TestScheduler sched task {:keys [auto-advance? max-steps max-time-ms label]
                              :or   {auto-advance? true
                                     max-steps     100000
                                     max-time-ms   60000}}]
  (ensure-driver-thread! sched "run")
  (let [start-time (now-ms sched)
        job        (start! sched task {:label label})]
    (loop [steps 0]
      (let [steps (+ steps (tick! sched))
            elapsed (- (now-ms sched) start-time)]
        (when (> steps (long max-steps))
          (throw (mt-ex budget-exceeded sched
                        (str "Step budget exceeded: " steps " > " max-steps)
                        {:label label
                         :mt/steps steps
                         :mt/max-steps max-steps})))
        (when (> elapsed (long max-time-ms))
          (throw (mt-ex budget-exceeded sched
                        (str "Time budget exceeded: " elapsed "ms > " max-time-ms "ms")
                        {:label label
                         :mt/elapsed-ms elapsed
                         :mt/max-time-ms max-time-ms})))

        (if (done? job)
          (result job)
          (if-not auto-advance?
            (throw (mt-ex deadlock sched
                          "Deadlock: task not done after draining microtasks, and auto-advance? is false."
                          {:label label}))
            (if-let [t-next (next-timer-time sched)]
              (do
                ;; enforce time budget even if we jump forward
                (when (> (- t-next start-time) (long max-time-ms))
                  (throw (mt-ex budget-exceeded sched
                                (str "Time budget exceeded before advancing: next timer at " t-next "ms.")
                                {:label label
                                 :mt/next-timer-ms t-next
                                 :mt/start-ms start-time
                                 :mt/max-time-ms max-time-ms})))
                (let [steps (+ steps (advance-to! sched t-next))]
                  (recur steps)))
              (throw (mt-ex deadlock sched
                            "Deadlock: no microtasks, no timers, and task still pending."
                            {:label label})))))))))

(defn run
  "Run task deterministically to completion (or throw).

  JVM: returns value or throws.
  CLJS: returns a js/Promise that resolves/rejects."
  ([^TestScheduler sched task]
   (run sched task {}))
  ([^TestScheduler sched task opts]
   #?(:clj  (run* sched task opts)
      :cljs (js/Promise.
             (fn [resolve reject]
               (try
                 (resolve (run* sched task opts))
                 (catch :default e
                   (reject e))))))))

;; -----------------------------------------------------------------------------
;; Deterministic executors (JVM-only)
;; -----------------------------------------------------------------------------

#?(:clj
   (defn executor
     "Deterministic java.util.concurrent.Executor. Enqueues runnables as scheduler microtasks."
     [^TestScheduler sched]
     (reify Executor
       (execute [_ runnable]
         (enqueue-microtask!
          sched
          (fn []
            (.run ^Runnable runnable))
          {:kind :executor
           :lane :default
           :label "executor"})))))

#?(:cljs
   (defn executor [_]
     (throw (ex-info "mt/executor is JVM-only." {:mt/kind ::unsupported}))))

#?(:clj
   (defn cpu-executor [sched]
     ;; single-lane by default; lane label retained for introspection/trace
     (reify Executor
       (execute [_ runnable]
         (enqueue-microtask!
          sched
          (fn []
            (.run ^Runnable runnable))
          {:kind :executor
           :lane :cpu
           :label "cpu-executor"})))))

#?(:cljs
   (defn cpu-executor [_]
     (throw (ex-info "mt/cpu-executor is JVM-only." {:mt/kind ::unsupported}))))

#?(:clj
   (defn blk-executor [sched]
     (reify Executor
       (execute [_ runnable]
         (enqueue-microtask!
          sched
          (fn []
            (.run ^Runnable runnable))
          {:kind :executor
           :lane :blk
           :label "blk-executor"})))))

#?(:cljs
   (defn blk-executor [_]
     (throw (ex-info "mt/blk-executor is JVM-only." {:mt/kind ::unsupported}))))

;; -----------------------------------------------------------------------------
;; Integration macro: with-determinism
;; -----------------------------------------------------------------------------

#?(:clj
   (defmacro with-determinism
     "Scope deterministic behavior to a test body by rebinding/redefining Missionary vars.

     Usage:
       (with-determinism [sched (mt/make-scheduler {:strict? true})]
         ...)

     JVM effects:
     - missionary.core/sleep    -> mt/sleep
     - missionary.core/timeout  -> mt/timeout
     - missionary.core/cpu      -> deterministic executor
     - missionary.core/blk      -> deterministic executor

     CLJS effects:
     - patches time primitives only (sleep/timeout)."
     [[sched-sym sched-expr] & body]
     (let [cljs? (boolean &env)]
       `(let [~sched-sym ~sched-expr]
          (binding [*scheduler* ~sched-sym]
            (with-redefs
             [missionary.core/sleep   sleep
              missionary.core/timeout timeout
              ~@(when-not cljs?
                  `[missionary.core/cpu (cpu-executor ~sched-sym)
                    missionary.core/blk (blk-executor ~sched-sym)])]
              ~@body))))))

;; -----------------------------------------------------------------------------
;; Flow determinism: scheduled-flow + spawn-flow!
;; -----------------------------------------------------------------------------

(defn- on-scheduler-context?
  "True if currently executing scheduler-driven work for sched."
  [^TestScheduler sched]
  (and *in-scheduler* (identical? *scheduler* sched)))

(defn scheduled-flow
  "Wrap a flow to marshal readiness/termination signals through the scheduler.

  (mt/scheduled-flow sched flow {:mode :async|:inline-when-on-scheduler :label ...})"
  ([^TestScheduler sched flow]
   (scheduled-flow sched flow {}))
  ([^TestScheduler sched flow {:keys [mode label]
                               :or   {mode :async}}]
   (fn [n t]
     (let [wrap (fn [thunk kind]
                  (fn []
                    (if (and (= mode :inline-when-on-scheduler)
                             (on-scheduler-context? sched))
                      (thunk)
                      (enqueue-microtask!
                       sched
                       (fn [] (thunk))
                       {:kind  kind
                        :label label
                        :lane  :default}))))]
       (flow (wrap n :flow/notifier)
             (wrap t :flow/terminator))))))

(defrecord FlowProcess
           [^TestScheduler sched label state process]
  ICancellable
  (-cancel! [_]
    (ensure-driver-thread! sched "flow cancel!")
    (binding [*scheduler* sched
              *in-scheduler* true]
      (swap! state assoc :cancelled? true)
      (when (and process (ifn? process))
        (try
          (process) ;; dispose/cancel if supported
          (catch #?(:clj Throwable :cljs :default) _ nil))))
    nil))

(defn spawn-flow!
  "Spawn a flow process under the scheduler and return a deterministic handle.

  Handle ops:
    (mt/ready? p)
    (mt/terminated? p)
    (mt/transfer! p)
    (mt/cancel! p)"
  ([^TestScheduler sched flow]
   (spawn-flow! sched flow {}))
  ([^TestScheduler sched flow {:keys [label] :as _opts}]
   (ensure-driver-thread! sched "spawn-flow!")
   (let [st (atom {:ready?      false
                   :terminated? false
                   :cancelled?  false})
         ;; internal n/t mutate flags only; flow is wrapped to marshal signals via scheduler
         n* (fn [] (swap! st assoc :ready? true))
         t* (fn [] (swap! st assoc :terminated? true :ready? false))
         f* (scheduled-flow sched flow {:mode :async :label label})
         proc (binding [*scheduler* sched] (f* n* t*))]
     (->FlowProcess sched label st proc))))

(defn ready? [^FlowProcess p] (:ready? @(:state p)))
(defn terminated? [^FlowProcess p] (:terminated? @(:state p)))

(defn transfer!
  "Perform one transfer. Must be called on the scheduler driver thread.
  Throws ::mt/illegal-transfer if not ready or already terminated."
  [^FlowProcess p]
  (let [sched (:sched p)
        label (:label p)]
    (ensure-driver-thread! sched "transfer!")
    (binding [*scheduler* sched
              *in-scheduler* true]
      (let [{:keys [ready? terminated? cancelled?]} @(:state p)]
        (cond
          terminated?
          (throw (mt-ex illegal-transfer sched "Illegal transfer: flow already terminated."
                        {:label label}))

          (not ready?)
          (throw (mt-ex illegal-transfer sched "Illegal transfer: flow not ready."
                        {:label label}))

          :else
          (do
            (swap! (:state p) assoc :ready? false)
            (deref (:process p))))))))

;; -----------------------------------------------------------------------------
;; Deterministic flow sources: subject (discrete) and state (continuous)
;; -----------------------------------------------------------------------------

(defn subject
  "Create a controlled discrete stream usable in place of observe in tests.

  Returns map:
    {:flow  <discrete-flow>
     :emit  (fn [v] task)    ;; completes when transferred
     :offer (fn [v] boolean) ;; best-effort, returns false if backpressured
     :close (fn [] task)     ;; normal termination
     :fail  (fn [ex] task)}  ;; failure

  Notes:
  - Capacity is effectively 1 'notified transfer' at a time, but additional emits are queued.
  - `emit` tasks complete when their value is actually transferred.
  - `offer` only succeeds when there is no backlog (pending/queued/failed/closed)."
  ([^TestScheduler sched] (subject sched {}))
  ([^TestScheduler sched {:keys [label] :as _opts}]
   (let [st (atom {:n          nil
                   :t          nil
                   :ready?     false
                   :terminated? false
                   :closed?    false
                   :failed     nil         ;; ex to throw on transfer
                   :pending    nil         ;; {:v ... :done? atom :s s :f f}
                   :queue      []          ;; vector of entries
                   :cancelled? false})

         ;; helper: schedule notifier if transfer available and not already ready
         signal-ready!
         (fn []
           (let [enqueue? (atom false)]
             (swap! st
                    (fn [s]
                      (if (and (not (:terminated? s))
                               (not (:ready? s))
                               (or (:failed s) (:pending s)))
                        (do (reset! enqueue? true)
                            (assoc s :ready? true))
                        s)))
             (when @enqueue?
               (when-let [n (:n @st)]
                 (enqueue-microtask! sched (fn [] (n))
                                     {:kind :subject/notifier
                                      :label label})))))

         ;; helper: schedule terminator when closed and no backlog
         signal-terminate!
         (fn []
           (let [enqueue? (atom false)]
             (swap! st
                    (fn [s]
                      (if (and (not (:terminated? s))
                               (:closed? s)
                               (nil? (:failed s))
                               (nil? (:pending s))
                               (empty? (:queue s)))
                        (do (reset! enqueue? true)
                            (assoc s :terminated? true :ready? false))
                        s)))
             (when @enqueue?
               (when-let [t (:t @st)]
                 (enqueue-microtask! sched (fn [] (t))
                                     {:kind :subject/terminator
                                      :label label})))))

         ;; completion helpers
         complete-entry-success!
         (fn [entry]
           (when (and entry (:done? entry))
             (when (compare-and-set! (:done? entry) false true)
               (let [s-cont (:s entry)]
                 (when s-cont
                   (enqueue-microtask! sched (fn [] (s-cont nil))
                                       {:kind :subject/emit-success
                                        :label label}))))))

         complete-entry-failure!
         (fn [entry ex]
           (when (and entry (:done? entry))
             (when (compare-and-set! (:done? entry) false true)
               (let [f-cont (:f entry)]
                 (when f-cont
                   (enqueue-microtask! sched (fn [] (f-cont ex))
                                       {:kind :subject/emit-failure
                                        :label label}))))))

         ;; apply failure: clear backlog, fail emit tasks, and make transfer throw ex
         apply-failure!
         (fn [ex]
           (let [{:keys [pending queue]} @st]
             (doseq [e (cond-> []
                         pending (conj pending)
                         (seq queue) (into queue))]
               (complete-entry-failure! e ex)))
           (swap! st
                  (fn [s]
                    (-> s
                        (assoc :failed ex :pending nil :queue [] :closed? true)
                        (assoc :ready? false))))
           (signal-ready!))]

     {:flow
      (fn [n t]
        (ensure-driver-thread! sched "subject flow spawn")
        ;; attach
        (when (:n @st)
          (throw (mt-ex illegal-transfer sched "subject flow already has a subscriber." {:label label})))
        (swap! st assoc :n n :t t :terminated? false :ready? false :cancelled? false)
        ;; if already failed/pending, signal readiness; if already closed and empty, signal termination
        (signal-ready!)
        (signal-terminate!)
        ;; process object
        (reify
          #?(:clj clojure.lang.IDeref :cljs cljs.core/IDeref)
          (#?(:clj deref :cljs -deref) [_]
            (ensure-driver-thread! sched "subject transfer")
            (binding [*scheduler* sched
                      *in-scheduler* true]
              (let [{:keys [ready? terminated? cancelled? failed pending queue closed?]} @st]
                (when terminated?
                  (throw (mt-ex illegal-transfer sched "Transfer after termination." {:label label})))
                (when-not ready?
                  (throw (mt-ex illegal-transfer sched "Transfer attempted when not ready." {:label label})))
                ;; consume readiness
                (swap! st assoc :ready? false)
                (cond
                  cancelled?
                  (throw (cancelled-ex))

                  failed
                  (throw failed)

                  pending
                  (let [v (:v pending)]
                    ;; mark pending consumed
                    (swap! st assoc :pending nil)
                    (complete-entry-success! pending)
                    ;; promote next queued value if any
                    (when-let [next (first (:queue @st))]
                      (swap! st (fn [s] (-> s
                                            (assoc :pending next)
                                            (update :queue subvec 1)))))
                    ;; after consuming, either signal next ready or terminate
                    (signal-ready!)
                    (signal-terminate!)
                    v)

                  :else
                  (throw (mt-ex illegal-transfer sched
                                "Ready signaled but no pending transfer."
                                {:label label}))))))
          #?(:clj clojure.lang.IFn :cljs cljs.core/IFn)
          (#?(:clj invoke :cljs -invoke) [_]
            ;; cancel/dispose
            (ensure-driver-thread! sched "subject cancel")
            (binding [*scheduler* sched
                      *in-scheduler* true]
              (swap! st assoc :cancelled? true :closed? true :failed (cancelled-ex)
                     :pending nil :queue [] :ready? false)
              ;; wake consumer to observe cancellation via transfer error
              (signal-ready!)
              nil))))

      :emit
      (fn [v]
        (fn [s f]
          (let [done? (atom false)
                entry {:v v :s s :f f :done? done?}]
            (enqueue-microtask!
             sched
             (fn []
               (let [{:keys [terminated? closed? failed]} @st]
                 (cond
                   terminated?
                   (f (mt-ex illegal-transfer sched "emit on terminated subject." {:label label}))

                   failed
                   (f failed)

                   closed?
                   (f (mt-ex illegal-transfer sched "emit on closed subject." {:label label}))

                   :else
                   (do
                     (swap! st
                            (fn [st0]
                              (cond
                                (nil? (:pending st0))
                                (assoc st0 :pending entry)

                                :else
                                (update st0 :queue conj entry))))
                     (signal-ready!)))))
             {:kind :subject/emit
              :label label})
            (fn cancel []
              (when (compare-and-set! done? false true)
                (enqueue-microtask! sched (fn [] (f (cancelled-ex)))
                                    {:kind :subject/emit-cancel
                                     :label label}))
              nil))))

      :offer
      (fn [v]
        ;; best-effort: succeed only with no backlog
        (let [accepted? (atom false)]
          (swap! st
                 (fn [s]
                   (if (and (not (:terminated? s))
                            (not (:closed? s))
                            (nil? (:failed s))
                            (nil? (:pending s))
                            (empty? (:queue s)))
                     (do (reset! accepted? true)
                         (assoc s :pending {:v v :s nil :f nil :done? nil}))
                     s)))
          (when @accepted? (signal-ready!))
          @accepted?))

      :close
      (fn []
        (fn [s f]
          (enqueue-microtask!
           sched
           (fn []
             (swap! st assoc :closed? true)
             (signal-terminate!)
             (s nil))
           {:kind :subject/close
            :label label})
          (fn cancel []
            (enqueue-microtask! sched (fn [] (f (cancelled-ex)))
                                {:kind :subject/close-cancel
                                 :label label})
            nil)))

      :fail
      (fn [ex]
        (fn [s f]
          (enqueue-microtask!
           sched
           (fn []
             (apply-failure! ex)
             (s nil))
           {:kind :subject/fail
            :label label})
          (fn cancel []
            (enqueue-microtask! sched (fn [] (f (cancelled-ex)))
                                {:kind :subject/fail-cancel
                                 :label label})
            nil)))})))

(defn state
  "Create a controlled continuous signal similar to watch, but deterministic.

  Returns map:
    {:flow  <continuous-flow>
     :set   (fn [v] nil)
     :fail  (fn [ex] nil)
     :close (fn [] nil)}

  Semantics:
  - initial transfer yields :initial
  - set schedules readiness if no transfer is currently pending
  - does not block; readiness is always marshaled through scheduler microtasks"
  ([^TestScheduler sched] (state sched {}))
  ([^TestScheduler sched {:keys [initial label] :as _opts}]
   (let [st (atom {:n          nil
                   :t          nil
                   :ready?     false
                   :terminated? false
                   :closed?    false
                   :failed     nil
                   :cancelled? false
                   :value      initial})

         signal-ready!
         (fn []
           (let [enqueue? (atom false)]
             (swap! st
                    (fn [s]
                      (if (and (not (:terminated? s))
                               (not (:ready? s))
                               (not (:closed? s))
                               (or (some? (:failed s))
                                   true)) ; value always transferable when ready
                        (do (reset! enqueue? true)
                            (assoc s :ready? true))
                        s)))
             (when @enqueue?
               (when-let [n (:n @st)]
                 (enqueue-microtask! sched (fn [] (n))
                                     {:kind :state/notifier
                                      :label label})))))

         signal-terminate!
         (fn []
           (let [enqueue? (atom false)]
             (swap! st
                    (fn [s]
                      (if (and (not (:terminated? s))
                               (:closed? s))
                        (do (reset! enqueue? true)
                            (assoc s :terminated? true :ready? false))
                        s)))
             (when @enqueue?
               (when-let [t (:t @st)]
                 (enqueue-microtask! sched (fn [] (t))
                                     {:kind :state/terminator
                                      :label label})))))]

     {:flow
      (fn [n t]
        (ensure-driver-thread! sched "state flow spawn")
        (when (:n @st)
          (throw (mt-ex illegal-transfer sched "state flow already has a subscriber." {:label label})))
        (swap! st assoc :n n :t t :ready? false :terminated? false :cancelled? false)
        ;; initial readiness
        (signal-ready!)
        (reify
          #?(:clj clojure.lang.IDeref :cljs cljs.core/IDeref)
          (#?(:clj deref :cljs -deref) [_]
            (ensure-driver-thread! sched "state transfer")
            (binding [*scheduler* sched
                      *in-scheduler* true]
              (let [{:keys [ready? terminated? cancelled? failed value]} @st]
                (when terminated?
                  (throw (mt-ex illegal-transfer sched "Transfer after termination." {:label label})))
                (when-not ready?
                  (throw (mt-ex illegal-transfer sched "Transfer attempted when not ready." {:label label})))
                (swap! st assoc :ready? false)
                (cond
                  cancelled? (throw (cancelled-ex))
                  failed     (throw failed)
                  :else      value))))
          #?(:clj clojure.lang.IFn :cljs cljs.core/IFn)
          (#?(:clj invoke :cljs -invoke) [_]
            ;; cancel/dispose => fail with Cancelled and wake consumer
            (ensure-driver-thread! sched "state cancel")
            (binding [*scheduler* sched
                      *in-scheduler* true]
              (swap! st assoc :cancelled? true :failed (cancelled-ex) :closed? true :ready? false)
              (signal-ready!)
              nil))))

      :set
      (fn [v]
        (swap! st assoc :value v)
        ;; Only schedule readiness if no transfer pending
        (let [should-signal? (atom false)]
          (swap! st
                 (fn [s]
                   (if (and (not (:ready? s))
                            (not (:closed? s))
                            (not (:terminated? s))
                            (nil? (:failed s)))
                     (do (reset! should-signal? true)
                         s)
                     s)))
          (when @should-signal?
            (signal-ready!)))
        nil)

      :fail
      (fn [ex]
        (swap! st assoc :failed ex :closed? true)
        (signal-ready!) ; wake consumer to observe error
        nil)

      :close
      (fn []
        (swap! st assoc :closed? true)
        (signal-terminate!)
        nil)})))

;; -----------------------------------------------------------------------------
;; Flow collection convenience
;; -----------------------------------------------------------------------------

(defn collect
  "Convenience: consume a flow into a task yielding a vector (or reduced value).

  (mt/collect flow {:xf (take 10)
                    :timeout-ms 1000
                    :label \"optional\"})

  Notes:
  - If :timeout-ms is provided, wraps with mt/timeout and returns ::mt/timeout on expiry."
  ([flow] (collect flow {}))
  ([flow {:keys [xf timeout-ms label]
          :or   {xf nil}}]
   (let [f (if xf (m/eduction xf flow) flow)
         t (m/reduce conj [] f)
         t (if timeout-ms
             (timeout t timeout-ms ::timeout)
             t)]
     t)))
