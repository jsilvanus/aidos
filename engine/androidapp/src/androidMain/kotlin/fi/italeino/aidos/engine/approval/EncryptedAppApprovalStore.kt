package fi.italeino.aidos.engine.approval

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * EncryptedSharedPreferences-backed implementation of [AppApprovalStore] (RFC-0103).
 *
 * Stores each app's record as a JSON blob in encrypted preferences,
 * keyed by package name. Survives Engine restarts.
 *
 * All mutations and reads are serialized with [mutex] and exposed via [MutableStateFlow]
 * seeded with the persisted records at construction time.
 */
class EncryptedAppApprovalStore(context: Context) : AppApprovalStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val prefs: SharedPreferences
    private val approvals: MutableStateFlow<List<AppApprovalRecord>>

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "aidos_engine_approvals",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        approvals = MutableStateFlow(readAllLocked())
    }

    override suspend fun getApproval(packageName: String): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            mutex.withLock { readLocked(packageName) }
        }

    override suspend fun listAllApprovals(): List<AppApprovalRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock { readAllLocked() }
        }

    override fun watchApprovals(): Flow<List<AppApprovalRecord>> = approvals.asStateFlow()

    override suspend fun approveApp(packageName: String): AppApprovalRecord? =
        update(packageName) { current ->
            val now = Instant.now().toString()
            current.copy(
                status = AppApprovalStatus.APPROVED,
                decidedAt = now,
                lastSeenAt = now,
                attemptCount = current.attemptCount + 1
            )
        }

    override suspend fun denyApp(packageName: String): AppApprovalRecord? =
        update(packageName) { current ->
            val now = Instant.now().toString()
            current.copy(
                status = AppApprovalStatus.DENIED,
                decidedAt = now,
                lastSeenAt = now,
                attemptCount = current.attemptCount + 1
            )
        }

    override suspend fun undoDenyApp(packageName: String): AppApprovalRecord? =
        update(packageName) { current ->
            if (current.status != AppApprovalStatus.DENIED) return@update current
            current.copy(
                status = AppApprovalStatus.PENDING,
                decidedAt = null,
                lastSeenAt = Instant.now().toString()
            )
        }

    override suspend fun revokeApproval(packageName: String): AppApprovalRecord? =
        update(packageName) { current ->
            if (current.status != AppApprovalStatus.APPROVED) return@update current
            current.copy(
                status = AppApprovalStatus.PENDING,
                decidedAt = null,
                agentAllowed = false,
                lastSeenAt = Instant.now().toString()
            )
        }

    override suspend fun setAgentAllowed(packageName: String, allowed: Boolean): AppApprovalRecord? =
        update(packageName) { current ->
            if (current.agentAllowed == allowed) return@update current
            current.copy(agentAllowed = allowed)
        }

    override suspend fun setLocalityLimit(packageName: String, localityLimit: String?): AppApprovalRecord? =
        update(packageName) { current ->
            if (current.localityLimit == localityLimit) return@update current
            current.copy(localityLimit = localityLimit)
        }

    override suspend fun recordLocalityRefusal(
        packageName: String,
        requiredLocality: String,
        placement: String,
    ): AppApprovalRecord? =
        update(packageName) { current ->
            current.copy(
                localityRefusalCount = current.localityRefusalCount + 1,
                lastLocalityRefusalAt = Instant.now().toString(),
                lastLocalityRefusalCode = "required_$requiredLocality|placement_$placement",
            )
        }

    override suspend fun recordFirstHandshake(
        packageName: String,
        displayName: String
    ): AppApprovalRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = Instant.now().toString()
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
            saveLocked(record)
            approvals.value = readAllLocked()
            record
        }
    }

    override suspend fun recordHandshakeAttempt(packageName: String): AppApprovalRecord? =
        update(packageName) { current ->
            current.copy(
                lastSeenAt = Instant.now().toString(),
                attemptCount = current.attemptCount + 1
            )
        }

    override suspend fun updateRequestCount(packageName: String, increment: Int): AppApprovalRecord? =
        update(packageName) { current ->
            current.copy(requestCount = current.requestCount + increment)
        }

    override suspend fun recordClientVersions(packageName: String, sdkVersion: String?, apiVersion: Int?): AppApprovalRecord? =
        update(packageName) { current ->
            current.copy(
                sdkVersion = sdkVersion ?: current.sdkVersion,
                apiVersion = apiVersion ?: current.apiVersion
            )
        }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prefs.edit().clear().commit()
                approvals.value = emptyList()
            }
        }
    }

    private suspend fun update(
        packageName: String,
        transform: (AppApprovalRecord) -> AppApprovalRecord,
    ): AppApprovalRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readLocked(packageName) ?: return@withLock null
            val updated = transform(current)
            if (updated != current) {
                saveLocked(updated)
                approvals.value = readAllLocked()
            }
            updated
        }
    }

    private fun readLocked(packageName: String): AppApprovalRecord? {
        val raw = prefs.getString(packageName, null) ?: return null
        return runCatching { json.decodeFromString<AppApprovalRecord>(raw) }.getOrNull()
    }

    private fun readAllLocked(): List<AppApprovalRecord> =
        prefs.all
            .mapNotNull { (_, value) ->
                val raw = value as? String ?: return@mapNotNull null
                runCatching { json.decodeFromString<AppApprovalRecord>(raw) }.getOrNull()
            }
            .sortedByDescending { it.lastSeenAt }

    private fun saveLocked(record: AppApprovalRecord) {
        prefs.edit().putString(record.packageName, json.encodeToString(record)).commit()
    }
}
