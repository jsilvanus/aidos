package dev.aidos.capability

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.RelPath
import java.io.File

/**
 * JVM/Desktop DirHandle implementation (RFC-0018, RFC-0034).
 *
 * All paths are resolved against [rootAbsolutePath] using [RelPath], which already rejects
 * absolute paths, parent traversal, and NUL bytes. A second guard here refuses symlinks that
 * would point outside the root — defense in depth, not a replacement for [RelPath].
 *
 * This implementation is JVM-only; Android will provide its own actual backed by SAF or
 * scoped storage when the Android module arrives (Phase 4).
 */
class SqliteDirHandle(
    override val capabilityId: CapabilityId,
    private val rootAbsolutePath: String,
    @Suppress("UNUSED_PARAMETER") private val manager: SqliteCapabilityManager,
) : DirHandle {

    private fun resolve(relative: RelPath): File {
        val target = File(rootAbsolutePath, relative.value).canonicalFile
        val root = File(rootAbsolutePath).canonicalFile
        require(target.path.startsWith(root.path)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }
        return target
    }

    override suspend fun read(relative: RelPath): Result<ByteArray> = runCatching {
        resolve(relative).readBytes()
    }

    override suspend fun write(relative: RelPath, content: ByteArray): Result<Unit> = runCatching {
        val target = resolve(relative)
        target.parentFile?.mkdirs()
        target.writeBytes(content)
    }

    override suspend fun list(relative: RelPath): Result<List<DirEntry>> = runCatching {
        resolve(relative).listFiles()?.map { f ->
            DirEntry(f.name, f.isDirectory, if (f.isDirectory) 0L else f.length())
        } ?: emptyList()
    }

    override suspend fun exists(relative: RelPath): Boolean =
        resolve(relative).exists()
}
