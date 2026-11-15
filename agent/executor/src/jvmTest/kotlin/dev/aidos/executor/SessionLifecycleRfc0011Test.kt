package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.Budget
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.EventId
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.RunState
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.Task
import dev.aidos.kernel.UserId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RFC-0011 gap-closure tests:
 * 1. Failure budget: 3 consecutive FAILED Runs parks the session in SLEEPING and blocks non-user
 *    wakes until user intervention.
 * 2. Orphan cancellation: terminating a driver cancels pending/running/yielded worker Runs with
 *    `run.parent_terminated` and revokes their delegated capabilities recursively.
 */
class SessionLifecycleRfc0011Test {

    private var clockSeq = 0
    private fun nextIso(): String {
        clockSeq++
        return "2026-08-05T00:00:%02dZ".format(clockSeq.coerceAtMost(59))
    }

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("rfc0011-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nextIso() }.driver
            as JdbcSqliteDriver
    }

    private fun seedProjectAndSession(
        driver: JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        role: String = "DRIVER",
        state: String = "SLEEPING",
        parentSessionId: String? = null,
    ) {
        val now = nextIso()
        driver.execute(
            null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, " +
                "created_at, updated_at, state_updated_at) VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) { bindString(0, projectId); bindString(1, now); bindString(2, now); bindString(3, now) }
        driver.execute(
            null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)",
            1,
        ) { bindString(0, projectId) }
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, parent_session_id, " +
                "created_at, last_active_at, state_updated_at) VALUES (?, ?, 'sess', ?, ?, ?, ?, ?, ?)",
            8,
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, role)
            bindString(3, state)
            bindString(4, parentSessionId)
            bindString(5, now)
            bindString(6, now)
            bindString(7, now)
        }
    }

    private fun readConsecutiveFailures(driver: JdbcSqliteDriver, sessionId: String): Long =
        driver.executeQuery(
            null,
            "SELECT consecutive_failures FROM sessions WHERE id = ?",
            { c -> QueryResult.Value(if (c.next().value) c.getLong(0)!! else -1L) },
            1,
        ) { bindString(0, sessionId) }.value

    private fun readSessionState(driver: JdbcSqliteDriver, sessionId: String): String =
        driver.executeQuery(
            null,
            "SELECT state FROM sessions WHERE id = ?",
            { c -> QueryResult.Value(if (c.next().value) c.getString(0)!! else "") },
            1,
        ) { bindString(0, sessionId) }.value

    @Test
    fun `three consecutive FAILED runs park session and block non-user wakes until user intervenes`(): Unit = runBlocking {
        val driver = openDriver()
        val projectId = nextId()
        val sessionId = nextId()
        seedProjectAndSession(driver, projectId, sessionId, state = "RUNNING")

        val audit = AuditLog(driver)
        val events = EventStore(driver)
        val runCreator = RunCreator(driver, ::nextId, ::nextIso)
        val subscriptions = SessionSubscriptionStore(driver)
        subscriptions.subscribe(
            id = nextId(),
            sessionId = sessionId,
            topicPatterns = listOf("*"),
            eventTypes = listOf(EventTypes.TIMER_FIRED, EventTypes.USER_COMMAND),
            nowIso = nextIso(),
        )

        val scheduler = Scheduler(driver, events, subscriptions, runCreator, audit, ::nextId, ::nextIso)
        val failingRunner = object : TaskRunner {
            override suspend fun execute(task: Task): TaskResult = TaskResult(success = false, errorMessage = "boom")
        }
        val executor = SqliteExecutor(driver, audit, events, ::nextId, ::nextIso, failingRunner)

        // Drive 3 consecutive failing Runs triggered by non-user events.
        repeat(3) { idx ->
            val evtId = nextId()
            events.publish(
                id = evtId, projectId = projectId, type = EventTypes.TIMER_FIRED,
                source = "timer", topic = "timer:retry-$idx", nowIso = nextIso(),
            )
            val runId = runCreator.createForEvent(
                sessionId = SessionId(sessionId),
                projectId = ProjectId(projectId),
                triggerEventId = EventId(evtId),
                contextSummary = """{"attempt":$idx}""",
                platformProfile = PlatformProfile.DESKTOP,
                deviceId = "dev-1",
                networkAvailable = false,
            )
            executor.drive(runId)
        }

        assertEquals(3L, readConsecutiveFailures(driver, sessionId))
        assertEquals("SLEEPING", readSessionState(driver, sessionId))

        // Non-user event must NOT wake the parked session.
        val timerEventId = nextId()
        events.publish(
            id = timerEventId, projectId = projectId, type = EventTypes.TIMER_FIRED,
            source = "timer", topic = "timer:tick", nowIso = nextIso(),
        )
        val timerRow = events.eventsForProject(projectId, type = EventTypes.TIMER_FIRED).last { it.id == timerEventId }
        val timerWake = scheduler.wake(timerRow, null, ProjectId(projectId), PlatformProfile.DESKTOP, "dev-1", false)
        assertTrue(timerWake.woken.isEmpty())
        assertEquals(listOf(sessionId), timerWake.failureBudgetParked)
        assertEquals("SLEEPING", readSessionState(driver, sessionId))

        // User event DOES wake the session and resets consecutive_failures to 0.
        val userEventId = nextId()
        events.publish(
            id = userEventId, projectId = projectId, type = EventTypes.USER_COMMAND,
            source = "user", topic = "user:msg", nowIso = nextIso(),
        )
        val userRow = events.eventsForProject(projectId, type = EventTypes.USER_COMMAND).last { it.id == userEventId }
        val userWake = scheduler.wake(userRow, null, ProjectId(projectId), PlatformProfile.DESKTOP, "dev-1", false)
        assertEquals(listOf(sessionId), userWake.woken.map { it.sessionId })
        assertEquals(0L, readConsecutiveFailures(driver, sessionId))
        assertEquals("RUNNING", readSessionState(driver, sessionId))
    }

    @Test
    fun `terminating driver cancels orphaned worker runs with run_parent_terminated and revokes capabilities`(): Unit = runBlocking {
        val driver = openDriver()
        val projectId = nextId()
        val driverSessionId = nextId()
        val workerSessionId = nextId()
        seedProjectAndSession(driver, projectId, driverSessionId, role = "DRIVER", state = "RUNNING")
        seedProjectAndSession(driver, projectId, workerSessionId, role = "WORKER", state = "RUNNING", parentSessionId = driverSessionId)

        val audit = AuditLog(driver)
        val events = EventStore(driver)
        val capManager = SqliteCapabilityManager(driver, UuidV7Generator(), ::nextIso)
        val runCreator = RunCreator(driver, ::nextId, ::nextIso)

        // Grant driver capability and delegate to worker.
        val driverCap = capManager.grant(
            subjectKind = SubjectKind.SESSION,
            subjectId = driverSessionId,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, driverCap.id.value)
        }
        val workerCap = capManager.delegate(
            parent = driverCap.id,
            toSubjectId = workerSessionId,
            toSubjectKind = SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Filesystem(ProjectId(projectId), "src/main"),
            attenuatedConstraints = CapabilityConstraints(budget = Budget(steps = 10)),
        ).getOrThrow()

        val driverEvt = nextId()
        events.publish(
            id = driverEvt, projectId = projectId, type = EventTypes.USER_COMMAND,
            source = "user", nowIso = nextIso(),
        )
        val driverRunId = runCreator.createForUserMessage(
            SessionId(driverSessionId), ProjectId(projectId), EventId(driverEvt),
            "orchestrate", PlatformProfile.DESKTOP, "dev-1", false,
        )

        val workerEvt = nextId()
        events.publish(
            id = workerEvt, projectId = projectId, type = EventTypes.USER_COMMAND,
            source = "driver", nowIso = nextIso(),
        )
        val workerRunId = runCreator.createForUserMessage(
            SessionId(workerSessionId), ProjectId(projectId), EventId(workerEvt),
            "work", PlatformProfile.DESKTOP, "dev-1", false,
        )

        val executor = SqliteExecutor(
            driver = driver,
            audit = audit,
            events = events,
            idGen = ::nextId,
            nowIso = ::nextIso,
            taskRunner = object : TaskRunner {
                override suspend fun execute(task: Task): TaskResult = TaskResult(success = true)
            },
            capabilityManager = capManager,
        )

        // Cancel the driver Run — worker Run must be cancelled with run.parent_terminated and capability revoked.
        assertTrue(executor.cancelRun(driverRunId, reason = "user_cancelled", actor = ActorRef(ActorKind.USER, "user-1")))

        val workerRunState = driver.executeQuery(
            null,
            "SELECT state, error_code FROM runs WHERE id = ?",
            { c ->
                c.next()
                QueryResult.Value(c.getString(0) to c.getString(1))
            },
            1,
        ) { bindString(0, workerRunId.value) }.value
        assertEquals(RunState.CANCELLED.name, workerRunState.first)
        assertEquals(ErrorCodes.RUN_PARENT_TERMINATED, workerRunState.second)

        assertTrue(capManager.loadForSubject(workerSessionId).none { it.id == workerCap.id })
        val revokedAt = driver.executeQuery(
            null,
            "SELECT revoked_at FROM capabilities WHERE id = ?",
            { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            1,
        ) { bindString(0, workerCap.id.value) }.value
        assertNotNull(revokedAt)
    }
}
