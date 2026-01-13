# PROJECT_SUMMARY.md

> For Claude Code. Constraints and intent over description.

## Purpose

Deterministic testing library for [Missionary](https://github.com/leonoel/missionary) async code. Provides virtual time scheduler that eliminates real-time delays and non-determinism in tests.

## Architecture

### Core Model

```
TestScheduler (record)
├── state (atom)
│   ├── :now-ms        - virtual clock (long)
│   ├── :next-id       - monotonic ID generator
│   ├── :micro-q       - PersistentQueue of pending microtasks
│   ├── :timers        - sorted-map [at-ms tie order id] -> timer-map
│   ├── :trace         - nil | vector of trace events
│   ├── :driver-thread - JVM: Thread | nil, CLJS: ::na
│   └── :schedule-idx  - current position in schedule (for interleaving)
├── policy             - :fifo | :seeded
├── seed               - long (for :seeded policy)
├── strict?            - boolean (thread safety checks)
├── trace?             - boolean
└── schedule           - nil | vector of selection decisions
```

### Execution Model

1. **Microtask queue**: FIFO queue of pending work. Executed synchronously by `step!`/`tick!`.
2. **Timer heap**: Sorted map of future timers. Promoted to microtask queue when `now-ms >= at-ms`.
3. **Virtual time**: Never advances automatically. Use `advance!`/`advance-to!` or `run` with `auto-advance? true`.
4. **Driver thread**: In strict mode, only one thread may drive the scheduler (step/tick/advance/run/transfer).

### State Transitions

```
Timer scheduled → waits in :timers sorted-map
Time advanced   → due timers promoted to :micro-q
Microtask runs  → may schedule more timers/microtasks
Task completes  → result delivered via microtask (deterministic ordering)
```

## Namespace Map

| Namespace | Responsibility |
|-----------|----------------|
| `de.levering-it.missionary-testkit` | Single namespace. All public API. Suggested alias: `mt` |
| `build` | tools.build functions for JAR/deploy |

## Key Records and Protocols

| Type | Purpose |
|------|---------|
| `TestScheduler` | Virtual time scheduler with microtask queue and timer heap |
| `Job` | Handle for a running task. Implements `ICancellable` |
| `FlowProcess` | Handle for a spawned flow. Implements `ICancellable` |
| `ICancellable` | Protocol: `(-cancel! [x])` |

## Public API (by category)

### Scheduler Creation
- `(make-scheduler)` / `(make-scheduler opts)` → TestScheduler

### Time Inspection (read-only, safe)
- `(now-ms sched)` → long
- `(pending sched)` → {:microtasks [...] :timers [...]}
- `(trace sched)` → nil | vector

### Time Control (mutates scheduler state)
- `(step! sched)` → executes 1 microtask, returns task-map or `::idle`
- `(tick! sched)` → drains microtask queue, returns count
- `(advance! sched dt-ms)` → advances time by delta, promotes timers, ticks
- `(advance-to! sched t)` → advances to absolute time, promotes timers, ticks

### Task Execution
- `(run sched task)` / `(run sched task opts)` → result or throws
- `(start! sched task)` / `(start! sched task opts)` → Job

### Job Handle
- `(done? job)` → boolean
- `(result job)` → value | throws | `::pending`
- `(cancel! job)` → nil

### Flow Testing
- `(subject sched)` / `(subject sched opts)` → {:flow :emit :offer :close :fail}
- `(state sched)` / `(state sched opts)` → {:flow :set :fail :close}
- `(spawn-flow! sched flow)` → FlowProcess
- `(ready? proc)` → boolean
- `(terminated? proc)` → boolean
- `(transfer! proc)` → value or throws

### Virtual Time Primitives
- `(sleep ms)` / `(sleep ms x)` → task (requires `*scheduler*` binding)
- `(timeout task ms)` / `(timeout task ms x)` → task

### Integration
- `(with-determinism [sched expr] & body)` → macro, rebinds `m/sleep`, `m/timeout`, `m/cpu`, `m/blk`
- `(collect flow)` / `(collect flow opts)` → task yielding vector
- `(executor sched)` → java.util.concurrent.Executor (JVM only)
- `(cpu-executor sched)` → Executor (JVM only)
- `(blk-executor sched)` → Executor (JVM only)

### Interleaving (Concurrency Bug Testing)
- `(trace->schedule trace)` → vector of selection decisions from trace
- `(replay-schedule task schedule)` / `(replay-schedule task schedule opts)` → runs task with exact schedule
- `(seed->schedule seed n)` → deterministically generates schedule of length n
- `(check-interleaving task-fn opts)` → runs task-fn with many interleavings, returns nil or failure info
- `(explore-interleavings task-fn opts)` → explores interleavings, returns {:unique-results n :results [...]}
- `(selection-gen)` → test.check generator for single selection decision
- `(schedule-gen max-decisions)` → test.check generator for schedule vector

Note: `check-interleaving` and `explore-interleavings` take a task-fn (0-arg function returning a task)
to ensure fresh state for each iteration.

## Dynamic Vars

| Var | Purpose |
|-----|---------|
| `*scheduler*` | Currently bound TestScheduler. Required by `sleep`/`timeout`. |
| `*in-scheduler*` | true while executing scheduler-driven work |

## Error Keywords (`:mt/kind` in ex-data)

| Keyword | Condition |
|---------|-----------|
| `::idle` | No microtasks to execute (return value, not error) |
| `::deadlock` | Task pending but no microtasks or timers |
| `::budget-exceeded` | max-steps or max-time-ms exceeded |
| `::off-scheduler-callback` | Strict mode: callback from wrong thread |
| `::illegal-transfer` | Transfer when not ready or after termination |
| `::no-scheduler` | `*scheduler*` not bound |

## Configuration

### deps.edn Aliases

| Alias | Purpose |
|-------|---------|
| `:test` | Test runner with test.check |
| `:nrepl` | REPL on port 7888 with CIDER middleware |
| `:build` | tools.build for JAR/deploy |

### Environment Variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `CLOJARS_USERNAME` | For deploy | Clojars authentication |
| `CLOJARS_PASSWORD` | For deploy | Clojars authentication |

### clojure-mcp Config

File: `.clojure-mcp/config.edn`
```clojure
{:start-nrepl-cmd ["clojure" "-M:nrepl"]}
```

## Commands

### Start REPL
```bash
clojure -M:nrepl
# Starts nREPL on port 7888
```

### Run Tests
```bash
clojure -X:test cognitect.test-runner.api/test
```

### Build JAR
```bash
clojure -T:build ci
```

### Deploy to Clojars
```bash
clojure -T:build deploy
# Requires CLOJARS_USERNAME and CLOJARS_PASSWORD
```

## REPL Safety Notes

### SAFE Operations (read-only)
```clojure
(mt/now-ms sched)      ; read current time
(mt/pending sched)     ; inspect queue state
(mt/trace sched)       ; read trace log
(mt/done? job)         ; check job status
(mt/ready? proc)       ; check flow readiness
(mt/terminated? proc)  ; check flow termination
```

### MUTATING Operations (advance scheduler state)
```clojure
(mt/step! sched)       ; executes microtask
(mt/tick! sched)       ; drains microtask queue
(mt/advance! sched n)  ; advances time, may run tasks
(mt/advance-to! sched t) ; advances time, may run tasks
(mt/run sched task)    ; runs task to completion
(mt/start! sched task) ; starts task
(mt/transfer! proc)    ; transfers from flow
(mt/cancel! job)       ; cancels job
```

### DO NOT
- Call `run` or `advance!` without understanding they execute arbitrary user code
- Create schedulers with `strict? true` and drive from multiple threads
- Transfer from a flow without checking `ready?` first
- Assume `result` is safe - it may throw if task failed

## Behavioral Constraints

### Scheduler Invariants
1. Time never decreases: `advance-to!` throws if `t < now-ms`
2. Microtasks execute in FIFO order within same virtual time (unless schedule overrides)
3. Timers promote to microtask queue in sorted order when due
4. All task completions route through microtask queue (deterministic ordering)
5. With `:schedule`, selection decisions override FIFO when queue has >1 item

### run Defaults
- `auto-advance? true` - automatically advances to next timer
- `max-steps 100000` - throws `::budget-exceeded` if exceeded
- `max-time-ms 60000` - throws `::budget-exceeded` if virtual time exceeds

### Flow Constraints
- `subject` supports exactly one subscriber
- `state` supports exactly one subscriber
- `transfer!` throws if `ready?` is false
- `transfer!` throws if `terminated?` is true

### Thread Safety (JVM)
- Scheduler state is in an atom (thread-safe reads)
- With `strict? true`, only one thread may drive (step/tick/advance/run/transfer)
- Enqueuing from other threads is allowed even in strict mode

## Cross-Platform Notes

- File extension: `.cljc` (reader conditionals for CLJ/CLJS)
- `Executor` functions are JVM-only, throw on CLJS
- `with-determinism` macro: on CLJS only patches sleep/timeout (no executor rebinding)
- `run` returns Promise on CLJS, value on JVM

## Test Coverage

26 tests, 125 assertions covering:
- Scheduler creation and time control
- Sleep and timeout behavior
- Job lifecycle
- Subject (discrete flow)
- State (continuous flow)
- Flow process operations
- with-determinism macro
- run budgets and deadlock detection
- Executor operations
- Error handling
- Interleaving (schedule selection, trace extraction, replay, check/explore)

## File Structure

```
.
├── deps.edn                           # Project config
├── build.clj                          # Build scripts
├── src/
│   └── de/levering_it/
│       └── missionary_testkit.cljc    # All source (1231 lines)
├── test/
│   └── de/levering_it/
│       └── missionary_testkit_test.clj # Tests
├── .clojure-mcp/
│   └── config.edn                     # clojure-mcp nREPL config
├── README.md                          # Human documentation
├── LLM_CODE_STYLE.md                  # Code style preferences
└── PROJECT_SUMMARY.md                 # This file
```
