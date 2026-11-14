package fi.italeino.aidos.engine.license

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * In-memory implementation of [LicenseAcceptanceStore] for tests and ephemeral hosts.
 *
 * All state is guarded by [mutex] and exposed via a hot [MutableStateFlow] initialized to `emptyList()`.
 */
class InMemoryLicenseAcceptanceStore : LicenseAcceptanceStore {

    private val mutex = Mutex()
    private val records = mutableMapOf<Pair<String, String>, LicenseAcceptanceRecord>()
    private val acceptances = MutableStateFlow<List<LicenseAcceptanceRecord>>(emptyList())

    override suspend fun isAccepted(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): Boolean = mutex.withLock {
        val record = records[modelId to modelVersion] ?: return@withLock false
        record.licenseTextDigest == licenseTextDigest
    }

    override suspend fun accept(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): LicenseAcceptanceRecord = mutex.withLock {
        val now = Instant.now().toString()
        val record = LicenseAcceptanceRecord(
            modelId = modelId,
            modelVersion = modelVersion,
            licenseTextDigest = licenseTextDigest,
            acceptedAt = now
        )
        records[modelId to modelVersion] = record
        acceptances.value = sortedSnapshotLocked()
        record
    }

    override suspend fun getAcceptance(
        modelId: String,
        modelVersion: String
    ): LicenseAcceptanceRecord? = mutex.withLock {
        records[modelId to modelVersion]
    }

    override suspend fun listAllAcceptances(): List<LicenseAcceptanceRecord> = mutex.withLock {
        sortedSnapshotLocked()
    }

    override fun watchAcceptances(): Flow<List<LicenseAcceptanceRecord>> = acceptances.asStateFlow()

    override suspend fun revoke(modelId: String, modelVersion: String): Boolean = mutex.withLock {
        val removed = records.remove(modelId to modelVersion) != null
        if (removed) {
            acceptances.value = sortedSnapshotLocked()
        }
        removed
    }

    override suspend fun clear() = mutex.withLock {
        records.clear()
        acceptances.value = emptyList()
    }

    private fun sortedSnapshotLocked(): List<LicenseAcceptanceRecord> =
        records.values
            .sortedWith(compareByDescending<LicenseAcceptanceRecord> { it.acceptedAt }.thenByDescending { it.modelId })
}
