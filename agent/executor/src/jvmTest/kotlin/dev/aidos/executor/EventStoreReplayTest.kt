package dev.aidos.executor

import dev.aidos.identity.UuidV7Generator
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC-0004 MVP item 5: events can be queried by topic and by time range (replay).
 */
class EventStoreReplayTest {

    private val nowIso = "2026-08-09T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("event-store-replay-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun seedProject(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
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
    }

    @Test
    fun eventsForProjectFiltersByTopicPattern() {
        val driver = openDriver()
        val store = EventStore(driver)
        val projectId = nextId()
        seedProject(driver, projectId)

        store.publish(
            id = nextId(), projectId = projectId, type = "FileModified", source = "filesystem",
            topic = "filesystem:/project/src/main.rs", nowIso = nowIso,
        )
        store.publish(
            id = nextId(), projectId = projectId, type = "FileModified", source = "filesystem",
            topic = "filesystem:/project/docs/readme.md", nowIso = nowIso,
        )

        val srcOnly = store.eventsForProject(projectId, topicPattern = "filesystem:/project/src/**")
        assertEquals(1, srcOnly.size)
        assertEquals("filesystem:/project/src/main.rs", srcOnly.single().topic)
    }

    @Test
    fun eventsForProjectCombinesTypeAndTopicFilters() {
        val driver = openDriver()
        val store = EventStore(driver)
        val projectId = nextId()
        seedProject(driver, projectId)

        store.publish(
            id = nextId(), projectId = projectId, type = "FileModified", source = "filesystem",
            topic = "filesystem:/project/src/main.rs", nowIso = nowIso,
        )
        store.publish(
            id = nextId(), projectId = projectId, type = "GitCommit", source = "git",
            topic = "filesystem:/project/src/main.rs", nowIso = nowIso,
        )

        val filtered = store.eventsForProject(projectId, type = "GitCommit", topicPattern = "filesystem:/project/src/**")
        assertEquals(1, filtered.size)
        assertEquals("GitCommit", filtered.single().type)
    }

    @Test
    fun eventsBetweenFiltersByTimeRangeInSequenceOrder() {
        val driver = openDriver()
        val store = EventStore(driver)
        val projectId = nextId()
        seedProject(driver, projectId)

        store.publish(id = nextId(), projectId = projectId, type = "A", source = "test", nowIso = "2026-08-01T00:00:00Z")
        store.publish(id = nextId(), projectId = projectId, type = "B", source = "test", nowIso = "2026-08-05T00:00:00Z")
        store.publish(id = nextId(), projectId = projectId, type = "C", source = "test", nowIso = "2026-08-10T00:00:00Z")

        val inRange = store.eventsBetween(projectId, fromIso = "2026-08-02T00:00:00Z", toIso = "2026-08-09T00:00:00Z")
        assertEquals(listOf("B"), inRange.map { it.type })
    }

    @Test
    fun eventsBetweenAppliesTopicPatternWithinTheTimeRange() {
        val driver = openDriver()
        val store = EventStore(driver)
        val projectId = nextId()
        seedProject(driver, projectId)

        store.publish(
            id = nextId(), projectId = projectId, type = "FileModified", source = "filesystem",
            topic = "filesystem:/project/src/main.rs", nowIso = "2026-08-05T00:00:00Z",
        )
        store.publish(
            id = nextId(), projectId = projectId, type = "FileModified", source = "filesystem",
            topic = "filesystem:/project/docs/readme.md", nowIso = "2026-08-05T00:00:00Z",
        )

        val filtered = store.eventsBetween(
            projectId,
            fromIso = "2026-08-01T00:00:00Z",
            toIso = "2026-08-09T00:00:00Z",
            topicPattern = "filesystem:/project/docs/**",
        )
        assertEquals(1, filtered.size)
        assertEquals("filesystem:/project/docs/readme.md", filtered.single().topic)
    }
}
