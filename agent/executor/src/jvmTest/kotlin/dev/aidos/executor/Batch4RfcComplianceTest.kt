package dev.aidos.executor

import dev.aidos.broker.AuditLog
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.EventId
import dev.aidos.kernel.ExecutionWindow
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.Task
import dev.aidos.kernel.TaskId
import dev.aidos.kernel.UserId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end compliance tests for Batch 4 RFCs:
 * - RFC-0004: Event categories, visibility, and subscribe authorization
 * - RFC-0006: CancelRun, PRIORITY_INTERRUPT, and serial session event queue
 * - RFC-0009: INTENT row before effects, idempotency_key, deadline budget, non-fabricated recovery
 * - RFC-0019: Cycle check, TARGETED/IMPLEMENTS/RETRY_OF writers, and retry loop
 */
class Batch4RfcComplianceTest {

    private val nowIso = "2026-11-14T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun openDriver(): app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver {
        val root = Files.createTempDirectory("batch4-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProject(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, pid: String) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) { bindString(0, pid); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
        driver.execute(
            null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)",
            1,
        ) { bindString(0, pid) }
    }

    private fun seedSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        pid: String,
        sid: String,
        state: String = "SLEEPING",
        parentSessionId: String? = null,
        role: String = "DRIVER",
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, parent_session_id, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 's', ?, ?, ?, ?, ?, ?)",
            8,
        ) {
            bindString(0, sid); bindString(1, pid); bindString(2, role); bindString(3, state)
            bindString(4, parentSessionId); bindString(5, nowIso); bindString(6, nowIso); bindString(7, nowIso)
        }
    }

    // ─── RFC-0004 ─────────────────────────────────────────────────────────────

    @Test
    fun `RFC-0004 sessions cannot publish COMMAND events and SIGNAL events are not persisted`() {
        val driver = openDriver()
        val pid = nextId()
        seedProject(driver, pid)
        val store = EventStore(driver)

        assertFailsWith<IllegalArgumentException> {
            store.publish(
                id = nextId(),
                projectId = pid,
                type = "CancelRun",
                category = EventCategory.COMMAND.name,
                source = "session:s1",
                nowIso = nowIso,
                publisherKind = ActorKind.SESSION,
            )
        }

        val receivedSignals = mutableListOf<EventRow>()
        store.addSignalListener { receivedSignals.add(it) }
        val signalSeq = store.publish(
            id = nextId(),
            projectId = pid,
            type = "TokenDelta",
            category = EventCategory.SIGNAL.name,
            source = "session:s1",
            payload = """{"delta":"hi"}""",
            nowIso = nowIso,
        )
        assertNull(signalSeq, "SIGNAL events must not be persisted")
        assertEquals(1, receivedSignals.size)
        assertTrue(store.eventsForProject(pid).isEmpty())
    }

    @Test
    fun `RFC-0004 visibility rules isolate SESSION and PRIVILEGED events from unauthorized subscribers`() {
        val driver = openDriver()
        val pid = nextId()
        val parentSid = nextId()
        val childSid = nextId()
        val otherSid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, parentSid)
        seedSession(driver, pid, childSid, parentSessionId = parentSid, role = "WORKER")
        seedSession(driver, pid, otherSid)

        val store = EventStore(driver)
        store.publish(
            id = nextId(), projectId = pid, type = "GitCommit", category = EventCategory.FACT.name,
            visibility = EventVisibility.PUBLIC.name, source = "git", topic = "git:main", nowIso = nowIso,
        )
        store.publish(
            id = nextId(), projectId = pid, type = "ToolCompleted", category = EventCategory.FACT.name,
            visibility = EventVisibility.SESSION.name, source = "session:$childSid", topic = "session:$childSid", nowIso = nowIso,
        )
        store.publish(
            id = nextId(), projectId = pid, type = "PermissionGranted", category = EventCategory.FACT.name,
            visibility = EventVisibility.PRIVILEGED.name, source = "runtime", topic = "permission:grant", nowIso = nowIso,
        )

        val userEvents = store.eventsForSubscriber(pid, ActorKind.USER, "user-1")
        assertEquals(3, userEvents.size)

        val childEvents = store.eventsForSubscriber(pid, ActorKind.SESSION, childSid)
        assertEquals(listOf("GitCommit", "ToolCompleted"), childEvents.map { it.type })

        val parentEvents = store.eventsForSubscriber(pid, ActorKind.SESSION, parentSid)
        assertEquals(listOf("GitCommit", "ToolCompleted"), parentEvents.map { it.type })

        val otherEvents = store.eventsForSubscriber(pid, ActorKind.SESSION, otherSid)
        assertEquals(listOf("GitCommit"), otherEvents.map { it.type })
    }

    @Test
    fun `RFC-0004 subscribeAuthorized enforces EVENT_SUBSCRIBE capability for external topics`() = runBlocking {
        val driver = openDriver()
        val pid = nextId()
        val sid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid)

        val capMgr = SqliteCapabilityManager(
            projectDriver = driver,
            ids = UuidV7Generator(),
            nowIso = { nowIso },
        )
        val subStore = SessionSubscriptionStore(driver)

        // Own session topic allowed without EVENT_SUBSCRIBE.
        subStore.subscribeAuthorized(
            id = nextId(), sessionId = sid, topicPatterns = listOf("session:$sid"),
            nowIso = nowIso, capabilityManager = capMgr,
        )

        // External topic refused without EVENT_SUBSCRIBE.
        assertFailsWith<IllegalArgumentException> {
            subStore.subscribeAuthorized(
                id = nextId(), sessionId = sid, topicPatterns = listOf("git:*"),
                nowIso = nowIso, capabilityManager = capMgr,
            )
        }

        // Grant EVENT_SUBSCRIBE scoped to git:* and retry.
        capMgr.grant(
            subjectId = sid,
            subjectKind = SubjectKind.SESSION,
            permission = Permission.EVENT_SUBSCRIBE,
            scope = CapabilityScope.Events(listOf("git:*")),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        )
        subStore.subscribeAuthorized(
            id = nextId(), sessionId = sid, topicPatterns = listOf("git:*"),
            nowIso = nowIso, capabilityManager = capMgr,
        )
        assertEquals(2, subStore.forSession(sid).size)
    }

    // ─── RFC-0006 ─────────────────────────────────────────────────────────────

    @Test
    fun `RFC-0006 cancelRun cancels active Run and skips pending tasks`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "RUNNING")
        val events = EventStore(driver)
        events.publish(id = eid, projectId = pid, type = "UserCommand", source = "user", nowIso = nowIso)

        val runId = RunCreator(driver, { nextId() }, { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "do work", platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1", networkAvailable = false,
        )
        // Add a second task that should be SKIPPED when the first task cancels.
        val t2 = nextId()
        driver.execute(
            null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, 1, 'TOOL_CALL', 'second', 'PENDING', '{}')",
            4,
        ) { bindString(0, t2); bindString(1, runId.value); bindString(2, sid); bindString(3, pid) }

        lateinit var executor: SqliteExecutor
        executor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = events,
            idGen = { nextId() },
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task): TaskResult {
                    executor.cancelRun(runId, reason = "user_cancelled")
                    return TaskResult(success = true)
                }
            },
        )

        executor.drive(runId)

        val runState = driver.executeQuery(
            null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1,
        ) { bindString(0, runId.value) }.value
        assertEquals("CANCELLED", runState)

        val t2State = driver.executeQuery(
            null, "SELECT state FROM tasks WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1,
        ) { bindString(0, t2) }.value
        assertEquals("SKIPPED", t2State)
    }

    @Test
    fun `RFC-0006 scheduler queues events for RUNNING session, drains serially, and PRIORITY_INTERRUPT clears queue`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "SLEEPING")

        val events = EventStore(driver)
        val subs = SessionSubscriptionStore(driver)
        subs.subscribe(nextId(), sid, listOf("git:*", "session:$sid"), nowIso = nowIso)
        val scheduler = Scheduler(
            driver = driver,
            events = events,
            subscriptions = subs,
            runCreator = RunCreator(driver, { nextId() }, { nowIso }),
            audit = AuditLog(driver),
            idGen = { nextId() },
            nowIso = { nowIso },
        )

        val e1Id = nextId()
        events.publish(id = e1Id, projectId = pid, type = "GitCommit", source = "git", topic = "git:main", nowIso = nowIso)
        val e1 = events.eventsForProject(pid).single { it.id == e1Id }

        val w1 = scheduler.wake(e1, null, ProjectId(pid), PlatformProfile.DESKTOP, "dev-1", false)
        assertEquals(1, w1.woken.size)

        // Second event arrives while session is RUNNING -> queued, not woken concurrently.
        val e2Id = nextId()
        events.publish(id = e2Id, projectId = pid, type = "GitCommit", source = "git", topic = "git:main", nowIso = nowIso)
        val e2 = events.eventsForProject(pid).single { it.id == e2Id }
        val w2 = scheduler.wake(e2, null, ProjectId(pid), PlatformProfile.DESKTOP, "dev-1", false)
        assertTrue(w2.woken.isEmpty())
        assertEquals(listOf(sid), w2.queued)
        assertEquals(1, scheduler.eventQueue.pendingFor(sid).size)

        // Draining onRunFinished starts a Run for the queued event.
        val nextWoken = scheduler.onRunFinished(sid)
        assertNotNull(nextWoken)
        assertTrue(scheduler.eventQueue.pendingFor(sid).isEmpty())

        // Queue another event, then send PRIORITY_INTERRUPT -> cancels Run and clears queue.
        val e3Id = nextId()
        events.publish(id = e3Id, projectId = pid, type = "GitCommit", source = "git", topic = "git:main", nowIso = nowIso)
        val e3 = events.eventsForProject(pid).single { it.id == e3Id }
        scheduler.wake(e3, null, ProjectId(pid), PlatformProfile.DESKTOP, "dev-1", false)
        assertEquals(1, scheduler.eventQueue.pendingFor(sid).size)

        val interruptId = nextId()
        events.publish(
            id = interruptId, projectId = pid, type = EventTypes.PRIORITY_INTERRUPT,
            category = EventCategory.COMMAND.name, source = "frontend:cli", topic = "session:$sid", nowIso = nowIso,
        )
        val interruptEvent = events.eventsForProject(pid).single { it.id == interruptId }
        val interruptResult = scheduler.wake(interruptEvent, null, ProjectId(pid), PlatformProfile.DESKTOP, "dev-1", false)
        assertEquals(listOf(sid), interruptResult.interrupted)
        assertTrue(scheduler.eventQueue.pendingFor(sid).isEmpty())
    }

    // ─── RFC-0009 ─────────────────────────────────────────────────────────────

    @Test
    fun `RFC-0009 end-to-end crash recovery without fabricated DB state and deadline budget`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "RUNNING")
        val events = EventStore(driver)
        events.publish(id = eid, projectId = pid, type = "UserCommand", source = "user", nowIso = nowIso)

        val runId = RunCreator(driver, { nextId() }, { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "crash mid effect", platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1", networkAvailable = false,
        )

        // Simulated process crash mid-effect: INTENT attempt row is committed before execute() runs,
        // then the runner throws a simulated crash Error (not caught as a normal TaskResult).
        class SimulatedCrashError : Error("kill -9")

        val crashingExecutor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = events,
            idGen = { nextId() },
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task): TaskResult {
                    // Verify INTENT row already exists in SQLite before effect runs!
                    val runningAttempts = driver.executeQuery(
                        null,
                        "SELECT idempotency_key FROM attempts WHERE task_id = ? AND state = 'RUNNING'",
                        mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) },
                        1,
                    ) { bindString(0, task.id.value) }.value
                    assertNotNull(runningAttempts, "INTENT row with idempotency_key must exist before effect")
                    throw SimulatedCrashError()
                }
            },
        )

        try {
            crashingExecutor.drive(runId)
        } catch (_: SimulatedCrashError) {
            // Expected simulated crash
        }

        // Fresh executor recovers and resumes, but with a tight deadline budget first -> stops cleanly.
        val tightWindow = object : ExecutionWindow {
            override fun remainingMillis(): Long = 100L
            override fun permitsLocalInference(): Boolean = true
        }
        val tightExecutor = SqliteExecutor(
            driver = driver, audit = AuditLog(driver), events = events,
            idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task) = TaskResult(success = true)
            },
            executionWindow = tightWindow,
            estimatedStepMillis = 1_000L,
        )
        val report = tightExecutor.recover(ProjectId(pid))
        assertEquals(1, report.runsExamined)
        assertEquals(1, report.runsResumed)
        tightExecutor.drive(runId) // Stops at deadline budget checkpoint

        val stateAfterTight = driver.executeQuery(
            null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1,
        ) { bindString(0, runId.value) }.value
        assertEquals("RUNNING", stateAfterTight)

        // Next window (unbounded) completes the Run.
        val resumingExecutor = SqliteExecutor(
            driver = driver, audit = AuditLog(driver), events = events,
            idGen = { nextId() }, nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task) = TaskResult(success = true)
            },
        )
        resumingExecutor.drive(runId)

        val finalState = driver.executeQuery(
            null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1,
        ) { bindString(0, runId.value) }.value
        assertEquals("COMPLETED", finalState)

        val retryEdges = driver.executeQuery(
            null, "SELECT COUNT(*) FROM execution_edges WHERE edge_kind = 'RETRY_OF'",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) }, 0,
        ).value
        assertEquals(1L, retryEdges, "Resumed attempt after crash must have a RETRY_OF edge")
    }

    // ─── RFC-0019 ─────────────────────────────────────────────────────────────

    @Test
    fun `RFC-0019 cycle check rejects DEPENDS_ON cycles and retry loop respects RetryPolicy and RecoveryClass`() = runBlocking {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId(); val eid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "RUNNING")
        val events = EventStore(driver)
        events.publish(id = eid, projectId = pid, type = "UserCommand", source = "user", nowIso = nowIso)

        val intentId = nextId()
        driver.execute(
            null,
            "INSERT INTO intent_nodes (id, project_id, type, title, created_at, created_by_kind, created_by_id, modified_at, modified_by_kind, modified_by_id) " +
                "VALUES (?, ?, 'GOAL', 'Ship feature', ?, 'USER', 'u1', ?, 'USER', 'u1')",
            4,
        ) { bindString(0, intentId); bindString(1, pid); bindString(2, nowIso); bindString(3, nowIso) }

        val runId = RunCreator(driver, { nextId() }, { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "ship", platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1", networkAvailable = false, targetIntentNodeId = intentId,
        )

        // Update task 0 with a RetryPolicy (maxAttempts = 3, retryOn = TRANSIENT).
        val t1 = driver.executeQuery(
            null, "SELECT id FROM tasks WHERE run_id = ? AND ordinal = 0",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(c.next().let { c.getString(0)!! }) }, 1,
        ) { bindString(0, runId.value) }.value
        driver.execute(
            null,
            "UPDATE tasks SET retry_policy_json = '{\"maxAttempts\":3,\"retryOn\":[\"TRANSIENT\"]}' WHERE id = ?",
            1,
        ) { bindString(0, t1) }

        val t2 = nextId()
        driver.execute(
            null,
            "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
                "VALUES (?, ?, ?, ?, 1, 'TOOL_CALL', 't2', 'PENDING', '{}')",
            4,
        ) { bindString(0, t2); bindString(1, runId.value); bindString(2, sid); bindString(3, pid) }

        var attemptsSeen = 0
        val executor = SqliteExecutor(
            driver = driver,
            audit = AuditLog(driver),
            events = events,
            idGen = { nextId() },
            nowIso = { nowIso },
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task): TaskResult {
                    if (task.id.value == t1) {
                        attemptsSeen++
                        if (attemptsSeen == 1) {
                            return TaskResult(
                                success = false,
                                error = AidosError("net.timeout", ErrorClass.TRANSIENT, "timeout"),
                                recoveryClass = RecoveryClass.IDEMPOTENT,
                            )
                        }
                    }
                    return TaskResult(success = true)
                }
            },
        )

        // Cycle check: t2 DEPENDS_ON t1 is valid; t1 DEPENDS_ON t2 creates a cycle and must reject.
        executor.addDependency(ProjectId(pid), TaskId(t2), TaskId(t1))
        val cycleEx = assertFailsWith<ExecutionCycleException> {
            executor.addDependency(ProjectId(pid), TaskId(t1), TaskId(t2))
        }
        assertEquals(ErrorCodes.EXECUTION_CYCLE_REJECTED, cycleEx.code)

        executor.drive(runId)
        executor.writeImplementsEdge(ProjectId(pid), runId, intentId, confirmed = true, confirmedByKind = "USER", confirmedById = "u1")

        assertEquals(2, attemptsSeen)
        val targetedCount = driver.executeQuery(
            null, "SELECT COUNT(*) FROM execution_edges WHERE from_node_id = ? AND edge_kind = 'TARGETED'",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) }, 1,
        ) { bindString(0, runId.value) }.value
        val implementsCount = driver.executeQuery(
            null, "SELECT COUNT(*) FROM execution_edges WHERE from_node_id = ? AND edge_kind = 'IMPLEMENTS' AND confirmed = 1",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) }, 1,
        ) { bindString(0, runId.value) }.value
        val retryOfCount = driver.executeQuery(
            null, "SELECT COUNT(*) FROM execution_edges WHERE edge_kind = 'RETRY_OF'",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) }, 0,
        ).value

        assertEquals(1L, targetedCount)
        assertEquals(1L, implementsCount)
        assertEquals(1L, retryOfCount)
    }
}
