# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Added
- Thread pool modeling with `:cpu-threads` option for `make-scheduler` (default 8)
- Lane-based concurrency: `:default` (single-threaded), `:cpu` (thread pool), `:blk` (unlimited)
- `:task-start` and `:task-complete` trace events for tasks with duration
- `:cpu-threads` option for `check-interleaving` and `explore-interleavings`
- `cpu-threads` included in failure bundles for faithful replay
- `:cancel-promoted-timer` trace event when cancelling a timer that was already promoted to microtask queue

### Changed
- Tasks with duration now go through split execution (start → in-flight → complete) instead of instant execution
- CPU tasks respect thread pool capacity; BLK tasks can run concurrently without limit
- `next-tasks` now filters by lane capacity - only returns tasks that can actually run (blocked tasks excluded)
- `:select-task` trace event `:alternatives` now shows only runnable tasks (filtered by lane capacity)
- Job completion is now synchronous (matching Missionary semantics) instead of via extra microtask hop

### Fixed
- Cancelled timers that were already promoted to microtask queue are now properly removed
- `start-task-in-flight!` no longer executes work-thunk before CAS succeeds (prevents duplicate execution on retry)
- `:select-task` trace alternatives no longer include tasks blocked by lane capacity
