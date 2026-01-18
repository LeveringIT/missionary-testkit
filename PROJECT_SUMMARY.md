# PROJECT_SUMMARY.md (encoded)

C: purpose=deterministic-testkit|missionary-async ; elim={real-time,rand,nondet} ; prod
A: core=TestScheduler(state:atom) ; edge={public-api,macros} ; infra={build,clojars,nrepl}
E: ns=de.levering-it.missionary-testkit as mt ; build-ns=build
D: state{:now-ms :next-id :micro-q :timers :trace :driver-thread :schedule-idx :rng-select :rng-duration} ; timer-order∈{:fifo,:seeded} ; seed≠0⇒:seeded(default) ; seed=0⇒:fifo(default) ; schedule=nil|vec(task-ids) ; schedule-format=[id1 id2 id3] (bare ints) ; duration-range=nil|[lo,hi]⇒yield/via-call-only ; timer-policy∈{:promote-first,:microtasks-first}
X: model=micro-q(FIFO) + timers(sorted-map[at tie order id]→timer) ; time=virtual(manual) ; strict⇒1-driver-thread ; duration-ms⇒yield+via-call(user-work) ; infra-microtasks⇒0ms
Δ: timers→promote(now>=at)→micro-q ; microtask→may enqueue ; completion→via micro-q ; schedule⇒override(>1 item) ; step!/tick!⇒auto-promote ; step!→advance-time-by-duration→promote-due-timers→exec
T: cmd=clojure -M:test -m cognitect.test-runner ; reload-before-test=require:reload ; cover≈42t/215a
M: tools={Clojure-edit,Clojure-eval} ; struct≻str ; light⇒paren✓post ; miss<5%⇒form(type+name)
S: if≻cond1 ; cond≻if(n>1) ; if-let|when-let≻let+if ; ->|->>≻lets ; nil≻flag-obj ; ¬comments
V: vars{*scheduler* *is-deterministic*} ; *is-deterministic*=bool(not fn)
E!: ex-data[:mt/kind]∈{::deadlock ::budget-exceeded ::off-scheduler-callback ::illegal-transfer ::no-scheduler ::replay-without-determinism ::task-id-not-found ::unknown-decision ::microtask-exception} ; ::idle=return
API:
  make=(make-scheduler [opts])
  read=(now-ms pending trace clock next-tasks next-event)
  ctrl=(step! [step! sched task-id] tick! advance! advance-to! cancel-microtask!)
  run=(run start!) ; job=(done? result cancel!)
  vt=(sleep timeout yield[x opts{:duration-fn}]) ; integ=(with-determinism collect executor cpu-executor blk-executor)
  interleave=(trace->schedule replay-schedule[task schedule opts] replay[task-fn failure] check-interleaving explore-interleavings)
I: inv={determinism∀f, iface-consistency, time↑only, random@same-time(seeded) unless explicit-schedule, timers→sorted, completion→micro-q, schedule→select if >1, cancel→sync(match-missionary)}
DC: det-yes={sleep,timeout,yield,race,join,amb,amb=,seed,sample,relieve,sem,rdv,mbx,dfv,via(cpu|blk),watch(int),signal(int),stream(int),latest,observe(int)} ; det-no={publisher,via(custom),real-IO,observe(ext),watch(ext)}
EX: coord={mbx,dfv,rdv,sem} ; flows={diamond-dag,glitch-free,nested-diamond,sampling} ; observe={event-emitter,controlled-callbacks} ; manual={run-inspect-edit-replay,manual-stepping,custom-schedule}
TS: strict⇒drive-only-one-thread ; off-thread-cb⇒fail(::off-scheduler-callback) ; atom⇒safe-reads ; with-determinism⇒global-lock(parallel-safe)
via: m/via(cpu|blk)⇒sync-on-driver ; real-exec⇒break-determinism ; cancel⇒cooperative(no-interrupt)
XP: .cljc ; JVM-only={Executor fns} ; CLJS={with-determinism patches sleep/timeout only, run⇒Promise} ; JVM run⇒value
CFG: deps-alias{:test,:nrepl(7888),:build} ; env{CLOJARS_USERNAME,CLOJARS_PASSWORD} ; mcp-config=.clojure-mcp/config.edn start-nrepl=["clojure","-M:nrepl"]
CMD: repl=clojure -M:nrepl ; build=clojure -T:build ci ; deploy=clojure -T:build deploy
SAFE: read-only={now-ms pending trace clock done?}
MUT: advances={step! tick! advance! advance-to! run start! cancel! cancel-microtask!}
FS: src=src/de/levering_it/missionary_testkit.cljc ; test=test/de/levering_it/missionary_testkit_test.clj ; build=build.clj ; ex=examples/*.clj
R: Δ1=schedule-format:bare-ids ; Δ2=+next-tasks ; Δ3=step!:2-arity(task-id) ; Δ4=+manual_schedule.clj ; Δ5=refactor:select-and-remove-task ; Δ6=cancel→sync(sleep,yield,timeout) ; Δ7=docs:with-determinism(create+execute) ; Δ8=step!/tick!:auto-promote-due-timers ; Δ9=fix:timeout-timer-leak-on-child-throw ; Δ10=step!:wrap-microtask-exceptions ; Δ11=+duration-range:virtual-task-duration ; Δ12=yield:opts{:duration-fn} ; Δ13=duration-range⇒yield+via-call-only(user-work) ; Δ14=split-rng:rng-select+rng-duration ; Δ15=+timer-policy:promote-first|microtasks-first ; Δ16=+cancel-microtask! ; Δ17=replay:full-determinism-config-in-failure-bundle ; Δ18=simplify:replay+replay-schedule(no-opts-merge)
