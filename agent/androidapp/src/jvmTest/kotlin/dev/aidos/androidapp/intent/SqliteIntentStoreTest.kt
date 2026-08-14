package dev.aidos.androidapp.intent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0012 / D20: flat intent items persisted to intent_nodes. Proposals and edges are out of
 * scope here — see SqliteIntentStore's class doc.
 */
class SqliteIntentStoreTest {

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("intent-store-test").toFile()
        val db = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-09T00:00:00Z" }
        val driver = db.driver as JdbcSqliteDriver
        // intent_nodes.project_id is a foreign key into projects — seed the two IDs these tests use.
        for (id in listOf("proj-1", "proj-2")) {
            driver.execute(
                identifier = null,
                sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                parameters = 6,
            ) {
                bindString(0, id)
                bindString(1, id)
                bindString(2, "/projects/$id")
                bindString(3, "2026-08-09T00:00:00Z")
                bindString(4, "2026-08-09T00:00:00Z")
                bindString(5, "2026-08-09T00:00:00Z")
            }
        }
        return driver
    }

    private val user = ActorRef(ActorKind.USER, "user-1")

    private fun item(
        id: String = "intent-1",
        title: String = "Ship the thing",
        priority: IntentPriority = IntentPriority.MEDIUM,
        userOverride: UserStatusOverride? = null,
    ) = IntentItem(id = id, title = title, description = "A description", priority = priority, userOverride = userOverride)

    @Test
    fun `created item round-trips through listActive`() {
        val store = SqliteIntentStore(openDriver())
        store.create(item(), "proj-1", user, "2026-08-09T00:00:00Z")

        val active = store.listActive("proj-1")
        assertEquals(1, active.size)
        assertEquals("intent-1", active[0].id)
        assertEquals("Ship the thing", active[0].title)
        assertEquals("A description", active[0].description)
        assertEquals(IntentPriority.MEDIUM, active[0].priority)
        assertNull(active[0].userOverride)
        assertNull(active[0].targetedByRunId)
    }

    @Test
    fun `items order by priority`() {
        val store = SqliteIntentStore(openDriver())
        store.create(item(id = "low", priority = IntentPriority.LOW), "proj-1", user, "2026-08-09T00:00:00Z")
        store.create(item(id = "high", priority = IntentPriority.HIGH), "proj-1", user, "2026-08-09T00:00:01Z")
        store.create(item(id = "medium", priority = IntentPriority.MEDIUM), "proj-1", user, "2026-08-09T00:00:02Z")

        val active = store.listActive("proj-1")
        assertEquals(listOf("high", "medium", "low"), active.map { it.id })
    }

    @Test
    fun `archived items are excluded from listActive`() {
        val store = SqliteIntentStore(openDriver())
        store.create(item(), "proj-1", user, "2026-08-09T00:00:00Z")

        store.archive("intent-1", user, "2026-08-09T00:01:00Z")

        assertTrue(store.listActive("proj-1").isEmpty())
    }

    @Test
    fun `items are scoped to their project`() {
        val store = SqliteIntentStore(openDriver())
        store.create(item(id = "a"), "proj-1", user, "2026-08-09T00:00:00Z")
        store.create(item(id = "b"), "proj-2", user, "2026-08-09T00:00:00Z")

        assertEquals(listOf("a"), store.listActive("proj-1").map { it.id })
        assertEquals(listOf("b"), store.listActive("proj-2").map { it.id })
    }

    @Test
    fun `user override round-trips and can be cleared`() {
        val store = SqliteIntentStore(openDriver())
        store.create(item(), "proj-1", user, "2026-08-09T00:00:00Z")

        val override = UserStatusOverride(
            claimedStatus = DerivedIntentStatus.COMPLETED,
            overriddenAt = "2026-08-09T01:00:00Z",
            overriddenByUserId = "user-1",
        )
        store.setUserOverride("intent-1", override, user, "2026-08-09T01:00:00Z")

        val withOverride = store.listActive("proj-1").single()
        assertEquals(override, withOverride.userOverride)

        store.setUserOverride("intent-1", null, user, "2026-08-09T02:00:00Z")
        assertNull(store.listActive("proj-1").single().userOverride)
    }

    @Test
    fun `created item can carry its user override from the start`() {
        val store = SqliteIntentStore(openDriver())
        val override = UserStatusOverride(
            claimedStatus = DerivedIntentStatus.FAILED,
            overriddenAt = "2026-08-09T00:00:00Z",
            overriddenByUserId = "user-1",
        )
        store.create(item(userOverride = override), "proj-1", user, "2026-08-09T00:00:00Z")

        assertEquals(override, store.listActive("proj-1").single().userOverride)
    }
}
