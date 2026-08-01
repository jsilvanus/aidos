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

This RFC does not specify multi-threaded or distributed scheduling. Aidos is single-threaded within a project.

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

A session progresses through the following states:

```
Created → Ready → Running → Yielding → Sleeping
                                ↓
                              [Work done]
                                ↓
                             Archived
```

- **Created**: A new session is created (via user request or system action). It is in "sleeping" state by default.
- **Sleeping**: The session is dormant. It has subscriptions, but has no active work.
- **Ready**: An event matching the session's subscriptions has arrived. The session is queued to run.
- **Running**: The Scheduler is executing the session's code.
- **Yielding**: The session is waiting for something external (AI response, tool completion). It remains in memory but does not consume CPU.
- **Completion**: The session completes its work. It updates subscriptions and returns to sleep.
- **Archived**: The session is archived (user deletes it or it completes permanently). It is moved to cold storage and is no longer woken.

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

- **Execution Timeout**: A session has a maximum execution time (e.g., 5 minutes per run). If exceeded, the session is interrupted.
- **Memory Limit**: A session has a maximum memory usage. If exceeded, it is killed and archived.
- **Event Limit**: A session can generate at most N new events per run. Prevents infinite event loops.
- **Subscription Limit**: A session can have at most K subscriptions.

These limits are per-project configuration. Default values are reasonable but can be tuned.

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

Pseudocode for the Scheduler's main loop:

```
while True:
  event = event_bus.wait_for_event()
  
  # Find all sessions interested in this event
  matching_sessions = subscription_index.find_matching(event)
  
  # Add to work queue
  for session in matching_sessions:
    work_queue.add(session, priority=session.priority, event=event)
  
  # Execute work queue
  while not work_queue.empty():
    session, event = work_queue.dequeue()
    
    # Check resource limits
    if session.exceeded_memory_limit():
      session.kill()
      event_bus.publish(SessionError(session, "memory limit"))
      continue
    
    # Run the session
    try:
      session.load_state()
      timeout = session.execution_timeout
      session.run(event, timeout)
      session.persist_state()
    except TimeoutError:
      event_bus.publish(SessionError(session, "timeout"))
      session.persist_state()
    except Exception as e:
      event_bus.publish(SessionError(session, str(e)))
      session.persist_state()
    
    # If session yielded (waiting for external work),
    # keep it in memory. Otherwise, it sleeps.
    if session.is_yielding():
      session.wait()
    else:
      session.sleep()
```

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

1. All sessions in the project are loaded (except archived ones).
2. Each session's subscriptions are loaded from storage.
3. Subscriptions are registered in the subscription index.
4. The Event Bus is connected and begins monitoring events.
5. Pending events (from before shutdown) are replayed, waking relevant sessions.
6. The Scheduler main loop begins.

This ensures that no events are lost, even if the runtime crashes.

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
  state: Enum                       # created, sleeping, ready, running, yielding, archived
  priority: Int                     # For fair queuing (higher = sooner)
  created_at: Timestamp
  last_active: Timestamp
  execution_timeout: Duration       # eg. 5 minutes
  memory_limit: Bytes               # eg. 512 MB
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
