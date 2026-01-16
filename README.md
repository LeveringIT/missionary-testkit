# missionary-testkit

[![Clojars Project](https://img.shields.io/clojars/v/de.levering-it/missionary-testkit.svg)](https://clojars.org/de.levering-it/missionary-testkit)
[![Tests](https://github.com/LeveringIT/missionary-testkit/actions/workflows/test.yml/badge.svg?branch=main)](https://github.com/LeveringIT/missionary-testkit/actions/workflows/test.yml)

A deterministic testing toolkit for [Missionary](https://github.com/leonoel/missionary) - the functional effect and streaming library for Clojure/ClojureScript.

## The Problem

Testing asynchronous code with real time is painful:

- **Slow tests**: A test with `(m/sleep 5000)` takes 5 real seconds
- **Flaky tests**: Race conditions cause intermittent failures
- **Non-deterministic**: Same code can produce different results between runs
- **Hard to debug**: Async execution order is unpredictable

## The Solution

missionary-testkit provides a **virtual time scheduler** that makes async tests:

- **Fast**: Sleep for 1 hour in microseconds
- **Deterministic**: Same inputs always produce same outputs
- **Debuggable**: Full execution trace with timestamps
- **Controllable**: Step through async execution one microtask at a time
- **Thorough**: Explore different task interleavings to find concurrency bugs

## Quickstart

### Installation

Add to your `deps.edn`:

```clojure
{:deps {de.levering-it/missionary-testkit {:mvn/version "RELEASE"}}}
```

### Basic Usage

```clojure
(require '[de.levering-it.missionary-testkit :as mt]
         '[missionary.core :as m])

;; Wrap your test in with-determinism - this rebinds m/sleep and m/timeout
;; to use virtual time instead of real time
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp
        ;; This "sleeps" for 10 seconds but executes instantly!
        (m/? (m/sleep 10000))
        :done))))
;; => :done (returns immediately)
```

### Testing Concurrent Operations

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp
        ;; Race two tasks - deterministic winner every time
        (m/? (m/race
               (m/sleep 100 :fast)
               (m/sleep 200 :slow)))))))
;; => :fast
```

### Testing Timeouts

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp
        ;; Task takes 500ms but timeout is 100ms
        (m/? (m/timeout
               (m/sleep 500 :completed)
               100
               :timed-out))))))
;; => :timed-out
```

## Core Concepts

### The `with-determinism` Entry Point

**IMPORTANT:** The `with-determinism` macro is the entry point to all deterministic behavior. All flows and tasks under test **must** be created inside the macro body (or by factory functions called from within the body).

```clojure
;; ✅ CORRECT: Task created inside with-determinism
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp
        (m/? (m/sleep 100))  ; Uses virtual time
        :done))))

;; ✅ CORRECT: Factory function called inside with-determinism
(defn make-my-task []
  (m/sp
    (m/? (m/sleep 100))
    :done))

(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched (make-my-task))))  ; Factory called inside macro

;; ❌ WRONG: Task created BEFORE with-determinism
(def my-task (m/sp (m/? (m/sleep 100)) :done))  ; Created outside!

(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched my-task)))  ; m/sleep was captured at def time, NOT virtualized!
```

**Why this matters:** The `with-determinism` macro rebinds `m/sleep`, `m/timeout`, and the executors (`m/cpu`, `m/blk`) to their virtual-time equivalents. If you create a task *before* entering the macro, those tasks capture the *real* versions of these primitives, and your tests will use real time instead of virtual time—defeating the purpose of the testkit.

All flows, tasks, and atoms that you test must be created and modified inside `with-determinism` to ensure proper scheduling.

### Checking Deterministic Mode

You can check if code is running in deterministic mode using `*is-deterministic*`:

```clojure
mt/*is-deterministic*  ; => false (normal execution)

(mt/with-determinism
  mt/*is-deterministic*)  ; => true (inside determinism context)
```

This is useful for code that needs to behave differently in tests vs production.

### TestScheduler

The scheduler manages virtual time and a queue of pending tasks:

```clojure
(def sched (mt/make-scheduler {:initial-ms      0       ; starting time
                                :seed            42      ; seed for random ordering (nil = FIFO)
                                :trace?          true    ; enable execution trace
                                :micro-schedule  nil}))  ; explicit decisions (see Schedule Decisions)

(mt/now-ms sched)   ; => 0 (current virtual time)
(mt/pending sched)  ; => {:microtasks [...] :timers [...]}
(mt/trace sched)    ; => [{:event :enqueue-timer ...} ...]
```

**Ordering behavior:**
- **No seed (default):** FIFO ordering for both timers and microtasks. Predictable, good for unit tests.
- **With seed:** Random ordering (seeded RNG) for both timers and microtasks. Deterministic but shuffled, good for fuzz/property testing.

```clojure
;; FIFO ordering (default) - predictable for unit tests
(mt/make-scheduler)
(mt/make-scheduler {:trace? true})

;; Random ordering - for exploring interleavings
(mt/make-scheduler {:seed 42})
```

### Using Clock in Production Code

The `mt/clock` function returns the current time in milliseconds. It automatically uses:
- **Virtual time** when inside `with-determinism` (for tests)
- **Real time** (`System/currentTimeMillis` or `js/Date.now`) otherwise (for production)

This allows you to write time-aware code that works in both contexts:

```clojure
;; Production code - uses mt/clock for timestamps
(defn record-event [event]
  {:timestamp (mt/clock)
   :event event})

;; In production: uses real system time
(record-event :user-login)
;; => {:timestamp 1704067200000 :event :user-login}

;; In tests: uses virtual time, runs instantly
(mt/with-determinism
  (let [sched (mt/make-scheduler {:initial-ms 1000})]
    (mt/run sched
      (m/sp
        (let [e1 (record-event :start)]
          (m/? (m/sleep 5000))  ; "sleeps" 5 seconds instantly
          (let [e2 (record-event :end)]
            (- (:timestamp e2) (:timestamp e1))))))))
;; => 5000 (deterministic, executes in microseconds)
```

### Time Control

```clojure
;; Advance time and execute due tasks
(mt/advance! sched 100)      ; advance by 100ms
(mt/advance-to! sched 500)   ; advance to absolute time 500ms

;; Fine-grained control
(mt/tick! sched)  ; execute all ready microtasks at current time
(mt/step! sched)  ; execute exactly one microtask
```

### Running Tasks

```clojure
;; Run a task to completion with auto-advancing time
(mt/run sched task)

;; With options
(mt/run sched task {:auto-advance? true    ; auto-advance time (default)
                    :max-steps     100000  ; prevent infinite loops
                    :max-time-ms   60000   ; virtual time budget
                    :label         "my-task"})

;; Manual task management
(def job (mt/start! sched task {:label "my-job"}))
(mt/done? job)    ; => false
(mt/advance! sched 1000)
(mt/done? job)    ; => true
(mt/result job)   ; => the task's result (or throws on failure)
(mt/cancel! job)  ; cancel a running job
```

## Testing Flows

Flows work deterministically when atom modifications happen inside the controlled task. The testkit provides `mt/collect` to gather flow values:

```clojure
;; Testing with m/watch - modify atoms inside the controlled task
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        !counter (atom 0)]
    (mt/run sched
      (m/sp
        (m/? (m/race
               ;; Consumer collects first 3 values
               (m/reduce (fn [acc v]
                           (let [acc (conj acc v)]
                             (if (= 3 (count acc))
                               (reduced acc)
                               acc)))
                         [] (m/watch !counter))
               ;; Producer - mutations happen at controlled yield points
               (m/sp
                 (m/? (m/sleep 100))
                 (swap! !counter inc)  ; signal propagates synchronously
                 (m/? (m/sleep 100))
                 (swap! !counter inc)
                 (m/? (m/sleep 100)))))))))
;; => [0 1 2]

;; Testing discrete flows with m/seed
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp
        (m/? (mt/collect (m/seed [1 2 3])))))))
;; => [1 2 3]
```

## Coordination Primitives

Missionary's coordination primitives (`m/mbx`, `m/dfv`, `m/rdv`, `m/sem`) work fully deterministically under the testkit. These enable communication and synchronization between concurrent tasks.

### Mailbox (m/mbx) - Async Queue

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        mbx (m/mbx)]
    (mt/run sched
      (m/sp
        ;; Post values (1-arity = post, never blocks)
        (mbx :a)
        (mbx :b)
        ;; Fetch values (2-arity = task, use with m/?)
        [(m/? mbx) (m/? mbx)]))))
;; => [:a :b]  (FIFO order)
```

### Deferred Value (m/dfv) - Single-Assignment Promise

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        result (m/dfv)]
    (mt/run sched
      (m/sp
        (m/? (m/join vector
               ;; Worker: compute and assign
               (m/sp
                 (m/? (m/sleep 100))
                 (result (* 6 7))  ; assign (1-arity)
                 :worker-done)
               ;; Waiter: block until assigned
               (m/sp
                 (m/? result))))))))  ; deref (2-arity task)
;; => [:worker-done 42]
```

### Rendezvous (m/rdv) - Synchronous Handoff

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        rdv (m/rdv)]
    (mt/run sched
      (m/sp
        (m/? (m/join vector
               ;; Sender: (rdv value) returns a task
               (m/sp
                 (m/? (rdv :handoff))  ; give - blocks until receiver ready
                 :sender-done)
               ;; Receiver: (rdv s f) is the take task
               (m/sp
                 (m/? rdv))))))))      ; take - blocks until giver ready
;; => [:sender-done :handoff]
```

### Semaphore (m/sem) - Resource Limiting

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        sem (m/sem 2)  ; 2 permits
        max-concurrent (atom 0)
        active (atom 0)]
    (mt/run sched
      (m/sp
        (m/? (m/join vector
               ;; 4 workers competing for 2 permits
               (m/sp (m/? sem) (swap! active inc) (swap! max-concurrent max @active)
                     (m/? (m/sleep 50)) (swap! active dec) (sem) :w1)
               (m/sp (m/? sem) (swap! active inc) (swap! max-concurrent max @active)
                     (m/? (m/sleep 50)) (swap! active dec) (sem) :w2)
               (m/sp (m/? sem) (swap! active inc) (swap! max-concurrent max @active)
                     (m/? (m/sleep 50)) (swap! active dec) (sem) :w3)
               (m/sp (m/? sem) (swap! active inc) (swap! max-concurrent max @active)
                     (m/? (m/sleep 50)) (swap! active dec) (sem) :w4)))
        @max-concurrent))))
;; => 2  (never exceeded 2 concurrent)
```

See `examples/coordination_primitives.clj` for more patterns including producer-consumer, request-response, and mutex implementations.

## Continuous Flows and Diamond DAGs

Continuous flows (`m/signal`, `m/watch`, `m/latest`) work deterministically when atom changes happen inside the controlled task. The testkit is particularly useful for testing complex signal topologies.

### Diamond-Shaped DAG

When a signal feeds multiple derived signals that later merge, you get a "diamond" topology. This tests glitch-free propagation:

```clojure
;;       !input
;;        /   \
;;    <left   <right
;;        \   /
;;       <merged

(mt/with-determinism
  (let [sched (mt/make-scheduler)
        !input (atom 1)
        <input (m/signal (m/watch !input))
        <left  (m/signal (m/latest #(* 2 %) <input))    ; 2x
        <right (m/signal (m/latest #(+ 10 %) <input))   ; +10
        <merged (m/signal (m/latest (fn [l r] {:left l :right r :sum (+ l r)})
                                    <left <right))
        results (atom [])]
    (mt/run sched
      (m/sp
        (m/? (m/race
               (m/reduce (fn [_ v]
                           (swap! results conj v)
                           (when (>= (count @results) 3) (reduced @results)))
                         nil <merged)
               (m/sp
                 (m/? (m/sleep 50)) (reset! !input 2)
                 (m/? (m/sleep 50)) (reset! !input 5)
                 (m/? (m/sleep 1000)))))))))
;; Each result is consistent: left = 2*input, right = input+10
;; No glitches like {:left 4 :right 11} (one updated, other didn't)
```

### Verifying Glitch-Free Behavior

Use `check-interleaving` to verify diamond DAGs never produce inconsistent states:

```clojure
(mt/with-determinism
  (mt/check-interleaving
    (fn []
      (let [!input (atom 1)
            <input (m/signal (m/watch !input))
            <doubled (m/signal (m/latest #(* 2 %) <input))
            <plus-one (m/signal (m/latest inc <input))
            <check (m/signal (m/latest (fn [d p]
                                         {:consistent? (= (/ d 2) (dec p))})
                                       <doubled <plus-one))]
        (m/sp
          (m/? (m/race
                 (m/reduce (fn [acc v] (conj acc v))
                           [] <check)
                 (m/sp
                   (dotimes [i 5]
                     (m/? (m/sleep 10))
                     (reset! !input i))
                   (m/? (m/sleep 100))))))))
    {:num-tests 50
     :seed 42
     :property (fn [results] (every? :consistent? results))}))
;; => {:ok? true ...}
```

See `examples/continuous_flows.clj` for more patterns including nested diamonds, sampling, and dashboard examples.

## Testing Discrete Flows with m/observe

`m/observe` can be tested deterministically when callback invocations happen from **inside** the controlled task (via scheduler microtasks or timers), not from external threads.

### Pattern: Controlled Event Emitter

Create an emitter where you control when events fire:

```clojure
(defn make-event-emitter
  "Returns {:flow <discrete-flow> :emit! <fn>}"
  []
  (let [!cb (atom nil)]
    {:flow  (m/observe (fn [cb]
                         (reset! !cb cb)
                         #(reset! !cb nil)))
     :emit! (fn [v] (when-let [cb @!cb] (cb v)))}))

(mt/with-determinism
  (let [sched (mt/make-scheduler)
        {:keys [flow emit!]} (make-event-emitter)]
    (mt/run sched
      (m/sp
        (m/? (m/race
               ;; Consumer: collect events from discrete flow
               (m/reduce (fn [acc event]
                           (let [acc (conj acc event)]
                             (if (>= (count acc) 3) (reduced acc) acc)))
                         [] flow)
               ;; Producer: emit at controlled points
               (m/sp
                 (m/? (m/sleep 10)) (emit! :click)
                 (m/? (m/sleep 10)) (emit! :scroll)
                 (m/? (m/sleep 10)) (emit! :keypress)
                 (m/? (m/sleep 1000)))))))))
;; => [:click :scroll :keypress]
```

This pattern works because `emit!` is called from inside the `m/sp` task at controlled yield points (`m/sleep`), so the callback invocation is part of the deterministic execution.

See `examples/discrete_observe.clj` for more patterns including pub/sub channels and throttled event streams.

## Debugging with Traces

Enable tracing to see exactly what happened:

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler {:trace? true})]
    (mt/run sched (m/sp (m/? (m/sleep 100 :done))))
    (mt/trace sched)))

;; => [{:event :enqueue-timer, :id 2, :at-ms 100, :now-ms 0}
;;     {:event :advance-to, :from 0, :to 100, :now-ms 0}
;;     {:event :promote-timers, :ids [2], :count 1, :now-ms 100}
;;     {:event :run-microtask, :id 2, :kind :sleep, :now-ms 100}
;;     ...]
```

## Concurrency Bug Testing

missionary-testkit can explore different task interleavings to find race conditions and concurrency bugs.

### Yield Points

Use `mt/yield` to create scheduling points that allow other tasks to interleave. Unlike `m/sleep`, yield does nothing in production - it only creates interleaving opportunities during testing:

```clojure
;; In production: completes immediately, returns :done
(m/? (mt/yield :done))

;; In tests: creates a scheduling point where other tasks can run
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (let [order (atom [])]
      (mt/run sched
        (m/sp
          (m/? (m/join vector
                 (m/sp (swap! order conj :a1) (m/? (mt/yield)) (swap! order conj :a2))
                 (m/sp (swap! order conj :b1) (m/? (mt/yield)) (swap! order conj :b2))))
          @order)))))
;; Different schedules can produce [:a1 :b1 :a2 :b2], [:a1 :b1 :b2 :a2], etc.
```

This is useful for:
- Testing concurrent code under different task orderings
- Creating explicit interleaving points without artificial time delays
- Code that should be order-independent in production but needs interleaving testing

### Finding Bugs with check-interleaving

```clojure
(mt/with-determinism
  (let [;; A task factory - returns fresh task with fresh state each iteration
        make-task (fn []
                    (let [shared (atom 0)]
                      (m/sp
                       (m/? (m/join (fn [& _] @shared)
                                   (m/sp
                                     (m/? (m/sleep 0))  ; suspension point
                                     (swap! shared + 10))
                                   (m/sp
                                     (m/? (m/sleep 0))  ; suspension point
                                     (swap! shared * 2)))))))

        ;; Check if any interleaving produces unexpected results
        result (mt/check-interleaving make-task
                 {:num-tests 100
                  :seed 42  ; Always specify for reproducibility
                  :property (fn [v] (#{20 10} v))})]  ; only valid results

    (when-not (:ok? result)
      (println "Bug found!")
      (println "Seed:" (:seed result))
      (println "Schedule:" (:schedule result)))))
```

**Note:** For reproducible tests, always specify `:seed`. Without it, the current system time is used, making failures harder to reproduce.

### Replaying a Failing Schedule

When `check-interleaving` finds a bug, you can replay the exact schedule:

```clojure
;; Replay using the failure bundle directly
(mt/with-determinism
  (mt/replay make-buggy-task result))

;; Or extract the schedule manually
(mt/with-determinism
  (mt/replay-schedule (make-buggy-task) (:schedule result)))
```

**Note:** `replay` takes a task factory and the failure bundle. `replay-schedule` takes a task instance directly. Both must be called inside `with-determinism`.

### Exploring All Outcomes

See how many distinct outcomes a concurrent task can produce:

```clojure
(mt/with-determinism
  (let [make-task (fn [] ...)  ; task factory
        result (mt/explore-interleavings make-task {:num-samples 50 :seed 42})]
    (println "Unique results:" (:unique-results result))
    (println "Seed used:" (:seed result))))  ; for reproducibility
```

**Note:** For reproducible tests, always specify `:seed`. Without it, the current system time is used. The seed is always returned in the result map for later reproduction.

### Schedule Decisions

Schedules control task selection when the queue has multiple ready tasks. Schedules are vectors of **task IDs** extracted from execution traces:

```clojure
;; Run once with tracing to discover task IDs
(mt/with-determinism
  (let [sched (mt/make-scheduler {:trace? true :seed 42})]
    (mt/run sched (make-task))
    (mt/trace->schedule (mt/trace sched))))
;; => [2 4 3]  ; bare task IDs

;; Replay with the same schedule
(mt/with-determinism
  (mt/replay-schedule (make-task) [2 4 3]))

;; Or modify the schedule to test different orderings
(mt/with-determinism
  (mt/replay-schedule (make-task) [4 2 3]))  ; different order
```

**Run-time selection** (when no explicit schedule):
- No seed: FIFO ordering (first in, first out)
- With seed: Random ordering (deterministic via seed)

### Manual Stepping

For fine-grained control, use `next-tasks` to see available tasks and `step!` with a task ID:

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler {:trace? true})]
    (mt/start! sched (make-concurrent-task))

    ;; See what tasks are available
    (println "Available:" (mt/next-tasks sched))
    ;; => [{:id 2 :kind :yield ...} {:id 3 :kind :yield ...}]

    ;; Step a specific task by ID
    (mt/step! sched 3)  ; run task with ID 3
    (mt/step! sched 2)  ; run task with ID 2

    ;; Or let the scheduler choose (FIFO/random)
    (mt/step! sched)))  ; no ID = automatic selection
```

See `examples/manual_schedule.clj` for complete examples of:
- Run → Inspect schedule → Edit → Replay workflow
- Manual stepping with `next-tasks` and `step!`
- Custom schedule construction

## Error Detection

The scheduler automatically detects common problems:

```clojure
;; Deadlock detection
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp (m/? (m/sleep 100)))
      {:auto-advance? false})))
;; throws: "Deadlock: task not done after draining microtasks"

;; Infinite loop protection
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp (loop [] (m/? (m/sleep 0)) (recur)))
      {:max-steps 100})))
;; throws: "Step budget exceeded: 101 > 100"

;; Time budget
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp (m/? (m/sleep 999999)))
      {:max-time-ms 1000})))
;; throws: "Time budget exceeded"
```

## API Reference

### Scheduler Creation
- `(make-scheduler)` / `(make-scheduler opts)` - create a scheduler

### Time Inspection
- `(now-ms sched)` - current virtual time in milliseconds (requires scheduler)
- `(clock)` - current time: virtual when scheduler bound, real otherwise (for production code)
- `(pending sched)` - queued microtasks and timers
- `(next-event sched)` - what would execute next: `{:type :microtask ...}`, `{:type :timer ...}`, or `nil`
- `(next-tasks sched)` - vector of available microtasks with `:id`, `:kind`, `:label`, `:lane` (for manual stepping)
- `(trace sched)` - execution trace (if enabled)

### Time Control
- `(step! sched)` - run one microtask (FIFO/random selection), returns `::mt/idle` if none
- `(step! sched task-id)` - run specific microtask by ID (for manual stepping)
- `(tick! sched)` - run all microtasks at current time, returns count
- `(advance! sched dt-ms)` - advance by delta, run due tasks
- `(advance-to! sched t)` - advance to absolute time, run due tasks

### Task Execution
- `(run sched task)` / `(run sched task opts)` - run task to completion
- `(start! sched task)` / `(start! sched task opts)` - start task, return job handle

### Job Handle
- `(done? job)` - has job completed?
- `(result job)` - get result or throw error
- `(cancel! job)` - cancel the job

### Virtual Time Primitives
- `(mt/sleep ms)` / `(mt/sleep ms x)` - virtual sleep task
- `(mt/timeout task ms)` / `(mt/timeout task ms x)` - virtual timeout wrapper
- `(mt/yield)` / `(mt/yield x)` - yield point for interleaving (no-op in production)

### Utilities
- `(collect flow)` / `(collect flow opts)` - collect flow to vector task
- `(executor)` - deterministic `java.util.concurrent.Executor` (requires `*scheduler*` bound)
- `(cpu-executor)` - deterministic CPU executor (requires `*scheduler*` bound)
- `(blk-executor)` - deterministic blocking executor (requires `*scheduler*` bound)

### Dynamic Vars
- `*scheduler*` - the current TestScheduler (bound automatically by `run` and `start!`)
- `*is-deterministic*` - `true` when inside `with-determinism` scope

### Integration Macro
- `(with-determinism & body)` - set `*is-deterministic*` to `true` and rebind `m/sleep`, `m/timeout`, `m/cpu`, `m/blk`. **All tasks and flows must be created inside this macro body** (see [The `with-determinism` Entry Point](#the-with-determinism-entry-point)). `run` and `start!` automatically bind `*scheduler*` to the passed scheduler.

### Interleaving (Concurrency Testing)
- `(check-interleaving task-fn opts)` - find failures across many interleavings. Returns `{:ok? true :seed s ...}` or `{:ok? false :kind ... :schedule ... :seed s ...}`.
- `(explore-interleavings task-fn opts)` - explore unique outcomes, returns `{:unique-results n :results [...] :seed s}`.
- `(replay task-fn failure)` - replay a failure bundle from `check-interleaving`
- `(replay-schedule task schedule)` - replay exact execution order (task created inside `with-determinism`)
- `(trace->schedule trace)` - extract schedule (vector of task IDs) from trace

## Running Tests

```bash
# Run tests
clojure -X:test cognitect.test-runner.api/test
```

## Determinism Contract

The testkit provides deterministic execution guarantees under specific conditions.

**Supported deterministically:**

| Primitive | Deterministic? | Notes |
|-----------|---------------|-------|
| `m/sleep`, `m/timeout` | ✓ | Virtual time, fully controlled |
| `mt/yield` | ✓ | Scheduling points for interleaving |
| `m/race`, `m/join`, `m/amb`, `m/amb=` | ✓ | Under single-threaded scheduler driving |
| `m/seed`, `m/sample` | ✓ | Under single-threaded scheduler driving |
| `m/relieve`, `m/sem`, `m/rdv`, `m/mbx`, `m/dfv` | ✓ | Under single-threaded scheduler driving |
| `m/via` with `m/cpu` or `m/blk` | ✓ | JVM only; executors rebound to scheduler microtasks |
| `m/signal`, `m/watch`, `m/latest`, `m/stream` | ✓ | When atom changes happen inside the controlled task (see below) |

**Explicitly NOT supported (non-deterministic):**

| Primitive | Why |
|-----------|-----|
| `m/publisher` | Reactive-streams subsystem with internal scheduling |
| `m/via` with custom executors | Work runs on uncontrolled threads |
| Real I/O (HTTP, file, database) | Actual wall-clock time, external systems |
| `m/observe` with external callbacks | Events arrive from outside the scheduler |
| `m/watch` on atoms modified externally | Modifications from other threads |

### Why `m/signal` and `m/watch` Work

Signal propagation in Missionary is **synchronous**. When you call `(swap! !atom f)`:

1. The atom's value changes
2. The watch callback fires **immediately in the same call stack**
3. The signal recomputes **immediately in the same call stack**
4. Downstream consumers receive the value **immediately in the same call stack**

No async scheduling is involved. The testkit controls *when* that `swap!` happens (via `m/sleep` or `mt/yield` yield points), which gives you deterministic control over when signals propagate.

```clojure
;; ✅ WORKS: Atom changes inside the controlled task
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        !input (atom 1)]
    (mt/run sched
      (m/sp
        (let [<x (m/signal (m/watch !input))
              <y (m/signal (m/latest + <x <x))  ; diamond DAG
              results (atom [])]
          (m/? (m/race
                 ;; Consumer
                 (m/reduce (fn [_ v]
                             (swap! results conj v)
                             (when (= 3 (count @results))
                               (reduced @results)))
                           nil <y)
                 ;; Producer - changes happen at yield points
                 (m/sp
                   (m/? (m/sleep 0))        ; yield point
                   (swap! !input inc)       ; signal propagates HERE
                   (m/? (m/sleep 0))        ; yield point
                   (swap! !input inc)       ; signal propagates HERE
                   (m/? (m/sleep 1000))))))))))
;; => [2 4 6]  (deterministic, 3 is never observed due to diamond dedup)

;; ❌ DEADLOCK: No internal yield points to trigger changes
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        !input (atom 1)]
    (mt/run sched
      (m/sp
        (let [<x (m/signal (m/watch !input))]
          ;; Task waits for signal changes, but nothing triggers them
          (m/? (m/reduce (fn [_ v] (when (= v 4) (reduced v))) nil <x)))))))
;; => throws "Deadlock: no microtasks, no timers, and task still pending"
```

**The rule:** Atom mutations must happen **inside** the `m/sp` task with yield points (`m/sleep` or `mt/yield`). External mutations cause deadlock because the scheduler sees no pending work.

**Thread control requirement:** Determinism is guaranteed only when the scheduler drives execution from a single thread. All task completions, flow transfers, and timer callbacks must occur on the scheduler's driver thread. Off-thread callbacks (e.g., from real executors or external event sources) will either throw `::mt/off-scheduler-callback` or silently break determinism.

**Custom executors:** If you must use `m/via` with a custom executor, you fully control that executor's threads and accept that those sections are non-deterministic. The testkit cannot virtualize work scheduled on arbitrary thread pools.

## Limitations

### What is virtualized

The testkit virtualizes **time-based primitives** only:

| Primitive | Virtualized | Notes |
|-----------|-------------|-------|
| `m/sleep` | Yes | Uses virtual time |
| `m/timeout` | Yes | Uses virtual time |
| `mt/clock` | Yes | Returns virtual time in tests, real time in production |
| `mt/yield` | Yes | Scheduling point in tests, no-op in production |
| `m/cpu` | Yes (JVM) | Deterministic executor |
| `m/blk` | Yes (JVM) | Deterministic executor |
| `m/race`, `m/join`, `m/amb=` | No (not needed) | Pure combinators, work correctly with virtualized primitives |

### What is NOT virtualized

- **Real I/O** - HTTP requests, file operations, database calls execute in real time
- **External `m/observe` callbacks** - Events from external sources (DOM, network) are not controlled
- **External `m/watch` modifications** - Atom changes from threads outside the task are not controlled

**Solution:** Keep atom mutations inside the controlled task. See [Why `m/signal` and `m/watch` Work](#why-msignal-and-mwatch-work).

```clojure
;; Modify atoms inside the controlled task for deterministic behavior
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        !counter (atom 0)]
    (mt/run sched
      (m/sp
        (m/? (m/race
               ;; Consumer
               (m/reduce (fn [acc v]
                           (let [acc (conj acc v)]
                             (if (= 3 (count acc))
                               (reduced acc)
                               acc)))
                         [] (m/watch !counter))
               ;; Producer - mutations at controlled yield points
               (m/sp
                 (m/? (m/sleep 10))
                 (swap! !counter inc)  ; signal propagates synchronously
                 (m/? (m/sleep 10))
                 (swap! !counter inc)
                 (m/? (m/sleep 10)))))))))
;; => [0 1 2]
```

### Cancellation Semantics

The testkit implements Missionary's documented cancellation behavior: **"cancelling sleep makes it fail immediately"**.

#### What happens when a task is cancelled

When a task is cancelled (e.g., the loser of `m/race` or a task that exceeds `m/timeout`):

1. **Pending sleeps throw `missionary.Cancelled`** - The sleep does NOT deliver its value
2. **Cancellation is immediate** - Happens at the winner's time, not the sleep's scheduled time
3. **`finally` blocks execute** - Cleanup code runs as expected
4. **Catch blocks can handle `Cancelled`** - The task can recover and continue

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler {:trace? true})]
    (mt/run sched
      (m/sp
        (m/? (m/race
               (m/sp
                 (try
                   (m/? (m/sleep 500 :never-delivered))  ; scheduled for t=500
                   (catch missionary.Cancelled _
                     :was-cancelled)))                   ; this runs instead
               (m/sleep 100 :winner)))))))              ; wins at t=100
;; => :winner (the race returns the winner's value)
;; The losing task's catch block runs at t=100, not t=500
```

#### Execution order during cancellation

When a race is decided, the execution order is:

1. Winner's continuation runs
2. Loser's cancellation is processed (via microtask)
3. Loser's `catch`/`finally` blocks run
4. Code after the race continues

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        events (atom [])]
    (mt/run sched
      (m/sp
        (let [result (m/? (m/race
                           (m/sp
                             (try
                               (m/? (m/sleep 200))
                               (finally
                                 (swap! events conj :loser-cleanup))))
                           (m/sp
                             (m/? (m/sleep 100))
                             (swap! events conj :winner-done)
                             :fast)))]
          (swap! events conj :after-race)
          result)))
    @events))
;; => [:winner-done :loser-cleanup :after-race]
```

#### Comparison with real Missionary

The testkit's cancellation semantics match real Missionary:

| Behavior | Testkit | Real Missionary |
|----------|---------|-----------------|
| `Cancelled` exception thrown | ✓ | ✓ |
| Immediate cancellation | ✓ | ✓ |
| Sleep value not delivered | ✓ | ✓ |
| `finally` blocks run | ✓ | ✓ |
| Nested cancellation propagates | ✓ | ✓ |

**One subtle difference:** The testkit delivers `Cancelled` via microtask for deterministic ordering. Real Missionary may deliver it synchronously. This ensures reproducible execution order in tests but means the testkit always processes cancellation in a specific order relative to other pending work.

#### Testing cancellation scenarios

Use the testkit to verify your code handles cancellation correctly:

```clojure
;; Test that resources are cleaned up on cancellation
(mt/with-determinism
  (let [sched (mt/make-scheduler)
        resource-released? (atom false)]
    (mt/run sched
      (m/sp
        (m/? (m/timeout
               (m/sp
                 (try
                   (m/? (m/sleep 1000))
                   (finally
                     (reset! resource-released? true))))
               100
               :timed-out))))
    (is @resource-released?)))

;; Test that catch blocks can recover from cancellation
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (is (= :recovered
           (mt/run sched
             (m/sp
               (m/? (m/race
                      (m/sp
                        (try
                          (m/? (m/sleep 200))
                          (catch missionary.Cancelled _
                            :recovered)))
                      (m/sleep 100 :winner)))))))))
```

### Threading model

Missionary uses cooperative single-threaded execution by default. Tasks interleave at `m/?` suspension points, not truly in parallel. The testkit controls:

1. **Virtual time** - Which sleeps/timeouts complete first
2. **Task ordering** - Which ready task runs next (via schedule)
3. **Built-in executors** - `m/cpu` and `m/blk` are rebound to deterministic executors

### m/via Behavior

`m/via m/cpu` and `m/via m/blk` work correctly because those executors are rebound by `with-determinism` to run work as scheduler microtasks. The via body executes synchronously on the driver thread.

**Important:** Do NOT use real executors (e.g., `Executors/newFixedThreadPool`) inside `with-determinism` - they will cause off-thread callbacks that break determinism.

**Interrupt behavior:** When a via task is cancelled before its microtask executes, the via body will run with `Thread.interrupted()` returning `true`. Blocking calls in the via body will throw `InterruptedException`. The interrupt flag is cleared after the via body completes, so the scheduler remains usable.

```clojure
;; CORRECT: m/via with virtualized executors
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/sp
        (m/? (m/via m/cpu (+ 1 2 3)))))))  ; Works - runs on driver thread
;; => 6

;; WRONG: m/via with real executor
(import '[java.util.concurrent Executors])
(def real-exec (Executors/newSingleThreadExecutor))
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (mt/run sched
      (m/via real-exec (do-work)))))  ; Fails - off-thread callback
```

### Thread safety (JVM only)

The scheduler enforces single-threaded access to catch accidental non-determinism from off-thread callbacks. Off-thread operations will throw errors like "Scheduler driven from multiple threads".

This protection is automatic on JVM. ClojureScript is single-threaded so no enforcement is needed.

### Parallel test execution

`with-determinism` uses reference-counted var rebinding for safe parallel test runs. The first test to enter rebinds `m/sleep`, `m/timeout`, `m/cpu`, and `m/blk` to their virtual equivalents; the last test to exit restores the originals. Tests run fully concurrently with correct isolation via thread-local `*is-deterministic*` binding.

### Flow forking

`m/amb` and `m/amb=` require a forking context (`m/ap` with `m/?>`):

```clojure
;; Won't work - no forking context
(m/sp (m/? (m/reduce conj [] (m/amb= flow1 flow2))))

;; Works - m/ap provides forking via m/?>
(m/sp (m/? (m/reduce conj []
             (m/ap (m/?> (m/amb= flow1 flow2))))))
```

## License

Copyright 2026 Levering IT GmbH

Distributed under the Eclipse Public License version 1.0.
