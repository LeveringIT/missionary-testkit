(ns de.levering-it.missionary-testkit
  (:require [missionary.core :as m])
  #?(:clj
     (:import (java.util.concurrent Executor)
              (missionary Cancelled)))
  #?(:cljs
     (:import (missionary Cancelled))))

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
(def ^:const microtask-exception ::microtask-exception)
(def ^:const not-in-deterministic-mode ::not-in-deterministic-mode)

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

(defn- require-deterministic-mode!
  "Throw if not inside with-determinism scope. Used by functions that require
  deterministic mode to work correctly (e.g., check-interleaving)."
  [fn-name]
  (when-not *is-deterministic*
    (throw (ex-info (str fn-name " must be called inside mt/with-determinism body. "
                         "Without with-determinism, m/sleep and m/timeout use real time, "
                         "breaking deterministic interleaving exploration.")
                    {:mt/kind ::not-in-deterministic-mode
                     :fn fn-name}))))

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
;; RNG helpers (needed by make-scheduler, defined early)
;; -----------------------------------------------------------------------------

(defn- lcg-next
  "Linear Congruential Generator step. Returns next RNG state.
  Uses parameters that work well within 32-bit range for CLJS compatibility."
  [rng-state]
  (let [;; LCG parameters (same as MINSTD)
        a 48271
        m 2147483647 ; 2^31 - 1
        next-state (mod (* a (long rng-state)) m)]
    (if (zero? next-state) 1 next-state)))

;; -----------------------------------------------------------------------------
;; Scheduler
;; -----------------------------------------------------------------------------

(defrecord TestScheduler
           [state seed trace? micro-schedule duration-range timer-policy cpu-threads])

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
   :seed           nil | number        ; nil (default) = FIFO task selection
                                       ; number = random selection with that seed
   :trace?         true|false
   :micro-schedule nil | vector        ; explicit selection decisions (overrides seed)
   :duration-range nil | [lower upper] ; virtual duration range for microtasks
                                       ; nil (default) = 0ms duration (instant)
                                       ; [lo hi] = random duration in [lo,hi]ms per task
   :timer-policy   :promote-first | :microtasks-first
   :cpu-threads    8}                  ; CPU thread pool size (default 8)

  Task selection behavior:
  - No seed (or nil): FIFO selection from the microtask queue.
    Predictable, good for unit tests with specific expected order.
  - With seed: Random selection from the microtask queue (seeded RNG).
    Deterministic but shuffled, good for fuzz/property testing.

  Timer promotion:
  - Timers are always promoted to the microtask queue in FIFO order (by id)
    when their at-ms is reached.
  - Random tie-breaking for same-time timers happens at selection time,
    not at promotion time. This is captured in :select-task trace events.

  Duration behavior:
  - No duration-range (or nil): All microtasks complete instantly (0ms).
    Current behavior, backward compatible.
  - With duration-range [lo hi]: Each microtask gets a random duration in [lo,hi]ms
    at enqueue time. Before the callback runs, virtual time advances by that duration.
    This enables realistic timeout races and timing-dependent interleaving.

  Timer policy (microtasks vs timers at same virtual time):
  - :promote-first (default) - promote due timers to microtask queue first,
    then select among all available. More adversarial; timers compete equally.
  - :microtasks-first - drain existing microtasks first, then promote/run
    timers due at that time. JS-like microtask priority; more realistic.

  Thread pool modeling:
  - :cpu-threads (default 8) - simulates a fixed-size CPU thread pool.
    When cpu-threads tasks are in-flight, additional :cpu lane tasks must wait.
  - :blk lane - unlimited threads, each blocking call gets its own thread.
  - :default lane - single-threaded microtask queue (like JS event loop).
  - Tasks with duration > 0 occupy their thread for that duration.
  - Multiple in-flight tasks can have overlapping execution times.

  RNG streams:
  - Uses separate RNG streams for task selection and duration generation.
  - This means enabling :duration-range won't change interleaving order.
  - During replay, schedule + seed + duration-range produces identical behavior.

  The :micro-schedule option provides explicit control over microtask selection
  when you need to replay a specific interleaving or test a particular order.

  Thread safety: On JVM, all scheduler operations must be performed from a single
  thread. Cross-thread callbacks will throw an error to catch accidental nondeterminism."
  ([]
   (make-scheduler {}))
  ([{:keys [initial-ms seed trace? micro-schedule duration-range timer-policy cpu-threads]
     :or {initial-ms 0
          trace? false
          micro-schedule nil
          duration-range nil
          timer-policy :promote-first
          cpu-threads 8}}]
   ;; Derive separate RNG seeds for selection and duration
   ;; Using different offsets ensures independent streams
   (let [base-seed (if seed (long seed) 0)
         select-seed base-seed
         ;; Offset duration seed to avoid correlation
         duration-seed (if seed (lcg-next (+ base-seed 1000000)) 0)]
     (->TestScheduler
      (atom {:now-ms (long initial-ms)
             :next-id 0
             :micro-q empty-queue
             ;; timers: sorted-map keyed by [at-ms id] -> timer-map (FIFO within same at-ms)
             :timers (sorted-map)
             ;; trace is either nil or vector
             :trace (when trace? [])
             ;; JVM-only "driver thread" captured lazily for thread safety enforcement
             :driver-thread #?(:clj nil :cljs ::na)
             ;; interleaving: micro-schedule index (consumed as used)
             :schedule-idx 0
             ;; Split RNG streams for independent randomization
             ;; :rng-select - used for task selection (interleaving)
             ;; :rng-duration - used for duration generation
             :rng-select select-seed
             :rng-duration duration-seed
             ;; Thread pool state: in-flight tasks and active thread counts
             ;; in-flight: sorted-map keyed by [end-ms id] -> task-info
             :in-flight (sorted-map)
             ;; active-threads: count of threads currently executing per lane
             :active-threads {:default 0 :cpu 0 :blk 0}})
      seed (boolean trace?) micro-schedule duration-range timer-policy cpu-threads))))

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

(defn- rand-in-range
  "Generate a random integer in [lo, hi] inclusive using RNG state.
  Returns [value new-rng-state]."
  [rng-state lo hi]
  (let [next-rng (lcg-next rng-state)
        range-size (inc (- (long hi) (long lo)))
        value (+ (long lo) (mod next-rng range-size))]
    [value next-rng]))

(defn- next-duration!
  "Get next duration from scheduler's duration-range, atomically updating RNG.
  Returns duration in ms (0 if no duration-range configured).
  Uses :rng-duration stream (separate from :rng-select for interleaving).
  Used by yield and via-call to assign durations to user work."
  [^TestScheduler sched]
  (if-let [[lo hi] (:duration-range sched)]
    (let [state-atom (:state sched)]
      (loop []
        (let [s @state-atom
              [duration new-rng] (rand-in-range (:rng-duration s) lo hi)]
          (if (compare-and-set! state-atom s (assoc s :rng-duration new-rng))
            duration
            (recur)))))
    0))

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
  "Enqueue a microtask. Duration is only applied if explicitly provided.
  Use next-duration! to get a duration from the scheduler's duration-range
  for operations that represent user work (yield, via-call)."
  ([^TestScheduler sched f] (enqueue-microtask! sched f {}))
  ([^TestScheduler sched f {:keys [label kind lane duration-ms]
                            :or {kind :microtask
                                 lane :default}}]
   (ensure-driver-thread! sched "enqueue-microtask!")
   (let [id (next-id! sched)
         duration (or duration-ms 0)]
     (swap! (:state sched)
            (fn [s]
              (let [now (:now-ms s)
                    mt {:id id
                        :kind kind
                        :label label
                        :lane lane
                        :enq-ms now
                        :from :micro
                        :duration-ms duration
                        :f f}]
                (-> s
                    (update :micro-q q-conj mt)
                    (maybe-trace-state {:event :enqueue-microtask
                                        :id id
                                        :kind kind
                                        :label label
                                        :lane lane
                                        :duration-ms duration
                                        :now-ms now})))))
     id)))

(defn- schedule-timer!
  "Schedule f to run at now+delay-ms as a timer; returns a cancellation token.
  Timers are stored in a sorted-map keyed by [at-ms id] for FIFO ordering
  among timers with the same at-ms. Random tie-breaking happens at selection
  time from the microtask queue, not at promotion time."
  ([^TestScheduler sched delay-ms f] (schedule-timer! sched delay-ms f {}))
  ([^TestScheduler sched delay-ms f {:keys [label kind lane]
                                     :or {kind :timer
                                          lane :default}}]
   (ensure-driver-thread! sched "schedule-timer!")
   (let [id (next-id! sched)
         now (:now-ms @(:state sched))
         at-ms (+ now (long delay-ms))
         k [at-ms id]
         t {:id id
            :kind kind
            :label label
            :lane lane
            :at-ms at-ms
            :key k
            :f f}]
     (swap! (:state sched)
            (fn [s]
              (-> s
                  (update :timers assoc k t)
                  (maybe-trace-state {:event :enqueue-timer
                                      :id id
                                      :at-ms at-ms
                                      :kind kind
                                      :label label
                                      :lane lane
                                      :now-ms now}))))
     k)))

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

(defn- remove-microtask-by-id
  "Remove a microtask from the queue by its ID. Returns the updated queue."
  [q target-id]
  (let [v (vec (seq q))]
    (reduce (fn [acc mt]
              (if (= target-id (:id mt))
                acc ; skip this one
                (conj acc mt)))
            #?(:clj clojure.lang.PersistentQueue/EMPTY
               :cljs cljs.core/PersistentQueue.EMPTY)
            v)))

(defn cancel-microtask!
  "Cancel an enqueued microtask by its ID, removing it from the microtask queue.

  This is a scheduler-level cancellation that completely removes the microtask
  from the queue, so it won't consume a scheduler step or advance virtual time.

  Returns true if the microtask was found and removed, false otherwise.

  Usage:
    (let [id (mt/enqueue-microtask! sched work-fn {:label \"work\"})]
      ;; Later, before it executes:
      (mt/cancel-microtask! sched id))

  Note: For yield/timeout/via-call, the cancel thunk returned at creation time
  will call this internally. Direct use is for advanced scenarios where you
  store the microtask ID and want to cancel before execution."
  [^TestScheduler sched microtask-id]
  (ensure-driver-thread! sched "cancel-microtask!")
  (let [state-atom (:state sched)]
    (loop []
      (let [s @state-atom
            q (:micro-q s)
            v (vec (seq q))
            idx (first (keep-indexed (fn [i mt] (when (= microtask-id (:id mt)) i)) v))]
        (if idx
          (let [mt (nth v idx)
                s' (-> s
                       (assoc :micro-q (remove-microtask-by-id q microtask-id))
                       (maybe-trace-state {:event :cancel-microtask
                                           :id microtask-id
                                           :kind (:kind mt)
                                           :label (:label mt)
                                           :now-ms (:now-ms s)}))]
            (if (compare-and-set! state-atom s s')
              true
              (recur)))
          false)))))

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
      ;; Uses :rng-select stream for interleaving decisions
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

;; -----------------------------------------------------------------------------
;; Thread pool / in-flight task management
;; -----------------------------------------------------------------------------

(defn- lane-max-threads
  "Get maximum threads for a lane. :default=1, :cpu=configurable, :blk=unlimited."
  [^TestScheduler sched lane]
  (case lane
    :default 1
    :cpu (or (:cpu-threads sched) 8)
    :blk Long/MAX_VALUE
    ;; Unknown lanes default to 1 (safe)
    1))

(defn- lane-has-capacity?
  "Check if a lane has capacity for another task."
  [^TestScheduler sched s lane]
  (let [active (get-in s [:active-threads lane] 0)
        max-threads (lane-max-threads sched lane)]
    (< active max-threads)))

(defn- filter-by-capacity
  "Filter queue items to only those whose lane has capacity."
  [^TestScheduler sched s q]
  (let [v (q->vec q)]
    (vec->q (filterv #(lane-has-capacity? sched s (:lane %)) v))))

(defn- next-in-flight-completion
  "Get the next in-flight task that can complete (end-ms <= now).
  Returns [key task-info] or nil."
  [s]
  (when-let [[k t] (first (:in-flight s))]
    (when (<= (first k) (:now-ms s))
      [k t])))

(defn- next-in-flight-time
  "Get the earliest end-ms of any in-flight task, or nil if none."
  [s]
  (when-let [[k _] (first (:in-flight s))]
    (first k)))

(defn- start-in-flight!
  "Mark a task as in-flight: increment lane thread count, add to in-flight map."
  [s ^TestScheduler sched mt]
  (let [lane (:lane mt)
        duration (:duration-ms mt 0)
        end-ms (+ (:now-ms s) (long duration))
        key [end-ms (:id mt)]]
    (-> s
        (update-in [:active-threads lane] (fnil inc 0))
        (assoc-in [:in-flight key] mt)
        (maybe-trace-state {:event :task-start
                            :id (:id mt)
                            :kind (:kind mt)
                            :label (:label mt)
                            :lane lane
                            :duration-ms duration
                            :end-ms end-ms
                            :now-ms (:now-ms s)}))))

(defn- complete-in-flight!
  "Complete an in-flight task: decrement lane thread count, remove from in-flight."
  [s ^TestScheduler _sched key mt]
  (let [lane (:lane mt)]
    (-> s
        (update-in [:active-threads lane] (fnil dec 1))
        (update :in-flight dissoc key)
        (maybe-trace-state {:event :task-complete
                            :id (:id mt)
                            :kind (:kind mt)
                            :label (:label mt)
                            :lane lane
                            :now-ms (:now-ms s)}))))

(defn- select-and-remove-task
  "Select a task from queue and return [mt q' decision rng-select s-after].
  When task-id is provided, selects that specific task.
  Otherwise uses schedule/FIFO/random selection.
  Uses :rng-select stream for random selection (separate from :rng-duration)."
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
          [(nth v idx) (vec->q (remove-nth v idx)) task-id (:rng-select s) s]))
      ;; No task-id: use schedule/FIFO/random
      (if (= q-size 1)
        [(q-peek q) (q-pop q) :fifo (:rng-select s) s]
        (let [[decision s-after] (get-schedule-decision sched s)
              [mt q' new-rng] (select-from-queue q decision (:rng-select s-after))]
          [mt q' decision new-rng s-after])))))

(defn- promote-due-timers!
  "Promote all timers due at current time to microtask queue."
  [^TestScheduler sched]
  (let [state-atom (:state sched)]
    (loop []
      (let [s @state-atom
            s' (promote-due-timers-in-state sched s)]
        (when-not (identical? s s')
          (when-not (compare-and-set! state-atom s s')
            (recur)))))))

(defn- advance-time-by-duration!
  "Advance virtual time by duration-ms and promote any newly-due timers.
  Called before a microtask executes to account for its virtual duration.
  This way, when the task's continuation runs, it sees the updated time."
  [^TestScheduler sched duration-ms]
  (when (pos? duration-ms)
    (let [state-atom (:state sched)]
      (loop []
        (let [s @state-atom
              old-now (:now-ms s)
              new-now (+ old-now (long duration-ms))
              s' (-> s
                     (assoc :now-ms new-now)
                     (maybe-trace-state {:event :duration-advance
                                         :from-ms old-now
                                         :to-ms new-now
                                         :duration-ms duration-ms})
                     (->> (promote-due-timers-in-state sched)))]
          (when-not (compare-and-set! state-atom s s')
            (recur)))))))

(defn- complete-one-in-flight!
  "Complete a single in-flight task. Returns the task or nil if none ready."
  [^TestScheduler sched]
  (let [state-atom (:state sched)]
    (loop []
      (let [s @state-atom]
        (when-let [[key mt] (next-in-flight-completion s)]
          (let [s' (complete-in-flight! s sched key mt)]
            (if (compare-and-set! state-atom s s')
              (do
                (try
                  ((:f mt))
                  (catch #?(:clj Throwable :cljs :default) e
                    (throw (ex-info (str "In-flight task completion threw: " (ex-message e))
                                    (cond-> {:mt/kind ::microtask-exception
                                             :mt/now-ms (:now-ms s')
                                             :mt/task {:id (:id mt)
                                                       :kind (:kind mt)
                                                       :label (:label mt)
                                                       :lane (:lane mt)}
                                             :mt/pending (pending sched)}
                                      (:trace? sched)
                                      (assoc :mt/recent-trace (vec (take-last 10 (trace sched)))))
                                    e))))
                mt)
              (recur))))))))

(defn- start-task-in-flight!
  "Start a task as in-flight (with duration > 0). Returns the task."
  [^TestScheduler sched s mt q' decision new-rng s-after]
  (let [state-atom (:state sched)
        now (:now-ms s-after)
        q-size (count (:micro-q s))
        select-trace (when (> q-size 1)
                       {:event :select-task
                        :decision decision
                        :queue-size q-size
                        :selected-id (:id mt)
                        :alternatives (mapv :id (filter #(not= (:id %) (:id mt)) (q->vec (:micro-q s))))
                        :now-ms now})
        s' (-> s-after
               (assoc :micro-q q')
               (assoc :rng-select new-rng)
               (cond-> select-trace (maybe-trace-state select-trace))
               (start-in-flight! sched mt))]
    (if (compare-and-set! state-atom s s')
      mt
      nil)))

(defn- run-task-immediately!
  "Run a task immediately (duration == 0). Returns the task."
  [^TestScheduler sched s mt q' decision new-rng s-after]
  (let [state-atom (:state sched)
        now (:now-ms s-after)
        duration (:duration-ms mt 0)
        q-size (count (:micro-q s))
        select-trace (when (> q-size 1)
                       {:event :select-task
                        :decision decision
                        :queue-size q-size
                        :selected-id (:id mt)
                        :alternatives (mapv :id (filter #(not= (:id %) (:id mt)) (q->vec (:micro-q s))))
                        :now-ms now})
        s' (-> s-after
               (assoc :micro-q q')
               (assoc :rng-select new-rng)
               (cond-> select-trace (maybe-trace-state select-trace))
               (maybe-trace-state {:event :run-microtask
                                   :id (:id mt)
                                   :kind (:kind mt)
                                   :label (:label mt)
                                   :lane (:lane mt)
                                   :duration-ms duration
                                   :now-ms now}))]
    (if (compare-and-set! state-atom s s')
      (do
        (try
          ((:f mt))
          (catch #?(:clj Throwable :cljs :default) e
            (throw (ex-info (str "Microtask threw: " (ex-message e))
                            (cond-> {:mt/kind ::microtask-exception
                                     :mt/now-ms now
                                     :mt/task {:id (:id mt)
                                               :kind (:kind mt)
                                               :label (:label mt)
                                               :lane (:lane mt)}
                                     :mt/pending (pending sched)}
                              (:trace? sched)
                              (assoc :mt/recent-trace (vec (take-last 10 (trace sched)))))
                            e))))
        mt)
      nil)))

(defn step!
  "Execute one scheduler step: complete an in-flight task OR start a new one.

  Thread pool model:
  - :default lane: single-threaded (max 1 concurrent task)
  - :cpu lane: fixed thread pool (configurable via :cpu-threads, default 8)
  - :blk lane: unlimited threads (each blocking call gets its own)

  Step behavior:
  1. Promote due timers (based on :timer-policy)
  2. Complete any in-flight task whose end-ms <= now (runs completion callback)
  3. If no completions, start a new task from queue (if lane has capacity):
     - Tasks with duration > 0: start as in-flight, thread occupied until end-ms
     - Tasks with duration == 0: execute immediately (backward compatible)
  4. Return ::idle if nothing can be done

  Timer promotion behavior depends on :timer-policy:
  - :promote-first (default): promote due timers first, then select among all.
  - :microtasks-first: only promote timers when microtask queue is empty.

  (step! sched)        - select next task per schedule/FIFO/random
  (step! sched task-id) - run specific task by ID (for manual stepping)

  Binds *scheduler* to sched for the duration of execution."
  ([^TestScheduler sched] (step! sched nil))
  ([^TestScheduler sched task-id]
   (ensure-driver-thread! sched "step!")
   (binding [*scheduler* sched]
     ;; Timer promotion based on policy
     (let [policy (or (:timer-policy sched) :promote-first)
           q-before (:micro-q @(:state sched))]
       (when (or (= policy :promote-first)
                 (and (= policy :microtasks-first) (q-empty? q-before)))
         (promote-due-timers! sched)))
     ;; First: try to complete an in-flight task
     (if-let [completed (complete-one-in-flight! sched)]
       completed
       ;; Second: try to start a new task from the queue
       (let [state-atom (:state sched)]
         (loop []
           (let [s @state-atom
                 q (:micro-q s)
                 ;; Filter queue to tasks whose lane has capacity
                 available-q (filter-by-capacity sched s q)]
             (if (q-empty? available-q)
               ;; No tasks can start - either queue empty or all lanes full
               idle
               ;; Select from available-q (tasks with lane capacity), but remove from original q
               (let [[mt _ decision new-rng s-after] (select-and-remove-task sched s available-q task-id)
                     q' (remove-microtask-by-id q (:id mt))
                     duration (:duration-ms mt 0)]
                 ;; Lane has capacity (guaranteed by available-q filter) - start or run the task
                 (if (pos? duration)
                   ;; Task has duration: start as in-flight
                   (if-let [result (start-task-in-flight! sched s mt q' decision new-rng s-after)]
                     result
                     (recur))
                   ;; No duration: run immediately (backward compatible)
                   (if-let [result (run-task-immediately! sched s mt q' decision new-rng s-after)]
                     result
                     (recur))))))))))))

(defn tick!
  "Drain all microtasks at current virtual time. Returns number of microtasks executed.

  Timer promotion follows the scheduler's :timer-policy (see step! docs).
  With :promote-first (default), timers compete with microtasks.
  With :microtasks-first, microtasks drain before timers are promoted.

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
  (mt/yield x opts)

  In production (outside with-determinism): completes immediately with x (or nil).
  In test mode (inside with-determinism): creates a scheduling point that allows
  other concurrent tasks to interleave, then completes with x.

  Options map (optional, only used in deterministic mode):
  - :duration-fn  (fn [] ms) - called before enqueueing to get this yield's duration.
                  Takes precedence over scheduler's :duration-range.

  This is useful for:
  - Testing concurrent code under different task orderings
  - Creating explicit interleaving points without time delays
  - Simulating cooperative multitasking yield points
  - Modeling specific work durations with :duration-fn

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
      {:property (fn [r] (= 4 (count r)))})

    ;; Yield with explicit duration
    (m/? (mt/yield :result {:duration-fn (constantly 50)}))"
  ([] (yield nil))
  ([x] (yield x nil))
  ([x opts]
   (fn [s f]
     (if *is-deterministic*
       ;; Test mode: create a scheduling point via microtask
       (let [sched (require-scheduler!)
             done? (atom false)
             ;; Duration priority: explicit :duration-fn > scheduler's duration-range > 0
             duration-ms (if-let [duration-fn (:duration-fn opts)]
                           (duration-fn)
                           (next-duration! sched))
             ;; Store microtask ID for cancellation
             mt-id (enqueue-microtask!
                    sched
                    (fn []
                      (when (compare-and-set! done? false true)
                        (s x)))
                    {:kind :yield
                     :label "yield"
                     :duration-ms duration-ms})]
         (fn cancel []
           (when (compare-and-set! done? false true)
             ;; Remove from queue so it doesn't consume a step or advance time
             (cancel-microtask! sched mt-id)
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
(def ^:private original-via-call m/via-call)

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

      ;; Start timer only if child didn't already complete (e.g., threw on start)
      (when-not @done?
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
                  :label "timeout-timer"})))

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
            ;; Find next event: timer or in-flight task completion, whichever is sooner
            (let [timer-time (next-timer-time sched)
                  in-flight-time (next-in-flight-time @(:state sched))
                  t-next (cond
                           (and timer-time in-flight-time) (min timer-time in-flight-time)
                           timer-time timer-time
                           in-flight-time in-flight-time
                           :else nil)]
              (if t-next
                (do
                  ;; enforce time budget even if we jump forward
                  (when (> (- t-next start-time) (long max-time-ms))
                    (throw (mt-ex budget-exceeded sched
                                  (str "Time budget exceeded before advancing: next event at " t-next "ms.")
                                  {:label label
                                   :mt/next-event-ms t-next
                                   :mt/start-ms start-time
                                   :mt/max-time-ms max-time-ms})))
                  (recur (+ total-steps (advance-to! sched t-next))))
                ;; Recheck done? to handle off-thread callbacks that may have completed the job
                (if (done? job)
                  (result job)
                  (throw (mt-ex deadlock sched
                                "Deadlock: no microtasks, no timers, no in-flight tasks, and task still pending."
                                {:label label})))))))))))

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

(def ^:const unsupported-executor ::unsupported-executor)

#?(:clj
   (defn deterministic-via-call*
     "Deterministic replacement for missionary.core/via-call.

      Contract (deterministic mode):
      - This is a 'scheduler hop' - thunk runs on the microtask queue, not a real executor.
      - Thunk SHOULD be pure or non-blocking. m/? inside thunk works but blocks the scheduler.
      - Thunk is executed synchronously once its microtask starts; no real concurrency.
      - Duration-range applies: thunk gets a virtual duration for timeout race testing.

      Cancellation semantics:
      - Before microtask starts: task is removed from queue, no CPU/time consumed.
      - During microtask execution: NOT interruptible (single-threaded scheduler).
      - After microtask completes: result discarded if cancel races with completion.
      - Always fails with missionary.Cancelled when cancelled.

      Limitations vs production:
      - No JVM thread interrupt - cancellation is cooperative only.
      - Thunk cannot be interrupted mid-execution (use yield points for interleaving).
      - Real I/O in thunk will break determinism (same as any real I/O in tests)."
     [exec thunk]
     (fn [s f]
       (let [sched (require-scheduler!)
             ;; Optional: enforce your testkit contract (only cpu/blk are supported deterministically).
             ;; If you'd rather "ignore exec and still run deterministically", remove this check.
             _ (when-not (or (identical? exec m/cpu)
                             (identical? exec m/blk))
                 (throw (mt-ex unsupported-executor sched
                               "Deterministic via/via-call only supports m/cpu or m/blk executors."
                               {:exec (pr-str exec)})))

             lane (cond
                    (identical? exec m/cpu) :cpu
                    (identical? exec m/blk) :blk
                    :else :default)

             done? (atom false)
             duration-ms (next-duration! sched)
             ;; Store microtask ID for cancellation
             mt-id (enqueue-microtask!
                    sched
                    (fn []
                      ;; If cancelled before we start, do nothing.
                      (when-not @done?
                        (let [cont (try
                                     (let [x (thunk)]
                                       #(s x))
                                     (catch Exception e
                                       #(f e))
                                     (catch missionary.Cancelled t
                                       nil))]
                          (when (compare-and-set! done? false true)
                            (when cont
                              (cont))))))
                    {:kind :via-call
                     :label "via-call"
                     :lane lane
                     :duration-ms duration-ms})]

         ;; Cancel thunk
         (fn cancel []
           ;; keep determinism: cancellation must happen on the driver thread
           (ensure-driver-thread! sched "via-call cancel")
           (when (compare-and-set! done? false true)
             ;; Remove from queue so it doesn't consume a step or advance time
             (cancel-microtask! sched mt-id)
             ;; fail immediately like other cancelled primitives
             (f (cancelled-ex)))
           nil))))
   :cljs
   (defn deterministic-via-call* [exec thunk]
     (throw (ex-info "via-call is JVM-only." {:mt/kind ::unsupported}))))

(defn via-call
  "Execute thunk on executor, with dispatch based on determinism mode.

  In deterministic mode (*is-deterministic* true):
  - Thunk runs on the scheduler's microtask queue (not a real executor).
  - This is a 'scheduler hop' for deterministic testing, not real thread pool execution.
  - Thunk SHOULD be pure or non-blocking; m/? works but blocks the scheduler.
  - Duration-range applies: gets virtual duration for timeout race testing.
  - Cancellation removes from queue if not started, or races with completion.
  - Only m/cpu and m/blk executors are supported; others throw.

  In production mode (*is-deterministic* false):
  - Delegates to original missionary.core/via-call.
  - Thunk runs on the actual executor with real concurrency.

  Contract for deterministic testing:
  - Thunk cannot be interrupted mid-execution (no Thread.interrupt).
  - For interleaving at points within thunk, use mt/yield explicitly.
  - Tests passing under testkit may still have concurrency bugs in production
    if thunk relies on real thread interruption.

  See deterministic-via-call* for detailed cancellation semantics."
  ([exec thunk]
   ;; Return a task that dispatches at execution time
   (fn [s f]
     (if *is-deterministic*
       ((deterministic-via-call* exec thunk) s f)
       ((original-via-call exec thunk) s f)))))

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
           (alter-var-root #'missionary.core/via-call (constantly via-call)))
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
           (alter-var-root #'missionary.core/via-call (constantly original-via-call)))))
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
  "Run a task with the exact schedule and configuration from a previous run.
  Returns the task result.

  IMPORTANT: Must be called inside a with-determinism body.

  Typically you should use mt/replay instead, which automatically extracts
  the correct configuration from a check-interleaving failure bundle."
  ([task schedule]
   (replay-schedule task schedule {}))
  ([task schedule opts]
   (require-deterministic-mode! "replay-schedule")
   (let [sched (make-scheduler (assoc opts :micro-schedule schedule))]
     (run sched task (select-keys opts [:max-steps :max-time-ms])))))

(defn replay
  "Replay a failure from check-interleaving.

  IMPORTANT: Must be called inside a with-determinism body.

  Takes a failure bundle (from check-interleaving) and a task factory,
  re-runs the task with the exact schedule and configuration that caused
  the failure.

  Usage:
    (let [result (mt/check-interleaving make-task {:seed 42 :property valid?})]
      (when-not (:ok? result)
        (mt/with-determinism
          (mt/replay make-task result))))"
  [task-fn failure]
  (let [schedule (or (:schedule failure)
                     (throw (ex-info "Failure bundle missing :schedule"
                                     {:mt/kind ::invalid-failure-bundle
                                      :failure failure})))]
    (replay-schedule (task-fn) schedule failure)))

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
  [task {:keys [test-seed max-steps max-time-ms duration-range initial-ms timer-policy cpu-threads]
         :or {max-time-ms 60000
              initial-ms 0
              timer-policy :promote-first
              cpu-threads 8}}]
  (let [sched (make-scheduler {:trace? true
                               :seed test-seed
                               :duration-range duration-range
                               :initial-ms initial-ms
                               :timer-policy timer-policy
                               :cpu-threads cpu-threads})
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
  - :num-tests      - number of different interleavings to try (default 100)
  - :seed           - base seed for RNG (default: current time)
  - :property       - (fn [result] boolean) - returns true if result is valid
  - :max-steps      - max scheduler steps per run (default 10000)
  - :max-time-ms    - max virtual time per run (default 60000)
  - :duration-range - [lo hi] virtual duration range for microtasks (default nil)
                      When set, each microtask gets a random duration in [lo,hi]ms.
                      This enables realistic timeout races and timing exploration.
  - :timer-policy   - :promote-first (default) or :microtasks-first
                      Controls whether timers compete with microtasks at same time.
  - :cpu-threads    - CPU thread pool size (default 8)
                      Controls max concurrent :cpu lane tasks.

  Returns on success:
  {:ok?            true
   :seed           base seed used (for reproducibility)
   :iterations-run number of iterations completed}

  Returns on failure:
  {:ok?            false
   :kind           :exception | :property-failed
   :seed           seed used for this iteration (for RNG replay)
   :schedule       schedule that caused failure (for replay)
   :duration-range [lo hi] if timing fuzz was enabled (for replay)
   :timer-policy   timer policy if non-default (for replay)
   :cpu-threads    cpu thread pool size if non-default (for replay)
   :trace          full trace
   :iteration      which iteration failed
   :error          exception (present when :kind is :exception)
   :value          result value (present when :kind is :property-failed)}

  Note: For reproducible tests, always specify :seed. Without it, the current
  system time is used, making results non-reproducible across runs.

  The failure bundle contains all information needed to reproduce the exact
  failure using mt/replay, including timing and thread pool configuration."
  [task-fn {:keys [num-tests seed property max-steps max-time-ms duration-range initial-ms timer-policy cpu-threads]
            :or {num-tests 100
                 max-steps 10000
                 max-time-ms 60000
                 initial-ms 0
                 timer-policy :promote-first
                 cpu-threads 8}}]
  (require-deterministic-mode! "check-interleaving")
  (let [base-seed (or seed (default-base-seed))]
    (loop [i 0]
      (if (>= i num-tests)
        {:ok? true :seed base-seed :iterations-run num-tests}
        (let [run-result (run-with-random-interleaving (task-fn)
                                                       {:test-seed (+ base-seed i)
                                                        :max-steps max-steps
                                                        :max-time-ms max-time-ms
                                                        :duration-range duration-range
                                                        :initial-ms initial-ms
                                                        :timer-policy timer-policy
                                                        :cpu-threads cpu-threads})
              {:keys [result seed micro-schedule trace]} run-result
              exception? (some? (:error result))
              property-failed? (and property
                                    (not exception?)
                                    (not (property (:value result))))]
          (if (or exception? property-failed?)
            (cond-> {:ok? false
                     :kind (if exception? :exception :property-failed)
                     :seed seed
                     :schedule micro-schedule
                     :trace trace
                     :iteration i}
              ;; Include timing config for replay
              duration-range (assoc :duration-range duration-range)
              (not= 0 initial-ms) (assoc :initial-ms initial-ms)
              (not= :promote-first timer-policy) (assoc :timer-policy timer-policy)
              (not= 8 cpu-threads) (assoc :cpu-threads cpu-threads)
              ;; Include result data
              exception? (assoc :error (:error result))
              (not exception?) (assoc :value (:value result)))
            (recur (inc i))))))))

(defn explore-interleavings
  "Explore different interleavings of a task and return a summary.

  IMPORTANT: Must be called inside a with-determinism body.

  task-fn should be a 0-arg function that returns a fresh task for each test.
  This ensures mutable state (like atoms) is reset between iterations.

  Options:
  - :num-samples    - number of different interleavings to try (default 100)
  - :seed           - base seed for RNG (default: current time)
  - :max-steps      - max scheduler steps per run (default 10000)
  - :duration-range - [lo hi] virtual duration range for microtasks (default nil)
                      When set, each microtask gets a random duration in [lo,hi]ms.
                      This enables realistic timeout races and timing exploration.
  - :timer-policy   - :promote-first (default) or :microtasks-first
                      Controls whether timers compete with microtasks at same time.
  - :cpu-threads    - CPU thread pool size (default 8)
                      Controls max concurrent :cpu lane tasks.

  Returns:
  {:unique-results - count of distinct results seen
   :results        - vector of {:result r :micro-schedule s} maps
   :seed           - the base seed used (for reproducibility)}

  Note: For reproducible tests, always specify :seed. Without it, the current
  system time is used, making results non-reproducible across runs."
  [task-fn {:keys [num-samples seed max-steps duration-range timer-policy cpu-threads]
            :or {num-samples 100
                 max-steps 10000
                 timer-policy :promote-first
                 cpu-threads 8}}]
  (require-deterministic-mode! "explore-interleavings")
  (let [base-seed (or seed (default-base-seed))
        results (for [i (range num-samples)
                      :let [run-result (run-with-random-interleaving (task-fn)
                                                                     {:test-seed (+ base-seed i)
                                                                      :max-steps max-steps
                                                                      :duration-range duration-range
                                                                      :timer-policy timer-policy
                                                                      :cpu-threads cpu-threads})
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
