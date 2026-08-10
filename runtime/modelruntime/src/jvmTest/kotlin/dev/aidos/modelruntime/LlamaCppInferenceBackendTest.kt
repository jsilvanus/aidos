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

    /**
     * M20 (RFC-0022, RFC-0054, RFC-0045): the audit's Part 3 finding was that every catalog
     * entry shipped `digest = null`, so [GlobalModelRuntime.load]'s verification had nothing
     * pinned to compare against and could only ever re-hash the same installed file against
     * itself. This locks in the fix -- each curated model now carries a real published SHA-256
     * (a Hugging Face LFS blob's own `oid`) that a load-time hash can actually be checked
     * against.
     */
    @Test
    fun `catalog entries carry a pinned SHA-256 digest, not a null placeholder`() = runTest {
        val backend = LlamaCppInferenceBackend()
        val catalog = backend.catalog()

        assertTrue(catalog.isNotEmpty())
        catalog.forEach { model ->
            val digest = model.digest
            assertNotNull(digest, "${model.id}: catalog digest must be pinned, not null")
            assertEquals(64, digest.length, "${model.id}: expected a 64-hex-char SHA-256 digest")
            assertTrue(
                digest.all { it.isDigit() || it in 'a'..'f' },
                "${model.id}: digest should be lowercase hex, was $digest",
            )
        }
        // Digests are per-file content hashes -- three different files must not collide.
        assertEquals(catalog.size, catalog.map { it.digest }.distinct().size)
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
