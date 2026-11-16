package dev.aidos.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * RFC-0022 "Acquisition": a model file whose digest does not match is moved out of the model
 * store into `quarantine/`, not kept in place and not silently discarded.
 *
 * Nothing in the Engine reads this directory: the catalogue, `/v1/models` and the loader only
 * look at the model store, so a quarantined file counts as not installed. There is deliberately
 * no way to release a file from here (future work in the RFC).
 */
object ModelQuarantine {
    const val DIR_NAME = "quarantine"
    const val SUFFIX = ".quarantined"

    // Bounds on disk use: a multi-gigabyte mismatched file is kept for examination, not forever.
    // Checked at engine start and after every new quarantine.
    const val MAX_QUARANTINED_FILES = 2
    const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    private val log = Logger.getLogger(ModelQuarantine::class.java.name)

    /** The quarantine directory that belongs to the model store [storeDir]. */
    fun dirFor(storeDir: File): File = File(storeDir, DIR_NAME)

    /**
     * Moves [file] into [quarantineDir]. Returns the quarantined file, or null when the move
     * failed and the file was deleted instead: a mismatched file never stays in the store.
     */
    fun quarantine(
        file: File,
        modelId: String,
        expectedDigest: String,
        actualDigest: String,
        sourceUrl: String?,
        quarantineDir: File = dirFor(file.absoluteFile.parentFile),
        nowMillis: Long = System.currentTimeMillis(),
    ): File? {
        val quarantined = try {
            quarantineDir.mkdirs()
            val target = File(quarantineDir, "${file.name}.${actualDigest.removePrefix("sha256:").take(12)}$SUFFIX")
            // Atomic, so the file is never half in the store and half in quarantine.
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            target.setLastModified(nowMillis) // age counts from quarantine, not from download
            writeSidecar(target, modelId, expectedDigest, actualDigest, sourceUrl, nowMillis)
            target
        } catch (e: Exception) {
            log.log(Level.WARNING, "Could not quarantine ${file.name}; deleting it instead", e)
            file.delete()
            null
        }
        enforceRetention(quarantineDir, nowMillis)
        return quarantined
    }

    /** Deletes quarantined files older than [MAX_AGE_MILLIS], then all but the newest [MAX_QUARANTINED_FILES]. */
    fun enforceRetention(quarantineDir: File, nowMillis: Long = System.currentTimeMillis()) {
        val files = quarantineDir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
            .sortedByDescending { it.lastModified() }
        files.forEachIndexed { index, f ->
            if (index >= MAX_QUARANTINED_FILES || nowMillis - f.lastModified() > MAX_AGE_MILLIS) {
                f.delete()
            }
        }
        // A sidecar without its file is just clutter.
        quarantineDir.listFiles { f -> f.isFile && f.name.endsWith("$SUFFIX.json") }.orEmpty().forEach {
            if (!File(it.path.removeSuffix(".json")).exists()) it.delete()
        }
    }

    private fun writeSidecar(target: File, modelId: String, expected: String, actual: String, url: String?, now: Long) {
        File("${target.path}.json").writeText(
            """{"modelId":${q(modelId)},"expectedDigest":${q(expected)},"actualDigest":${q(actual)},""" +
                """"sourceUrl":${url?.let { q(it) } ?: "null"},"quarantinedAtMillis":$now}"""
        )
    }

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
