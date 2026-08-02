# RFC-0007: Concurrency Model

Status: Draft

## Abstract

This RFC defines the threading and async concurrency model for the Aidos KMP runtime. It specifies which operations execute on which threads, how Kotlin coroutines are used for async work, which subsystems serialize access to shared state, and what isolation guarantees are provided between projects, sessions, and background services.

## Motivation

The Aidos runtime performs several categories of concurrent work:

- User input from one or more frontends
- Session execution (AI calls, tool invocations, event processing)
- Background indexing (Knowledge Engine)
- Filesystem watching
- Scheduler timers
- MCP server notifications
- Git operations (potentially long-running)

Without a defined concurrency model, different subsystems will make incompatible assumptions about thread safety. This leads to subtle races, deadlocks, and state corruption, especially during session recovery and cross-session event delivery.

The concurrency model must be both correct and practical. "Correct but impractical" (e.g., actors for everything) fails. "Practical but wrong" (e.g., global mutable state with locks) fails. This RFC defines the right balance for a local-first, single-user runtime implemented in Kotlin Multiplatform.

## Goals

1. Define the thread pool structure and coroutine dispatcher hierarchy.
2. Define which operations serialize per-project and which can run in parallel.
3. Define how frontends interact concurrently with the runtime.
4. Define how long-running background operations (indexing, git) avoid blocking session work.
5. Define how the Event Bus delivers events with correct ordering guarantees.
6. Define memory visibility guarantees for shared state.

## Non-goals

This RFC does not define remote execution or distributed concurrency.
It does not define multi-user or multi-device concurrency.
It does not define plugin isolation (RFC-0043).
It does not specify thread counts or pool sizes (those are runtime configuration).

## Design

### Coroutine-First Architecture

The Aidos KMP runtime is built on Kotlin Coroutines throughout. Blocking I/O and long-running operations are wrapped in coroutine-friendly adapters. No subsystem holds a thread indefinitely. This is the foundational rule.

All async work is modeled as `suspend` functions. Callbacks and futures from external libraries (SQLite drivers, Git bindings) are wrapped in `suspendCancellableCoroutine` adapters.

### Dispatcher Hierarchy

The runtime maintains four dispatchers:

```
RuntimeDispatchers
  ├── main        — UI-facing API calls, command dispatch, event publication
  ├── session     — Session execution, AI calls, tool invocations (coroutine-based)
  ├── io          — SQLite reads/writes, filesystem operations, network I/O
  └── background  — Knowledge Engine indexing, Git operations, cleanup tasks
```

**`main` dispatcher**: Single-threaded. All inbound frontend commands are received here. All outbound events are published here. State machines (project lifecycle, session wakeup/sleep) advance here. No blocking operations are permitted on this dispatcher.

**`session` dispatcher**: A bounded thread pool (default: min(4, CPU cores)). Session Runs execute here as coroutines. Each Run is a coroutine launched on this dispatcher. Multiple projects' sessions can execute in parallel. Within a single project, only one session Run executes at a time (enforced by the per-project session lock — see below).

**`io` dispatcher**: An unbounded thread pool for I/O operations. SQLite reads, SQLite writes, filesystem reads and writes, and network calls execute here. The io dispatcher is never used for business logic — only for I/O adapters.

**`background` dispatcher**: A low-priority bounded pool for work that must not impact session responsiveness: Knowledge Engine indexing, background Git status, telemetry flushing, export operations.

### Per-Project Session Lock

Within a project, session Runs execute serially. This is enforced by a `Mutex` held per project.

```kotlin
class ProjectExecutionContext(val projectId: UUID) {
    val sessionMutex = Mutex()
    // ... other per-project state
}
```

When a session Run begins, it acquires `sessionMutex`. It releases the mutex only when:
- The Run completes (COMPLETED, FAILED, CANCELLED)
- The Run yields at a safe serialization point (awaiting an async operation)

While a Run holds the mutex, no other session Run in the same project can start. When the Run yields, the mutex is released, allowing another Run to proceed. When the yielded Run is resumed, it re-acquires the mutex before continuing.

This gives the semantics of a "mostly single-threaded" project with cooperative multitasking — the design intent of the original Scheduler RFC — while not blocking the thread.

### SQLite Single-Writer Discipline

Each project has exactly one active SQLite write connection. All writes to a project's database go through the `ProjectWriteContext`, which executes them serially on the `io` dispatcher.

Read connections may be multiplexed (SQLite WAL mode enables concurrent reads). But all writes are serialized.

```kotlin
class ProjectWriteContext(val projectId: UUID) {
    private val writeMutex = Mutex()
    
    suspend fun <T> write(block: suspend (SQLiteConnection) -> T): T {
        return writeMutex.withLock {
            withContext(dispatchers.io) {
                block(writeConnection)
            }
        }
    }
}
```

Reads are not serialized — they use a connection pool that takes advantage of WAL mode's snapshot isolation.

### Event Bus Ordering

The Event Bus delivers events with the following ordering guarantees:

1. **Within a project**: Events are published to a project-scoped channel. The channel is processed in FIFO order by a single coroutine on the `main` dispatcher. This ensures that within a project, events are observed in publication order by all subscribers.

2. **Cross-project**: No ordering guarantee. Events from different projects are independent.

3. **Causality**: An event's `causal_event_id` field records the triggering event. Observers can reconstruct causal chains from the audit log, even though delivery order may not reflect wall-clock causality across projects.

The Event Bus does not guarantee that all subscribers observe an event before the next event is processed. Slow subscribers receive events in order, but may lag behind fast subscribers.

### Frontend Concurrency

Multiple frontends may connect to the same runtime simultaneously. The Runtime API (RFC-0052) provides a connection-oriented interface.

Each frontend connection has its own request/response coroutine. Frontend commands arrive at the `main` dispatcher. The runtime processes commands in the order received from each connection. Commands from different connections are multiplexed without ordering guarantee between connections.

Frontends receive event stream subscriptions. Each subscription delivers events to the subscribing frontend independently. A slow frontend does not block other frontends or the runtime's event publication.

### Knowledge Engine Indexing

The Knowledge Engine runs indexing work on the `background` dispatcher. Indexing is triggered by:

- Project open
- Filesystem change events (debounced)
- Git commit events

Indexing is structured as a series of short, cancellable coroutines, not a single long-running blocking job. Each indexing "tick" processes a batch of files and yields, allowing higher-priority work to proceed.

```kotlin
suspend fun indexProject(projectId: UUID) {
    val files = listProjectFiles(projectId)
    files.chunked(BATCH_SIZE).forEach { batch ->
        ensureActive()  // check for cancellation
        processBatch(batch)
        yield()  // yield to allow session work to proceed
    }
}
```

If a session Run is waiting for the Knowledge Engine to respond to a query, the session coroutine suspends and the query is fulfilled by whatever index state exists at that moment (a snapshot). Indexing does not block queries; it updates the index in the background.

### Git Operations

Git operations vary in duration from milliseconds (status, diff) to minutes (clone, fetch with large histories). All Git operations execute on the `io` dispatcher, wrapped as suspend functions.

Long-running Git operations (fetch, push, large clone) should be launched as background coroutines. The session requesting the operation yields and subscribes to a completion event. The Git operation publishes a completion event when done.

Git operations that modify project-tracked files should acquire the per-project session lock before writing, to prevent races between Git-managed files and session file writes.

### Cancellation Propagation

Kotlin coroutines support structured cancellation. All work in the runtime is launched within a coroutine scope hierarchy:

```
RuntimeScope
  └── ProjectScope(projectId)
       └── SessionScope(sessionId)
            └── RunScope(runId)
                 ├── AiCallCoroutine
                 └── ToolCallCoroutine
```

Cancelling a RunScope cancels the AI call and tool call coroutines it contains. Cancelling a SessionScope cancels all in-flight Runs. Cancelling a ProjectScope cancels all sessions within it.

This means the cancellation semantics from RFC-0006 are implemented naturally: when a user cancels a Run, the runtime cancels its RunScope, and all coroutines within it receive a CancellationException.

### Memory Visibility

Kotlin coroutines on JVM (and KMP targets with native memory model) do not guarantee memory visibility across coroutine dispatchers without explicit synchronization. The following rules apply:

1. Session state accessed from multiple coroutines must be read and written inside the per-project session mutex.
2. Event Bus state (subscriber registry, event queue) is owned by the `main` dispatcher and accessed only there.
3. SQLite state is owned by the `ProjectWriteContext` mutex and accessed through it.
4. Immutable-once-loaded data — project metadata, tool descriptors, model descriptors — is safe
   to read from any dispatcher.
5. **Capabilities are not in category 4.** They are revocable, and a cached capability is valid
   only while its `revocation_epoch` matches the project's current epoch (RFC-0018). Every
   validation compares epochs; a mismatch forces a re-read from SQLite.

Rule 5 corrects an earlier version of this RFC, which listed "capability definitions" as
immutable-once-loaded and safe to read without synchronization. That directly contradicted
RFC-0018's guarantee that revocation takes effect immediately, and the contradiction resolved
in favour of the cache — meaning a revoked capability could continue to authorize work. Epoch
comparison makes staleness detectable rather than depending on an invalidation message being
delivered.

### Android Specifics

Android is the first target, so its constraints shape the model rather than being handled as
exceptions. See RFC-0049 for the full platform profile and RFC-0009 for the execution model
that makes these constraints survivable.

**Dispatchers.** The runtime's `main` dispatcher is a dedicated single thread, *not* the Android
main (UI) thread. Binding runtime state machines to the UI looper would couple runtime progress
to UI lifecycle and make every runtime operation a jank risk. The UI thread is a client of the
Runtime API like any other.

**Execution windows.** Android does not grant long uninterrupted execution:

- Interactive Runs execute in a **foreground service** with a user-visible ongoing notification
  and a declared FGS type. This is a user-consented, visible mode of operation, not a
  background trick.
- The executor carries a **deadline budget** and stops cleanly at a checkpoint when the
  remaining window cannot cover the next step (RFC-0009). It never starts work it cannot
  finish.
- Eviction is routine, not exceptional. A killed service is indistinguishable from a pause:
  recovery resumes from the last committed checkpoint.

**Deferred work.** The `background` dispatcher uses `WorkManager` for indexing, compaction, and
cleanup. `WorkManager` periodic work has a 15-minute floor and no timing guarantee, and exact
alarms require special-access permission. Therefore **no session semantics may depend on a
timer firing at a precise moment** (RFC-0044). Timers on MOBILE are best-effort hints.

**Boot behaviour.** The runtime does not replay a backlog of stale events on start. Pending
events are coalesced per session and topic, and events older than the staleness window are
recorded and discarded (RFC-0028). A phone that was off overnight must not wake and spend
tokens catching up on file-change notifications.

**Concurrency, honestly.** On MOBILE the `session` dispatcher is bounded to a single thread. A
phone has neither the memory headroom to run several model-bearing Runs concurrently nor a
reason to: the user is watching one thing.

### Desktop Specifics

On desktop (JVM via Compose Multiplatform), the main thread is the UI event loop. The runtime's `main` dispatcher uses a dedicated non-UI thread to avoid blocking the Compose render loop.

Multiple windows may share a single runtime instance. Each window connects as a separate frontend client via the Runtime API.

## Data Model

The concurrency model does not introduce new persistent data structures. It defines operational structure:

```kotlin
// Core dispatcher bundle injected throughout the runtime
class RuntimeDispatchers(
    val main: CoroutineDispatcher,
    val session: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val background: CoroutineDispatcher
)

// Per-project execution context
class ProjectExecutionContext(
    val projectId: UUID,
    val sessionMutex: Mutex,
    val writeContext: ProjectWriteContext,
    val scope: CoroutineScope
)
```

## Security

The concurrency model itself does not introduce new security boundaries — those are defined by the capability model (RFC-0018). However:

- Per-project coroutine scopes prevent cross-project state leakage through coroutine locals.
- The session mutex prevents a fast session from interleaving with a slow session's partial state.
- Cancellation of RunScopes prevents leaked coroutines that might continue accessing capabilities after a session is revoked.

## MVP

The MVP implements:

1. Four dispatchers (main, session, io, background) using KMP coroutines.
2. Per-project session mutex for serial Run execution within a project.
3. Per-project SQLite write serialization via ProjectWriteContext.
4. In-process FIFO Event Bus using a project-scoped Channel.
5. Structured cancellation via coroutine scope hierarchy.
6. Android: foreground service for active session work.
7. Desktop: dedicated dispatcher thread separate from Compose main thread.

The MVP does not implement:
- Backpressure on the Event Bus for slow subscribers.
- Adaptive batch sizing for Knowledge Engine indexing.
- Cross-platform WorkManager integration for deferred background work.

## Future Work

Backpressure handling for frontends that cannot consume the event stream at the production rate.

Priority queue for the session dispatcher, allowing interactive sessions to preempt background sessions.

Distributed session execution across multiple machines (long-term).

Performance monitoring per dispatcher to detect scheduling starvation.
