package fi.italeino.aidos.engine.approval

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * EncryptedSharedPreferences-backed implementation of AppApprovalStore (RFC-0103).
 *
 * Stores each app's record as a JSON blob in encrypted preferences,
 * keyed by package name. Survives Engine restarts.
 *
 * Emits updates via a Flow so ConnectedAppsScreen can react in real-time.
 */
class EncryptedAppApprovalStore(context: Context) : AppApprovalStore {
    
    private val prefs: SharedPreferences
    private val appChangesFlow = MutableSharedFlow<List<AppApprovalRecord>>(replay = 1)
    
    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        prefs = EncryptedSharedPreferences.create(
            context,
            "aidos_engine_approvals",  // file name
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    override suspend fun getApproval(packageName: String): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            val json = prefs.getString(packageName, null) ?: return@withContext null
            try {
                Json.decodeFromString<AppApprovalRecord>(json)
            } catch (e: Exception) {
                null
            }
        }
    
    override suspend fun listAllApprovals(): List<AppApprovalRecord> =
        withContext(Dispatchers.IO) {
            val all = prefs.all
                .filterValue { it is String }
                .mapNotNull { (_, value) ->
                    try {
                        Json.decodeFromString<AppApprovalRecord>(value as String)
                    } catch (e: Exception) {
                        null
                    }
                }
            all.sortedByDescending { it.lastSeenAt }
        }
    
    override fun watchApprovals(): Flow<List<AppApprovalRecord>> = appChangesFlow
    
    override suspend fun approveApp(packageName: String): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            val current = getApproval(packageName) ?: return@withContext null
            val updated = current.copy(
                status = AppApprovalStatus.APPROVED,
                decidedAt = Instant.now().toString(),
                lastSeenAt = Instant.now().toString(),
                attemptCount = current.attemptCount + 1
            )
            saveRecord(updated)
            emitUpdate()
            updated
        }
    
    override suspend fun denyApp(packageName: String): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            val current = getApproval(packageName) ?: return@withContext null
            val updated = current.copy(
                status = AppApprovalStatus.DENIED,
                decidedAt = Instant.now().toString(),
                lastSeenAt = Instant.now().toString(),
                attemptCount = current.attemptCount + 1
            )
            saveRecord(updated)
            emitUpdate()
            updated
        }
    
    override suspend fun undoDenyApp(packageName: String): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            val current = getApproval(packageName) ?: return@withContext null
            if (current.status != AppApprovalStatus.DENIED) return@withContext current
            val updated = current.copy(
                status = AppApprovalStatus.PENDING,
                decidedAt = null,
                lastSeenAt = Instant.now().toString()
            )
            saveRecord(updated)
            emitUpdate()
            updated
        }
    
    override suspend fun revokeApproval(packageName: String): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            val current = getApproval(packageName) ?: return@withContext null
            if (current.status != AppApprovalStatus.APPROVED) return@withContext current
            val updated = current.copy(
                status = AppApprovalStatus.PENDING,
                decidedAt = null,
                lastSeenAt = Instant.now().toString()
            )
            saveRecord(updated)
            emitUpdate()
            updated
        }
    
    override suspend fun recordFirstHandshake(
        packageName: String,
        displayName: String
    ): AppApprovalRecord =
        withContext(Dispatchers.IO) {
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
            saveRecord(record)
            emitUpdate()
            record
        }
    
    override suspend fun recordHandshakeAttempt(packageName: String): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            val current = getApproval(packageName) ?: return@withContext null
            val updated = current.copy(
                lastSeenAt = Instant.now().toString(),
                attemptCount = current.attemptCount + 1
            )
            saveRecord(updated)
            emitUpdate()
            updated
        }
    
    override suspend fun updateRequestCount(packageName: String, increment: Int): AppApprovalRecord? =
        withContext(Dispatchers.IO) {
            val current = getApproval(packageName) ?: return@withContext null
            val updated = current.copy(requestCount = current.requestCount + increment)
            saveRecord(updated)
            emitUpdate()
            updated
        }
    
    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().clear().apply()
            emitUpdate()
        }
    }
    
    private suspend fun saveRecord(record: AppApprovalRecord) {
        withContext(Dispatchers.IO) {
            val json = Json.encodeToString(record)
            prefs.edit().putString(record.packageName, json).apply()
        }
    }
    
    private suspend fun emitUpdate() {
        val all = listAllApprovals()
        appChangesFlow.emit(all)
    }
}
