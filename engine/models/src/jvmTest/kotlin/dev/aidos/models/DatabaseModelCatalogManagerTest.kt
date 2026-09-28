package dev.aidos.models

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.ModelKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DatabaseModelCatalogManager (RFC-0022) against a real, in-memory SQLite driver.
 *
 * This class was deleted in 7d2c9ea with no replacement, leaving EngineService referencing a
 * class that no longer existed anywhere -- a build break that only a real Android compile catches
 * (this sandbox has no Android SDK; `gradle jvmTest` alone would not have caught it either, since
 * it never constructed one against real storage). These tests exercise the schema
 * (`createTables`) and the manager together, the way EngineService actually wires them.
 */
class DatabaseModelCatalogManagerTest {

    private fun manager(): DatabaseModelCatalogManager {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseModelCatalogManager.createTables(driver)
        return DatabaseModelCatalogManager(driver)
    }

    private fun entry(id: String) = CatalogEntry(
        id = id, name = "Model $id", kind = ModelKind.LLM, provider = "huggingface",
        remoteUrl = "https://example.test/$id", discoveredAt = "2026-08-25T00:00:00Z",
    )

    @Test
    fun `createTables is idempotent`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseModelCatalogManager.createTables(driver)
        DatabaseModelCatalogManager.createTables(driver) // must not throw "table already exists"
    }

    @Test
    fun `addToCatalog then listCatalog round-trips`() = runBlocking {
        val manager = manager()
        manager.addToCatalog(entry("model-a")).getOrThrow()

        val listed = manager.listCatalog().getOrThrow()
        assertEquals(1, listed.size)
        assertEquals("model-a", listed.first().id)
        assertEquals(ModelKind.LLM, listed.first().kind)
    }

    @Test
    fun `getCatalog returns null for an unknown id`() = runBlocking {
        val manager = manager()
        assertNull(manager.getCatalog("no-such-model").getOrThrow())
    }

    @Test
    fun `markInstalled then listInstalled round-trips`() = runBlocking {
        val manager = manager()
        manager.markInstalled("model-a", digest = "sha256:abc", path = "/models/a.gguf", sizeBytes = 4096L, quantization = "Q4_K_M").getOrThrow()

        val installed = manager.listInstalled().getOrThrow()
        assertEquals(1, installed.size)
        assertEquals("model-a", installed.first().modelId)
        assertEquals("Q4_K_M", installed.first().quantization)
        assertEquals(4096L, installed.first().sizeBytes)
    }

    @Test
    fun `uninstall removes the installed row`() = runBlocking {
        val manager = manager()
        manager.markInstalled("model-a", digest = "d", path = "/p", sizeBytes = 1L, quantization = null).getOrThrow()
        manager.uninstall("model-a").getOrThrow()

        assertTrue(manager.listInstalled().getOrThrow().isEmpty())
    }

    @Test
    fun `updateInstalledMetadata sets userLabel without touching propertiesJson`() = runBlocking {
        val manager = manager()
        manager.markInstalled("model-a", digest = "d", path = "/p", sizeBytes = 1L, quantization = null).getOrThrow()

        manager.updateInstalledMetadata("model-a", userLabel = "My Model").getOrThrow()

        val installed = manager.listInstalled().getOrThrow().first()
        assertEquals("My Model", installed.userLabel)
        assertEquals("{}", installed.propertiesJson)
    }
}
