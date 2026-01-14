# missionary-testkit

[![Clojars Project](https://img.shields.io/clojars/v/de.levering-it/missionary-testkit.svg)](https://clojars.org/de.levering-it/missionary-testkit)
![example branch parameter](https://github.com/LeveringIT/missionary-testkit/actions/workflows/test.yaml/badge.svg?branch=main)

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
(mt/with-determinism [sched (mt/make-scheduler)]
  (mt/run sched
    (m/sp
      ;; This "sleeps" for 10 seconds but executes instantly!
      (m/? (m/sleep 10000))
      :done)))
;; => :done (returns immediately)
```

### Testing Concurrent Operations

```clojure
(mt/with-determinism [sched (mt/make-scheduler)]
  (mt/run sched
    (m/sp
      ;; Race two tasks - deterministic winner every time
      (m/? (m/race
             (m/sleep 100 :fast)
             (m/sleep 200 :slow))))))
;; => :fast
```

### Testing Timeouts

```clojure
(mt/with-determinism [sched (mt/make-scheduler)]
  (mt/run sched
    (m/sp
      ;; Task takes 500ms but timeout is 100ms
      (m/? (m/timeout
             (m/sleep 500 :completed)
             100
             :timed-out)))))
;; => :timed-out
```

## Core Concepts

### TestScheduler

The scheduler manages virtual time and a queue of pending tasks:

```clojure
(def sched (mt/make-scheduler {:initial-ms 0      ; starting time
                                :trace?     true   ; enable execution trace
                                :strict?    false  ; thread safety checks
                                :policy     :fifo  ; task ordering
                                :schedule   nil})) ; interleaving schedule (see Schedule Decisions)

(mt/now-ms sched)   ; => 0 (current virtual time)
(mt/pending sched)  ; => {:microtasks [...] :timers [...]}
(mt/trace sched)    ; => [{:event :enqueue-timer ...} ...]
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
(mt/with-determinism [sched (mt/make-scheduler)]
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
               (mt/collect flow)))))))
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
(mt/with-determinism [sched (mt/make-scheduler)]
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
               (mt/collect flow {:xf (take 3)})))))))
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
(mt/with-determinism [sched (mt/make-scheduler {:trace? true})]
  (mt/run sched (m/sp (m/? (m/sleep 100 :done))))
  (mt/trace sched))

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
(mt/with-determinism [sched (mt/make-scheduler)]
  (let [order (atom [])]
    (mt/run sched
      (m/sp
        (m/? (m/join vector
               (m/sp (swap! order conj :a1) (m/? (mt/yield)) (swap! order conj :a2))
               (m/sp (swap! order conj :b1) (m/? (mt/yield)) (swap! order conj :b2))))
        @order))))
;; Different schedules can produce [:a1 :b1 :a2 :b2], [:a1 :b1 :b2 :a2], etc.
```

This is useful for:
- Testing concurrent code under different task orderings
- Creating explicit interleaving points without artificial time delays
- Code that should be order-independent in production but needs interleaving testing

### Finding Bugs with check-interleaving

```clojure
(mt/with-determinism [_ (mt/make-scheduler)]
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
                  :seed 42
                  :property (fn [v] (#{20 10} v))})]  ; only valid results

    (when result
      (println "Bug found!")
      (println "Seed:" (:seed result))
      (println "Schedule:" (:schedule result)))))
```

### Replaying a Failing Schedule

When `check-interleaving` finds a bug, you can replay the exact schedule:

```clojure
;; Replay the failing case
(mt/with-determinism [_ (mt/make-scheduler)]
  (mt/replay-schedule failing-task (:schedule result)))
```

### Exploring All Outcomes

See how many distinct outcomes a concurrent task can produce:

```clojure
(mt/with-determinism [_ (mt/make-scheduler)]
  (let [make-task (fn [] ...)  ; task factory
        result (mt/explore-interleavings make-task {:num-samples 50 :seed 42})]
    (println "Unique results:" (:unique-results result))))
```

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
;; Create scheduler with explicit schedule
(mt/make-scheduler {:schedule [:lifo :fifo :lifo :fifo]})

;; Mix basic and targeted decisions
(mt/make-scheduler {:schedule [:fifo [:nth 2] :lifo [:by-label "producer"]]})

;; Generate schedule from seed (basic decisions only)
(mt/seed->schedule 42 10) ; => [:lifo :fifo :random ...]
```

## Error Detection

The scheduler automatically detects common problems:

```clojure
;; Deadlock detection
(mt/with-determinism [sched (mt/make-scheduler)]
  (mt/run sched
    (m/sp (m/? (m/sleep 100)))
    {:auto-advance? false}))
;; throws: "Deadlock: task not done after draining microtasks"

;; Infinite loop protection
(mt/with-determinism [sched (mt/make-scheduler)]
  (mt/run sched
    (m/sp (loop [] (m/? (m/sleep 0)) (recur)))
    {:max-steps 100}))
;; throws: "Step budget exceeded: 101 > 100"

;; Time budget
(mt/with-determinism [sched (mt/make-scheduler)]
  (mt/run sched
    (m/sp (m/? (m/sleep 999999)))
    {:max-time-ms 1000}))
;; throws: "Time budget exceeded"
```

## API Reference

### Scheduler Creation
- `(make-scheduler)` / `(make-scheduler opts)` - create a scheduler

### Time Inspection
- `(now-ms sched)` - current virtual time in milliseconds
- `(pending sched)` - queued microtasks and timers
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
- `(executor sched)` - deterministic `java.util.concurrent.Executor`
- `(cpu-executor sched)` - deterministic CPU executor
- `(blk-executor sched)` - deterministic blocking executor

### Integration Macro
- `(with-determinism [sched expr] & body)` - rebind `m/sleep`, `m/timeout`, `m/cpu`, `m/blk`

### Interleaving (Concurrency Testing)
- `(check-interleaving task-fn opts)` - find failures across many interleavings (task-fn is a 0-arg function)
- `(explore-interleavings task-fn opts)` - explore unique outcomes (task-fn is a 0-arg function)
- `(replay-schedule task schedule)` - replay exact execution order
- `(trace->schedule trace)` - extract schedule from trace
- `(seed->schedule seed n)` - generate schedule from seed
- `(selection-gen)` / `(schedule-gen n)` - test.check generators

## Running Tests

```bash
# Run tests
clojure -X:test cognitect.test-runner.api/test
```

## Limitations

### What is virtualized

The testkit virtualizes **time-based primitives** only:

| Primitive | Virtualized | Notes |
|-----------|-------------|-------|
| `m/sleep` | Yes | Uses virtual time |
| `m/timeout` | Yes | Uses virtual time |
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
(mt/with-determinism [sched (mt/make-scheduler)]
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
               (mt/collect flow)))))))
;; => [:click :scroll]

;; Instead of m/watch on a real atom, use mt/state
(mt/with-determinism [sched (mt/make-scheduler)]
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
               (mt/collect flow)))))))
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

**2. Strict mode catches off-thread access**:

```clojure
;; m/observe: off-thread emit is NOT caught
(mt/with-determinism [sched (mt/make-scheduler {:strict? true})]
  (let [emit-fn (atom nil)
        flow (m/observe (fn [emit!] (reset! emit-fn emit!) #()))]
    ;; Start consuming the flow so emit-fn gets set
    (mt/start! sched (m/reduce (fn [_ x] x) nil flow) {})
    (mt/tick! sched)
    @(future (@emit-fn :value))   ; off-thread - NOT caught!
    :no-error))
;; => :no-error (silently non-deterministic!)

;; mt/subject: off-thread emit IS caught
(mt/with-determinism [sched (mt/make-scheduler {:strict? true})]
  (let [{:keys [emit]} (mt/subject sched)]
    (mt/step! sched)
    @(future ((emit :value) identity identity))))  ; off-thread - CAUGHT!
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
| Strict mode catches off-thread | No | Yes |
| Works for simple tests | Yes | Yes |

**Use testkit versions** when you want full traceability or strict mode protection. **Use Missionary built-ins** for simple tests where you control all modifications from within scheduled code.

### Threading model

Missionary uses cooperative single-threaded execution by default. Tasks interleave at `m/?` suspension points, not truly in parallel. The testkit controls:

1. **Virtual time** - Which sleeps/timeouts complete first
2. **Task ordering** - Which ready task runs next (via schedule)
3. **Built-in executors** - `m/cpu` and `m/blk` are rebound to deterministic executors

Note: `m/via` with a custom executor is **not** automatically deterministic. Only `m/via m/cpu` and `m/via m/blk` are controlled because those executors are patched by `with-determinism`.

### Strict mode (JVM only)

When `{:strict? true}`, the scheduler detects off-thread access to catch accidental non-determinism:

```clojure
;; This will throw in strict mode if called from wrong thread
(future ((:emit subject) :value))
;; => "Scheduler driven from multiple threads (enqueue-microtask!)"
```

Strict mode is unavailable in ClojureScript (single-threaded environment).

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
