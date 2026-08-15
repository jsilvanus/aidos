package fi.italeino.aidos.engine.approval

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for app approval storage (RFC-0103).
 *
 * These tests run on the JVM and verify approval CRUD operations
 * without requiring Android-specific code.
 */
class AppApprovalStoreTest {
    
    /**
     * In-memory implementation of AppApprovalStore for testing.
     */
    private class InMemoryAppApprovalStore : AppApprovalStore {
        private val records = mutableMapOf<String, AppApprovalRecord>()
        
        override suspend fun getApproval(packageName: String): AppApprovalRecord? =
            records[packageName]
        
        override suspend fun listAllApprovals(): List<AppApprovalRecord> =
            records.values.sortedByDescending { it.lastSeenAt }
        
        override fun watchApprovals() = kotlinx.coroutines.flow.flowOf(
            records.values.sortedByDescending { it.lastSeenAt }
        )
        
        override suspend fun approveApp(packageName: String): AppApprovalRecord? {
            val current = records[packageName] ?: return null
            val updated = current.copy(
                status = AppApprovalStatus.APPROVED,
                decidedAt = "2026-08-15T13:30:00Z"
            )
            records[packageName] = updated
            return updated
        }
        
        override suspend fun denyApp(packageName: String): AppApprovalRecord? {
            val current = records[packageName] ?: return null
            val updated = current.copy(
                status = AppApprovalStatus.DENIED,
                decidedAt = "2026-08-15T13:30:00Z"
            )
            records[packageName] = updated
            return updated
        }
        
        override suspend fun undoDenyApp(packageName: String): AppApprovalRecord? {
            val current = records[packageName] ?: return null
            if (current.status != AppApprovalStatus.DENIED) return current
            val updated = current.copy(
                status = AppApprovalStatus.PENDING,
                decidedAt = null
            )
            records[packageName] = updated
            return updated
        }
        
        override suspend fun revokeApproval(packageName: String): AppApprovalRecord? {
            val current = records[packageName] ?: return null
            if (current.status != AppApprovalStatus.APPROVED) return current
            val updated = current.copy(
                status = AppApprovalStatus.PENDING,
                decidedAt = null
            )
            records[packageName] = updated
            return updated
        }
        
        override suspend fun recordFirstHandshake(
            packageName: String,
            displayName: String
        ): AppApprovalRecord {
            val now = "2026-08-15T13:30:00Z"
            val record = AppApprovalRecord(
                packageName = packageName,
                displayName = displayName,
                status = AppApprovalStatus.PENDING,
                decidedAt = null,
                firstSeenAt = now,
                lastSeenAt = now,
                attemptCount = 1,
                requestCount = 0
            )
            records[packageName] = record
            return record
        }
        
        override suspend fun recordHandshakeAttempt(packageName: String): AppApprovalRecord? {
            val current = records[packageName] ?: return null
            val updated = current.copy(
                lastSeenAt = "2026-08-15T13:31:00Z",
                attemptCount = current.attemptCount + 1
            )
            records[packageName] = updated
            return updated
        }
        
        override suspend fun updateRequestCount(packageName: String, increment: Int): AppApprovalRecord? {
            val current = records[packageName] ?: return null
            val updated = current.copy(requestCount = current.requestCount + increment)
            records[packageName] = updated
            return updated
        }
        
        override suspend fun clear() {
            records.clear()
        }
    }
    
    @Test
    fun recordFirstHandshakeCreatesPendingRecord() = runBlocking {
        val store = InMemoryAppApprovalStore()
        val record = store.recordFirstHandshake("com.example.app", "Example App")
        
        assertEquals("com.example.app", record.packageName)
        assertEquals("Example App", record.displayName)
        assertEquals(AppApprovalStatus.PENDING, record.status)
        assertEquals(1, record.attemptCount)
        assertNull(record.decidedAt)
    }
    
    @Test
    fun approveAppChangesStatusToApproved() = runBlocking {
        val store = InMemoryAppApprovalStore()
        store.recordFirstHandshake("com.example.app", "Example App")
        val approved = store.approveApp("com.example.app")
        
        assertEquals(AppApprovalStatus.APPROVED, approved?.status)
        assertEquals("2026-08-15T13:30:00Z", approved?.decidedAt)
    }
    
    @Test
    fun denyAppChangesStatusToDenied() = runBlocking {
        val store = InMemoryAppApprovalStore()
        store.recordFirstHandshake("com.example.app", "Example App")
        val denied = store.denyApp("com.example.app")
        
        assertEquals(AppApprovalStatus.DENIED, denied?.status)
        assertEquals("2026-08-15T13:30:00Z", denied?.decidedAt)
    }
    
    @Test
    fun undoDenyReturnsAppToPending() = runBlocking {
        val store = InMemoryAppApprovalStore()
        store.recordFirstHandshake("com.example.app", "Example App")
        store.denyApp("com.example.app")
        val undone = store.undoDenyApp("com.example.app")
        
        assertEquals(AppApprovalStatus.PENDING, undone?.status)
        assertNull(undone?.decidedAt)
    }
    
    @Test
    fun revokeApprovalReturnsAppToPending() = runBlocking {
        val store = InMemoryAppApprovalStore()
        store.recordFirstHandshake("com.example.app", "Example App")
        store.approveApp("com.example.app")
        val revoked = store.revokeApproval("com.example.app")
        
        assertEquals(AppApprovalStatus.PENDING, revoked?.status)
        assertNull(revoked?.decidedAt)
    }
    
    @Test
    fun recordHandshakeAttemptIncrementsCounter() = runBlocking {
        val store = InMemoryAppApprovalStore()
        store.recordFirstHandshake("com.example.app", "Example App")
        val recorded = store.recordHandshakeAttempt("com.example.app")
        
        assertEquals(2, recorded?.attemptCount)
    }
    
    @Test
    fun updateRequestCountIncrementsCount() = runBlocking {
        val store = InMemoryAppApprovalStore()
        store.recordFirstHandshake("com.example.app", "Example App")
        store.updateRequestCount("com.example.app", 5)
        val updated = store.updateRequestCount("com.example.app", 3)
        
        assertEquals(8, updated?.requestCount)
    }
}
