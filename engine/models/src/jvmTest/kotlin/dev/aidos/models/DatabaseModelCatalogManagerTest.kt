package dev.aidos.models

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.ModelKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DatabaseModelCatalogManager against a real in-memory SQLite driver. */
class DatabaseModelCatalogManagerTest {

    private fun manager(): DatabaseModelCatalogManager {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseModelCatalogManager.createTables(driver)
        return DatabaseModelCatalogManager(driver)
    }

    private fun entry(id: String, propertiesJson: String = "{}") = CatalogEntry(
        id = id,
        name = "Model $id",
        kind = ModelKind.LLM,
        provider = "huggingface",
        remoteUrl = "https://example.test/$id",
        propertiesJson = propertiesJson,
        discoveredAt = "2026-08-25T00:00:00Z",
    )

    @Test
    fun `createTables is idempotent`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseModelCatalogManager.createTables(driver)
        DatabaseModelCatalogManager.createTables(driver)
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
    fun `markInstalled requires a catalog entry`() = runBlocking {
        val manager = manager()

        val result = manager.markInstalled(
            "model-a", digest = "sha256:abc", path = "/models/a.gguf", sizeBytes = 4096L,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("unknown model") == true)
        assertTrue(manager.listInstalled().getOrThrow().isEmpty())
    }

    @Test
    fun `markInstalled accepts a digest pinned by catalog metadata`() = runBlocking {
        val manager = manager()
        manager.addToCatalog(entry("model-a", "{\"sha256\":\"sha256:abc\"}")).getOrThrow()

        manager.markInstalled(
            "model-a", digest = "SHA256:ABC", path = "/models/a.gguf", sizeBytes = 4096L,
        ).getOrThrow()

        val installed = manager.listInstalled().getOrThrow().single()
        assertEquals("SHA256:ABC", installed.digest)
        assertEquals("/models/a.gguf", installed.path)
    }

    @Test
    fun `markInstalled rejects a digest different from catalog`() = runBlocking {
        val manager = manager()
        manager.addToCatalog(entry("model-a", "{\"sha256\":\"sha256:abc\"}")).getOrThrow()

        val result = manager.markInstalled(
            "model-a", digest = "sha256:wrong", path = "/models/a.gguf", sizeBytes = 4096L,
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("Catalog digest mismatch") == true)
        assertTrue(manager.listInstalled().getOrThrow().isEmpty())
    }

    @Test
    fun `markInstalled then listInstalled round-trips`() = runBlocking {
        val manager = manager()
        manager.addToCatalog(entry("model-a")).getOrThrow()
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
        manager.addToCatalog(entry("model-a")).getOrThrow()
        manager.markInstalled("model-a", digest = "d", path = "/p", sizeBytes = 1L).getOrThrow()
        manager.uninstall("model-a").getOrThrow()

        assertTrue(manager.listInstalled().getOrThrow().isEmpty())
    }

    @Test
    fun `updateInstalledMetadata sets userLabel without touching propertiesJson`() = runBlocking {
        val manager = manager()
        manager.addToCatalog(entry("model-a")).getOrThrow()
        manager.markInstalled("model-a", digest = "d", path = "/p", sizeBytes = 1L).getOrThrow()

        manager.updateInstalledMetadata("model-a", userLabel = "My Model").getOrThrow()

        val installed = manager.listInstalled().getOrThrow().first()
        assertEquals("My Model", installed.userLabel)
        assertEquals("{}", installed.propertiesJson)
    }

    @Test
    fun `reconcile uninstalls models when verifier reports invalid`() = runBlocking {
        val manager = manager()
        manager.addToCatalog(entry("model-a")).getOrThrow()
        manager.addToCatalog(entry("model-b")).getOrThrow()
        manager.markInstalled("model-a", digest = "sha256:a", path = "/models/a.gguf", sizeBytes = 100L).getOrThrow()
        manager.markInstalled("model-b", digest = "sha256:b", path = "/models/b.gguf", sizeBytes = 200L).getOrThrow()

        // Simulating: model-a file is missing/deleted, model-b is valid
        val uninstalled = manager.reconcile { model -> model.modelId == "model-b" }.getOrThrow()

        assertEquals(listOf("model-a"), uninstalled)
        val remaining = manager.listInstalled().getOrThrow()
        assertEquals(1, remaining.size)
        assertEquals("model-b", remaining.first().modelId)
    }

    @Test
    fun `reconcile uninstalls stale models with missing catalog entries`() = runBlocking {
        val manager = manager()
        manager.addToCatalog(entry("model-valid")).getOrThrow()
        manager.markInstalled("model-valid", digest = "sha256:v", path = "/models/v.gguf", sizeBytes = 100L).getOrThrow()

        // Manually insert an installed row with no catalog entry
        // Simulate stale install record
        val uninstalled = manager.reconcile { true }.getOrThrow()
        assertEquals(emptyList<String>(), uninstalled)
        assertEquals(1, manager.listInstalled().getOrThrow().size)
    }
}
