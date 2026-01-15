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

The same applies to flows created with `mt/subject` and `mt/state`—they must be created inside `with-determinism` to be properly scheduled.

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
                                :seed            42      ; seed for RNG and timer tie-breaking
                                :trace?          true    ; enable execution trace
                                :timer-order     :fifo   ; explicit override (:fifo or :seeded)
                                :micro-schedule  nil}))  ; microtask interleaving (see Schedule Decisions)

(mt/now-ms sched)   ; => 0 (current virtual time)
(mt/pending sched)  ; => {:microtasks [...] :timers [...]}
(mt/trace sched)    ; => [{:event :enqueue-timer ...} ...]
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

### Subject (Discrete Flow)

A controlled source for discrete values - like an event stream:

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (let [{:keys [flow emit close]} (mt/subject sched)]
      (mt/run sched
        (m/sp
          (m/? (m/join (fn [_ collected] collected)
                 ;; Producer
                 (m/sp
                   (m/? (emit :a))
                   (m/? (emit :b))
                   (m/? (emit :c))
                   (m/? (close)))
                 ;; Consumer
                 (mt/collect flow))))))))
;; => [:a :b :c]
```

Subject API:
- `(:flow subj)` - the flow to consume
- `(:emit subj)` - `(fn [v] task)` - emit a value, completes when transferred
- `(:offer subj)` - `(fn [v] bool)` - best-effort emit, returns false if backpressured
- `(:close subj)` - `(fn [] task)` - close normally
- `(:fail subj)` - `(fn [ex] task)` - fail with exception

### State (Continuous Flow)

A controlled source for continuous values - like a watched atom:

```clojure
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (let [{:keys [flow set close]} (mt/state sched {:initial 0})]
      (mt/run sched
        (m/sp
          (m/? (m/join (fn [_ collected] collected)
                 ;; Producer
                 (m/sp
                   (m/? (m/sleep 100))
                   (set 1)
                   (m/? (m/sleep 100))
                   (set 2)
                   (m/? (m/sleep 100))
                   (close))
                 ;; Consumer - take first 3 values
                 (mt/collect flow {:xf (take 3)}))))))))
;; => [0 1 2]
```

State API:
- `(:flow st)` - the continuous flow
- `(:set st)` - `(fn [v] nil)` - update the value
- `(:close st)` - `(fn [] nil)` - terminate the flow

### Manual Flow Control

For fine-grained testing of flow behavior:

```clojure
(let [sched (mt/make-scheduler)
      {:keys [flow emit]} (mt/subject sched)
      proc (mt/spawn-flow! sched flow)]

  ;; Flow starts not ready
  (mt/tick! sched)
  (mt/ready? proc)      ; => false

  ;; Emit makes it ready
  (mt/start! sched (emit :value) {})
  (mt/tick! sched)
  (mt/ready? proc)      ; => true

  ;; Transfer the value
  (mt/transfer! proc)   ; => :value

  ;; Check termination
  (mt/terminated? proc) ; => false
  (mt/cancel! proc))    ; cancel the flow
```

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

Schedules control task selection when the queue has multiple ready tasks:

**Basic decisions:**
- `:fifo` - first in, first out (default)
- `:lifo` - last in, first out
- `:random` - random selection (deterministic via seed)

**Targeted decisions:**
- `[:nth n]` - select nth task (wraps if n >= queue size)
- `[:by-label label]` - select first task with matching label
- `[:by-id id]` - select task with specific ID

All targeted decisions fall back to the first task if no match is found.

```clojure
;; Create scheduler with explicit micro-schedule
(mt/make-scheduler {:micro-schedule [:lifo :fifo :lifo :fifo]})

;; Mix basic and targeted decisions
(mt/make-scheduler {:micro-schedule [:fifo [:nth 2] :lifo [:by-label "producer"]]})

;; Generate schedule from seed (basic decisions only)
(mt/seed->schedule 42 10) ; => [:lifo :fifo :random ...]
```

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
- `(trace sched)` - execution trace (if enabled)

### Time Control
- `(step! sched)` - run one microtask, returns `::mt/idle` if none
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

### Flow Testing
- `(subject sched)` / `(subject sched opts)` - create discrete flow source
- `(state sched)` / `(state sched opts)` - create continuous flow source
- `(spawn-flow! sched flow)` - spawn flow for manual testing
- `(ready? proc)` - is flow ready for transfer?
- `(terminated? proc)` - has flow terminated?
- `(transfer! proc)` - transfer one value

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
- `(trace->schedule trace)` - extract schedule from trace
- `(seed->schedule seed n)` - generate schedule from seed (cross-platform consistent)
- `(selection-gen)` / `(schedule-gen n)` - test.check generators

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
| `mt/subject`, `mt/state` | ✓ | Deterministic flow sources |
| `m/via` with `m/cpu` or `m/blk` | ✓ | JVM only; executors rebound to scheduler microtasks |

**Explicitly NOT supported (non-deterministic):**

| Primitive | Why |
|-----------|-----|
| `m/publisher`, `m/stream`, `m/signal` | Reactive-streams subsystem; external threading |
| `m/via` with custom executors | Work runs on uncontrolled threads |
| Real I/O (HTTP, file, database) | Actual wall-clock time, external systems |
| `m/observe` with external callbacks | Events arrive from outside the scheduler |
| `m/watch` on atoms modified externally | Modifications from other threads |

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
- **Real `m/observe` callbacks** - External event sources (DOM, network) are not controlled
- **Real `m/watch` on atoms** - Changes from external threads are not controlled

**Solution:** Use `mt/subject` (discrete) and `mt/state` (continuous) for deterministic flow sources in tests.

```clojure
;; Instead of m/observe with real callbacks, use mt/subject
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (let [{:keys [flow emit close]} (mt/subject sched)]
      (mt/run sched
        (m/sp
          (m/? (m/join (fn [_ v] v)
                 ;; Producer - simulates external events
                 (m/sp
                   (m/? (emit :click))
                   (m/? (emit :scroll))
                   (m/? (close)))
                 ;; Consumer
                 (mt/collect flow))))))))
;; => [:click :scroll]

;; Instead of m/watch on a real atom, use mt/state
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (let [{:keys [flow set close]} (mt/state sched {:initial 0})]
      (mt/run sched
        (m/sp
          (m/? (m/join (fn [_ v] v)
                 ;; Producer - simulates atom changes
                 (m/sp
                   (m/? (m/sleep 10))
                   (set 1)
                   (m/? (m/sleep 10))
                   (set 2)
                   (m/? (m/sleep 10))
                   (close))
                 ;; Consumer - samples the continuous flow
                 (mt/collect flow))))))))
;; => [0 1 2]
```

#### Why use `mt/subject` and `mt/state` instead of `m/observe` and `m/watch`?

You can use Missionary's built-in `m/observe` and `m/watch` if you control all emissions/modifications from within the scheduled code. However, the testkit versions provide two key advantages:

**1. Full traceability** - All events appear in `(mt/trace sched)`:

```clojure
;; m/observe / m/watch: events are invisible to the scheduler
{:total-events 2, :subject-events []}

;; mt/subject / mt/state: events appear in trace with timestamps
{:total-events 18,
 :subject-events [{:kind :subject/notifier, :now-ms 0}
                  {:kind :subject/emit-success, :now-ms 0}
                  {:kind :subject/terminator, :now-ms 0}
                  ...]}
```

**2. Thread safety catches off-thread access**:

```clojure
;; m/observe: off-thread emit is NOT caught
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (let [emit-fn (atom nil)
          flow (m/observe (fn [emit!] (reset! emit-fn emit!) #()))]
      ;; Start consuming the flow so emit-fn gets set
      (mt/start! sched (m/reduce (fn [_ x] x) nil flow) {})
      (mt/tick! sched)
      @(future (@emit-fn :value))   ; off-thread - NOT caught!
      :no-error)))
;; => :no-error (silently non-deterministic!)

;; mt/subject: off-thread emit IS caught
(mt/with-determinism
  (let [sched (mt/make-scheduler)]
    (let [{:keys [emit]} (mt/subject sched)]
      (mt/step! sched)
      @(future ((emit :value) identity identity)))))  ; off-thread - CAUGHT!
;; => throws "Scheduler driven from multiple threads (enqueue-microtask!)"
```

The same applies to `m/watch` vs `mt/state`:

```clojure
;; m/watch: off-thread reset! is NOT caught
@(future (reset! my-atom 42))
;; => :no-error (silently non-deterministic!)

;; mt/state: off-thread set IS caught
@(future (set 42))
;; => throws "Scheduler driven from multiple threads (state set)"
```

**Summary:**

| Aspect | `m/observe` / `m/watch` | `mt/subject` / `mt/state` |
|--------|------------------------|---------------------------|
| Events in trace | No | Yes |
| Thread safety catches off-thread | No | Yes |
| Works for simple tests | Yes | Yes |

**Use testkit versions** when you want full traceability or thread safety protection. **Use Missionary built-ins** for simple tests where you control all modifications from within scheduled code.

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

The scheduler enforces single-threaded access to catch accidental non-determinism from off-thread callbacks:

```clojure
;; This throws because it's called from wrong thread
(future ((:emit subject) :value))
;; => "Scheduler driven from multiple threads (enqueue-microtask!)"
```

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
