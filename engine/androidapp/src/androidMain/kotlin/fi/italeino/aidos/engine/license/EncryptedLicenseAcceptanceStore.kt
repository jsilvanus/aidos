package fi.italeino.aidos.engine.license

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

/**
 * Android EncryptedSharedPreferences-backed implementation of LicenseAcceptanceStore (RFC-0103).
 *
 * Stores each model's acceptance record as a JSON blob in encrypted preferences,
 * keyed by "modelId/modelVersion". Survives Engine restarts.
 *
 * Emits updates via a Flow so settings/license screens can react in real-time.
 */
class EncryptedLicenseAcceptanceStore(context: Context) : LicenseAcceptanceStore {
    
    private val prefs: SharedPreferences
    private val acceptanceChangesFlow = MutableSharedFlow<List<LicenseAcceptanceRecord>>(replay = 1)

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        prefs = EncryptedSharedPreferences.create(
            context,
            "aidos_engine_licenses",  // file name
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun isAccepted(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): Boolean = withContext(Dispatchers.IO) {
        val record = getAcceptance(modelId, modelVersion)
        return@withContext record != null && record.licenseTextDigest == licenseTextDigest
    }

    override suspend fun accept(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): LicenseAcceptanceRecord = withContext(Dispatchers.IO) {
        val key = keyFor(modelId, modelVersion)
        val now = java.time.Instant.now().toString()
        val record = LicenseAcceptanceRecord(
            modelId = modelId,
            modelVersion = modelVersion,
            licenseTextDigest = licenseTextDigest,
            acceptedAt = now
        )
        val json = Json.encodeToString(record)
        prefs.edit().putString(key, json).apply()
        emitUpdate()
        record
    }

    override suspend fun getAcceptance(
        modelId: String,
        modelVersion: String
    ): LicenseAcceptanceRecord? = withContext(Dispatchers.IO) {
        val key = keyFor(modelId, modelVersion)
        val json = prefs.getString(key, null) ?: return@withContext null
        try {
            Json.decodeFromString<LicenseAcceptanceRecord>(json)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun listAllAcceptances(): List<LicenseAcceptanceRecord> = withContext(Dispatchers.IO) {
        prefs.all
            .mapNotNull { (_, value) ->
                if (value !is String) return@mapNotNull null
                try {
                    Json.decodeFromString<LicenseAcceptanceRecord>(value)
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.acceptedAt }
    }

    override fun watchAcceptances(): Flow<List<LicenseAcceptanceRecord>> {
        return acceptanceChangesFlow
    }

    override suspend fun revoke(modelId: String, modelVersion: String): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(modelId, modelVersion)
        val existed = prefs.contains(key)
        if (existed) {
            prefs.edit().remove(key).apply()
            emitUpdate()
        }
        existed
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        emitUpdate()
    }

    private suspend fun emitUpdate() {
        val all = listAllAcceptances()
        acceptanceChangesFlow.emit(all)
    }

    private fun keyFor(modelId: String, modelVersion: String): String {
        return "$modelId/$modelVersion"
    }
}
