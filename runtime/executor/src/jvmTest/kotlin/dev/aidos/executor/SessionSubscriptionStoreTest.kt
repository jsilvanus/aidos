package dev.aidos.executor

import dev.aidos.identity.UuidV7Generator
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0005 MVP item 1: subscriptions must survive a step boundary (D3), so they persist to
 * `session_subscriptions` rather than living only in memory.
 */
class SessionSubscriptionStoreTest {

    private val nowIso = "2026-08-09T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("session-subscription-store-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProjectAndSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
    ) {
        driver.execute(null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, " +
                "created_at, updated_at, state_updated_at) VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, " +
                "created_at, last_active_at, state_updated_at) VALUES (?, ?, 'test', 'DRIVER', 'SLEEPING', ?, ?, ?)", 5
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, nowIso)
            bindString(3, nowIso)
            bindString(4, nowIso)
        }
    }

    @Test
    fun subscribeAndReadBackRoundTripsTopicPatternsAndEventTypes() {
        val driver = openDriver()
        val store = SessionSubscriptionStore(driver)
        val projectId = nextId()
        val sessionId = nextId()
        seedProjectAndSession(driver, projectId, sessionId)

        store.subscribe(
            id = nextId(), sessionId = sessionId,
            topicPatterns = listOf("filesystem:/project/src/**", "git:*"),
            eventTypes = listOf("FileModified", "GitCommit"),
            selfWake = true,
            nowIso = nowIso,
        )

        val rows = store.forSession(sessionId)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(listOf("filesystem:/project/src/**", "git:*"), row.topicPatterns)
        assertEquals(listOf("FileModified", "GitCommit"), row.eventTypes)
        assertTrue(row.selfWake)
    }

    @Test
    fun eventTypesDefaultsToNullMeaningAllTypes() {
        val driver = openDriver()
        val store = SessionSubscriptionStore(driver)
        val projectId = nextId()
        val sessionId = nextId()
        seedProjectAndSession(driver, projectId, sessionId)

        store.subscribe(id = nextId(), sessionId = sessionId, topicPatterns = listOf("*"), nowIso = nowIso)

        val row = store.forSession(sessionId).single()
        assertNull(row.eventTypes)
        assertTrue(!row.selfWake)
    }

    @Test
    fun forProjectReturnsSubscriptionsAcrossSessionsInThatProject() {
        val driver = openDriver()
        val store = SessionSubscriptionStore(driver)
        val projectId = nextId()
        val sessionA = nextId()
        val sessionB = nextId()
        seedProjectAndSession(driver, projectId, sessionA)
        seedProjectAndSession(driver, projectId, sessionB)

        store.subscribe(id = nextId(), sessionId = sessionA, topicPatterns = listOf("git:*"), nowIso = nowIso)
        store.subscribe(id = nextId(), sessionId = sessionB, topicPatterns = listOf("session:*"), nowIso = nowIso)

        val rows = store.forProject(projectId)
        assertEquals(setOf(sessionA, sessionB), rows.map { it.sessionId }.toSet())
    }

    @Test
    fun unsubscribeRemovesTheRow() {
        val driver = openDriver()
        val store = SessionSubscriptionStore(driver)
        val projectId = nextId()
        val sessionId = nextId()
        seedProjectAndSession(driver, projectId, sessionId)

        val subId = nextId()
        store.subscribe(id = subId, sessionId = sessionId, topicPatterns = listOf("git:*"), nowIso = nowIso)
        store.unsubscribe(subId)

        assertTrue(store.forSession(sessionId).isEmpty())
    }
}
