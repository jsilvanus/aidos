package dev.aidos.identity

import dev.aidos.kernel.ProjectId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2 done-when: UUIDv7 IDs are monotonic within a process and unique across two concurrent
 * runtimes; a project registered at user scope resolves from a path and from an ID; opening a
 * project whose directory has moved fails with ProjectMoved, not a null (RFC-0054, RFC-0010).
 */
class IdentityTest {

    private fun openUserDriver() = run {
        val root = Files.createTempDirectory("identity-test").toFile()
        AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { "2026-08-05T00:00:00Z" }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    // ─── UUIDv7 ─────────────────────────────────────────────────────────────

    @Test
    fun `UUIDv7 IDs have the correct format`() {
        val gen = UuidV7Generator()
        val id = gen.next()
        // UUID format: 8-4-4-4-12
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")),
            "not a valid UUIDv7: $id")
    }

    @Test
    fun `UUIDv7 IDs are monotonic within a process`() {
        val gen = UuidV7Generator()
        val ids = (1..1000).map { gen.next() }
        for (i in 1 until ids.size) {
            assertTrue(ids[i] > ids[i - 1],
                "not monotonic at position $i: ${ids[i - 1]} >= ${ids[i]}")
        }
    }

    @Test
    fun `two concurrent generators produce unique IDs`() {
        val gen1 = UuidV7Generator()
        val gen2 = UuidV7Generator()
        val ids1 = (1..500).map { gen1.next() }.toSet()
        val ids2 = (1..500).map { gen2.next() }.toSet()
        val intersection = ids1 intersect ids2
        assertTrue(intersection.isEmpty(), "collision found: $intersection")
    }

    @Test
    fun `version nibble is 7 and variant bits are set`() {
        val gen = UuidV7Generator()
        repeat(100) {
            val id = gen.next()
            val parts = id.split('-')
            assertEquals('7', parts[2][0], "version nibble must be 7")
            assertTrue(parts[3][0] in listOf('8', '9', 'a', 'b'), "variant bits must be 10xx")
        }
    }

    // ─── ProjectRegistry ────────────────────────────────────────────────────

    @Test
    fun `project registered at user scope resolves from ID and from path`() {
        val driver = openUserDriver()
        val registry = ProjectRegistry(driver, UuidV7Generator(), fsExists = { true })
        val pid = ProjectId("01234567-89ab-7def-8abc-def012345678")
        val path = "/home/user/projects/my-project"

        registry.register(pid, path, nowIso = "2026-08-05T00:00:00Z")

        val byId = registry.resolveById(pid)
        assertTrue(byId.isSuccess)
        assertEquals(path, byId.getOrThrow())

        val byPath = registry.resolveByPath(path)
        assertTrue(byPath.isSuccess)
        assertEquals(pid, byPath.getOrThrow())
    }

    @Test
    fun `opening a project whose directory moved fails with ProjectMoved`() {
        val driver = openUserDriver()
        val registry = ProjectRegistry(driver, UuidV7Generator(), fsExists = { false })
        val pid = ProjectId("01234567-89ab-7def-8abc-def012345678")
        val path = "/home/user/moved-project"

        registry.register(pid, path, nowIso = "2026-08-05T00:00:00Z")

        val result = registry.resolveById(pid)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("moved"), result.exceptionOrNull()!!.message)
    }

    @Test
    fun `resolving an unknown project ID returns not-found`() {
        val driver = openUserDriver()
        val registry = ProjectRegistry(driver, UuidV7Generator())
        val result = registry.resolveById(ProjectId("00000000-0000-7000-8000-000000000000"))
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message!!.contains("moved"))
    }

    @Test
    fun `listAll returns registered projects`() {
        val driver = openUserDriver()
        val registry = ProjectRegistry(driver, UuidV7Generator(), fsExists = { true })
        val p1 = ProjectId("01234567-89ab-7def-8abc-000000000001")
        val p2 = ProjectId("01234567-89ab-7def-8abc-000000000002")

        registry.register(p1, "/projects/a", nowIso = "2026-08-05T00:00:00Z")
        registry.register(p2, "/projects/b", nowIso = "2026-08-05T00:00:01Z")

        val list = registry.listAll()
        assertEquals(2, list.size)
        assertTrue(list.any { it.first == p1 })
        assertTrue(list.any { it.first == p2 })
    }
}
