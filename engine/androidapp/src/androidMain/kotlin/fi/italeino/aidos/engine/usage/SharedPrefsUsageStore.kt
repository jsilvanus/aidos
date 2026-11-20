package fi.italeino.aidos.engine.usage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android [UsageStore] persisting lifetime totals in [SharedPreferences] so they survive
 * Engine restarts while session totals reset on Engine start (RFC-0103).
 */
class SharedPrefsUsageStore(
    private val prefs: SharedPreferences,
    maxConcurrency: Int = 1,
) : UsageStore {

    constructor(context: Context, maxConcurrency: Int = 1) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        maxConcurrency = maxConcurrency,
    )

    @Serializable
    private data class PersistedUsage(
        val lifetime: Map<UsageCategory, CategoryUsage> = emptyMap(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val delegate = InMemoryUsageStore(
        initialLifetime = loadLifetime(prefs, json),
        maxConcurrency = maxConcurrency,
        onPersistLifetime = ::saveLifetime,
    )

    override fun record(
        category: UsageCategory,
        tokens: Long,
        audioSeconds: Double,
        images: Long,
        timestampMs: Long,
    ) = delegate.record(category, tokens, audioSeconds, images, timestampMs)

    override fun updateLoad(activeRequests: Int, queuedRequests: Int, maxConcurrency: Int) =
        delegate.updateLoad(activeRequests, queuedRequests, maxConcurrency)

    override fun snapshot(): EngineUsageSnapshot = delegate.snapshot()

    private fun saveLifetime(lifetime: Map<UsageCategory, CategoryUsage>) {
        prefs.edit().putString(KEY_LIFETIME, json.encodeToString(PersistedUsage(lifetime))).apply()
    }

    companion object {
        private const val PREFS_NAME = "engine_usage"
        private const val KEY_LIFETIME = "lifetime_json"

        private fun loadLifetime(prefs: SharedPreferences, json: Json): Map<UsageCategory, CategoryUsage> {
            val raw = prefs.getString(KEY_LIFETIME, null) ?: return emptyMap()
            return try {
                json.decodeFromString<PersistedUsage>(raw).lifetime
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
