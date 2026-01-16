# PROJECT_SUMMARY.md (encoded)

C: purpose=deterministic-testkit|missionary-async ; elim={real-time,rand,nondet} ; prod
A: core=TestScheduler(state:atom) ; edge={public-api,macros} ; infra={build,clojars,nrepl}
E: ns=de.levering-it.missionary-testkit as mt ; build-ns=build
D: state{:now-ms :next-id :micro-q :timers :trace :driver-thread :schedule-idx :rng-state} ; policy∈{:fifo,:seeded} ; schedule=nil|vec(decisions)
X: model=micro-q(FIFO) + timers(sorted-map[at tie order id]→timer) ; time=virtual(manual) ; strict⇒1-driver-thread
Δ: timers→promote(now>=at)→micro-q ; microtask→may enqueue ; completion→via micro-q ; schedule⇒override(>1 item)
T: cmd=clojure -M:test -m cognitect.test-runner ; reload-before-test=require:reload ; cover≈35t/192a
M: tools={Clojure-edit,Clojure-eval} ; struct≻str ; light⇒paren✓post ; miss<5%⇒form(type+name)
S: if≻cond1 ; cond≻if(n>1) ; if-let|when-let≻let+if ; ->|->>≻lets ; nil≻flag-obj ; ¬comments
V: vars{*scheduler* *is-deterministic*} ; *is-deterministic*=bool(not fn)
E!: ex-data[:mt/kind]∈{::deadlock ::budget-exceeded ::off-scheduler-callback ::illegal-transfer ::no-scheduler ::replay-without-determinism} ; ::idle=return
API:
  make=(make-scheduler [opts])
  read=(now-ms pending trace clock)
  ctrl=(step! tick! advance! advance-to!)
  run=(run start!) ; job=(done? result cancel!)
  vt=(sleep timeout yield) ; integ=(with-determinism collect executor cpu-executor blk-executor)
  interleave=(trace->schedule replay-schedule seed->schedule check-interleaving explore-interleavings selection-gen schedule-gen)
I: inv={determinism∀f, iface-consistency, time↑only, fifo@same-time unless schedule, timers→sorted, completion→micro-q, schedule→select if >1}
DC: det-yes={sleep,timeout,yield,race,join,amb,amb=,seed,sample,relieve,sem,rdv,mbx,dfv,via(cpu|blk),watch(internal),signal(internal)} ; det-no={publisher,via(custom),real-IO,observe(ext),watch(ext)}
TS: strict⇒drive-only-one-thread ; off-thread-cb⇒fail(::off-scheduler-callback) ; atom⇒safe-reads ; with-determinism⇒global-lock(parallel-safe)
via: m/via(cpu|blk)⇒sync-on-driver ; real-exec⇒break-determinism ; cancel⇒interrupt ; InterruptedException possible
INT: step!⇒clear-before+after ; trace:interrupt-cleared{:before :after :id :now-ms} ; scheduler-thread-stable
XP: .cljc ; JVM-only={Executor fns} ; CLJS={with-determinism patches sleep/timeout only, run⇒Promise} ; JVM run⇒value
CFG: deps-alias{:test,:nrepl(7888),:build} ; env{CLOJARS_USERNAME,CLOJARS_PASSWORD} ; mcp-config=.clojure-mcp/config.edn start-nrepl=["clojure","-M:nrepl"]
CMD: repl=clojure -M:nrepl ; build=clojure -T:build ci ; deploy=clojure -T:build deploy
SAFE: read-only={now-ms pending trace clock done?}
MUT: advances={step! tick! advance! advance-to! run start! cancel!}
FS: src=src/de/levering_it/missionary_testkit.cljc ; test=test/de/levering_it/missionary_testkit_test.clj ; build=build.clj
