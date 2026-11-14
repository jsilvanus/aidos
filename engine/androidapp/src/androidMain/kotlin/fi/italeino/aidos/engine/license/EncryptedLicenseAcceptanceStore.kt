package fi.italeino.aidos.engine.license

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
 * Android EncryptedSharedPreferences-backed implementation of [LicenseAcceptanceStore] (RFC-0103).
 *
 * Stores each model's acceptance record as a JSON blob in encrypted preferences,
 * keyed by `modelId/modelVersion`. Survives Engine restarts.
 *
 * All mutations and reads are serialized with [mutex] and exposed via [MutableStateFlow]
 * seeded with the persisted records at construction time.
 */
class EncryptedLicenseAcceptanceStore(context: Context) : LicenseAcceptanceStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val prefs: SharedPreferences
    private val acceptances: MutableStateFlow<List<LicenseAcceptanceRecord>>

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "aidos_engine_licenses",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        acceptances = MutableStateFlow(readAllLocked())
    }

    override suspend fun isAccepted(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = getAcceptanceLocked(modelId, modelVersion)
            record != null && record.licenseTextDigest == licenseTextDigest
        }
    }

    override suspend fun accept(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): LicenseAcceptanceRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = keyFor(modelId, modelVersion)
            val record = LicenseAcceptanceRecord(
                modelId = modelId,
                modelVersion = modelVersion,
                licenseTextDigest = licenseTextDigest,
                acceptedAt = Instant.now().toString()
            )
            prefs.edit().putString(key, json.encodeToString(record)).commit()
            acceptances.value = readAllLocked()
            record
        }
    }

    override suspend fun getAcceptance(
        modelId: String,
        modelVersion: String
    ): LicenseAcceptanceRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            getAcceptanceLocked(modelId, modelVersion)
        }
    }

    override suspend fun listAllAcceptances(): List<LicenseAcceptanceRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAllLocked()
        }
    }

    override fun watchAcceptances(): Flow<List<LicenseAcceptanceRecord>> = acceptances.asStateFlow()

    override suspend fun revoke(modelId: String, modelVersion: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = keyFor(modelId, modelVersion)
            val existed = prefs.contains(key)
            if (existed) {
                prefs.edit().remove(key).commit()
                acceptances.value = readAllLocked()
            }
            existed
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prefs.edit().clear().commit()
                acceptances.value = emptyList()
            }
        }
    }

    private fun getAcceptanceLocked(modelId: String, modelVersion: String): LicenseAcceptanceRecord? {
        val raw = prefs.getString(keyFor(modelId, modelVersion), null) ?: return null
        return runCatching { json.decodeFromString<LicenseAcceptanceRecord>(raw) }.getOrNull()
    }

    private fun readAllLocked(): List<LicenseAcceptanceRecord> =
        prefs.all
            .mapNotNull { (_, value) ->
                val raw = value as? String ?: return@mapNotNull null
                runCatching { json.decodeFromString<LicenseAcceptanceRecord>(raw) }.getOrNull()
            }
            .sortedWith(compareByDescending<LicenseAcceptanceRecord> { it.acceptedAt }.thenByDescending { it.modelId })

    private fun keyFor(modelId: String, modelVersion: String): String = "$modelId/$modelVersion"
}
