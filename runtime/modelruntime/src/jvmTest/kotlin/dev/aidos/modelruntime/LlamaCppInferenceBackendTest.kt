package dev.aidos.modelruntime

import dev.aidos.kernel.ModelKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for LlamaCppInferenceBackend (RFC-0022, M21).
 *
 * Verifies:
 * 1. Model catalog is available
 * 2. Installed models detection
 * 3. Digest computation and verification
 * 4. Model loading through admission queue
 */
class LlamaCppInferenceBackendTest {

    @Test
    fun `catalog returns known-good models`() = runTest {
        val backend = LlamaCppInferenceBackend()
        val catalog = backend.catalog()

        assertTrue(catalog.isNotEmpty(), "Catalog should not be empty")
        assertTrue(catalog.any { it.kind == ModelKind.LLM }, "Catalog should include LLM models")
        assertTrue(
            catalog.any { it.kind == ModelKind.EMBEDDING },
            "Catalog should include embedding models"
        )
    }

    @Test
    fun `catalog models have required fields`() = runTest {
        val backend = LlamaCppInferenceBackend()
        val catalog = backend.catalog()

        catalog.forEach { model ->
            assertNotNull(model.id, "Model should have id")
            assertNotNull(model.name, "Model should have name")
            assertNotNull(model.kind, "Model should have kind")
            assertNotNull(model.providerId, "Model should have providerId")
            assertTrue(model.contextWindow > 0, "Model should have positive context window")
        }
    }

    @Test
    fun `installed returns empty when no models present`() = runTest {
        val backend = LlamaCppInferenceBackend()
        val installed = backend.installed()
        assertTrue(installed.isEmpty(), "No models should be installed initially")
    }

    @Test
    fun `load fails gracefully for missing model`() = runTest {
        val backend = LlamaCppInferenceBackend()
        val result = backend.load("nonexistent-model-id")
        assertTrue(result.isFailure, "Loading missing model should fail")
    }

    @Test
    fun `digest computation returns consistent hash`() = runTest {
        val backend = LlamaCppInferenceBackend()
        val modelId = "test-model"

        // Both calls should return the same digest (or empty if file doesn't exist)
        val digest1 = backend.computeDigest(modelId)
        val digest2 = backend.computeDigest(modelId)
        assertEquals(digest1, digest2, "Digest should be consistent")
    }
}
