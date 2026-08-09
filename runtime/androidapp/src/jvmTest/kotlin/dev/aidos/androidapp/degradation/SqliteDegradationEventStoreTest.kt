package dev.aidos.androidapp.degradation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.DegradationRung
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0045: degradation_events rows open when a rung becomes active and close (exited_at set)
 * when it stops being active, reconciled by querying currently-open rows rather than tracking
 * state in memory.
 */
class SqliteDegradationEventStoreTest {

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("degradation-store-test").toFile()
        val db = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-09T00:00:00Z" }
        val driver = db.driver as JdbcSqliteDriver
        driver.execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) VALUES ('proj-1', 'proj-1', '/projects/proj-1', ?, ?, ?)",
            parameters = 3,
        ) {
            bindString(0, "2026-08-09T00:00:00Z")
            bindString(1, "2026-08-09T00:00:00Z")
            bindString(2, "2026-08-09T00:00:00Z")
        }
        return driver
    }

    private fun idGenerator(): () -> String {
        val counter = AtomicInteger(0)
        return { "event-${counter.incrementAndGet()}" }
    }

    @Test
    fun `newly active rung opens an event with no exited_at`() {
        val store = SqliteDegradationEventStore(openDriver())
        store.apply(mapOf(DegradationRung.PAUSE_INDEXING to "sustained background pressure"), "proj-1", "2026-08-09T00:00:00Z", idGenerator())

        val history = store.history("proj-1")
        assertEquals(1, history.size)
        assertEquals(DegradationRung.PAUSE_INDEXING, history[0].rung)
        assertEquals("sustained background pressure", history[0].trigger)
        assertNull(history[0].exitedAt)
    }

    @Test
    fun `a rung that stops being active gets its event closed`() {
        val store = SqliteDegradationEventStore(openDriver())
        val ids = idGenerator()
        store.apply(mapOf(DegradationRung.PAUSE_INDEXING to "background pressure"), "proj-1", "2026-08-09T00:00:00Z", ids)
        store.apply(emptyMap(), "proj-1", "2026-08-09T00:05:00Z", ids)

        val event = store.history("proj-1").single()
        assertNotNull(event.exitedAt)
        assertEquals("2026-08-09T00:05:00Z", event.exitedAt.toString())
    }

    @Test
    fun `re-applying the same active set is a no-op — no duplicate rows`() {
        val store = SqliteDegradationEventStore(openDriver())
        val ids = idGenerator()
        val active = mapOf(DegradationRung.UNLOAD_MODEL to "memory pressure")
        store.apply(active, "proj-1", "2026-08-09T00:00:00Z", ids)
        store.apply(active, "proj-1", "2026-08-09T00:01:00Z", ids)
        store.apply(active, "proj-1", "2026-08-09T00:02:00Z", ids)

        val history = store.history("proj-1")
        assertEquals(1, history.size)
        assertNull(history[0].exitedAt)
    }

    @Test
    fun `independent rungs open and close independently`() {
        val store = SqliteDegradationEventStore(openDriver())
        val ids = idGenerator()
        store.apply(
            mapOf(
                DegradationRung.PAUSE_INDEXING to "background pressure",
                DegradationRung.SUSPEND_DEFERRED_WORK to "low battery, not charging",
            ),
            "proj-1", "2026-08-09T00:00:00Z", ids,
        )
        // memory pressure recovered; battery still low
        store.apply(
            mapOf(DegradationRung.SUSPEND_DEFERRED_WORK to "low battery, not charging"),
            "proj-1", "2026-08-09T00:05:00Z", ids,
        )

        val history = store.history("proj-1").associateBy { it.rung }
        assertNotNull(history[DegradationRung.PAUSE_INDEXING]!!.exitedAt)
        assertNull(history[DegradationRung.SUSPEND_DEFERRED_WORK]!!.exitedAt)
    }

    @Test
    fun `a rung that re-enters after closing opens a new event, not the old one`() {
        val store = SqliteDegradationEventStore(openDriver())
        val ids = idGenerator()
        val active = mapOf(DegradationRung.PARK_RUNS to "critical memory")
        store.apply(active, "proj-1", "2026-08-09T00:00:00Z", ids)
        store.apply(emptyMap(), "proj-1", "2026-08-09T00:01:00Z", ids)
        store.apply(active, "proj-1", "2026-08-09T00:02:00Z", ids)

        val history = store.history("proj-1")
        assertEquals(2, history.size)
        assertEquals(1, history.count { it.exitedAt == null })
    }

    @Test
    fun `device-wide events (null project id) are scoped separately from project events`() {
        val store = SqliteDegradationEventStore(openDriver())
        val ids = idGenerator()
        store.apply(mapOf(DegradationRung.SUSPEND_DEFERRED_WORK to "low battery"), null, "2026-08-09T00:00:00Z", ids)
        store.apply(mapOf(DegradationRung.PAUSE_INDEXING to "background pressure"), "proj-1", "2026-08-09T00:00:00Z", ids)

        assertEquals(1, store.history(null).size)
        assertEquals(1, store.history("proj-1").size)
        assertTrue(store.history(null).none { it.projectId == "proj-1" })
    }
}
