# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Added
- Thread pool modeling with `:cpu-threads` option for `make-scheduler` (default 8)
- Lane-based concurrency: `:default` (single-threaded), `:cpu` (thread pool), `:blk` (unlimited)
- `:task-start` and `:task-complete` trace events for tasks with duration
- `:cpu-threads` option for `check-interleaving` and `explore-interleavings`
- `cpu-threads` included in failure bundles for faithful replay

### Changed
- Tasks with duration now go through split execution (start → in-flight → complete) instead of instant execution
- CPU tasks respect thread pool capacity; BLK tasks can run concurrently without limit
