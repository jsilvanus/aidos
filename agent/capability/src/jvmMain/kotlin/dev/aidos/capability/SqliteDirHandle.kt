package dev.aidos.capability

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.RelPath
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

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

    private fun canonicalRoot(): Path {
        val rootFile = File(rootAbsolutePath).canonicalFile
        return if (rootFile.exists()) rootFile.toPath().toRealPath() else rootFile.toPath().normalize()
    }

    private fun resolve(relative: RelPath): Path {
        val root = canonicalRoot()
        val normalizedRel = relative.value.replace('\\', '/')
        val lexical = root.resolve(normalizedRel).normalize()
        // Use Path.startsWith for component-based comparison, not string prefix matching.
        // String prefix matching is vulnerable to prefix confusion: root `/tmp/proj` would
        // incorrectly accept `/tmp/proj-evil` as a child path.
        require(lexical.startsWith(root)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }
        val canonical = lexical.toFile().canonicalFile.toPath()
        require(canonical.startsWith(root)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }
        return canonical
    }

    private fun resolveNoFollow(relative: RelPath): Path {
        val root = canonicalRoot()
        val normalizedRel = relative.value.replace('\\', '/')
        val lexical = root.resolve(normalizedRel).normalize()
        require(lexical.startsWith(root)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }
        if (lexical == root) {
            return root
        }
        val parent = lexical.parent
            ?: throw IllegalArgumentException("path escape detected: $relative resolves outside root $rootAbsolutePath")
        val canonicalParent = parent.toFile().canonicalFile.toPath()
        require(canonicalParent.startsWith(root)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }
        val target = canonicalParent.resolve(lexical.fileName).normalize()
        require(target.startsWith(root)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }
        return target
    }

    override suspend fun read(relative: RelPath): Result<ByteArray> = runCatching {
        Files.readAllBytes(resolve(relative))
    }

    override suspend fun write(relative: RelPath, content: ByteArray): Result<Unit> = runCatching {
        val root = canonicalRoot()
        val target = resolve(relative)
        require(target != root) {
            "Cannot write to root directory: $relative"
        }
        val parent = target.parent
            ?: throw IOException("Missing parent directory for $relative")
        require(parent.startsWith(root)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }

        Files.createDirectories(parent)
        val realParent = parent.toRealPath()
        require(realParent.startsWith(root)) {
            "path escape detected: $relative parent resolves outside root $rootAbsolutePath"
        }

        val realTarget = realParent.resolve(target.fileName)
        if (Files.isDirectory(realTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Target is a directory: $realTarget")
        }

        val tempFile = Files.createTempFile(realParent, ".aidos-", ".tmp")
        try {
            Files.write(tempFile, content)
            try {
                Files.move(
                    tempFile,
                    realTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, realTarget, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tempFile) }
        }
    }

    override suspend fun list(relative: RelPath): Result<List<DirEntry>> = runCatching {
        resolve(relative).toFile().listFiles()?.map { f ->
            DirEntry(f.name, f.isDirectory, if (f.isDirectory) 0L else f.length())
        } ?: emptyList()
    }

    override suspend fun exists(relative: RelPath): Boolean =
        Files.exists(resolve(relative))

    override suspend fun delete(relative: RelPath, recursive: Boolean): Result<Int> = runCatching {
        val root = canonicalRoot()
        val target = resolveNoFollow(relative)
        require(target != root) {
            "Cannot delete root directory"
        }

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching 0
        }

        if (Files.isSymbolicLink(target)) {
            Files.delete(target)
            return@runCatching 1
        }

        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            if (recursive) {
                deleteRecursively(root, target)
            } else {
                val isNonEmpty = Files.list(target).use { stream -> stream.findFirst().isPresent }
                if (isNonEmpty) {
                    throw IOException("Directory not empty: $target")
                }
                Files.delete(target)
                1
            }
        } else {
            Files.delete(target)
            1
        }
    }

    override suspend fun move(from: RelPath, to: RelPath, overwrite: Boolean): Result<Unit> = runCatching {
        val root = canonicalRoot()
        val source = resolveNoFollow(from)
        val dest = resolveNoFollow(to)
        require(source != root && dest != root) {
            "Cannot move root directory"
        }

        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Source does not exist: $source")
        }

        // Handle same normalized path as an explicit safe no-op instead of deleting source/dest.
        if (source == dest) {
            return@runCatching
        }

        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw IOException("Destination already exists: $dest")
        }

        val destParent = dest.parent
            ?: throw IOException("Missing parent directory for destination: $dest")
        Files.createDirectories(destParent)
        require(destParent.toRealPath().startsWith(root)) {
            "path escape detected: $to parent resolves outside root $rootAbsolutePath"
        }

        if (overwrite) {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(source, dest)
        }
    }

    override suspend fun mkdir(relative: RelPath): Result<Unit> = runCatching {
        val root = canonicalRoot()
        val target = resolve(relative)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target)) {
                throw IOException("Path exists and is not a directory: $target")
            }
            return@runCatching
        }
        Files.createDirectories(target)
        require(target.toRealPath().startsWith(root)) {
            "path escape detected: $relative resolves outside root $rootAbsolutePath"
        }
    }

    private fun deleteRecursively(root: Path, target: Path): Int {
        require(target.startsWith(root) && target != root) {
            "path escape detected: $target resolves outside root $rootAbsolutePath"
        }
        var count = 0
        Files.walkFileTree(
            target,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || Files.isSymbolicLink(dir)) {
                        // Never follow symlinked directories; delete only the symlink itself.
                        require(dir.normalize().startsWith(root)) {
                            "path escape detected: $dir resolves outside root $rootAbsolutePath"
                        }
                        dir.parent?.let { parent ->
                            require(parent.toRealPath().startsWith(root)) {
                                "path escape detected: $dir parent resolves outside root $rootAbsolutePath"
                            }
                        }
                        Files.delete(dir)
                        count++
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    require(dir.toRealPath().startsWith(root)) {
                        "path escape detected: $dir resolves outside root $rootAbsolutePath"
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(file.normalize().startsWith(root)) {
                        "path escape detected: $file resolves outside root $rootAbsolutePath"
                    }
                    file.parent?.let { parent ->
                        require(parent.toRealPath().startsWith(root)) {
                            "path escape detected: $file parent resolves outside root $rootAbsolutePath"
                        }
                    }
                    Files.delete(file)
                    count++
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    require(dir.toRealPath().startsWith(root)) {
                        "path escape detected: $dir resolves outside root $rootAbsolutePath"
                    }
                    Files.delete(dir)
                    count++
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return count
    }
}
