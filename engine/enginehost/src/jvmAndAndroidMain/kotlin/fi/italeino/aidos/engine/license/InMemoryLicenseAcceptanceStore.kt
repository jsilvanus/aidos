package fi.italeino.aidos.engine.license

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

/**
 * In-memory implementation of LicenseAcceptanceStore for testing.
 *
 * All data is discarded when the instance is garbage collected.
 * This is NOT suitable for production; use EncryptedLicenseAcceptanceStore on Android.
 */
class InMemoryLicenseAcceptanceStore : LicenseAcceptanceStore {
    
    private val records = mutableMapOf<String, LicenseAcceptanceRecord>()
    private val changesFlow = MutableSharedFlow<List<LicenseAcceptanceRecord>>(replay = 1)

    init {
        // Initialize with empty list broadcast so watchers don't hang
        try {
            changesFlow.tryEmit(emptyList())
        } catch (e: Exception) {
            // tryEmit may fail in some contexts; that's OK for tests
        }
    }

    override suspend fun isAccepted(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): Boolean {
        val key = "$modelId/$modelVersion"
        val record = records[key] ?: return false
        return record.licenseTextDigest == licenseTextDigest
    }

    override suspend fun accept(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): LicenseAcceptanceRecord {
        val key = "$modelId/$modelVersion"
        val now = Instant.now().toString()
        val record = LicenseAcceptanceRecord(
            modelId = modelId,
            modelVersion = modelVersion,
            licenseTextDigest = licenseTextDigest,
            acceptedAt = now
        )
        records[key] = record
        changesFlow.tryEmit(records.values.toList())
        return record
    }

    override suspend fun getAcceptance(
        modelId: String,
        modelVersion: String
    ): LicenseAcceptanceRecord? {
        val key = "$modelId/$modelVersion"
        return records[key]
    }

    override suspend fun listAllAcceptances(): List<LicenseAcceptanceRecord> {
        return records.values
            .sortedWith(compareByDescending<LicenseAcceptanceRecord> { it.acceptedAt }.thenByDescending { it.modelId })
    }

    override fun watchAcceptances(): Flow<List<LicenseAcceptanceRecord>> {
        return changesFlow
    }

    override suspend fun revoke(modelId: String, modelVersion: String): Boolean {
        val key = "$modelId/$modelVersion"
        val removed = records.remove(key) != null
        if (removed) {
            changesFlow.tryEmit(records.values.toList())
        }
        return removed
    }

    override suspend fun clear() {
        records.clear()
        changesFlow.tryEmit(emptyList())
    }
}
