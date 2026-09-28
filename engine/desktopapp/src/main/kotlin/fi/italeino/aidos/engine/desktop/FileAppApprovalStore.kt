package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.approval.AppApprovalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * JSON-file [AppApprovalStore] for the desktop debug host (RFC-0103).
 *
 * Same state transitions as Android's EncryptedAppApprovalStore, but unencrypted: there is no
 * Binder caller on desktop, so records only ever come from the debug "simulate handshake" action
 * and hold no secrets. Writes go to a temp file first and are moved into place, so a crash
 * mid-write can't truncate the store.
 */
class FileAppApprovalStore(
    private val file: File,
    private val now: () -> Instant = Instant::now,
) : AppApprovalStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val records = MutableStateFlow(read())

    override suspend fun getApproval(packageName: String): AppApprovalRecord? = records.value[packageName]

    override suspend fun listAllApprovals(): List<AppApprovalRecord> = sorted(records.value)

    override fun watchApprovals(): Flow<List<AppApprovalRecord>> = records.map(::sorted)

    override suspend fun approveApp(packageName: String): AppApprovalRecord? = update(packageName) {
        val stamp = now().toString()
        it.copy(status = AppApprovalStatus.APPROVED, decidedAt = stamp, lastSeenAt = stamp, attemptCount = it.attemptCount + 1)
    }

    override suspend fun denyApp(packageName: String): AppApprovalRecord? = update(packageName) {
        val stamp = now().toString()
        it.copy(status = AppApprovalStatus.DENIED, decidedAt = stamp, lastSeenAt = stamp, attemptCount = it.attemptCount + 1)
    }

    override suspend fun undoDenyApp(packageName: String): AppApprovalRecord? = update(packageName) {
        if (it.status != AppApprovalStatus.DENIED) it
        else it.copy(status = AppApprovalStatus.PENDING, decidedAt = null, lastSeenAt = now().toString())
    }

    override suspend fun revokeApproval(packageName: String): AppApprovalRecord? = update(packageName) {
        if (it.status != AppApprovalStatus.APPROVED) it
        else it.copy(status = AppApprovalStatus.PENDING, decidedAt = null, lastSeenAt = now().toString())
    }

    override suspend fun recordFirstHandshake(packageName: String, displayName: String): AppApprovalRecord =
        mutex.withLock {
            val stamp = now().toString()
            val record = AppApprovalRecord(
                packageName = packageName,
                displayName = displayName,
                status = AppApprovalStatus.PENDING,
                decidedAt = null,
                firstSeenAt = stamp,
                lastSeenAt = stamp,
                attemptCount = 1,
                requestCount = 0,
            )
            commit(records.value + (packageName to record))
            record
        }

    override suspend fun recordHandshakeAttempt(packageName: String): AppApprovalRecord? = update(packageName) {
        it.copy(lastSeenAt = now().toString(), attemptCount = it.attemptCount + 1)
    }

    override suspend fun updateRequestCount(packageName: String, increment: Int): AppApprovalRecord? =
        update(packageName) { it.copy(requestCount = it.requestCount + increment) }

    override suspend fun clear() {
        mutex.withLock { commit(emptyMap()) }
    }

    private suspend fun update(
        packageName: String,
        transform: (AppApprovalRecord) -> AppApprovalRecord,
    ): AppApprovalRecord? = mutex.withLock {
        val current = records.value[packageName] ?: return@withLock null
        val updated = transform(current)
        if (updated != current) commit(records.value + (packageName to updated))
        updated
    }

    private suspend fun commit(next: Map<String, AppApprovalRecord>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(sorted(next)))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        records.value = next
    }

    private fun read(): Map<String, AppApprovalRecord> {
        if (!file.isFile) return emptyMap()
        return try {
            json.decodeFromString<List<AppApprovalRecord>>(file.readText()).associateBy { it.packageName }
        } catch (_: Exception) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            emptyMap()
        }
    }

    private fun sorted(records: Map<String, AppApprovalRecord>): List<AppApprovalRecord> =
        records.values.sortedByDescending { it.lastSeenAt }
}
