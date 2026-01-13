# missionary-testkit

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
                                :policy     :fifo})) ; task ordering

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

### Utilities
- `(collect flow)` / `(collect flow opts)` - collect flow to vector task
- `(executor sched)` - deterministic `java.util.concurrent.Executor`
- `(cpu-executor sched)` - deterministic CPU executor
- `(blk-executor sched)` - deterministic blocking executor

### Integration Macro
- `(with-determinism [sched expr] & body)` - rebind `m/sleep`, `m/timeout`, `m/cpu`, `m/blk`

## Running Tests

```bash
# Run tests
clojure -X:test cognitect.test-runner.api/test
```

## License

Copyright 2026 Hendrik

Distributed under the Eclipse Public License version 1.0.
