package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.usage.CategoryUsage
import fi.italeino.aidos.engine.usage.EngineUsageSnapshot
import fi.italeino.aidos.engine.usage.InMemoryUsageStore
import fi.italeino.aidos.engine.usage.UsageCategory
import fi.italeino.aidos.engine.usage.UsageStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop [UsageStore] persisting lifetime totals atomically to `usage.json` in [stateDir].
 * Session totals and load metrics are kept in memory and reset on Engine restart (RFC-0103).
 */
class FileUsageStore(
    stateDir: File,
    maxConcurrency: Int = 1,
) : UsageStore {

    @Serializable
    private data class PersistedUsage(
        val lifetime: Map<UsageCategory, CategoryUsage> = emptyMap(),
    )

    private val file = File(stateDir, "usage.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val delegate = InMemoryUsageStore(
        initialLifetime = loadLifetime(file, json),
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
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(PersistedUsage(lifetime)), Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        private fun loadLifetime(file: File, json: Json): Map<UsageCategory, CategoryUsage> {
            if (!file.isFile) return emptyMap()
            return try {
                json.decodeFromString<PersistedUsage>(file.readText(Charsets.UTF_8)).lifetime
            } catch (_: Exception) {
                runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt")) }
                emptyMap()
            }
        }
    }
}
