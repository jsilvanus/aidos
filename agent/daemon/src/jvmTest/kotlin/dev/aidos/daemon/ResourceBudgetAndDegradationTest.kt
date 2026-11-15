package dev.aidos.daemon

import dev.aidos.api.RunResult
import dev.aidos.api.UserMessage
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.BatteryState
import dev.aidos.kernel.DegradationRung
import dev.aidos.kernel.DeviceSignals
import dev.aidos.kernel.MemoryPressureLevel
import dev.aidos.kernel.PlatformProfile
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceBudgetAndDegradationTest {

    private val nowIso = "2026-11-15T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun openProjectDb() = run {
        val root = Files.createTempDirectory("rfc0045-proj").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
    }

    private fun openUserDb() = run {
        val root = Files.createTempDirectory("rfc0045-user").toFile()
        AidosStorage.openUser(java.io.File(root, "user.db").path, "test-1.0") { nowIso }.driver
    }

    private fun seedProjectAndSession(driver: app.cash.sqldelight.db.SqlDriver, projectId: String, sessionId: String) {
        driver.execute(
            null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
        driver.execute(
            null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'main', 'DRIVER', 'SLEEPING', ?, ?, ?)",
            5,
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
    }

    @Test
    fun `SqliteResourceBudgetStore resolves project override over user scope and default`() {
        val userDb = openUserDb()
        val store = SqliteResourceBudgetStore(userDb)

        assertEquals(20L, store.resolve("proj-1", SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 20L))

        store.set(SqliteResourceBudgetStore.SCOPE_USER, null, SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 25L)
        assertEquals(25L, store.resolve("proj-1", SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 20L))

        // Updating user-level (null scope_id) replaces rather than duplicating.
        store.set(SqliteResourceBudgetStore.SCOPE_USER, null, SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 30L)
        assertEquals(30L, store.resolve("proj-2", SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 20L))

        // Project-level override wins for proj-1.
        store.set(SqliteResourceBudgetStore.SCOPE_PROJECT, "proj-1", SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 15L)
        assertEquals(15L, store.resolve("proj-1", SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 20L))
        assertEquals(30L, store.resolve("proj-2", SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, 20L))
    }

    @Test
    fun `DaemonDegradationMonitor records events, pauses maintenance, and refuses new Runs on critical memory`(): Unit = runBlocking {
        val projectDb = openProjectDb()
        val userDb = openUserDb()
        val pid = nextId(); val sid = nextId()
        seedProjectAndSession(projectDb, pid, sid)

        SqliteResourceBudgetStore(userDb).set(
            SqliteResourceBudgetStore.SCOPE_USER,
            null,
            SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT,
            25L,
        )

        var signals = DeviceSignals(
            memoryPressure = MemoryPressureLevel.BACKGROUND_PRESSURE,
            battery = BatteryState(percent = 20, isCharging = false), // below 25% floor
        )
        val monitor = DaemonDegradationMonitor(
            signalsProvider = { signals },
            userDriver = userDb,
            idGen = ::nextId,
            nowIso = { nowIso },
        )

        // Rung 1 (PAUSE_INDEXING) + Rung 4 (SUSPEND_DEFERRED_WORK) active -> maintenance paused, Run allowed.
        assertTrue(monitor.shouldPauseBackgroundMaintenance(projectDb, pid))
        assertNull(monitor.refusalForNewRun(projectDb, pid))

        val executor = SqliteRunExecutor(
            idGen = ::nextId,
            nowIso = { nowIso },
            degradationMonitor = monitor,
        )

        // Transition to CRITICAL -> Rung 5 active, new Run refused!
        signals = DeviceSignals(
            memoryPressure = MemoryPressureLevel.CRITICAL,
            battery = BatteryState(percent = 80, isCharging = true),
        )
        val refused = executor.send(
            projectDriver = projectDb,
            projectId = pid,
            sessionId = sid,
            message = UserMessage("hello"),
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1",
            networkAvailable = false,
        )
        val err = assertIs<RunResult.Error>(refused)
        assertEquals("run.critical_memory", err.code)

        // Recover to NONE -> Rung 5 exited_at stamped, maintenance resumed, Run accepted.
        signals = DeviceSignals(
            memoryPressure = MemoryPressureLevel.NONE,
            battery = BatteryState(percent = 80, isCharging = true),
        )
        assertFalse(monitor.shouldPauseBackgroundMaintenance(projectDb, pid))
        val accepted = executor.send(
            projectDriver = projectDb,
            projectId = pid,
            sessionId = sid,
            message = UserMessage("hello again"),
            platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1",
            networkAvailable = false,
        )
        assertIs<RunResult.Accepted>(accepted)

        val history = monitor.history(projectDb, pid)
        assertTrue(history.any { it.rung == DegradationRung.PARK_RUNS && it.exitedAt != null })
        assertTrue(history.any { it.rung == DegradationRung.SUSPEND_DEFERRED_WORK && it.exitedAt != null })
        assertNotNull(history.firstOrNull { it.rung == DegradationRung.PAUSE_INDEXING }?.exitedAt)
    }
}
