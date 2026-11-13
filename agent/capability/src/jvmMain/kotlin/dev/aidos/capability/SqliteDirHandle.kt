package dev.aidos.capability

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.RelPath
import java.io.File
import java.io.IOException
import java.nio.file.Path

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
        // Use Path.startsWith for component-based comparison, not string prefix matching.
        // String prefix matching is vulnerable to prefix confusion: root `/tmp/proj` would
        // incorrectly accept `/tmp/proj-evil` as a child path.
        require(target.toPath().startsWith(root.toPath())) {
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

    override suspend fun delete(relative: RelPath, recursive: Boolean): Result<Int> = runCatching {
        val target = resolve(relative)
        var count = 0
        
        when {
            !target.exists() -> count = 0
            target.isFile -> {
                count = if (target.delete()) 1 else throw IOException("Failed to delete file: ${target.path}")
            }
            target.isDirectory -> {
                if (recursive) {
                    count = deleteRecursively(target)
                } else {
                    val contents = target.listFiles()
                    if (contents != null && contents.isNotEmpty()) {
                        throw IOException("Directory not empty: ${target.path}")
                    }
                    count = if (target.delete()) 1 else throw IOException("Failed to delete directory: ${target.path}")
                }
            }
        }
        count
    }

    override suspend fun move(from: RelPath, to: RelPath, overwrite: Boolean): Result<Unit> = runCatching {
        val source = resolve(from)
        val dest = resolve(to)
        
        if (!source.exists()) {
            throw IOException("Source does not exist: ${source.path}")
        }
        
        if (dest.exists() && !overwrite) {
            throw IOException("Destination already exists: ${dest.path}")
        }
        
        if (dest.exists()) {
            dest.delete()
        }
        
        dest.parentFile?.mkdirs()
        if (!source.renameTo(dest)) {
            throw IOException("Failed to move ${source.path} to ${dest.path}")
        }
    }

    override suspend fun mkdir(relative: RelPath): Result<Unit> = runCatching {
        val target = resolve(relative)
        if (!target.exists()) {
            if (!target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.path}")
            }
        }
    }

    private fun deleteRecursively(file: File): Int {
        var count = 0
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                count += deleteRecursively(child)
            }
        }
        if (file.delete()) {
            count++
        } else {
            throw IOException("Failed to delete: ${file.path}")
        }
        return count
    }
}
