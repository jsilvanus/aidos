package fi.italeino.aidos.engine.approval

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test double for [AppApprovalStore] following its documented state machine (RFC-0103): a first
 * handshake records PENDING; approve/deny/revoke/undoDeny move between the three states; denial
 * is sticky. Timestamps are a monotonic counter so ordering is deterministic.
 */
class InMemoryAppApprovalStore : AppApprovalStore {
    private val records = LinkedHashMap<String, AppApprovalRecord>()
    private var clock = 0

    private fun now() = "2026-09-29T00:00:%02dZ".format(clock++)

    override suspend fun getApproval(packageName: String) = records[packageName]

    override suspend fun listAllApprovals() = records.values.sortedByDescending { it.lastSeenAt }

    override fun watchApprovals(): Flow<List<AppApprovalRecord>> = flowOf(records.values.toList())

    private fun set(packageName: String, change: (AppApprovalRecord) -> AppApprovalRecord): AppApprovalRecord? {
        val current = records[packageName] ?: return null
        return change(current).also { records[packageName] = it }
    }

    override suspend fun approveApp(packageName: String) =
        set(packageName) { it.copy(status = AppApprovalStatus.APPROVED, decidedAt = now()) }

    override suspend fun denyApp(packageName: String) =
        set(packageName) { it.copy(status = AppApprovalStatus.DENIED, decidedAt = now()) }

    override suspend fun undoDenyApp(packageName: String) = set(packageName) {
        if (it.status == AppApprovalStatus.DENIED) it.copy(status = AppApprovalStatus.PENDING, decidedAt = null) else it
    }

    override suspend fun revokeApproval(packageName: String) = set(packageName) {
        if (it.status == AppApprovalStatus.APPROVED) it.copy(status = AppApprovalStatus.PENDING, decidedAt = null) else it
    }

    override suspend fun recordFirstHandshake(packageName: String, displayName: String): AppApprovalRecord {
        val t = now()
        return AppApprovalRecord(
            packageName = packageName,
            displayName = displayName,
            status = AppApprovalStatus.PENDING,
            decidedAt = null,
            firstSeenAt = t,
            lastSeenAt = t,
            attemptCount = 1
        ).also { records[packageName] = it }
    }

    override suspend fun recordHandshakeAttempt(packageName: String) =
        set(packageName) { it.copy(lastSeenAt = now(), attemptCount = it.attemptCount + 1) }

    override suspend fun updateRequestCount(packageName: String, increment: Int) =
        set(packageName) { it.copy(requestCount = it.requestCount + increment) }

    override suspend fun clear() = records.clear()
}
