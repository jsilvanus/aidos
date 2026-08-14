package dev.aidos.executor

import dev.aidos.broker.AuditLog
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC-0005's wake-to-Run wiring: [Scheduler.wake] acting on [SchedulerMatcher]'s pure decision —
 * transitioning a matched, `SLEEPING` session to `RUNNING` and creating its `PENDING` Run.
 */
class SchedulerTest {

    private val nowIso = "2026-08-09T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("scheduler-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProject(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String) {
        driver.execute(null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
    }

    private fun seedSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        state: String = "SLEEPING",
    ) {
        driver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', ?, ?, ?, ?)", 6
        ) {
            bindString(0, sessionId); bindString(1, projectId); bindString(2, state)
            bindString(3, nowIso); bindString(4, nowIso); bindString(5, nowIso)
        }
    }

    private fun sessionState(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, sessionId: String): String? =
        driver.executeQuery(null, "SELECT state FROM sessions WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, sessionId) }.value

    private fun scheduler(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver) = Scheduler(
        driver = driver,
        events = EventStore(driver),
        subscriptions = SessionSubscriptionStore(driver),
        runCreator = RunCreator(driver, idGen = { nextId() }, nowIso = { nowIso }),
        audit = AuditLog(driver),
        idGen = { nextId() },
        nowIso = { nowIso },
    )

    private fun publishGitCommit(store: EventStore, projectId: String, causalDepth: Int = 0): EventRow {
        val id = nextId()
        store.publish(
            id = id, projectId = projectId, type = EventTypes.GIT_COMMIT, source = "git",
            topic = "git:master", causalDepth = causalDepth, nowIso = nowIso,
        )
        return store.eventsForProject(projectId, type = EventTypes.GIT_COMMIT).last { it.id == id }
    }

    @Test
    fun `a matching subscription wakes a sleeping session with a SessionWoken event and a new run`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "SLEEPING")
        SessionSubscriptionStore(driver).subscribe(
            id = nextId(), sessionId = sid, topicPatterns = listOf("git:*"),
            eventTypes = listOf(EventTypes.GIT_COMMIT), nowIso = nowIso,
        )
        val eventStore = EventStore(driver)
        val event = publishGitCommit(eventStore, pid)

        val result = scheduler(driver).wake(
            event = event, sourceSessionId = null, projectId = ProjectId(pid),
            platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )

        assertEquals(1, result.woken.size)
        assertEquals(sid, result.woken.single().sessionId)
        assertEquals("RUNNING", sessionState(driver, sid))

        val wokenEvents = eventStore.eventsForProject(pid, type = EventTypes.SESSION_WOKEN)
        assertEquals(1, wokenEvents.size)
        assertEquals(event.id, wokenEvents.single().causality)

        val runState = driver.executeQuery(null, "SELECT state FROM runs WHERE id = ?",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, result.woken.single().runId) }.value
        assertEquals("PENDING", runState)
    }

    @Test
    fun `a non-matching subscription does nothing`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "SLEEPING")
        SessionSubscriptionStore(driver).subscribe(
            id = nextId(), sessionId = sid, topicPatterns = listOf("filesystem:/**"), nowIso = nowIso,
        )
        val eventStore = EventStore(driver)
        val event = publishGitCommit(eventStore, pid)

        val result = scheduler(driver).wake(
            event = event, sourceSessionId = null, projectId = ProjectId(pid),
            platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )

        assertTrue(result.woken.isEmpty())
        assertEquals("SLEEPING", sessionState(driver, sid))
        assertTrue(eventStore.eventsForProject(pid, type = EventTypes.SESSION_WOKEN).isEmpty())
    }

    @Test
    fun `self-wake refusal writes an audit row and does not wake the session`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "SLEEPING")
        SessionSubscriptionStore(driver).subscribe(
            id = nextId(), sessionId = sid, topicPatterns = listOf("git:*"),
            eventTypes = listOf(EventTypes.GIT_COMMIT), selfWake = false, nowIso = nowIso,
        )
        val eventStore = EventStore(driver)
        val event = publishGitCommit(eventStore, pid)

        val result = scheduler(driver).wake(
            event = event, sourceSessionId = sid, projectId = ProjectId(pid),
            platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )

        assertTrue(result.woken.isEmpty())
        assertEquals(listOf(sid), result.selfWakeRefused)
        assertEquals("SLEEPING", sessionState(driver, sid))

        val auditRows = AuditLog(driver).rowsForProject(pid)
        assertEquals(1, auditRows.size)
        assertEquals("WakeRefused", auditRows.single().kind)
        assertEquals(sid, auditRows.single().actorId)
    }

    @Test
    fun `an already running session is not double-woken`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "RUNNING")
        SessionSubscriptionStore(driver).subscribe(
            id = nextId(), sessionId = sid, topicPatterns = listOf("git:*"),
            eventTypes = listOf(EventTypes.GIT_COMMIT), nowIso = nowIso,
        )
        val eventStore = EventStore(driver)
        val event = publishGitCommit(eventStore, pid)

        val result = scheduler(driver).wake(
            event = event, sourceSessionId = null, projectId = ProjectId(pid),
            platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )

        assertTrue(result.woken.isEmpty())
        assertEquals(listOf(sid), result.alreadyRunning)
        assertEquals("RUNNING", sessionState(driver, sid))
        // SessionWoken is still published -- an honest record a wake was attempted -- but no run.
        assertEquals(1, eventStore.eventsForProject(pid, type = EventTypes.SESSION_WOKEN).size)
        val runCount = driver.executeQuery(null, "SELECT COUNT(*) FROM runs",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0
        ) {}.value
        assertEquals(0L, runCount)
    }

    @Test
    fun `causal depth ceiling refusal writes an audit row and creates no run`() {
        val driver = openDriver()
        val pid = nextId(); val sid = nextId()
        seedProject(driver, pid)
        seedSession(driver, pid, sid, state = "SLEEPING")
        SessionSubscriptionStore(driver).subscribe(
            id = nextId(), sessionId = sid, topicPatterns = listOf("git:*"),
            eventTypes = listOf(EventTypes.GIT_COMMIT), nowIso = nowIso,
        )
        val eventStore = EventStore(driver)
        // The wake's own SessionWoken publish is causalDepth = event.causalDepth + 1, so seeding
        // the trigger event at the ceiling itself pushes the wake one past it.
        val event = publishGitCommit(eventStore, pid, causalDepth = EventStore.MAX_CAUSAL_DEPTH)

        val result = scheduler(driver).wake(
            event = event, sourceSessionId = null, projectId = ProjectId(pid),
            platformProfile = PlatformProfile.DESKTOP, deviceId = "dev-1", networkAvailable = false,
        )

        assertTrue(result.woken.isEmpty())
        assertEquals(listOf(sid), result.depthCeilingRefused)
        assertEquals("SLEEPING", sessionState(driver, sid))
        assertTrue(eventStore.eventsForProject(pid, type = EventTypes.SESSION_WOKEN).isEmpty())

        val auditRows = AuditLog(driver).rowsForProject(pid)
        assertEquals(1, auditRows.size)
        assertEquals("WakeRefused", auditRows.single().kind)
    }
}
