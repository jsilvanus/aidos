package fi.italeino.aidos.engine.license

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import java.time.Instant

class LicenseAcceptanceStoreTest {

    @Test
    fun acceptedLicense_isRemembered() = runTest {
        val store = InMemoryLicenseAcceptanceStore()
        val modelId = "qwen2.5-3b"
        val version = "1.0"
        val digest = "abc123def"

        // Initially not accepted
        assertFalse(store.isAccepted(modelId, version, digest))

        // Accept the license
        val record = store.accept(modelId, version, digest)
        assertEquals(modelId, record.modelId)
        assertEquals(version, record.modelVersion)
        assertEquals(digest, record.licenseTextDigest)

        // Now it's accepted
        assertTrue(store.isAccepted(modelId, version, digest))
    }

    @Test
    fun licenseWithChangedDigest_requiresReacceptance() = runTest {
        val store = InMemoryLicenseAcceptanceStore()
        val modelId = "qwen2.5-3b"
        val version = "1.0"
        val oldDigest = "abc123"
        val newDigest = "def456"

        // Accept with old digest
        store.accept(modelId, version, oldDigest)
        assertTrue(store.isAccepted(modelId, version, oldDigest))

        // Old digest still accepted
        assertTrue(store.isAccepted(modelId, version, oldDigest))

        // New digest not accepted
        assertFalse(store.isAccepted(modelId, version, newDigest))
    }

    @Test
    fun getAcceptance_returnsRecord() = runTest {
        val store = InMemoryLicenseAcceptanceStore()
        val modelId = "whisper-base"
        val version = "2.0"
        val digest = "hash1"

        // No record initially
        assertEquals(null, store.getAcceptance(modelId, version))

        // Accept
        store.accept(modelId, version, digest)

        // Record exists
        val record = store.getAcceptance(modelId, version)
        assertNotNull(record)
        assertEquals(modelId, record.modelId)
        assertEquals(version, record.modelVersion)
        assertEquals(digest, record.licenseTextDigest)
    }

    @Test
    fun listAllAcceptances_returnsMultipleRecords() = runTest {
        val store = InMemoryLicenseAcceptanceStore()

        store.accept("model-a", "1.0", "digest-a")
        store.accept("model-b", "2.0", "digest-b")
        store.accept("model-c", "1.5", "digest-c")

        val all = store.listAllAcceptances()
        assertEquals(3, all.size)
        assertEquals("model-a", all[2].modelId)  // oldest first, but list is sorted descending
    }

    @Test
    fun revoke_removesAcceptance() = runTest {
        val store = InMemoryLicenseAcceptanceStore()
        val modelId = "test-model"
        val version = "1.0"
        val digest = "hash"

        // Accept
        store.accept(modelId, version, digest)
        assertTrue(store.isAccepted(modelId, version, digest))

        // Revoke
        val revoked = store.revoke(modelId, version)
        assertTrue(revoked)

        // No longer accepted
        assertFalse(store.isAccepted(modelId, version, digest))
        assertEquals(null, store.getAcceptance(modelId, version))
    }

    @Test
    fun revoke_nonexistent_returnsFalse() = runTest {
        val store = InMemoryLicenseAcceptanceStore()
        val revoked = store.revoke("nonexistent-model", "1.0")
        assertFalse(revoked)
    }

    @Test
    fun clear_removesAllRecords() = runTest {
        val store = InMemoryLicenseAcceptanceStore()

        store.accept("model-a", "1.0", "digest-a")
        store.accept("model-b", "2.0", "digest-b")

        val before = store.listAllAcceptances()
        assertEquals(2, before.size)

        store.clear()

        val after = store.listAllAcceptances()
        assertEquals(0, after.size)
        assertFalse(store.isAccepted("model-a", "1.0", "digest-a"))
    }

    @Test
    fun watchAcceptances_emitsUpdates() = runTest {
        val store = InMemoryLicenseAcceptanceStore()
        val updates = mutableListOf<List<LicenseAcceptanceRecord>>()

        val flow = store.watchAcceptances()
        val job = kotlinx.coroutines.launch {
            flow.collect { updates.add(it) }
        }

        // Give collector time to attach
        kotlinx.coroutines.delay(10)

        store.accept("model-a", "1.0", "digest-a")
        kotlinx.coroutines.delay(10)
        store.accept("model-b", "2.0", "digest-b")
        kotlinx.coroutines.delay(10)
        store.revoke("model-a", "1.0")
        kotlinx.coroutines.delay(10)

        job.cancel()

        // Should have received at least 3 updates (accept, accept, revoke)
        assertTrue(updates.size >= 3)
    }

    @Test
    fun multipleVersions_trackedSeparately() = runTest {
        val store = InMemoryLicenseAcceptanceStore()
        val modelId = "gpt-model"

        // Accept version 1.0
        store.accept(modelId, "1.0", "digest-v1")
        assertTrue(store.isAccepted(modelId, "1.0", "digest-v1"))

        // Version 2.0 not yet accepted
        assertFalse(store.isAccepted(modelId, "2.0", "digest-v2"))

        // Accept version 2.0
        store.accept(modelId, "2.0", "digest-v2")

        // Both versions now accepted with their respective digests
        assertTrue(store.isAccepted(modelId, "1.0", "digest-v1"))
        assertTrue(store.isAccepted(modelId, "2.0", "digest-v2"))

        // Wrong digest still fails
        assertFalse(store.isAccepted(modelId, "1.0", "digest-v2"))
    }
}
