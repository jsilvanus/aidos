# RFC-0005: Scheduler

Status: Draft

## Abstract

The Scheduler is the runtime component responsible for waking dormant sessions in response to events. It maintains subscription mappings, monitors the Event Bus, and dispatches work to sessions. The Scheduler ensures that sessions are idle by default, waking only when relevant events occur, and that work is coordinated to avoid conflicts. This RFC establishes the scheduling model that keeps Aidos efficient and responsive.

## Motivation

In a traditional application, the main loop might be:

```
while True:
  check if there is work to do
  if yes, do it
  sleep for a moment
  repeat
```

This is inefficient. The sleep duration is a tradeoff: sleep too little (high CPU), sleep too long (high latency).

Aidos inverts this model. Sessions are dormant by default. They sleep indefinitely, waking only when:
- The user issues a command.
- A timer fires.
- A file changes.
- Git detects new commits.
- An MCP server sends a notification.
- Another session publishes an artifact they are interested in.

This is fundamentally more efficient. No polling, no wasted cycles. Wakeup is instant when something important happens.

The Scheduler is the component that implements this model. It:

1. Tracks which events each session cares about (subscriptions).
2. Monitors the Event Bus for events.
3. Wakes the appropriate session(s) when a matching event occurs.
4. Queues work so sessions are executed fairly and without conflicts.
5. Handles timeouts and prevents runaway sessions.

## Goals

1. **Establish an event-driven scheduling model** where sessions are dormant by default and wake on events.

2. **Define subscription management**: How do sessions express what events they care about?

3. **Specify fairness and ordering**: How does the Scheduler decide which session to wake when multiple events arrive?

4. **Define timeout and resource limits**: Prevent runaway sessions from consuming all resources.

5. **Establish session lifecycle**: Creation, waking, sleeping, archival.

6. **Specify interaction with the Event Bus**: How does the Scheduler monitor events and notify sessions?

## Non-goals

This RFC does not specify distributed scheduling.

It does not define the concurrency model (RFC-0007) or the execution mechanism (RFC-0009). An
earlier version stated "Aidos is single-threaded within a project" and included a blocking main
loop; both are superseded. Runs execute concurrently, including within a project. What
serializes is the *contended resource*, not the project: the working tree and Git index, SQLite
writes, and — usually the real limit on a phone — device-global model inference. Treeless
workers (RFC-0049) contend on none of these and run genuinely in parallel.

This RFC does not address real-time scheduling guarantees. Aidos is not a real-time system; best-effort wake-up is acceptable.

This RFC does not specify the exact implementation of subscriptions (that belongs in the Event Bus RFC or implementation docs).

This RFC does not mandate specific algorithms for priority queuing or fair scheduling. The model is flexible; different implementations can use different strategies.

## Design

### The Scheduling Model

The Scheduler uses an **event-driven, dormant-by-default model**:

1. **Dormancy**: Sessions are asleep by default, consuming no CPU.

2. **Subscriptions**: Each session declares what events it cares about (via the subscription model from RFC-0004).

3. **Monitoring**: The Scheduler monitors the Event Bus for incoming events.

4. **Matching**: When an event arrives, the Scheduler checks which sessions are subscribed to that event.

5. **Wakeup**: Subscribed sessions are marked as "ready to run" and added to the work queue.

6. **Execution**: The Scheduler dequeues a ready session and executes it until it completes (or yields).

7. **Completion/Sleep**: When the session completes its work, it updates its subscriptions and returns to sleep.

This model is efficient because:
- Idle sessions consume no resources.
- Work is triggered by events, not by periodic polling.
- Multiple sessions can coexist without interfering.

### Session Lifecycle

The canonical session state machine is defined in RFC-0017: `CREATED → SLEEPING ⇄ RUNNING →
ARCHIVED`. This RFC previously defined a variant adding `Ready` and `Yielding`, and RFC-0011
defined a third; three definitions of a state exposed on the public Runtime API is three
contracts.

`Ready` and `Yielding` were properties of a *Run*, not of a session:

- "Ready" is a queued wake — it is a work-queue entry, not a session state. A session with a
  queued event remains SLEEPING until the executor drives it.
- "Yielding" is `Run.state = YIELDED` (RFC-0006). Its session stays RUNNING while it holds a
  parked Run.

### Subscriptions and Filtering

As described in RFC-0004, sessions subscribe to events via topic patterns and event types:

```
Session S1:
  - Topics: ["filesystem:/project/src/**", "git:main"]
  - Event Types: [FileModified, GitCommit]

Session S2:
  - Topics: ["ai:model:*"]
  - Event Types: [ModelQueryCompleted]
```

When an event arrives (e.g., `FileModified` on topic `filesystem:/project/src/main.rs`), the Scheduler checks:
1. Does the topic match any subscription? (Yes, matches `filesystem:/project/src/**`)
2. Does the event type match? (Yes, matches FileModified)

If both match, S1 is woken.

### Work Queue and Fairness

The Scheduler maintains a **work queue** of sessions ready to run. When an event matches multiple sessions, all are added to the queue:

```
Event: FileModified on /project/src/main.rs
Matching subscriptions: S1, S2
Action: Add S1 and S2 to work queue

Work queue: [S1, S2]
```

The Scheduler dequeues and runs sessions in a fair manner. Several strategies are possible:

#### FIFO (First In First Out)

Simple: sessions are executed in the order they were added to the queue. Fair, but high-priority sessions may wait.

#### Priority-based

Sessions have priority levels. Higher-priority sessions are run first. Useful for user-initiated commands (high priority) vs. background tasks (low priority).

#### Round-robin

Each session gets a time slice. After the time slice expires, it yields, and the next session runs. Prevents one session from starving others.

#### Weighted Fair Queuing

Sessions have weights. Higher-weight sessions get more CPU time.

The MVP uses FIFO or priority-based scheduling. Weighted scheduling can be added later.

### Execution Model

When the Scheduler runs a session:

1. **Load Context**: The session's state is loaded from storage (previous context, memory, intent graph).

2. **Deliver Event**: The event that woke the session is passed to the session's code.

3. **Perform Work**: The session executes its logic (query AI, call tools, update intent graph, generate artifacts).

4. **Yield or Complete**: The session either:
   - Completes: All work is done. Update subscriptions, persist state, return to sleep.
   - Yields: Waiting for external work (AI response, tool completion). Remain in memory, do not consume CPU.
   - Error: An exception occurred. Log error, archive session or return to sleep.

5. **Persist State**: The session's state is written back to storage.

### Timeouts and Resource Limits

To prevent runaway sessions:

- **Step ceiling**: a Run terminates after `max_steps` agent-loop iterations (default 24,
  RFC-0008). This is the primary control, because it bounds work in the unit that actually
  costs money.
- **Budget**: tokens, cost, and model calls are reserved before spend and settled after, at
  Run, session, and project scope (RFC-0028).
- **Execution timeout**: wall-clock limit per Run; on expiry the Run stops at the next
  checkpoint and is parked as resumable rather than destroyed.
- **Causal depth and wake rate**: events carry a depth; sessions that wake each other beyond
  `maxCausalDepth`, or that exceed the per-project wake rate, trip a circuit breaker
  (RFC-0028). This is what stops cross-session cycles — the old per-run event cap did not,
  because a cycle spanning two sessions never exceeds any single run's budget.
- **Subscription limit**: at most K subscriptions per session.

**Memory limits are not enforced per session.** An earlier version specified a per-session
`memory_limit: Bytes` with "if exceeded, it is killed." On the JVM and on Android there is no
supported way to measure or bound the heap consumed by one coroutine, and on Android the OOM
killer terminates the whole process rather than one worker. The honest controls are the
device-level footprint budgets in RFC-0045 and the storage pressure stages in RFC-0056; a knob
that cannot be enforced is worse than no knob, because it implies a protection that does not
exist.

These limits are per-project configuration with conservative defaults.

If a limit is exceeded, the Scheduler:
1. Logs the violation.
2. Halts the session.
3. Generates an error event.
4. Optionally, archives the session.

### Sleeping and Waking

**Sleeping**:

A session enters sleep when:
- Its work is complete.
- It has no pending events to process.
- It has active subscriptions.

While sleeping, a session:
- Is not in memory (or in minimal memory).
- Does not consume CPU.
- Remains registered in the subscription index.

**Waking**:

A session is woken when:
- An event matching its subscriptions arrives.
- A timeout it subscribed to fires.
- A tool it is waiting for completes.

Waking is instant (from the user's perspective). The session is added to the work queue and will execute shortly.

### Session Scheduling Logic

The scheduler consumes events and dispatches Runs. It does not execute session logic itself —
that is the executor (RFC-0009).

```kotlin
// Per project. Runs on the `main` dispatcher; never blocks (RFC-0007).
suspend fun schedulerLoop(project: ProjectExecutionContext) {
    for (event in project.eventChannel) {                 // FIFO by sequence
        if (!admissionControl.accept(event)) continue     // depth / rate limits (RFC-0028)

        val sessions = subscriptionIndex.authorizedMatches(event)   // RFC-0004 visibility
        for (session in sessions) {
            val run = runStore.createOrQueue(session, event)
            project.scope.launch(dispatchers.session) {
                executor.drive(run.id)      // RFC-0009; locks are taken per effect, not per Run
            }
        }
    }
}
```

Three properties this shape has and the previous blocking loop did not:

- **New events are observed while work is in progress.** The old loop drained its work queue
  inside the event loop, so a user command arriving mid-drain was not seen until the queue
  emptied.
- **A parked Run does not hold the project.** `drive()` returns at a checkpoint when a Run
  yields, releasing the mutex.
- **Nothing is lost on process death.** Queued work is rows, not in-memory queue entries.

Priority is expressed by ordering within `createOrQueue`: user-initiated Runs preempt queued
background Runs at the next checkpoint boundary.

### User Commands

User commands (from frontends) are a special case. When the user issues a command:

1. A `UserCommand` event is generated.
2. The event specifies which session should handle it (or creates a new session).
3. The Scheduler immediately wakes that session (high priority).
4. The session processes the command and reports results back to the frontend.

This ensures responsive, low-latency interaction with the user.

### Cross-Session Coordination

Sessions can coordinate through:

- **Artifacts**: A session publishes an artifact, which other sessions can observe and react to.
- **Events**: A session publishes an event, which triggers other sessions' subscriptions.
- **Shared Resources**: Sessions can read shared resources (architecture docs, code standards), though they cannot directly modify each other's state.

The Scheduler does not provide explicit synchronization primitives (locks, mutexes). Sessions coordinate through higher-level events and artifacts.

### Scheduler Boot-up

When the runtime starts:

1. The project lock is acquired (RFC-0055).
2. Git reconciliation runs if the repository fingerprint moved (RFC-0053).
3. Crash recovery runs for in-flight Runs (RFC-0009).
4. Sessions and subscriptions are loaded and indexed.
5. Pending events are **coalesced, not replayed**: at most one wake per session per topic, and
   events older than the staleness window (default 1 hour) are recorded and discarded.
6. The scheduler loop begins.

Step 5 replaces "pending events are replayed, waking relevant sessions." Replaying a backlog
would produce a wake storm and a burst of model calls on every start — unacceptable on a device
that is off far more than it is on, which is the normal state for the primary mobile use case.
Nothing is lost that matters: the events remain in the log, and the coalesced wake tells the
session that its topic changed, which is what it needed to know.

### Graceful Shutdown

When the runtime shuts down:

1. A `SystemShuttingDown` event is published to all sessions.
2. Active sessions are given time to complete (e.g., 30 seconds).
3. Any yielding sessions are interrupted.
4. Session state is persisted.
5. Subscriptions are saved.
6. The Event Bus is flushed (pending events are persisted).
7. The runtime exits.

On restart, the scheduler will resume from where it left off.

## Data Model

### Subscription (from RFC-0004)

```
Subscription {
  id: UUID
  session_id: UUID
  topic_patterns: List<String>
  event_types: List<EventType>?
  since: Timestamp?                 # For catchup
  is_persistent: Boolean
}
```

### SessionState

```
SessionState {
  id: UUID
  project_id: UUID
  state: Enum                       # CREATED | SLEEPING | RUNNING | ARCHIVED (RFC-0017)
  priority: Int                     # For fair queuing (higher = sooner)
  created_at: Timestamp
  last_active: Timestamp
  execution_timeout: Duration       # wall-clock per Run; stops at a checkpoint
  consecutive_failures: Int         # failure budget (RFC-0011); 3 parks the session
  subscriptions: List<UUID>         # IDs of subscriptions
}
```

### WorkQueueEntry

```
WorkQueueEntry {
  session_id: UUID
  event: Event                      # The event that triggered this entry
  priority: Int
  queued_at: Timestamp
}
```

## Security

The Scheduler enforces security at multiple levels:

1. **Permission Checks**: Before executing a session, verify it has required permissions.
2. **Event Filtering**: Sessions only receive events they subscribed to (no eavesdropping).
3. **Resource Limits**: Prevent sessions from exhausting system resources.
4. **Isolation**: Yield/sleep ensures sessions cannot interfere.

The Scheduler itself is trusted code; it runs with full access. It enforces the security policies on behalf of sessions.

## MVP

The MVP Scheduler includes:

1. **Event-driven Waking**: Sessions wake when matching events arrive.
2. **Subscription Management**: Sessions can subscribe to topics and event types.
3. **Work Queue**: Sessions are queued fairly (FIFO or priority-based).
4. **Timeouts**: Execution timeouts prevent runaway sessions.
5. **Session Lifecycle**: Create, wake, sleep, archive.
6. **Persistence**: Subscriptions and session state are persisted.
7. **User Commands**: User input is high-priority.
8. **Error Handling**: Timeouts and exceptions are caught and logged.

The MVP does not include:
- Weighted fair queuing.
- Memory limits (can be added).
- Complex scheduling algorithms.
- Distributed scheduling.

## Future Work

### Weighted Fair Queuing

Sessions should be able to specify weights. Higher-weight sessions get more execution time:

```
Coding Session (weight=10): Get 10x more time than
Background Archive Task (weight=1)
```

### Memory Pressure

When memory is limited, the Scheduler should:
- Spill sleeping sessions to disk.
- Compress long-term session state.
- Wake sessions that can free memory.

### Latency SLOs

Sessions can declare latency requirements:

```
session.latency_slo = 100.milliseconds

# Scheduler wakes this session within 100ms of an event
```

### Distributed Scheduling

When Aidos spans multiple machines:
- Scheduler coordinates across machines.
- Maintains global event ordering.
- Balances work across compute resources.

### Adaptive Timeouts

The Scheduler should learn from experience:
- Track how long sessions typically take.
- Adjust timeouts based on history.
- Warn if a session is about to exceed its typical time.

### Preemption and Priorities

Support for more nuanced priority levels:
- Real-time (guaranteed latency).
- Interactive (low latency, user-facing).
- Batch (best-effort, no latency requirements).

Sessions can be preempted by higher-priority work.

### Event Coalescing

If many similar events arrive (e.g., 100 FileModified events), coalesce them into a single notification:

```
Instead of:
  [FileModified, FileModified, FileModified, ...]

Send:
  [DirectoryScanned(files_modified=100)]
```

This reduces wake-up storms.
