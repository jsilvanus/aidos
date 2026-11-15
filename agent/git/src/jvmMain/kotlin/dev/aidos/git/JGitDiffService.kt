package dev.aidos.git

import dev.aidos.api.ChangeOrigin
import dev.aidos.api.CommitResult
import dev.aidos.api.DiffRange
import dev.aidos.api.DiffService
import dev.aidos.api.DiffSummary
import dev.aidos.api.FileChange
import dev.aidos.api.FileChangeKind
import dev.aidos.api.HunkId
import dev.aidos.api.ReviewState
import dev.aidos.kernel.ChangeOrigin as KernelChangeOrigin
import dev.aidos.kernel.DiffHunk
import dev.aidos.kernel.DiffLine
import dev.aidos.kernel.DiffLineKind
import dev.aidos.kernel.FileChange as KernelFileChange
import dev.aidos.kernel.FileChangeKind as KernelFileChangeKind
import dev.aidos.kernel.FileDiff
import dev.aidos.kernel.HunkId as KernelHunkId
import dev.aidos.kernel.ReviewState as KernelReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.dircache.DirCacheBuilder
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * JGit-backed [DiffService] implementing structured diff queries, hunk-level staging/reverting
 * with base-blob staleness detection (`diff.base_moved`), and staging-boundary commits
 * (RFC-0052, RFC-0032, D25).
 */
class JGitDiffService : DiffService {

    override suspend fun changes(projectPath: String, range: DiffRange): DiffSummary =
        withContext(Dispatchers.IO) {
            Git.open(File(projectPath)).use { git ->
                val repo = git.repository
                val entries = diffEntries(git, repo, range)
                val files = entries.map { entry ->
                    val path = entryPath(entry)
                    val baseHash = entry.oldId?.takeIf { it.toObjectId() != ObjectId.zeroId() }?.name()
                    val headHash = entry.newId?.takeIf { it.toObjectId() != ObjectId.zeroId() }?.name()
                    val unified = formatEntry(git, repo, range, path)
                    val binary = unified.contains("Binary files ") || unified.contains("GIT binary patch")
                    val parsed = if (binary) emptyList() else parseUnifiedHunks(path, baseHash ?: ZERO_BLOB, unified)
                    val added = parsed.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADDED } }
                    val removed = parsed.sumOf { h -> h.lines.count { it.kind == DiffLineKind.REMOVED } }
                    FileChange(
                        path = path,
                        previousPath = if (entry.changeType == DiffEntry.ChangeType.RENAME || entry.changeType == DiffEntry.ChangeType.COPY) entry.oldPath else null,
                        kind = entry.changeType.toApiKind(),
                        baseBlobHash = baseHash,
                        headBlobHash = headHash,
                        binary = binary,
                        modeChanged = entry.oldMode != entry.newMode,
                        hunkCount = parsed.size,
                        linesAdded = added,
                        linesRemoved = removed,
                        review = ReviewState.NOT_REVIEWED,
                        origin = ChangeOrigin.UNKNOWN,
                    )
                }
                DiffSummary(
                    range = range,
                    files = files,
                    filesChanged = files.size,
                    linesAdded = files.sumOf { it.linesAdded },
                    linesRemoved = files.sumOf { it.linesRemoved },
                )
            }
        }

    override suspend fun hunks(projectPath: String, range: DiffRange, path: String): Result<FileDiff> =
        withContext(Dispatchers.IO) {
            runCatching {
                Git.open(File(projectPath)).use { git ->
                    val repo = git.repository
                    val entry = diffEntries(git, repo, range).firstOrNull { entryPath(it) == path }
                        ?: throw NoSuchElementException("No diff entry for $path")
                    val baseHash = entry.oldId?.takeIf { it.toObjectId() != ObjectId.zeroId() }?.name()
                    val headHash = entry.newId?.takeIf { it.toObjectId() != ObjectId.zeroId() }?.name()
                    val unified = formatEntry(git, repo, range, path)
                    val binary = unified.contains("Binary files ") || unified.contains("GIT binary patch")
                    val hunks = if (binary) emptyList() else parseUnifiedHunks(path, baseHash ?: ZERO_BLOB, unified)
                    FileDiff(
                        file = KernelFileChange(
                            path = path,
                            previousPath = if (entry.changeType == DiffEntry.ChangeType.RENAME) entry.oldPath else null,
                            kind = entry.changeType.toKernelKind(),
                            baseBlobHash = baseHash,
                            headBlobHash = headHash,
                            binary = binary,
                            modeChanged = entry.oldMode != entry.newMode,
                            hunkCount = hunks.size,
                            linesAdded = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADDED } },
                            linesRemoved = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.REMOVED } },
                            review = KernelReviewState.NOT_REVIEWED,
                            origin = KernelChangeOrigin.UNKNOWN,
                        ),
                        hunks = hunks,
                    )
                }
            }
        }

    override suspend fun unified(projectPath: String, range: DiffRange, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                Git.open(File(projectPath)).use { git ->
                    val repo = git.repository
                    val entry = diffEntries(git, repo, range).firstOrNull { entryPath(it) == path }
                        ?: return@runCatching ""
                    formatEntry(git, repo, range, path)
                }
            }
        }

    override suspend fun stage(projectPath: String, hunks: List<HunkId>): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (hunks.isEmpty()) return@withContext Result.success(Unit)
            runCatching {
                Git.open(File(projectPath)).use { git ->
                    val repo = git.repository
                    val entries = diffEntries(git, repo, DiffRange.WorkingTree)
                    val byPath = hunks.groupBy { it.path }
                    val dirCache = repo.lockDirCache()
                    try {
                        val builder: DirCacheBuilder = dirCache.builder()
                        val updatedPaths = mutableSetOf<String>()
                        val removedPaths = mutableSetOf<String>()

                        for ((path, requestedHunks) in byPath) {
                            val entry = entries.firstOrNull { entryPath(it) == path }
                                ?: throw IllegalStateException("diff.base_moved: $path is no longer modified")
                            val currentBase = entry.oldId?.takeIf { it.toObjectId() != ObjectId.zeroId() }?.name() ?: ZERO_BLOB
                            if (requestedHunks.any { it.baseBlobHash != currentBase }) {
                                throw IllegalStateException("diff.base_moved: base blob moved for $path")
                            }
                            val unified = formatEntry(git, repo, DiffRange.WorkingTree, path)
                            val parsedHunks = parseUnifiedHunks(path, currentBase, unified)
                            val selectedIndices = requestedHunks.map { it.index }.toSet()

                            if (entry.changeType == DiffEntry.ChangeType.DELETE && selectedIndices.containsAll(parsedHunks.indices.toSet())) {
                                removedPaths.add(path)
                                continue
                            }

                            val baseText = readBaseContent(repo, entry)
                            val patchedText = applySelectedHunks(baseText, parsedHunks, selectedIndices, reverse = false)
                            val inserter = repo.newObjectInserter()
                            val blobId = inserter.use { ins ->
                                val id = ins.insert(Constants.OBJ_BLOB, patchedText.toByteArray(StandardCharsets.UTF_8))
                                ins.flush()
                                id
                            }
                            val cacheEntry = DirCacheEntry(path).apply {
                                fileMode = if (entry.newMode != FileMode.MISSING) entry.newMode else FileMode.REGULAR_FILE
                                setObjectId(blobId)
                            }
                            builder.add(cacheEntry)
                            updatedPaths.add(path)
                        }

                        // Preserve all untouched entries in the index.
                        for (i in 0 until dirCache.entryCount) {
                            val existing = dirCache.getEntry(i)
                            if (existing.pathString !in updatedPaths && existing.pathString !in removedPaths) {
                                builder.add(existing)
                            }
                        }
                        builder.commit()
                    } finally {
                        dirCache.unlock()
                    }
                    Unit
                }
            }
        }

    override suspend fun revert(projectPath: String, hunks: List<HunkId>): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (hunks.isEmpty()) return@withContext Result.success(Unit)
            runCatching {
                Git.open(File(projectPath)).use { git ->
                    val repo = git.repository
                    val entries = diffEntries(git, repo, DiffRange.WorkingTree)
                    val byPath = hunks.groupBy { it.path }

                    for ((path, requestedHunks) in byPath) {
                        val entry = entries.firstOrNull { entryPath(it) == path }
                            ?: throw IllegalStateException("diff.base_moved: $path is no longer modified")
                        val currentBase = entry.oldId?.takeIf { it.toObjectId() != ObjectId.zeroId() }?.name() ?: ZERO_BLOB
                        if (requestedHunks.any { it.baseBlobHash != currentBase }) {
                            throw IllegalStateException("diff.base_moved: base blob moved for $path")
                        }
                        val unified = formatEntry(git, repo, DiffRange.WorkingTree, path)
                        val parsedHunks = parseUnifiedHunks(path, currentBase, unified)
                        val selectedIndices = requestedHunks.map { it.index }.toSet()
                        val targetFile = File(projectPath, path)

                        if (entry.changeType == DiffEntry.ChangeType.ADD && selectedIndices.containsAll(parsedHunks.indices.toSet())) {
                            targetFile.delete()
                            continue
                        }
                        if (entry.changeType == DiffEntry.ChangeType.DELETE && selectedIndices.containsAll(parsedHunks.indices.toSet())) {
                            val restored = readBaseContent(repo, entry)
                            targetFile.parentFile?.mkdirs()
                            targetFile.writeText(restored, StandardCharsets.UTF_8)
                            continue
                        }

                        val workingText = if (targetFile.exists()) targetFile.readText(StandardCharsets.UTF_8) else ""
                        val revertedText = applySelectedHunks(workingText, parsedHunks, selectedIndices, reverse = true)
                        targetFile.parentFile?.mkdirs()
                        targetFile.writeText(revertedText, StandardCharsets.UTF_8)
                    }
                }
            }
        }

    override suspend fun commit(projectPath: String, message: String): CommitResult =
        withContext(Dispatchers.IO) {
            if (message.isBlank()) {
                return@withContext CommitResult.Error("commit.empty_message", "Commit message must not be empty")
            }
            try {
                Git.open(File(projectPath)).use { git ->
                    val status = git.status().call()
                    val hasStaged = status.added.isNotEmpty() || status.changed.isNotEmpty() || status.removed.isNotEmpty()
                    if (!hasStaged) {
                        return@withContext CommitResult.NothingStaged
                    }
                    val commit = git.commit().setMessage(message).call()
                    CommitResult.Success(
                        commitHash = commit.name,
                        shortMessage = commit.shortMessage,
                    )
                }
            } catch (e: Exception) {
                CommitResult.Error("commit.failed", e.message ?: "Commit failed")
            }
        }

    private fun diffEntries(git: Git, repo: Repository, range: DiffRange): List<DiffEntry> {
        return when (range) {
            is DiffRange.WorkingTree -> {
                val headIter = headTreeOrEmpty(repo)
                git.diff()
                    .setOldTree(headIter)
                    .setNewTree(FileTreeIterator(repo))
                    .call()
            }
            is DiffRange.Staged -> {
                val headIter = headTreeOrEmpty(repo)
                git.diff()
                    .setOldTree(headIter)
                    .setNewTree(DirCacheIterator(repo.readDirCache()))
                    .call()
            }
            is DiffRange.Refs -> {
                val oldIter = treeIteratorForRef(repo, range.base)
                val newIter = treeIteratorForRef(repo, range.head)
                git.diff()
                    .setOldTree(oldIter)
                    .setNewTree(newIter)
                    .call()
            }
        }
    }

    private fun headTreeOrEmpty(repo: Repository): AbstractTreeIterator {
        val headTree = repo.resolve("HEAD^{tree}") ?: return EmptyTreeIterator()
        return repo.newObjectReader().use { reader ->
            CanonicalTreeParser().apply { reset(reader, headTree) }
        }
    }

    private fun treeIteratorForRef(repo: Repository, ref: String): AbstractTreeIterator {
        val treeId = repo.resolve("$ref^{tree}") ?: return EmptyTreeIterator()
        return repo.newObjectReader().use { reader ->
            CanonicalTreeParser().apply { reset(reader, treeId) }
        }
    }

    private fun formatEntry(git: Git, repo: Repository, range: DiffRange, path: String): String {
        val out = ByteArrayOutputStream()
        val (oldIter, newIter) = when (range) {
            is DiffRange.WorkingTree -> headTreeOrEmpty(repo) to FileTreeIterator(repo)
            is DiffRange.Staged -> headTreeOrEmpty(repo) to DirCacheIterator(repo.readDirCache())
            is DiffRange.Refs -> treeIteratorForRef(repo, range.base) to treeIteratorForRef(repo, range.head)
        }
        git.diff()
            .setOldTree(oldIter)
            .setNewTree(newIter)
            .setPathFilter(PathFilter.create(path))
            .setOutputStream(out)
            .call()
        return out.toString(StandardCharsets.UTF_8)
    }

    private fun readBaseContent(repo: Repository, entry: DiffEntry): String {
        val oldId = entry.oldId?.toObjectId()
        if (oldId == null || oldId == ObjectId.zeroId()) return ""
        return repo.newObjectReader().use { reader ->
            String(reader.open(oldId).bytes, StandardCharsets.UTF_8)
        }
    }

    private fun entryPath(entry: DiffEntry): String =
        if (entry.newPath != DiffEntry.DEV_NULL) entry.newPath else entry.oldPath

    private fun applySelectedHunks(
        sourceText: String,
        hunks: List<DiffHunk>,
        selectedIndices: Set<Int>,
        reverse: Boolean,
    ): String {
        val hadTrailingNewline = sourceText.endsWith("\n")
        val sourceLines = if (sourceText.isEmpty()) emptyList() else sourceText.removeSuffix("\n").split("\n")
        val result = mutableListOf<String>()
        var cursor = 0 // 0-based index into sourceLines

        for ((idx, hunk) in hunks.withIndex()) {
            if (idx !in selectedIndices) continue
            val startLine = (if (reverse) hunk.headStart else hunk.baseStart)
            val zeroBasedStart = (startLine - 1).coerceAtLeast(0).coerceAtMost(sourceLines.size)
            while (cursor < zeroBasedStart) {
                result.add(sourceLines[cursor])
                cursor++
            }
            for (line in hunk.lines) {
                when (line.kind) {
                    DiffLineKind.CONTEXT -> {
                        if (cursor < sourceLines.size) {
                            result.add(sourceLines[cursor])
                            cursor++
                        } else {
                            result.add(line.text)
                        }
                    }
                    DiffLineKind.REMOVED -> {
                        if (reverse) {
                            result.add(line.text)
                        } else {
                            if (cursor < sourceLines.size) cursor++
                        }
                    }
                    DiffLineKind.ADDED -> {
                        if (reverse) {
                            if (cursor < sourceLines.size) cursor++
                        } else {
                            result.add(line.text)
                        }
                    }
                }
            }
        }
        while (cursor < sourceLines.size) {
            result.add(sourceLines[cursor])
            cursor++
        }
        if (result.isEmpty()) return ""
        return result.joinToString("\n") + if (hadTrailingNewline || sourceText.isEmpty()) "\n" else ""
    }

    private fun parseUnifiedHunks(path: String, baseBlobHash: String, unified: String): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        val lines = unified.lines()
        var i = 0
        var index = 0
        val headerRegex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""")

        while (i < lines.size) {
            val match = headerRegex.matchEntire(lines[i])
            if (match != null) {
                val baseStart = match.groupValues[1].toInt()
                val baseLines = match.groupValues[2].toIntOrNull() ?: 1
                val headStart = match.groupValues[3].toInt()
                val headLines = match.groupValues[4].toIntOrNull() ?: 1
                i++
                val diffLines = mutableListOf<DiffLine>()
                while (i < lines.size && !lines[i].startsWith("@@ ") && !lines[i].startsWith("diff --git ")) {
                    val l = lines[i]
                    when {
                        l.startsWith("+") -> diffLines.add(DiffLine(DiffLineKind.ADDED, l.drop(1)))
                        l.startsWith("-") -> diffLines.add(DiffLine(DiffLineKind.REMOVED, l.drop(1)))
                        l.startsWith(" ") -> diffLines.add(DiffLine(DiffLineKind.CONTEXT, l.drop(1)))
                        l.startsWith("\\ No newline at end of file") -> {
                            val last = diffLines.lastOrNull()
                            if (last != null) {
                                diffLines[diffLines.lastIndex] = last.copy(noNewlineAtEof = true)
                            }
                        }
                    }
                    i++
                }
                hunks.add(
                    DiffHunk(
                        id = KernelHunkId(path, baseBlobHash, index++),
                        baseStart = baseStart,
                        baseLines = baseLines,
                        headStart = headStart,
                        headLines = headLines,
                        lines = diffLines,
                    )
                )
            } else {
                i++
            }
        }
        return hunks
    }

    private fun DiffEntry.ChangeType.toApiKind(): FileChangeKind = when (this) {
        DiffEntry.ChangeType.ADD -> FileChangeKind.ADDED
        DiffEntry.ChangeType.MODIFY -> FileChangeKind.MODIFIED
        DiffEntry.ChangeType.DELETE -> FileChangeKind.DELETED
        DiffEntry.ChangeType.RENAME -> FileChangeKind.RENAMED
        DiffEntry.ChangeType.COPY -> FileChangeKind.COPIED
    }

    private fun DiffEntry.ChangeType.toKernelKind(): KernelFileChangeKind = when (this) {
        DiffEntry.ChangeType.ADD -> KernelFileChangeKind.ADDED
        DiffEntry.ChangeType.MODIFY -> KernelFileChangeKind.MODIFIED
        DiffEntry.ChangeType.DELETE -> KernelFileChangeKind.DELETED
        DiffEntry.ChangeType.RENAME -> KernelFileChangeKind.RENAMED
        DiffEntry.ChangeType.COPY -> KernelFileChangeKind.COPIED
    }

    private companion object {
        const val ZERO_BLOB = "0000000000000000000000000000000000000000"
    }
}
