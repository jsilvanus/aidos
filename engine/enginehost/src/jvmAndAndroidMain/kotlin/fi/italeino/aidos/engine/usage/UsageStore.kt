package fi.italeino.aidos.engine.usage

import kotlinx.serialization.Serializable
import java.util.EnumMap
import java.util.Locale

/**
 * Inference accounting categories for the Engine (RFC-0103 amendment 2026-11-20).
 */
@Serializable
enum class UsageCategory {
    CHAT,
    EMBEDDINGS,
    DECISIONS,
    STT,
    VISION,
    TTS;

    val wireName: String
        get() = name.lowercase()
}

@Serializable
data class CategoryUsage(
    val requests: Long = 0L,
    val tokens: Long = 0L,
    val audioSeconds: Double = 0.0,
    val images: Long = 0L,
) {
    operator fun plus(other: CategoryUsage): CategoryUsage = CategoryUsage(
        requests = requests + other.requests,
        tokens = tokens + other.tokens,
        audioSeconds = audioSeconds + other.audioSeconds,
        images = images + other.images,
    )
}

@Serializable
data class LastUsageEvent(
    val category: UsageCategory,
    val summary: String,
    val timestampMs: Long,
)

@Serializable
data class EngineUsageSnapshot(
    val activeRequests: Int = 0,
    val queuedRequests: Int = 0,
    val maxConcurrency: Int = 1,
    val lastUsage: LastUsageEvent? = null,
    val session: Map<UsageCategory, CategoryUsage> = emptyMap(),
    val lifetime: Map<UsageCategory, CategoryUsage> = emptyMap(),
)

/**
 * Engine-wide inference usage accounting store (RFC-0103).
 *
 * Tracks session totals (since Engine start), lifetime totals (persisted by host-specific stores),
 * the most recent usage event, and current active/queued request load.
 */
interface UsageStore {
    fun record(
        category: UsageCategory,
        tokens: Long = 0L,
        audioSeconds: Double = 0.0,
        images: Long = 0L,
        timestampMs: Long = System.currentTimeMillis(),
    )

    fun updateLoad(activeRequests: Int, queuedRequests: Int, maxConcurrency: Int)

    fun snapshot(): EngineUsageSnapshot
}

/**
 * Thread-safe in-memory [UsageStore]. Host-specific persistent stores ([FileUsageStore],
 * [SharedPrefsUsageStore]) pass [initialLifetime] and an [onPersistLifetime] hook.
 */
open class InMemoryUsageStore(
    initialLifetime: Map<UsageCategory, CategoryUsage> = emptyMap(),
    maxConcurrency: Int = 1,
    private val onPersistLifetime: ((Map<UsageCategory, CategoryUsage>) -> Unit)? = null,
) : UsageStore {

    private val lock = Any()
    private val session = EnumMap<UsageCategory, CategoryUsage>(UsageCategory::class.java)
    private val lifetime = EnumMap<UsageCategory, CategoryUsage>(UsageCategory::class.java)
    private var lastUsage: LastUsageEvent? = null
    private var activeRequests: Int = 0
    private var queuedRequests: Int = 0
    private var maxConcurrency: Int = maxConcurrency.coerceAtLeast(1)

    init {
        for (cat in UsageCategory.entries) {
            session[cat] = CategoryUsage()
            lifetime[cat] = initialLifetime[cat] ?: CategoryUsage()
        }
    }

    override fun record(
        category: UsageCategory,
        tokens: Long,
        audioSeconds: Double,
        images: Long,
        timestampMs: Long,
    ) {
        val delta = CategoryUsage(
            requests = 1L,
            tokens = tokens.coerceAtLeast(0L),
            audioSeconds = audioSeconds.coerceAtLeast(0.0),
            images = images.coerceAtLeast(0L),
        )
        val summary = formatUsageSummary(category, delta)
        synchronized(lock) {
            session[category] = (session[category] ?: CategoryUsage()) + delta
            lifetime[category] = (lifetime[category] ?: CategoryUsage()) + delta
            lastUsage = LastUsageEvent(
                category = category,
                summary = summary,
                timestampMs = timestampMs,
            )
            onPersistLifetime?.invoke(lifetime.toMap())
        }
    }

    override fun updateLoad(activeRequests: Int, queuedRequests: Int, maxConcurrency: Int) {
        synchronized(lock) {
            this.activeRequests = activeRequests.coerceAtLeast(0)
            this.queuedRequests = queuedRequests.coerceAtLeast(0)
            this.maxConcurrency = maxConcurrency.coerceAtLeast(1)
        }
    }

    override fun snapshot(): EngineUsageSnapshot = synchronized(lock) {
        EngineUsageSnapshot(
            activeRequests = activeRequests,
            queuedRequests = queuedRequests,
            maxConcurrency = maxConcurrency,
            lastUsage = lastUsage,
            session = session.toMap(),
            lifetime = lifetime.toMap(),
        )
    }

    companion object {
        fun formatUsageSummary(category: UsageCategory, delta: CategoryUsage): String = when (category) {
            UsageCategory.CHAT,
            UsageCategory.EMBEDDINGS,
            UsageCategory.DECISIONS -> if (delta.tokens > 0L) "${delta.tokens} tok" else "1 req"
            UsageCategory.VISION -> when {
                delta.images > 0L && delta.tokens > 0L -> "${delta.images} img · ${delta.tokens} tok"
                delta.images > 0L -> "${delta.images} img"
                delta.tokens > 0L -> "${delta.tokens} tok"
                else -> "1 req"
            }
            UsageCategory.STT,
            UsageCategory.TTS -> if (delta.audioSeconds > 0.0) {
                "${String.format(Locale.US, "%.1f", delta.audioSeconds)}s audio"
            } else {
                "1 req"
            }
        }
    }
}
