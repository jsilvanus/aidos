package dev.aidos.testing

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Controllable temporary filesystem with injectable I/O failures (RFC-0038):
 * simulates ENOSPC, EACCES, and partial writes deterministically without touching host permissions.
 */
class InjectableFilesystem(
    val root: Path = Files.createTempDirectory("aidos-test-fs"),
) : AutoCloseable {

    enum class Fault { ENOSPC, EACCES, PARTIAL_WRITE }

    private var pendingFault: Fault? = null

    fun injectNextWriteFault(fault: Fault) {
        pendingFault = fault
    }

    fun writeText(relativePath: String, content: String) {
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root.normalize())) { "Path escapes test root: $relativePath" }
        Files.createDirectories(target.parent)

        when (pendingFault) {
            Fault.ENOSPC -> {
                pendingFault = null
                throw IOException("No space left on device (ENOSPC)")
            }
            Fault.EACCES -> {
                pendingFault = null
                throw IOException("Permission denied (EACCES)")
            }
            Fault.PARTIAL_WRITE -> {
                pendingFault = null
                val half = content.take((content.length / 2).coerceAtLeast(1))
                Files.writeString(target, half)
                throw IOException("Interrupted partial write")
            }
            null -> Files.writeString(target, content)
        }
    }

    fun readText(relativePath: String): String {
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root.normalize())) { "Path escapes test root: $relativePath" }
        return Files.readString(target)
    }

    override fun close() {
        root.toFile().deleteRecursively()
    }
}
