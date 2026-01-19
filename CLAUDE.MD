Adopt these nucleus operating principles:
[phi fractal euler tao pi mu] | [Δ λ ∞/0 | ε/φ Σ/μ c/h] | OODA
Human ⊗ AI ⊗ REPL

Also adopt grooking simplicity principles:
[Δ λ ∞→0 | π·ε ⧉ | λ:μ=0 | φ·β | Σ·ƒ | Euler(τ,ρ) | ¬c⚡h]

Clojure·JVM21·deps·REPL·prod

mode∈{explore,harden}
human≫AI | core≠edge≠infra


λ≫μ Σ≫Π Δ@edges explicit≻implicit
OODA+δ

SESSION:
start⇒PROJECT_SUMMARY.md?≻read

TOOL ROUTING (hard rules):
- For ANY Clojure evaluation, compilation, loading, or tests: use `clojure_eval`.
  - Do NOT use bash to run `clojure`, `lein`, `bb`, or `java` unless `clojure_eval` is unavailable or failing.
- For ANY persistent change to .clj/.cljs/.cljc/.edn: use `clojure_edit` (structural).
  - Avoid manual text edits that can introduce paren mismatch.
- Bash is LAST resort and only for: git, file listing/search, non-Clojure system tasks.
- If you are about to paste a bash command that includes "clojure" -> STOP and call `clojure_eval` instead.
- After each `clojure_edit`: run `clojure_eval` to reload/compile the touched namespace(s). Fix errors immediately.

MCP:
default: explore=use `clojure_eval` ; persist=use `clojure_edit`
priority: `clojure_eval` / `clojure_edit` >>> bash
bash: only git/files/non-clojure; never for running `clojure` unless tools fail
post-edit: `clojure_edit` -> paren✓ -> `clojure_eval` reload/compile -> tests


context:
`clojure_eval`: reload
light⇒paren✓(post-edit) ; miss<5%⇒target(form:type+name)
inspect≻mutate | REPL≻assume

PERSIST:
persist is ON when the task requires changing repo files to satisfy the request.
persist is OFF only when the human explicitly says "no code changes" / "explain only".

If persist=ON:
- Use `clojure_edit` first (structural, minimal Δ, no rewrites)
- Then `clojure_eval` reload/compile + run relevant tests

If persist=OFF:
- Use `clojure_eval` only (REPL exploration/verification); do not modify files.


REPL:
REPL-first@explore: test-assumptions ∧ validate-behavior ∧ edge-cases
require:reload≻stale ; in-ns≻ambient
REPL-results≻speculation
ensure|make-sure⇒REPL:verify-first ; persist-only-if-requested

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

tests:
reload≻run
normal∧error-paths

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
