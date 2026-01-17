(ns de.levering-it.missionary-testkit
  (:require [missionary.core :as m])
  #?(:clj
     (:import (java.util.concurrent Executor)
              (missionary Cancelled))))

;; missionary-testkit
;;
;; Primary namespace: de.levering-it.missionary-testkit  (suggested alias: mt)
;;
;; NOTE on macros (ClojureScript):
;; - `with-determinism` is a macro defined on the CLJ (macro) side.
;;   In CLJS you typically require it like:
;;     (ns your.test
;;       (:require [de.levering-it.missionary-testkit :as mt])
;;       (:require-macros [de.levering-it.missionary-testkit :refer [with-determinism]]))
;;

;; -----------------------------------------------------------------------------
;; Determinism Contract
;; -----------------------------------------------------------------------------
;;
;; SUPPORTED DETERMINISTICALLY (under single-threaded scheduler driving):
;;   - m/sleep, m/timeout             ; virtual time, fully controlled
;;   - mt/yield                       ; scheduling points for interleaving
;;   - m/race, m/join, m/amb, m/amb=  ; combinators work with virtualized primitives
;;   - m/seed, m/sample               ; flow combinators
;;   - m/relieve, m/sem, m/rdv, m/mbx, m/dfv  ; coordination primitives
;;   - m/via with m/cpu or m/blk (JVM); executors rebound to scheduler microtasks
;;   - m/signal, m/watch, m/latest, m/stream ; when atom changes happen INSIDE the task
;;   - m/observe                      ; when callbacks are invoked INSIDE the task
;;
;; EXPLICITLY NOT SUPPORTED (non-deterministic):
;;   - m/publisher                    ; reactive-streams subsystem, external threading
;;   - m/via with custom executors    ; work runs on uncontrolled threads
;;   - Real I/O (HTTP, file, database); actual wall-clock time, external systems
;;   - m/observe with EXTERNAL callbacks ; events from outside the scheduler
;;   - m/watch on EXTERNALLY-modified atoms ; changes from threads outside the task
;;
;; KEY INSIGHT: m/signal, m/watch, m/stream work deterministically because signal
;; propagation is SYNCHRONOUS. When swap! is called inside the controlled task,
;; the watch callback, signal recomputation, and downstream consumers all execute
;; immediately in the same call stack. The testkit controls WHEN that swap! happens
;; (via m/sleep or mt/yield), giving deterministic control over propagation timing.
;;
;; THREAD CONTROL: Determinism is guaranteed only when the scheduler drives
;; execution from a single thread. Off-thread callbacks throw ::off-scheduler-callback
;; or silently break determinism. If you use m/via with a custom executor, you
;; accept that those sections are non-deterministic.
;;
;; -----------------------------------------------------------------------------

;; -----------------------------------------------------------------------------
;; Public keywords / kinds
;; -----------------------------------------------------------------------------

(def ^:const idle ::idle)

(def ^:const deadlock ::deadlock)
(def ^:const budget-exceeded ::budget-exceeded)
(def ^:const off-scheduler-callback ::off-scheduler-callback)
(def ^:const schedule-exhausted ::schedule-exhausted)
(def ^:const task-id-not-found ::task-id-not-found)
(def ^:const unknown-decision ::unknown-decision)

(def ^:const illegal-blocking-emission ::illegal-blocking-emission)
(def ^:const illegal-transfer ::illegal-transfer)

;; -----------------------------------------------------------------------------
;; Dynamic scheduler binding
;; -----------------------------------------------------------------------------

(def ^:dynamic *scheduler*
  "Dynamically bound to the current TestScheduler in deterministic tests."
  nil)

(def ^:dynamic *is-deterministic*
  "True when inside with-determinism scope. Use this to check if deterministic
  mode is active (m/sleep, m/timeout, m/cpu, m/blk are virtualized)."
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
  #?(:clj clojure.lang.PersistentQueue/EMPTY
     :cljs cljs.core/PersistentQueue.EMPTY))

(defn- q-empty? [q] (empty? q))
(defn- q-peek [q] (peek q))
(defn- q-pop [q] (pop q))
(defn- q-conj [q x] (conj q x))

;; -----------------------------------------------------------------------------
;; Scheduler
;; -----------------------------------------------------------------------------

(defrecord TestScheduler
           [state seed trace? micro-schedule])

(defn- maybe-trace-state
  "If tracing enabled (state has non-nil :trace vector), append event."
  [s event]
  (if (some? (:trace s))
    (update s :trace conj event)
    s))

(defn make-scheduler
  "Create a deterministic TestScheduler.

  Options:
  {:initial-ms     0
   :seed           nil | number        ; nil (default) = FIFO ordering
                                       ; number = random ordering with that seed
   :trace?         true|false
   :micro-schedule nil | vector        ; explicit selection decisions (overrides seed)}

  Ordering behavior:
  - No seed (or nil): FIFO ordering for both timers and microtasks.
    Predictable, good for unit tests with specific expected order.
  - With seed: Random ordering (seeded RNG) for both timers and microtasks.
    Deterministic but shuffled, good for fuzz/property testing.

  The :micro-schedule option provides explicit control over microtask selection
  when you need to replay a specific interleaving or test a particular order.

  Thread safety: On JVM, all scheduler operations must be performed from a single
  thread. Cross-thread callbacks will throw an error to catch accidental nondeterminism."
  ([]
   (make-scheduler {}))
  ([{:keys [initial-ms seed trace? micro-schedule]
     :or {initial-ms 0
          trace? false
          micro-schedule nil}}]
   (->TestScheduler
    (atom {:now-ms (long initial-ms)
           :next-id 0
           :micro-q empty-queue
           ;; timers: sorted-map keyed by [at-ms tie order id] -> timer-map
           :timers (sorted-map)
           ;; trace is either nil or vector
           :trace (when trace? [])
           ;; JVM-only "driver thread" captured lazily for thread safety enforcement
           :driver-thread #?(:clj nil :cljs ::na)
           ;; interleaving: micro-schedule index (consumed as used)
           :schedule-idx 0
           ;; deterministic RNG state for :random selection (LCG)
           :rng-state (if seed (long seed) 0)})
    seed (boolean trace?) micro-schedule)))

(defn now-ms
  "Current virtual time in milliseconds."
  [^TestScheduler sched]
  (:now-ms @(:state sched)))

(defn clock
  "Returns the current time in milliseconds.

   - Production (outside with-determinism): System/currentTimeMillis (JVM) or js/Date.now (CLJS)
   - Test mode (inside with-determinism): virtual time from the scheduler

   Use this in production code that needs timestamps, so tests can control time:

   (defn log-with-timestamp [msg]
     {:time (mt/clock) :msg msg})"
  []
  (if *is-deterministic*
    (now-ms (require-scheduler!))
    #?(:clj (System/currentTimeMillis)
       :cljs (js/Date.now))))

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

(defn next-event
  "Returns info about what would execute next, or nil if idle.

  Useful for stepwise debugging and agent introspection.

  Returns:
  - {:type :microtask :id ... :kind ... :label ... :lane ...}
    when a microtask is ready to run
  - {:type :timer :id ... :kind ... :label ... :at-ms ... :lane ...}
    when no microtasks but a timer is pending
  - nil when scheduler is idle (no work pending)

  Note: This reflects FIFO order. Actual selection may differ if a seed is
  provided (random ordering) or an explicit micro-schedule is configured."
  [^TestScheduler sched]
  (let [{:keys [micro-q timers]} @(:state sched)]
    (if-let [mt (peek micro-q)]
      {:type :microtask
       :id (:id mt)
       :kind (:kind mt)
       :label (:label mt)
       :lane (:lane mt)}
      (when-let [[_ t] (first timers)]
        {:type :timer
         :id (:id t)
         :kind (:kind t)
         :label (:label t)
         :at-ms (:at-ms t)
         :lane (:lane t)}))))

(defn next-tasks
  "Returns vector of available microtasks that can be selected for execution.

  Use this for manual stepping to see which tasks are available and their IDs.
  Each task map contains :id :kind :label :lane keys.

  Usage for manual stepping:
    (mt/start! sched task)
    (let [tasks (mt/next-tasks sched)]
      (println \"Available:\" (mapv :id tasks))
      (mt/step! sched (-> tasks first :id)))  ; step specific task

  Returns empty vector if no microtasks are ready (check timers with next-event)."
  [^TestScheduler sched]
  (let [{:keys [micro-q]} @(:state sched)]
    (mapv (fn [mt] (select-keys mt [:id :kind :label :lane]))
          (seq micro-q))))

(defn- diag
  ([sched] (diag sched nil))
  ([^TestScheduler sched label]
   (cond-> {:mt/now-ms (now-ms sched)
            :mt/pending (pending sched)}
     (:trace? sched) (assoc :mt/trace (trace sched))
     (some? label) (assoc :mt/label label))))

(defn- mt-ex
  ([kind sched msg]
   (mt-ex kind sched msg nil nil))
  ([kind sched msg {:keys [label] :as extra}]
   (mt-ex kind sched msg extra nil))
  ([kind ^TestScheduler sched msg extra cause]
   (let [data (merge {:mt/kind kind}
                     (diag sched (:label extra))
                     (dissoc extra :label))]
     #?(:clj (if (some? cause) (ex-info msg data cause) (ex-info msg data))
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
     "Enforce that ALL scheduler operations are performed by a single thread.
      This includes driving (step/tick/advance/run), enqueuing, timers, and flow control.
      Cross-thread access throws to catch accidental nondeterminism."
     [^TestScheduler sched op-label]
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
                           {:label op-label}))))))))

#?(:cljs
   (defn- ensure-driver-thread! [_ _] true))

(defn- enqueue-microtask!
  ([^TestScheduler sched f] (enqueue-microtask! sched f {}))
  ([^TestScheduler sched f {:keys [label kind lane]
                            :or {kind :microtask
                                 lane :default}}]
   (ensure-driver-thread! sched "enqueue-microtask!")
   (let [id (next-id! sched)]
     (swap! (:state sched)
            (fn [s]
              (let [now (:now-ms s)
                    mt {:id id
                        :kind kind
                        :label label
                        :lane lane
                        :enq-ms now
                        :from :micro
                        :f f}]
                (-> s
                    (update :micro-q q-conj mt)
                    (maybe-trace-state {:event :enqueue-microtask
                                        :id id
                                        :kind kind
                                        :label label
                                        :lane lane
                                        :now-ms now})))))
     id)))

(defn- schedule-timer!
  "Schedule f to run at now+delay-ms as a timer; returns a cancellation token."
  ([^TestScheduler sched delay-ms f] (schedule-timer! sched delay-ms f {}))
  ([^TestScheduler sched delay-ms f {:keys [label kind lane]
                                     :or {kind :timer
                                          lane :default}}]
   (ensure-driver-thread! sched "schedule-timer!")
   (let [id (next-id! sched)
         token (atom nil)]
     (swap! (:state sched)
            (fn [s]
              (let [now (:now-ms s)
                    at-ms (+ now (long delay-ms))
                    order id
                    ;; Timer tie-breaking: seeded only when doing random exploration
                    ;; (seed provided but no explicit micro-schedule), otherwise FIFO
                    tie (if (and (:seed sched) (not (:micro-schedule sched)))
                          (seeded-tie (:seed sched) order)
                          order)
                    k [at-ms tie order id]
                    t {:id id
                       :kind kind
                       :label label
                       :lane lane
                       :at-ms at-ms
                       :key k
                       :f f}]
                (reset! token k)
                (-> s
                    (update :timers assoc k t)
                    (maybe-trace-state {:event :enqueue-timer
                                        :id id
                                        :at-ms at-ms
                                        :kind kind
                                        :label label
                                        :lane lane
                                        :now-ms now})))))
     @token)))

(defn- cancel-timer!
  [^TestScheduler sched timer-token]
  (ensure-driver-thread! sched "cancel-timer!")
  (when timer-token
    (swap! (:state sched)
           (fn [s]
             (if-let [t (get-in s [:timers timer-token])]
               (-> s
                   (update :timers dissoc timer-token)
                   (maybe-trace-state {:event :cancel-timer
                                       :id (:id t)
                                       :at-ms (:at-ms t)
                                       :label (:label t)
                                       :now-ms (:now-ms s)}))
               s))))
  nil)

;; -----------------------------------------------------------------------------
;; Queue selection for interleaving
;; -----------------------------------------------------------------------------

(defn- q->vec
  "Convert queue to vector for indexed access."
  [q]
  (vec (seq q)))

(defn- vec->q
  "Convert vector back to queue."
  [v]
  (reduce q-conj empty-queue v))

(defn- remove-nth
  "Remove item at index n from vector."
  [v n]
  (into (subvec v 0 n) (subvec v (inc n))))

(defn- lcg-next
  "Linear Congruential Generator step. Returns next RNG state.
  Uses parameters that work well within 32-bit range for CLJS compatibility."
  [rng-state]
  (let [;; LCG parameters (same as MINSTD)
        a 48271
        m 2147483647 ; 2^31 - 1
        next-state (mod (* a (long rng-state)) m)]
    (if (zero? next-state) 1 next-state)))

(defn- select-from-queue
  "Select an item from the queue based on the decision.
  Returns [selected-item remaining-queue new-rng-state] or nil if queue is empty.

  Decisions (for micro-schedule replay):
  - integer (task ID) - select task with matching :id
  - [:by-id id] - same as bare integer (backward compatible)

  Decisions (for run-time selection, not in schedule):
  - :fifo - first in, first out (default when no seed)
  - :random - random selection (used internally when seed provided)"
  [q decision rng-state]
  (when-not (q-empty? q)
    (cond
      ;; Bare integer = task ID for replay
      (integer? decision)
      (let [v (q->vec q)
            target-id decision
            idx (first (keep-indexed (fn [i item] (when (= target-id (:id item)) i)) v))]
        (if idx
          [(nth v idx) (vec->q (remove-nth v idx)) rng-state]
          ;; ID not found - throw error for explicit replay schedules
          (throw (ex-info (str "Task ID " target-id " not found in queue")
                          {:mt/kind ::task-id-not-found
                           :target-id target-id
                           :available-ids (mapv :id v)}))))

      ;; [:by-id id] - backward compatible with previous format
      (and (vector? decision) (= :by-id (first decision)))
      (let [v (q->vec q)
            target-id (second decision)
            idx (first (keep-indexed (fn [i item] (when (= target-id (:id item)) i)) v))]
        (if idx
          [(nth v idx) (vec->q (remove-nth v idx)) rng-state]
          (throw (ex-info (str "Task ID " target-id " not found in queue")
                          {:mt/kind ::task-id-not-found
                           :target-id target-id
                           :available-ids (mapv :id v)}))))

      ;; :fifo - first in, first out (run-time default)
      (= decision :fifo)
      [(q-peek q) (q-pop q) rng-state]

      ;; :random - random selection (run-time with seed)
      (= decision :random)
      (let [v (q->vec q)
            n (count v)
            next-rng (lcg-next rng-state)
            idx (mod next-rng n)]
        [(nth v idx) (vec->q (remove-nth v idx)) next-rng])

      ;; Unknown decision type
      :else
      (throw (ex-info (str "Unknown schedule decision: " (pr-str decision))
                      {:mt/kind ::unknown-decision
                       :decision decision})))))

(defn- get-schedule-decision
  "Get the current schedule decision and advance the index.
  Returns [decision new-state].
  Throws if schedule is exhausted (explicit schedule provided but ran out)."
  [^TestScheduler sched s]
  (if-let [schedule (:micro-schedule sched)]
    (let [idx (:schedule-idx s)]
      (if (< idx (count schedule))
        [(nth schedule idx) (update s :schedule-idx inc)]
        (throw (mt-ex schedule-exhausted sched
                      "Schedule exhausted; increase schedule-length"
                      {:schedule-length (count schedule)
                       :decisions-used idx}))))
    ;; No explicit schedule: use FIFO if no seed, random if seed provided
    [(if (:seed sched) :random :fifo) s]))

(defn- finalize-timer-promotion
  "Finalize state after promoting timers, adding trace event if any were promoted."
  [s timers micro ids now]
  (cond-> (assoc s :timers timers :micro-q micro)
    (seq ids)
    (maybe-trace-state {:event :promote-timers
                        :ids ids
                        :count (count ids)
                        :now-ms now})))

(defn- promote-due-timers-in-state
  "Move all timers with at-ms <= now-ms into the microtask queue, in timer order."
  [^TestScheduler _sched s]
  (let [now (:now-ms s)]
    (loop [timers (:timers s)
           micro (:micro-q s)
           ids []]
      (if-let [[k t] (first timers)]
        (if (<= (:at-ms t) now)
          (recur (dissoc timers k)
                 (q-conj micro (assoc t :from :timer))
                 (conj ids (:id t)))
          (finalize-timer-promotion s timers micro ids now))
        (finalize-timer-promotion s timers micro ids now)))))

(defn- next-timer-time
  [^TestScheduler sched]
  (when-let [[_ t] (first (:timers @(:state sched)))]
    (:at-ms t)))

(defn- execute-microtask!
  "Internal: execute the microtask and handle interrupts (JVM) and tracing."
  [^TestScheduler sched state-atom mt now]
  #?(:clj
     ;; Clear any pending interrupt before executing microtask.
     ;; This prevents stale interrupt state from leaking into the task
     ;; and affecting blocking operations unexpectedly.
     (let [was-interrupted (Thread/interrupted)]
       (try
         ((:f mt))
         (catch Throwable e
           (throw e))
         (finally
           ;; Clear interrupt flag after execution to keep scheduler stable.
           ;; Record in trace if interrupt occurred during or before execution.
           (let [interrupted-after (Thread/interrupted)]
             (when (and (:trace? sched) (or was-interrupted interrupted-after))
               (swap! state-atom
                      maybe-trace-state
                      {:event :interrupt-cleared
                       :id (:id mt)
                       :before was-interrupted
                       :after interrupted-after
                       :now-ms now}))))))
     :cljs
     (try
       ((:f mt))
       (catch :default e
         (throw e))))
  (dissoc mt :f))

(defn- select-and-remove-task
  "Select a task from queue and return [mt q' decision rng-state s-after].
  When task-id is provided, selects that specific task.
  Otherwise uses schedule/FIFO/random selection."
  [^TestScheduler sched s q task-id]
  (let [q-size (count q)]
    (if task-id
      ;; Explicit task-id: select by ID
      (let [v (q->vec q)
            idx (first (keep-indexed (fn [i item] (when (= task-id (:id item)) i)) v))]
        (if-not idx
          (throw (ex-info (str "Task ID " task-id " not found in queue")
                          {:mt/kind task-id-not-found
                           :target-id task-id
                           :available-ids (mapv :id v)}))
          [(nth v idx) (vec->q (remove-nth v idx)) task-id (:rng-state s) s]))
      ;; No task-id: use schedule/FIFO/random
      (if (= q-size 1)
        [(q-peek q) (q-pop q) :fifo (:rng-state s) s]
        (let [[decision s-after] (get-schedule-decision sched s)
              [mt q' new-rng] (select-from-queue q decision (:rng-state s-after))]
          [mt q' decision new-rng s-after])))))

(defn step!
  "Run exactly 1 microtask. Returns ::idle if no microtasks.

  (step! sched)        - select next task per schedule/FIFO/random
  (step! sched task-id) - run specific task by ID (for manual stepping)

  Binds *scheduler* to sched for the duration of execution."
  ([^TestScheduler sched] (step! sched nil))
  ([^TestScheduler sched task-id]
   (ensure-driver-thread! sched "step!")
   (binding [*scheduler* sched]
     (let [state-atom (:state sched)]
       (loop []
         (let [s @state-atom
               q (:micro-q s)]
           (if (q-empty? q)
             idle
             (let [q-size (count q)
                   [mt q' decision new-rng s-after] (select-and-remove-task sched s q task-id)
                   now (:now-ms s-after)
                   select-trace (when (> q-size 1)
                                  {:event :select-task
                                   :decision decision
                                   :queue-size q-size
                                   :selected-id (:id mt)
                                   :alternatives (mapv :id (filter #(not= (:id %) (:id mt)) (q->vec q)))
                                   :now-ms now})
                   s' (-> s-after
                          (assoc :micro-q q')
                          (assoc :rng-state new-rng)
                          (cond-> select-trace (maybe-trace-state select-trace))
                          (maybe-trace-state {:event :run-microtask
                                              :id (:id mt)
                                              :kind (:kind mt)
                                              :label (:label mt)
                                              :lane (:lane mt)
                                              :now-ms now}))]
               (if (compare-and-set! state-atom s s')
                 (execute-microtask! sched state-atom mt now)
                 (recur))))))))))

(defn tick!
  "Drain all microtasks at current virtual time. Returns number of microtasks executed.

  Binds *scheduler* to sched for the duration of execution."
  [^TestScheduler sched]
  (ensure-driver-thread! sched "tick!")
  (binding [*scheduler* sched]
    (loop [n 0]
      (let [r (step! sched)]
        (if (= r idle)
          n
          (recur (inc n)))))))

(defn advance-to!
  "Set time to t (>= now), enqueue due timers, then tick. Returns number of microtasks executed by tick.

  Binds *scheduler* to sched for the duration of execution."
  [^TestScheduler sched t]
  (ensure-driver-thread! sched "advance-to!")
  (binding [*scheduler* sched]
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
      (tick! sched))))

(defn advance!
  "Advance virtual time by dt-ms (>=0), enqueue due timers, then tick.

  Binds *scheduler* to sched for the duration of execution."
  [^TestScheduler sched dt-ms]
  (ensure-driver-thread! sched "advance!")
  (binding [*scheduler* sched]
    (let [dt (long dt-ms)]
      (when (neg? dt)
        (throw (mt-ex illegal-transfer sched
                      (str "advance! requires non-negative dt-ms, got " dt-ms ".")
                      {:label "advance!"})))
      (advance-to! sched (+ (now-ms sched) dt)))))

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

  Automatically binds *scheduler* to sched for the task invocation.

  (def job (mt/start! sched task {:label \"optional\"}))"
  ([^TestScheduler sched task]
   (start! sched task {}))
  ([^TestScheduler sched task {:keys [label] :as _opts}]
   (ensure-driver-thread! sched "start!")
   (binding [*scheduler* sched]
     (let [id (next-id! sched)
         job-state (atom {:status :pending})
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
                       :kind :job/complete
                       :lane :default}))

         ;; Fail job directly without scheduler (for off-thread callback errors).
         ;; This avoids deadlock when ensure-driver-thread! would throw in enqueue-microtask!.
         fail-directly! (fn [ex]
                          (swap! job-state
                                 (fn [st]
                                   (if (= :pending (:status st))
                                     (assoc st :status :failure :error ex)
                                     st))))]

     ;; Start task immediately (but its completion is always delivered through scheduler microtasks).
     (try
       (let [cancel
             (task
              (fn [v]
                ;; Detect off-driver-thread callback (JVM only)
                #?(:clj
                   (let [owner (:driver-thread @(:state sched))]
                     (if (and owner (not= owner (Thread/currentThread)))
                       ;; Off-thread: fail directly to avoid deadlock
                       (fail-directly!
                        (mt-ex off-scheduler-callback sched
                               "Task success callback invoked off scheduler thread."
                               {:label label}))
                       (complete! :success v)))
                   :cljs
                   (complete! :success v)))
              (fn [e]
                #?(:clj
                   (let [owner (:driver-thread @(:state sched))]
                     (if (and owner (not= owner (Thread/currentThread)))
                       ;; Off-thread: fail directly to avoid deadlock
                       (fail-directly!
                        (mt-ex off-scheduler-callback sched
                               "Task failure callback invoked off scheduler thread."
                               {:label label
                                :mt/original-error (pr-str e)}))
                       (complete! :failure e)))
                   :cljs
                   (complete! :failure e))))]
         (reset! cancel-cell (or cancel (fn [] nil))))
       (catch #?(:clj Throwable :cljs :default) e
         (complete! :failure e)
         (reset! cancel-cell (fn [] nil))))

      (->Job sched id label job-state cancel-cell)))))

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
  "Cancel a Job handle."
  [x]
  (-cancel! x))

;; -----------------------------------------------------------------------------
;; Virtual time primitives (replacements for Missionary)
;; -----------------------------------------------------------------------------

(defn- cancelled-ex []
  #?(:clj (Cancelled.)
     :cljs (Cancelled.)))

(defn yield
  "Yield point task for testing interleavings.

  (mt/yield)
  (mt/yield x)

  In production (outside with-determinism): completes immediately with x (or nil).
  In test mode (inside with-determinism): creates a scheduling point that allows
  other concurrent tasks to interleave, then completes with x.

  This is useful for:
  - Testing concurrent code under different task orderings
  - Creating explicit interleaving points without time delays
  - Simulating cooperative multitasking yield points

  Example:
    ;; In production, this just returns :done immediately
    (m/? (mt/yield :done))

    ;; In tests with check-interleaving, different orderings are explored
    (mt/check-interleaving
      (fn []
        (let [result (atom [])]
          (m/sp
            (m/? (m/join vector
                   (m/sp (swap! result conj :a) (m/? (mt/yield)) (swap! result conj :a2))
                   (m/sp (swap! result conj :b) (m/? (mt/yield)) (swap! result conj :b2))))
            @result)))
      {:property (fn [r] (= 4 (count r)))})"
  ([] (yield nil))
  ([x]
   (fn [s f]
     (if *is-deterministic*
       ;; Test mode: create a scheduling point via microtask
       (let [sched (require-scheduler!)
             done? (atom false)]
         (enqueue-microtask!
          sched
          (fn []
            (when (compare-and-set! done? false true)
              (s x)))
          {:kind :yield
           :label "yield"})
         (fn cancel []
           (when (compare-and-set! done? false true)
             ;; fail synchronously, matching Missionary semantics
             (f (cancelled-ex)))
           nil))
       ;; Production mode: complete immediately
       (do
         (s x)
         (fn cancel [] nil))))))

;; Store original missionary implementations at load time
(def ^:private original-sleep m/sleep)
(def ^:private original-timeout m/timeout)

(defn- deterministic-sleep
  "Internal: virtual sleep implementation for deterministic mode."
  [ms x]
  (fn [s f]
    (let [sched (require-scheduler!)
          done? (atom false)
          tok (schedule-timer!
               sched (long ms)
               (fn []
                 (when (compare-and-set! done? false true)
                   (s x)))
               {:kind :sleep
                :label "sleep"})]
      (fn cancel
        []
        (when (compare-and-set! done? false true)
          (cancel-timer! sched tok)
          ;; fail synchronously, matching Missionary semantics
          (f (cancelled-ex)))
        nil))))

(defn sleep
  "Sleep task that dispatches based on determinism mode.

  (mt/sleep ms)
  (mt/sleep ms x)

  Behavior:
  - In deterministic mode (*is-deterministic* true): uses virtual time via scheduler
  - In production mode: delegates to original missionary.core/sleep

  The dispatch decision is made at task execution time, not at creation time.
  This allows tasks created outside with-determinism to still use virtual time
  when executed inside with-determinism.

  Semantics (both modes):
  - completes after delay with x (or nil)
  - cancelling fails immediately with missionary.Cancelled"
  ([ms] (sleep ms nil))
  ([ms x]
   ;; Return a task that dispatches at execution time
   (fn [s f]
     (if *is-deterministic*
       ((deterministic-sleep ms x) s f)
       ((original-sleep ms x) s f)))))

(defn- deterministic-timeout
  "Internal: virtual timeout implementation for deterministic mode."
  [task ms x]
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
               {:kind :timeout/timer
                :label "timeout-timer"}))

      ;; cancellation thunk
      (fn cancel []
        (when (compare-and-set! done? false true)
          (cancel-timer! sched @timer-token)
          (when-let [c @cancel-child]
            (try (c) (catch #?(:clj Throwable :cljs :default) _ nil)))
          ;; fail synchronously, matching Missionary semantics
          (f (cancelled-ex)))
        nil))))

(defn timeout
  "Timeout wrapper task that dispatches based on determinism mode.

  (mt/timeout task ms)
  (mt/timeout task ms x)

  Behavior:
  - In deterministic mode (*is-deterministic* true): uses virtual time via scheduler
  - In production mode: delegates to original missionary.core/timeout

  The dispatch decision is made at task execution time, not at creation time.
  This allows tasks created outside with-determinism to still use virtual time
  when executed inside with-determinism.

  Semantics (both modes):
  - if input completes before ms, propagate success/failure
  - else, cancel input task and succeed with x (default nil)
  - cancelling the timeout task fails with missionary.Cancelled (and cancels input)."
  ([task ms] (timeout task ms nil))
  ([task ms x]
   ;; Return a task that dispatches at execution time
   (fn [s f]
     (if *is-deterministic*
       ((deterministic-timeout task ms x) s f)
       ((original-timeout task ms x) s f)))))

;; -----------------------------------------------------------------------------
;; mt/run
;; -----------------------------------------------------------------------------

(defn- run*
  [^TestScheduler sched task {:keys [auto-advance? max-steps max-time-ms label]
                              :or {auto-advance? true
                                   max-steps 100000
                                   max-time-ms 60000}}]
  (ensure-driver-thread! sched "run")
  (let [start-time (now-ms sched)
        job (start! sched task {:label label})]
    (loop [total-steps 0]
      (let [total-steps (+ total-steps (tick! sched))
            elapsed (- (now-ms sched) start-time)]
        (when (> total-steps (long max-steps))
          (throw (mt-ex budget-exceeded sched
                        (str "Step budget exceeded: " total-steps " > " max-steps)
                        {:label label
                         :mt/steps total-steps
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
            ;; Recheck done? to handle off-thread callbacks that may have completed the job
            (if (done? job)
              (result job)
              (throw (mt-ex deadlock sched
                            "Deadlock: task not done after draining microtasks, and auto-advance? is false."
                            {:label label})))
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
                (recur (+ total-steps (advance-to! sched t-next))))
              ;; Recheck done? to handle off-thread callbacks that may have completed the job
              (if (done? job)
                (result job)
                (throw (mt-ex deadlock sched
                              "Deadlock: no microtasks, no timers, and task still pending."
                              {:label label}))))))))))

(defn run
  "Run task deterministically to completion (or throw).

  Automatically binds *scheduler* to sched for the duration of execution,
  so m/via with m/cpu or m/blk works correctly.

  JVM: returns value or throws.
  CLJS: returns a js/Promise that resolves/rejects."
  ([^TestScheduler sched task]
   (run sched task {}))
  ([^TestScheduler sched task opts]
   #?(:clj (binding [*scheduler* sched]
             (run* sched task opts))
      :cljs (js/Promise.
             (fn [resolve reject]
               (try
                 (binding [*scheduler* sched]
                   (resolve (run* sched task opts)))
                 (catch :default e
                   (reject e))))))))

;; -----------------------------------------------------------------------------
;; Deterministic executors (JVM-only)
;; -----------------------------------------------------------------------------

;; Store original cpu/blk executors at load time (sleep/timeout originals are stored above)
#?(:clj (def ^:private original-cpu m/cpu))
#?(:clj (def ^:private original-blk m/blk))

#?(:clj
   (defn- make-executor
     "Factory for deterministic executors. Enqueues runnables as scheduler microtasks.
     Uses require-scheduler! to get the current scheduler from *scheduler* binding."
     [lane label]
     (reify Executor
       (execute [_ runnable]
         (let [sched (require-scheduler!)]
           (enqueue-microtask!
            sched
            (fn [] (.run ^Runnable runnable))
            {:kind :executor
             :lane lane
             :label label}))))))

#?(:clj
   (defn executor
     "Deterministic java.util.concurrent.Executor. Enqueues runnables as scheduler microtasks.
     Must be called inside with-determinism with a scheduler bound to *scheduler*."
     []
     (make-executor :default "executor")))

#?(:cljs
   (defn executor []
     (throw (ex-info "mt/executor is JVM-only." {:mt/kind ::unsupported}))))

#?(:clj
   (defn cpu-executor
     "Deterministic CPU executor for m/via. Lane :cpu retained for introspection/trace.
     Must be called inside with-determinism with a scheduler bound to *scheduler*."
     []
     (make-executor :cpu "cpu-executor")))

#?(:cljs
   (defn cpu-executor []
     (throw (ex-info "mt/cpu-executor is JVM-only." {:mt/kind ::unsupported}))))

#?(:clj
   (defn blk-executor
     "Deterministic blocking executor for m/via. Lane :blk retained for introspection/trace.
     Must be called inside with-determinism with a scheduler bound to *scheduler*."
     []
     (make-executor :blk "blk-executor")))

#?(:cljs
   (defn blk-executor []
     (throw (ex-info "mt/blk-executor is JVM-only." {:mt/kind ::unsupported}))))

#?(:clj
   (def cpu
     "CPU executor that dispatches based on determinism mode.

     Behavior:
     - In deterministic mode (*is-deterministic* true): enqueues as scheduler microtask
     - In production mode: delegates to original missionary.core/cpu

     The dispatch decision is made when execute is called, not at load time."
     (reify Executor
       (execute [_ runnable]
         (if *is-deterministic*
           (.execute (cpu-executor) runnable)
           (.execute ^Executor original-cpu runnable))))))

#?(:cljs
   (def cpu
     "CPU executor - CLJS stub. Not supported in CLJS."
     nil))

#?(:clj
   (def blk
     "Blocking executor that dispatches based on determinism mode.

     Behavior:
     - In deterministic mode (*is-deterministic* true): enqueues as scheduler microtask
     - In production mode: delegates to original missionary.core/blk

     The dispatch decision is made when execute is called, not at load time."
     (reify Executor
       (execute [_ runnable]
         (if *is-deterministic*
           (.execute (blk-executor) runnable)
           (.execute ^Executor original-blk runnable))))))

#?(:cljs
   (def blk
     "Blocking executor - CLJS stub. Not supported in CLJS."
     nil))

;; -----------------------------------------------------------------------------
;; Integration macro: with-determinism
;; -----------------------------------------------------------------------------

#?(:clj
   (defonce ^{:doc "Global lock and state for with-determinism macro to ensure thread-safe with-redefs.
     This allows parallel test runs without var rebinding conflicts."
              :no-doc true}
     determinism-state
     (atom {:active-count 0})))

#?(:clj
   (defonce ^:no-doc determinism-lock (Object.)))

#?(:clj
   (defn ^:no-doc acquire-determinism!
     "Acquire determinism context. First caller rebinds vars.
     Returns true, always succeeds.

     Note: sleep, timeout, cpu, and blk are now dispatching wrappers that check
     *is-deterministic*, so we just need to point m/sleep -> mt/sleep, etc.
     The mt/ functions will dispatch to originals when not in deterministic mode."
     []
     (locking determinism-lock
       (let [state @determinism-state]
         (when (zero? (:active-count state))
           ;; First acquirer: rebind vars to dispatching wrappers
           (alter-var-root #'missionary.core/sleep (constantly sleep))
           (alter-var-root #'missionary.core/timeout (constantly timeout))
           (alter-var-root #'missionary.core/cpu (constantly cpu))
           (alter-var-root #'missionary.core/blk (constantly blk)))
         (swap! determinism-state update :active-count inc)))
     true))

#?(:clj
   (defn ^:no-doc release-determinism!
     "Release determinism context. Last caller restores original vars."
     []
     (locking determinism-lock
       (swap! determinism-state update :active-count dec)
       (let [state @determinism-state]
         (when (zero? (:active-count state))
           ;; Last releaser: restore originals
           (alter-var-root #'missionary.core/sleep (constantly original-sleep))
           (alter-var-root #'missionary.core/timeout (constantly original-timeout))
           (alter-var-root #'missionary.core/cpu (constantly original-cpu))
           (alter-var-root #'missionary.core/blk (constantly original-blk)))))
     nil))

#?(:clj
   (defmacro with-determinism
     "Scope deterministic behavior to a test body by rebinding/redefining Missionary vars.

     IMPORTANT: This macro is the entry point to deterministic behavior. All flows
     and tasks under test MUST be created inside the macro body (or by factory
     functions called from within the body). Tasks/flows created BEFORE or OUTSIDE
     the macro will capture the real (non-virtual) primitives and will NOT be
     deterministic.

     Usage:
       (with-determinism
         (let [sched (mt/make-scheduler)]
           (mt/run sched
             (m/sp (m/? (m/sleep 100)) :done))))

     Also correct (factory function called inside):
       (defn make-task [] (m/sp (m/? (m/sleep 100)) :done))
       (with-determinism
         (let [sched (mt/make-scheduler)]
           (mt/run sched (make-task))))

     WRONG (task created outside - will use real time!):
       (def my-task (m/sp (m/? (m/sleep 100)) :done))  ; WRONG: created outside
       (with-determinism
         (let [sched (mt/make-scheduler)]
           (mt/run sched my-task)))  ; m/sleep was NOT rebound when task was created

     Effects:
     - Sets *is-deterministic* to true
     - missionary.core/sleep    -> mt/sleep (dispatches to virtual or original based on *is-deterministic*)
     - missionary.core/timeout  -> mt/timeout (dispatches to virtual or original based on *is-deterministic*)
     - missionary.core/cpu      -> mt/cpu (dispatches to deterministic or original based on *is-deterministic*)
     - missionary.core/blk      -> mt/blk (dispatches to deterministic or original based on *is-deterministic*)

     DISPATCHING BEHAVIOR: mt/sleep, mt/timeout, mt/cpu, and mt/blk are wrapper
     functions/values that check *is-deterministic* at call/deref time. When true,
     they use virtual time via the scheduler. When false (outside with-determinism),
     they delegate to the original missionary implementations, allowing the same code
     to work in both test and production contexts.

     NOTE: m/via with m/cpu or m/blk works correctly because these executors
     dispatch based on *is-deterministic*. Do NOT use real executors
     (e.g., Executors/newFixedThreadPool) inside with-determinism.

     NOTE: mt/run and mt/start! automatically bind *scheduler* to sched, so you
     don't need any explicit binding - just pass the scheduler as an argument.

     INTERRUPT BEHAVIOR: When a via task is cancelled before its microtask executes,
     the via body will run with Thread.interrupted() returning true. Blocking calls
     in the via body will throw InterruptedException. The interrupt flag is cleared
     after the via body completes, so the scheduler remains usable.

     CONCURRENCY: Uses reference counting to make var rebinding safe for parallel test runs.
     Multiple tests can run concurrently - first acquires the rebindings, last restores originals."
     [& body]
     (let [cljs? (boolean &env)]
       (if cljs?
         ;; CLJS: single-threaded, use simple with-redefs
         `(binding [*is-deterministic* true]
            (with-redefs
             [missionary.core/sleep sleep
              missionary.core/timeout timeout]
              ~@body))
         ;; CLJ: use reference-counted var rebinding for parallel safety
         `(do
            (acquire-determinism!)
            (try
              (binding [*is-deterministic* true]
                ~@body)
              (finally
                (release-determinism!))))))))

;; -----------------------------------------------------------------------------
;; Flow determinism: scheduled-flow + spawn-flow!
;; -----------------------------------------------------------------------------

(defn scheduled-flow
  "Wrap a flow to marshal readiness/termination signals through the scheduler.

  (mt/scheduled-flow sched flow {:label ...})"
  ([^TestScheduler sched flow]
   (scheduled-flow sched flow {}))
  ([^TestScheduler sched flow {:keys [label]}]
   (fn [n t]
     (let [wrap (fn [thunk kind]
                  (fn []
                    (enqueue-microtask!
                     sched
                     (fn [] (thunk))
                     {:kind kind
                      :label label
                      :lane :default})))]
       (flow (wrap n :flow/notifier)
             (wrap t :flow/terminator))))))

;; -----------------------------------------------------------------------------
;; Interleaving: trace extraction and replay
;; -----------------------------------------------------------------------------

(defn trace->schedule
  "Extract the sequence of task IDs from a trace for replay.
  Returns a vector of task IDs [id1 id2 id3 ...] that can be used to replay
  the exact same execution order.

  Usage:
    (def schedule (mt/trace->schedule (mt/trace sched)))
    ;; => [2 4 3]  ; bare task IDs
    ;; User can inspect/modify: [2 3 4]  ; different order
    (mt/replay-schedule (make-task) schedule)"
  [trace]
  (->> trace
       (filter #(= :select-task (:event %)))
       (mapv :selected-id)))

(defn replay-schedule
  "Run a task with the exact schedule from a previous trace.
  Returns the task result.

  IMPORTANT: Must be called inside a with-determinism body.

  Usage:
    (def original-trace (mt/trace sched))
    (with-determinism
      (mt/replay-schedule (make-task) (mt/trace->schedule original-trace)))"
  ([task schedule]
   (replay-schedule task schedule {}))
  ([task schedule {:keys [trace? max-steps max-time-ms]
                   :or {trace? true
                        max-steps 100000
                        max-time-ms 60000}}]
   (when-not *is-deterministic*
     (throw (ex-info "replay-schedule must be called inside with-determinism body"
                     {:mt/kind ::replay-without-determinism})))
   (let [sched (make-scheduler {:micro-schedule schedule :trace? trace?})]
     (run sched task {:max-steps max-steps
                      :max-time-ms max-time-ms}))))

(defn replay
  "Replay a failure from check-interleaving.

  IMPORTANT: Must be called inside a with-determinism body.

  Takes a failure bundle (from check-interleaving) and a task factory,
  re-runs the task with the same schedule that caused the failure.

  Usage:
    (let [result (mt/check-interleaving make-task {:seed 42 :property valid?})]
      (when-not (:ok? result)
        (mt/replay make-task result)))

  Options (merged with failure bundle):
  - :trace?      - whether to record trace (default true)
  - :max-steps   - max scheduler steps (default 100000)
  - :max-time-ms - max virtual time (default 60000)

  Returns the task result (same as replay-schedule)."
  ([task-fn failure]
   (replay task-fn failure {}))
  ([task-fn failure opts]
   (let [schedule (or (:schedule failure)
                      (throw (ex-info "Failure bundle missing :schedule"
                                      {:mt/kind ::invalid-failure-bundle
                                       :failure failure})))]
     (replay-schedule (task-fn) schedule opts))))


;; -----------------------------------------------------------------------------
;; Interleaving: test helpers
;; -----------------------------------------------------------------------------

(defn- default-base-seed []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn- run-with-random-interleaving
  "Run a task once with random selection seeded by test-seed. Returns map with result info.
  Internal helper for check-interleaving and explore-interleavings.
  Must be called inside a with-determinism body."
  [task {:keys [test-seed max-steps max-time-ms]
         :or {max-time-ms 60000}}]
  (let [sched (make-scheduler {:trace? true
                               :seed test-seed})
        result (try
                 {:value (run sched task {:max-steps max-steps
                                          :max-time-ms max-time-ms})}
                 (catch #?(:clj Throwable :cljs :default) e
                   {:error e}))]
    {:result result
     :seed test-seed
     :micro-schedule (trace->schedule (trace sched))
     :trace (trace sched)}))

(defn check-interleaving
  "Run a task with many different interleavings to find failures.

  IMPORTANT: Must be called inside a with-determinism body.

  task-fn should be a 0-arg function that returns a fresh task for each test.
  This ensures mutable state (like atoms) is reset between iterations.

  Options:
  - :num-tests   - number of different interleavings to try (default 100)
  - :seed        - base seed for RNG (default: current time)
  - :property    - (fn [result] boolean) - returns true if result is valid
  - :max-steps   - max scheduler steps per run (default 10000)
  - :max-time-ms - max virtual time per run (default 60000)

  Returns on success:
  {:ok?            true
   :seed           base seed used (for reproducibility)
   :iterations-run number of iterations completed}

  Returns on failure:
  {:ok?       false
   :kind      :exception | :property-failed
   :seed      seed used for this iteration
   :schedule  schedule that caused failure (for replay)
   :trace     full trace
   :iteration which iteration failed
   :error     exception (present when :kind is :exception)
   :value     result value (present when :kind is :property-failed)}

  Note: For reproducible tests, always specify :seed. Without it, the current
  system time is used, making results non-reproducible across runs."
  [task-fn {:keys [num-tests seed property max-steps max-time-ms]
            :or {num-tests 100
                 max-steps 10000
                 max-time-ms 60000}}]
  (let [base-seed (or seed (default-base-seed))]
    (loop [i 0]
      (if (>= i num-tests)
        {:ok? true :seed base-seed :iterations-run num-tests}
        (let [run-result (run-with-random-interleaving (task-fn)
                                                       {:test-seed (+ base-seed i)
                                                        :max-steps max-steps
                                                        :max-time-ms max-time-ms})
              {:keys [result seed micro-schedule trace]} run-result
              exception? (some? (:error result))
              property-failed? (and property
                                    (not exception?)
                                    (not (property (:value result))))]
          (if (or exception? property-failed?)
            {:ok? false
             :kind (if exception? :exception :property-failed)
             :seed seed
             :schedule micro-schedule
             :trace trace
             :iteration i
             :error (when exception? (:error result))
             :value (when-not exception? (:value result))}
            (recur (inc i))))))))

(defn explore-interleavings
  "Explore different interleavings of a task and return a summary.

  IMPORTANT: Must be called inside a with-determinism body.

  task-fn should be a 0-arg function that returns a fresh task for each test.
  This ensures mutable state (like atoms) is reset between iterations.

  Options:
  - :num-samples - number of different interleavings to try (default 100)
  - :seed        - base seed for RNG (default: current time)
  - :max-steps   - max scheduler steps per run (default 10000)

  Returns:
  {:unique-results - count of distinct results seen
   :results        - vector of {:result r :micro-schedule s} maps
   :seed           - the base seed used (for reproducibility)}

  Note: For reproducible tests, always specify :seed. Without it, the current
  system time is used, making results non-reproducible across runs."
  [task-fn {:keys [num-samples seed max-steps]
            :or {num-samples 100
                 max-steps 10000}}]
  (let [base-seed (or seed (default-base-seed))
        results (for [i (range num-samples)
                      :let [run-result (run-with-random-interleaving (task-fn)
                                                                     {:test-seed (+ base-seed i)
                                                                      :max-steps max-steps})
                            ;; Extract value or return error map
                            r (let [res (:result run-result)]
                                (if (:error res) res (:value res)))]]
                  {:result r
                   :micro-schedule (:micro-schedule run-result)})]
    {:unique-results (count (distinct (map :result results)))
     :results (vec results)
     :seed base-seed}))


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
  ([flow {:keys [xf timeout-ms]
          :or {xf nil}}]
   (let [f (if xf (m/eduction xf flow) flow)
         t (m/reduce conj [] f)
         t (if timeout-ms
             (timeout t timeout-ms ::timeout)
             t)]
     t)))
