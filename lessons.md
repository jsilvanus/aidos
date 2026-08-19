# LESSONS — what this project learned the hard way

Durable lessons accumulated while building Aidos. [`PIPELINE.md`](PIPELINE.md) is the roadmap —
what is next. This file is the residue — what we already paid for and should not pay for twice.

Each entry states the rule first, then the incident that produced it. The incident is there so the
rule can be judged, not for its own sake; where the code has moved on, the rule is what survives.
Add to this file when a mistake teaches something transferable. Do not add a status report.

---

## 1. Epistemics: what counts as knowing

**RFC status is not implementation status.** An independent review found RFCs marked Accepted with
no code behind them, one credited as done that was an untested stub, and one credited as unbuilt
that had real tested code. Before implementing against or reporting on an RFC, grep for the
implementation. A status line is a claim, not evidence.

**Accepted is a claim someone checked.** The first acceptance pass marked 45 RFCs Accepted on the
strength of their headers; sampling four found body-level contradictions in three, and 18 were
reverted the same day. Re-accept a document only after reading it end to end.

**An observation is not an inference.** "This fails here, and here is the mechanism" does not
license "and therefore nowhere else". Five failures were recorded as environment-only; when CI
actually ran those modules, three were genuinely environmental and two were real bugs the
environment error had been masking. Do not extend a diagnosis past the scope it was verified
against.

**A green test run is only evidence for the tree that produced it.** A schema change was committed
after a green run that predated it; CI caught what the stale evidence had hidden. Re-run after the
last edit, not before it.

**Check what CI actually runs before trusting a green check.** For months the agent workflow ran
only `:kernel`'s tests; every other module was neither compiled nor tested by CI, while PRs
reported "CI green". The claim was true and much narrower than it read.

**Verify against the tree, not against the story.** Two errors of this shape, both caught late:
attributing a CI failure to the wrong module without reading the log, and reporting a transport
rewrite as necessary when the real cause was a stale duplicated test fixture. Read the failure
before naming its cause.

**"Which RFCs does a milestone name" is not "which RFCs does the MVP depend on".** Every RFC named
by a milestone was Accepted — that check passed and was not the one that mattered. The one that
mattered: which *Draft* documents are cited by `schema/` and `kernel/`, the frozen artifacts.
RFC-0046 was governing `ActorRef` and four DDL columns while unaudited; auditing it found three
schema defects. Grep the frozen artifacts for Draft-RFC citations.

**Ask of any RFC claiming MVP scope: which milestone builds this?** A mechanical sweep found five
with no milestone behind them. The RFCs' MVP sections and the roadmap were written independently
and never reconciled, so each was internally consistent and they disagreed with each other. This
is worth a CI check; none exists.

---

## 2. Applying a decision

**Removing a concept means removing its column.** D30 deleted the MCP `TRUSTED` promotion and
`mcp_servers.trust` sat in canonical DDL holding values that no longer meant anything. A dead
column is worse than a stale paragraph: prose invites doubt, a column is a fact an implementor
will faithfully populate. Grep `schema/` before calling a deletion applied.

**A shape decision is made only once it is made everywhere the shape is read.** D25 settled
structured diff hunks and RFC-0032 said so — while `Preview.Diff` in the kernel still carried
`unified: String`, which would have forced client-side parsing, the exact outcome D25 exists to
prevent. Grep for every type carrying the data.

**The last model call in a deterministic path is the one to look for.** Three separate decisions
each removed a generation step, and a model summarizer survived in RFC-0026's `SUMMARY` kind —
where it was also a taint-laundering channel. When a decision says "compute it, do not generate
it", grep for the other places the same generation happens.

**A scope limit written as a platform fact outlives the platform fact.** D17 said MCP was "desktop
only" because Android cannot spawn a subprocess — true of *stdio*, and it silently became the rule
for MCP entirely, including the transport that works fine on a phone. The tell was that two other
RFCs already modelled HTTP MCP as available everywhere and nobody noticed the corpus disagreeing.
When a decision limits scope, check whether the limit follows from the reason given.

**A constraint recorded from a survey has a shelf life.** Of three constraints the knowledge-engine
survey recorded, one had quietly become false. Re-check against the dependency's current state
before designing around it.

**A banner marking a document known-wrong is deleted by the commit that makes it right.** A banner
that outlives its fix is worse than no banner.

---

## 3. Trust, authority, and approval

**Taint governs content that arrives during a Run; adoption governs content that is there before
it starts.** Anything sitting at high authority in every prompt cannot be handled by taint at all —
taint present from step 0 never clears and permanently degrades every Run. True of instruction
files and of tool catalogs. When new content enters the prompt, ask which side of the Run boundary
it arrives on before reaching for a control.

**Enumerate every field of the thing that reaches the prompt.** D31 was found by a user question,
not by the revision that should have caught it: the revision looked at tool *results* and missed
tool *descriptors*. A tool has a name, a schema, and a paragraph of prose; only two of those had a
rule.

**Hash exactly what the model is shown, and nothing else.** The MCP descriptor hash covers
`(name, description, inputSchema)` because that is what is rendered for the model. Hashing raw
wire bytes was considered and rejected: it fires on cosmetic re-serialization while saying nothing
more about the prose the model reads. The corollary needs a test, because it fails silently — a
field that starts reaching the model or steering policy must join the hash *and* the persisted
approval record in the same change, or a server can change it after adoption for free.

**Admission is not endorsement.** A human approving a tool catalog means the prose was let in, not
that it became trustworthy. It stays fenced, stays out of `resultGuidance`, and results stay
UNTRUSTED.

**Approval keys on the subject, not the act.** A user editing a file and a user reverting a hunk
both pass through the broker as ordinary mutations with audit rows, and neither asks for approval,
because the user is the authority an approval would consult. Only session-subject mutations can
need one.

**Verification, not modality, gates authority.** A user who asked what, where, why, and what-if-I-
refuse and heard structured answers has verified more than someone tapping a card they glanced at.
What survives as never-by-voice is the set that changes the *authority envelope* rather than
exercising it — egress, tainted Runs, new grants — because a structured readback cannot verify
those.

**`reversible` is not `RecoveryClass`.** `RecoveryClass` asks whether an effect can be re-executed
after a crash; `reversible` asks whether the user can get their work back. Discarding uncommitted
changes is in-project, untainted, and perfectly re-runnable — it satisfied every clause of the
benign class and would have been approvable by one spoken word. Answer both questions separately.

**"Localhost" is not one threat model across profiles.** A loopback exemption reasonable on desktop
is a credential-disclosure path on Android, where any app holding `INTERNET` can bind or connect to
a loopback port and the socket carries no peer identity. When a rule leans on *local means safe*,
check what "local" authenticates on each profile.

**Tool descriptions are two halves.** How to *call* a capability, and how to *read* its result —
thresholds, caveats, what a citation should look like. The second is runtime-authored and TRUSTED,
emitted with the result; a tool never supplies its own. An MCP server writing its own
interpretation guidance would be an UNTRUSTED subject telling the model how to weigh its own
evidence.

**Reach for a query before reaching for a model.** The Run Summary is a SQL projection over
`runs`/`tasks`/`attempts` — instant, offline, checkable against the audit trail. Asking a model to
summarize its own Run would violate D6, cost ten seconds at a two-second interaction, and park with
no foreground service in exactly the eyes-free case that motivated it.

**Blob-hash identity keeps paying.** Introduced so branch switching invalidates no knowledge index,
it also gives an instruction set an exact identity — the hash of its `(filename, blob hash)` pairs —
and is the same identity a diff hunk needs. If a subsystem needs to know whether project content
changed, reach for the blob hash before writing a cache-invalidation scheme.

---

## 4. Kotlin Multiplatform and Gradle

**The test task is `jvmTest`, not `test`.** These are KMP modules with a `jvm()` target;
`:executor:test` does not exist.

**Use `--continue`.** `gradle jvmTest` stops at the first failing module, so one red module masks
everything after it. A module's tests cannot compile until its main source does, so fixing one
break routinely reveals the next — enumerate them all in one run.

**`allWarningsAsErrors` is on in 18 modules.** `kernel` plus `api`, `broker`, `capability`, `cli`,
`daemon`, `executor`, `filesystem`, `git`, `http`, `identity`, `knowledge`, `lock`, `mcp-broker`,
`mcp-core`, `mcp-policy`, `settings`, `storage`. "Unnecessary non-null assertion", "No cast
needed", and override-parameter-name mismatches are build failures there, not advice.

**An `implementation(...)` dependency is never visible downstream, even transitively.** "Unresolved
reference" for a package that a dependency-of-a-dependency definitely has on its own classpath
means the owning module used `implementation`, not `api`. Re-declare the coordinate in the
consuming module; do not chase a phantom missing dependency.

**`jvmMain` is not visible to `androidMain`; only `commonMain` is shared.** Platform-neutral code
placed in `jvmMain` back when a module had only a `jvm()` target breaks the moment an Android
source set references it. And `gradle jvmTest` passing after adding `androidTarget()` proves
nothing about whether `androidMain` can see what it imports — that needs the Android SDK or a
manual source-set check.

**Gradle evaluates every subproject's build script even for a single-module task.** One module
pinning a plugin version that conflicts with the root blocked every Gradle command project-wide.

**Verify a dependency coordinate exists before believing an error about it.** `io.ktor:ktor-client-sse`
does not exist (Central returns `numFound: 0`); the artifact is `io.ktor:ktor-sse`. Worse:
`de.kherud:llama-java:0.3.2` never existed at any version, and the adapter written against it also
imported a sub-package that never existed and called a method that was never in the library's API —
the code was written against a fictional API that merely resembled the real one. Check the registry
index, not your memory of it.

**A second copy of a test fixture will go stale, and the failure will not name it.** `mcp-broker`
kept its own copy of the stdio fixture; when the protocol requirements tightened, only `mcp-core`'s
copy was fixed and every `McpTool` call silently hit a 60-second timeout. Point at one file
(`resources.srcDir(project(":other").projectDir.resolve("src/jvmTest/resources"))`) instead.

**`resources.srcDir` may point outside the module's own directory.** `../schema` is read as
classpath resources with no copy step — a build-time inclusion, never a duplicated source file.

**A literal `/*` inside a KDoc block opens a nested comment.** Kotlin's block comments nest, so a
doc comment quoting a glob or path like `filesystem:/project/src/*` fails with "Unclosed comment"
at a location nowhere near the cause. Before writing a KDoc that quotes a path-like string, grep
the comment text for `/*`.

---

## 5. Persistence and durable execution

**A foreign key minted before its row exists is a real ordering hazard.** A task id is minted by
the runner so a dependent row can point at it, but the task row does not exist until the executor
inserts it — writing the dependent row eagerly hits `SQLITE_CONSTRAINT_FOREIGNKEY` immediately.
The fix is an `afterInsert: () -> Unit` hook the insert calls right after each row lands, still
inside the same transaction. Anything that appends a task and also writes a row referencing it
should reach for this.

**A bare `SqlDriver` has no public transaction API.** `driver.newTransaction()` pairs with a
`protected fun endTransaction`, reachable only through a `Transacter` subclass. Use
`private val transacter = object : TransacterImpl(driver) {}`, then `transacter.transaction { ... }`
— it already rolls back and rethrows on an exception from its body.

**`JdbcSqliteDriver` opens a new connection per call, so session PRAGMAs set after construction do
not survive.** `synchronous` read back as SQLite's compiled default because the read happened on a
different connection than the write. Pass `SQLiteConfig` → `Properties` into the driver constructor.
`journal_mode=WAL` happens to work either way because WAL is persisted in the file — which is what
made the bug easy to miss. When wiring a per-connection setting through a per-call driver, verify
by reading it back through the same driver object.

**`org.sqlite.SQLiteConfig` needs an explicit direct dependency on `org.xerial:sqlite-jdbc`** —
the SQLDelight driver depends on it only at runtime.

**Durability pragmas are storage-engine behavior, not schema DDL.** What a real runtime gets comes
from the driver's `SQLiteConfig`, uniformly, regardless of what a given schema file's PRAGMA lines
say. Don't try to reconcile the two; they aren't the same mechanism.

**Each database versions independently, so each needs its own `migration_history`.** It existed in
`project.sql` only until the migration runner surfaced the gap.

**Never hand-write JSON for a sealed/polymorphic `@Serializable` type in a test.** Subclasses
without `@SerialName` use the fully-qualified class name as the discriminator, so hand-written JSON
silently fails to decode — and if production correctly skips unparseable rows, the test failure
points at the wrong layer. Construct the real object and encode it.

**A `Flow` built from a blocking-I/O `flow {}` builder does not stop when its collector does.**
`BufferedReader.readLine()` is not a suspension point, so cancellation never reaches it — a
`take(1)` collector let two events through before the read wedged forever. `Job.invokeOnCompletion`
did not fix it either, because the abort signal reaches a `flowOn`-wrapped producer asynchronously.
What works: `callbackFlow`, the blocking read loop on its own daemon thread pushing via `trySend`,
and the socket closed only in `awaitClose` — whose contract is that it runs exactly once for every
way collection can end.

---

## 6. Protocols and external systems

**Adopting a conformant client exposes your own non-conformance.** Migrating to the official MCP
SDK surfaced four spec violations in fixtures we had written and believed for months: `initialize`
omitting REQUIRED `protocolVersion` and `capabilities`, replies sent to notifications, `"error":
null` on success where JSON-RPC requires the member absent, and JSON-RPC parsing attempted on
`GET`/`DELETE`. Each surfaced as a timeout or a closed socket, never as a protocol error — which is
why they were slow to find.

**A handler that throws is reported as a transport failure.** A throw out of a `com.sun.net.httpserver`
handler closes the socket, and the client says "the server prematurely closed the connection" —
nothing about the method or the parse that actually failed. When a transport error names no cause,
suspect the peer's error handling before suspecting the transport.

**Read the dependency, not its README.** Four open questions sent to a dependency's PR as a review
comment found two real defects — a missing cooperative cancellation and an in-process variable that
under-reports after process death — both visible only in the source. They were fixed upstream within
the hour.

**Units are part of a type's contract.** `RequestOptions.timeout` is a `Duration`; passing a raw
millisecond `Long` meant nanoseconds, so every request effectively had no timeout. Nothing failed
loudly.

**Preserve the distinction between "the tool failed" and "the transport failed".** An SDK that
throws on a JSON-RPC error response will collapse the two if you let it: the model is told a server
is unreachable when it merely rejected the call. Map the error response back to a result; let only
connection-closed and timeout propagate.

---

## 7. Working practice

**Stage by path, and read the diffstat before committing.** `git add <dir>` swept 539 unreviewed
lines from a concurrently-running agent into an unrelated commit; later, a staged deletion rode
along into a commit about something else. Path-specific staging is necessary and not sufficient —
check `git diff --stat` between staging and committing.

**A correction supersedes rather than erases.** A false status line — G3 was once marked PASSED
without any device having run — is corrected by a dated entry that says what was wrong, not by a
quiet edit. Deleting the claim hides that it was ever believed.

**Name what is not done.** Every scope cut in this project that survived contact was one written
down explicitly ("still not wired into the broker", "requires real hardware") rather than implied
by silence. The audits found the opposite pattern repeatedly: correctly-designed logic wired to a
stub, reported as a finished milestone.

**Amend the RFC before departing from it**, in its own commit, not alongside the code. Amendments
to RFC-0031 are marked sections inside the RFC, not separate files — a second document competing
with the first is how a corpus starts disagreeing with itself.

**When a design question is genuinely the project owner's, stop and ask.** Two candidate designs
for the same mechanism were written up rather than one being guessed at, and the owner picked. The
alternative — building both, or building the wrong one convincingly — costs more than the wait.

**The architecture phase is over. Resist reopening it.** The next design question that arises
during implementation should be answered by amending one RFC in one commit and then continuing —
not by a new document, and not by a review.
