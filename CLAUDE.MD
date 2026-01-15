Clojure·JVM21·deps·REPL·prod

mode∈{explore,harden}
human≫AI | core≠edge≠infra

λ≫μ Σ≫Π Δ@edges explicit≻implicit
OODA+δ

MCP:
Clojure-eval ≻ Clojure-edit ≻ CLI
structural-edit ≻ find/replace
light⇒paren✓(post-edit)
match-miss<5% ⇒ target(form:type+name)
inspect≻mutate | REPL≻assume

style:
if≻cond(1-branch) ; cond≻if(n>1)
if-let|when-let≻let+if
when≻if(no-else)
cond->|cond->>≻nested-if

let⇐reuse∨clarity ; inline⇐once
->|->>≻temp-bindings

destructure@params≻let
keys(namespaced|plain)≻manual-get

value-tracking≻bool-flags
early-exit≻nesting
nil≻flag-object

nesting=min
threading≻sequence

fn:single-resp ∧ pure≻effectful
return≻side-effect-only
small-fns⇒↓tokens⇒☺

libs:
clj.str≻java.interop
predicates⇒?

REPL:
require:reload≻stale
in-ns≻ambient

tests:
reload≻run
normal∧error-paths

shell:
shell/sh≻CLI
handle-errors
dir≻implicit-cwd
-X:test≻piecemeal
output≠complete
CLI-tools≻reinvent

context:
Clojure-eval:reload
in-ns@work
fq-symbols@ns-boundary

err:
fail-fast@boundary | ex-info≻Exception | ctx+cause | never-silent

weights:
harden: clarity≫perf ; perf≻clarity iff measured
explore: clarity≫perf ; spike-OK ; no-API-break

gates:
tests=PASS via `clojure -M:test -m cognitect.test-runner`
invariants=HOLD

invariants:
API≈stable data≈plain ns≈narrow
determinism∀f (¬time ∧ ¬rand ∧ ¬IO ∧ ¬global-state)
iface≡consistent (naming+patterns+shapes)

summary-fn:
artifact=PROJECT_SUMMARY.md
op∈{create,update}
trigger: start(¬exists) ∨ end(session) ∨ after(δ>small) ∨ after(API-change) ∨ after(new-invariant) ∨ after(tooling-change)

method:
MCP:inspect≻mutate ; summarize≻speculate
encode: key:value ; 1-line/field ; no prose ; no dup ; keep ≤~30 lines

schema:
C A E D B T M S Q R
; ctx arch entry data beh tests mcp style todo recent

example:
C: <purpose>|<domain>|prod
A: core=<..> edge=<..> infra=<..>
E: api=<..> repl=<..>
D: shapes=<..> keys=<..> inv={determinism,iface-consistency}
B: contracts=<..> err=ex-info+ctx+cause
T: cmd=clojure -M:test -m cognitect.test-runner
M: tools={Clojure-edit,Clojure-eval} fallback=light:paren✓ post ; miss<5%⇒form(type+name)
S: if≻cond1 ; cond≻if(n>1) ; ->≻let ; ¬comments ; str≻interop
Q: next-δ=<..>
R: Δ1=<..> ; Δ2=<..>

¬invent ∧ ¬deps ∧ ¬rewrite ∧ ¬hidden-state
