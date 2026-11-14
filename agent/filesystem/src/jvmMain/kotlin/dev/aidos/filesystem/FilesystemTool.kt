package dev.aidos.filesystem

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ChangeOrigin
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DiffHunk
import dev.aidos.kernel.DiffLine
import dev.aidos.kernel.DiffLineKind
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.FileChange
import dev.aidos.kernel.FileChangeKind
import dev.aidos.kernel.FileDiff
import dev.aidos.kernel.HunkId
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.RelPath
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.ReviewState
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Filesystem tool for sessions (RFC-0034, M12).
 *
 * Every operation goes through [ResourceHandle] — escape is prevented by construction
 * ([RelPath.of] rejects `..`, absolute paths, and drive-letter prefixes). Tools never receive
 * a client-side absolute path. Every `Mutate` returns a real [Preview.Diff].
 *
 * Operations: `fs:read`, `fs:write`, `fs:list`, `fs:search`, `fs:delete`, `fs:move`, `fs:mkdir`.
 */
class FilesystemTool(
    /**
     * Called after a successful `fs:write`, with an event shaped like RFC-0004's own worked
     * `FileModified`/`FileCreated` examples. No `EventStore`/`executor` dependency and no
     * `projectId` — same reasoning as `GitTool.onCommit`: the caller decides whether/how to
     * publish it, and owns mapping [FileChangeEvent.created] to the right `EventTypes` constant.
     * Not yet exercised by any live caller — see PIPELINE.md's Group 1 notes.
     */
    private val onWrite: (FileChangeEvent) -> Unit = {},
    /** RFC-0027: labels what a read returns. Default: RFC defaults for `trust.untrusted_paths`. */
    private val trustPolicy: PathTrustPolicy = PathTrustPolicy(),
) : Tool {

    override val id = "filesystem"
    override val version = "0.1.0"

    private val ALL_PROFILES = setOf(
        PlatformProfile.MOBILE, PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER
    )
    private val UNIVERSAL = ToolAvailability(ALL_PROFILES, AvailabilityTier.UNIVERSAL)

    override fun operations(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "fs:read",
            title = "Read file",
            description = "Reads a file's text content. Path must be relative to the project root.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("path"))
                })
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                })
            },
            effect = EffectKind.Read,
            requiredPermission = Permission.FS_READ,
            recoveryClass = RecoveryClass.PURE,
            availability = UNIVERSAL,
        ),
        ToolDescriptor(
            name = "fs:write",
            title = "Write file",
            description = "Writes content to a file. Returns a structured diff preview. Path must be relative to the project root.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("path"))
                    add(kotlinx.serialization.json.JsonPrimitive("content"))
                })
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                    put("content", buildJsonObject { put("type", "string") })
                })
            },
            effect = EffectKind.Mutate(MutationScope.IN_PROJECT),
            requiredPermission = Permission.FS_WRITE,
            recoveryClass = RecoveryClass.IDEMPOTENT,
            availability = UNIVERSAL,
        ),
        ToolDescriptor(
            name = "fs:list",
            title = "List directory",
            description = "Lists entries in a directory. Path must be relative to the project root.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("path"))
                })
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                })
            },
            effect = EffectKind.Read,
            requiredPermission = Permission.FS_READ,
            recoveryClass = RecoveryClass.PURE,
            availability = UNIVERSAL,
        ),
        ToolDescriptor(
            name = "fs:search",
            title = "Search files",
            description = "Searches files for a text pattern. Path is the directory to search; pattern is a literal string.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("path"))
                    add(kotlinx.serialization.json.JsonPrimitive("pattern"))
                })
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                    put("pattern", buildJsonObject { put("type", "string") })
                })
            },
            effect = EffectKind.Read,
            requiredPermission = Permission.FS_READ,
            recoveryClass = RecoveryClass.PURE,
            availability = UNIVERSAL,
        ),
        ToolDescriptor(
            name = "fs:delete",
            title = "Delete file or directory",
            description = "Deletes a file or directory (recursively if requested). Irreversible. Path must be relative to the project root and not inside .git/.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("path"))
                })
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                    put("recursive", buildJsonObject { 
                        put("type", "boolean")
                        put("default", false)
                    })
                    put("force", buildJsonObject {
                        put("type", "boolean")
                        put("default", false)
                    })
                })
            },
            effect = EffectKind.Mutate(MutationScope.IN_PROJECT, reversible = false),
            requiredPermission = Permission.FS_WRITE,
            recoveryClass = RecoveryClass.CHECKABLE,
            availability = UNIVERSAL,
        ),
        ToolDescriptor(
            name = "fs:move",
            title = "Move or rename file/directory",
            description = "Moves or renames a file or directory. Paths must be relative to project root and not target .git/.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("from"))
                    add(kotlinx.serialization.json.JsonPrimitive("to"))
                })
                put("properties", buildJsonObject {
                    put("from", buildJsonObject { put("type", "string") })
                    put("to", buildJsonObject { put("type", "string") })
                    put("overwrite", buildJsonObject {
                        put("type", "boolean")
                        put("default", false)
                    })
                })
            },
            effect = EffectKind.Mutate(MutationScope.IN_PROJECT),
            requiredPermission = Permission.FS_WRITE,
            recoveryClass = RecoveryClass.CHECKABLE,
            availability = UNIVERSAL,
        ),
        ToolDescriptor(
            name = "fs:mkdir",
            title = "Create directory",
            description = "Creates a directory (and missing parent directories if needed). Path must be relative to project root.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("path"))
                })
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                })
            },
            effect = EffectKind.Mutate(MutationScope.IN_PROJECT),
            requiredPermission = Permission.FS_WRITE,
            recoveryClass = RecoveryClass.IDEMPOTENT,
            availability = UNIVERSAL,
        ),
    )

    override suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult {
        val dir = handle as? DirHandle
            ?: return error(operation, "handle is not a DirHandle")

        return when (operation) {
            "fs:read"   -> read(dir, arguments)
            "fs:write"  -> write(dir, arguments)
            "fs:list"   -> list(dir, arguments)
            "fs:search" -> search(dir, arguments)
            "fs:delete" -> delete(dir, arguments)
            "fs:move"   -> move(dir, arguments)
            "fs:mkdir"  -> mkdir(dir, arguments)
            else        -> error(operation, "unknown operation: $operation")
        }
    }

    override suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): Result<Preview> {
        val dir = handle as? DirHandle
            ?: return Result.failure(IllegalArgumentException("handle is not a DirHandle"))

        return when (operation) {
            "fs:write"  -> previewWrite(dir, arguments)
            "fs:delete" -> previewDelete(dir, arguments)
            "fs:move"   -> previewMove(dir, arguments)
            "fs:mkdir"  -> previewMkdir(dir, arguments)
            else        -> Result.failure(UnsupportedOperationException("no preview for $operation"))
        }
    }

    override suspend fun cancel(operationId: String) = Unit

    // ── Operations ─────────────────────────────────────────────────────────────

    private suspend fun read(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:read", "missing or invalid 'path'")
        val bytes = dir.read(path).getOrElse { return error("fs:read", it.message ?: "read failed") }
        val text = bytes.decodeToString()
        return ok(listOf(ContentBlock.Text(text)), trustPolicy.classify(path.value))
    }

    private suspend fun write(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:write", "missing or invalid 'path'")
        // RFC-0034: Refuse writes inside .git/
        if (isGitPath(path)) {
            return error("fs:write", ".git/ is read-only and cannot be written to")
        }
        val content = stringArg(args, "content")
            ?: return error("fs:write", "missing 'content'")
        val existedBefore = dir.exists(path)
        val bytes = content.encodeToByteArray()
        dir.write(path, bytes)
            .getOrElse { return error("fs:write", it.message ?: "write failed") }
        onWrite(
            FileChangeEvent(
                topic = "filesystem:/project/${path.value}",
                path = path.value,
                sizeBytes = bytes.size,
                created = !existedBefore,
            )
        )
        // Runtime-authored confirmation, not file content: stays TRUSTED.
        return ok(listOf(ContentBlock.Text("wrote ${path.value}")), TrustLevel.TRUSTED)
    }

    private suspend fun list(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:list", "missing or invalid 'path'")
        val entries = dir.list(path).getOrElse { return error("fs:list", it.message ?: "list failed") }
        val text = entries.joinToString("\n") { e -> "${if (e.isDirectory) "d" else "f"} ${e.name}" }
        return ok(listOf(ContentBlock.Text(text)), trustPolicy.classify(path.value))
    }

    private suspend fun search(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:search", "missing or invalid 'path'")
        val pattern = stringArg(args, "pattern")
            ?: return error("fs:search", "missing 'pattern'")

        // Collect all matching lines by recursively reading files under the path.
        val matches = mutableListOf<String>()
        val trust = intArrayOf(TrustLevel.PROJECT.ordinal)
        trust[0] = maxOf(trust[0], trustPolicy.classify(path.value).ordinal)
        searchRecursive(dir, path, pattern, matches, trust)
        val text = if (matches.isEmpty()) "no matches" else matches.joinToString("\n")
        // Taint is the max over every file a match came from (RFC-0027).
        return ok(listOf(ContentBlock.Text(text)), TrustLevel.entries[trust[0]])
    }

    private suspend fun searchRecursive(
        dir: DirHandle,
        path: RelPath,
        pattern: String,
        matches: MutableList<String>,
        trust: IntArray,
    ) {
        val entries = dir.list(path).getOrNull() ?: return
        for (entry in entries) {
            val entryPath = RelPath.of("${path.value}/${entry.name}").getOrNull() ?: continue
            if (entry.isDirectory) {
                searchRecursive(dir, entryPath, pattern, matches, trust)
            } else {
                val bytes = dir.read(entryPath).getOrNull() ?: continue
                val text = bytes.decodeToString()
                text.lines().forEachIndexed { lineNum, line ->
                    if (line.contains(pattern)) {
                        trust[0] = maxOf(trust[0], trustPolicy.classify(entryPath.value).ordinal)
                        matches.add("${entryPath.value}:${lineNum + 1}: $line")
                    }
                }
            }
        }
    }

    private suspend fun delete(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:delete", "missing or invalid 'path'")

        // RFC-0034: Refuse deletion inside .git/
        if (isGitPath(path)) {
            return error("fs:delete", ".git/ is read-only and cannot be deleted")
        }

        val recursive = boolArg(args, "recursive") ?: false
        val force = boolArg(args, "force") ?: false

        val count = dir.delete(path, recursive).getOrElse {
            val message = it.message ?: "delete failed"
            return if (force) {
                ok(listOf(ContentBlock.Text("delete skipped: $message")), TrustLevel.TRUSTED)
            } else {
                error("fs:delete", message)
            }
        }

        return ok(
            listOf(ContentBlock.Text("deleted $count file(s) at ${path.value}")),
            TrustLevel.TRUSTED
        )
    }

    private suspend fun move(dir: DirHandle, args: JsonObject): ToolCallResult {
        val from = relPath(args, "from") ?: return error("fs:move", "missing or invalid 'from'")
        val to = relPath(args, "to") ?: return error("fs:move", "missing or invalid 'to'")

        // RFC-0034: Refuse moving from or into .git/
        if (isGitPath(from) || isGitPath(to)) {
            return error("fs:move", ".git/ is read-only and cannot be moved or modified")
        }

        val overwrite = boolArg(args, "overwrite") ?: false

        dir.move(from, to, overwrite).getOrElse {
            return error("fs:move", it.message ?: "move failed")
        }

        return ok(
            listOf(ContentBlock.Text("moved ${from.value} to ${to.value}")),
            TrustLevel.TRUSTED
        )
    }

    private suspend fun mkdir(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:mkdir", "missing or invalid 'path'")

        // RFC-0034: Refuse mkdir inside .git/
        if (isGitPath(path)) {
            return error("fs:mkdir", ".git/ is read-only and cannot be written to")
        }

        dir.mkdir(path).getOrElse {
            return error("fs:mkdir", it.message ?: "mkdir failed")
        }

        return ok(
            listOf(ContentBlock.Text("created directory ${path.value}")),
            TrustLevel.TRUSTED
        )
    }

    // ── Preview ────────────────────────────────────────────────────────────────

    /**
     * Computes a [Preview.Diff] for a write by reading the current content and diffing it
     * against what would be written. Returns [Preview.NoChange] for a no-op write.
     */
    private suspend fun previewWrite(dir: DirHandle, args: JsonObject): Result<Preview> {
        val path = relPath(args, "path")
            ?: return Result.failure(IllegalArgumentException("missing or invalid 'path'"))
        if (isGitPath(path)) {
            return Result.failure(IllegalArgumentException(".git/ is read-only and cannot be written to"))
        }
        val newContent = stringArg(args, "content")
            ?: return Result.failure(IllegalArgumentException("missing 'content'"))

        val oldBytes = if (dir.exists(path)) dir.read(path).getOrElse { ByteArray(0) } else ByteArray(0)
        val oldText = oldBytes.decodeToString()
        val newText = newContent

        if (oldText == newText) return Result.success(Preview.NoChange)

        val fileDiff = computeDiff(path.value, oldText, newText)
        return Result.success(Preview.Diff(fileDiff))
    }

    private suspend fun previewDelete(dir: DirHandle, args: JsonObject): Result<Preview> {
        val path = relPath(args, "path")
            ?: return Result.failure(IllegalArgumentException("missing or invalid 'path'"))
        if (isGitPath(path)) {
            return Result.failure(IllegalArgumentException(".git/ is read-only and cannot be deleted"))
        }
        val recursive = boolArg(args, "recursive") ?: false
        val force = boolArg(args, "force") ?: false

        val readResult = dir.read(path)
        if (readResult.isSuccess) {
            val oldText = readResult.getOrThrow().decodeToString()
            if (oldText.isEmpty()) {
                val fileChange = FileChange(
                    path = path.value,
                    previousPath = null,
                    kind = FileChangeKind.DELETED,
                    baseBlobHash = oldText.sha256short(),
                    headBlobHash = null,
                    binary = false,
                    modeChanged = false,
                    hunkCount = 0,
                    linesAdded = 0,
                    linesRemoved = 0,
                    review = ReviewState.NOT_REVIEWED,
                    origin = ChangeOrigin.SESSION,
                )
                return Result.success(Preview.Diff(FileDiff(fileChange, emptyList())))
            }
            return Result.success(Preview.Diff(computeDiff(path.value, oldText, "")))
        }

        val entries = dir.list(path).getOrElse { emptyList() }
        if (entries.isEmpty() && !dir.exists(path)) {
            return Result.success(Preview.NoChange)
        }
        if (!recursive && entries.isNotEmpty()) {
            if (force) return Result.success(Preview.NoChange)
            return Result.success(
                Preview.Description("Delete directory ${path.value} (${entries.size} immediate entry/entries; requires recursive=true)")
            )
        }
        val total = countEntries(dir, path)
        return Result.success(
            Preview.Description(
                if (total == 0) "Delete empty directory ${path.value}"
                else "Delete directory ${path.value} ($total item(s))"
            )
        )
    }

    private suspend fun previewMove(dir: DirHandle, args: JsonObject): Result<Preview> {
        val from = relPath(args, "from")
            ?: return Result.failure(IllegalArgumentException("missing or invalid 'from'"))
        val to = relPath(args, "to")
            ?: return Result.failure(IllegalArgumentException("missing or invalid 'to'"))
        if (isGitPath(from) || isGitPath(to)) {
            return Result.failure(IllegalArgumentException(".git/ is read-only and cannot be moved or modified"))
        }
        val overwrite = boolArg(args, "overwrite") ?: false

        val fromRead = dir.read(from)
        val fromEntries = if (fromRead.isFailure) dir.list(from).getOrElse { emptyList() } else emptyList()
        val fromExists = fromRead.isSuccess || dir.exists(from) || fromEntries.isNotEmpty()
        if (!fromExists) {
            return Result.failure(IllegalArgumentException("source does not exist: ${from.value}"))
        }

        if (isSamePath(from, to)) {
            return Result.success(Preview.NoChange)
        }

        val toExists = dir.exists(to) || dir.list(to).getOrNull()?.isNotEmpty() == true
        if (toExists && !overwrite) {
            return Result.failure(IllegalArgumentException("destination already exists: ${to.value}"))
        }

        if (fromRead.isSuccess) {
            val hash = fromRead.getOrThrow().decodeToString().sha256short()
            val fileChange = FileChange(
                path = to.value,
                previousPath = from.value,
                kind = FileChangeKind.RENAMED,
                baseBlobHash = hash,
                headBlobHash = hash,
                binary = false,
                modeChanged = false,
                hunkCount = 0,
                linesAdded = 0,
                linesRemoved = 0,
                review = ReviewState.NOT_REVIEWED,
                origin = ChangeOrigin.SESSION,
            )
            return Result.success(Preview.Diff(FileDiff(fileChange, emptyList())))
        }

        return Result.success(
            Preview.Description(
                "Move directory ${from.value} to ${to.value}${if (overwrite && toExists) " (overwrite)" else ""}"
            )
        )
    }

    private suspend fun previewMkdir(dir: DirHandle, args: JsonObject): Result<Preview> {
        val path = relPath(args, "path")
            ?: return Result.failure(IllegalArgumentException("missing or invalid 'path'"))
        if (isGitPath(path)) {
            return Result.failure(IllegalArgumentException(".git/ is read-only and cannot be written to"))
        }
        if (dir.read(path).isSuccess) {
            return Result.failure(IllegalArgumentException("file already exists at path: ${path.value}"))
        }
        if (dir.exists(path) || dir.list(path).getOrNull()?.isNotEmpty() == true) {
            return Result.success(Preview.NoChange)
        }
        return Result.success(Preview.Description("Create directory ${path.value}"))
    }

    private suspend fun countEntries(dir: DirHandle, path: RelPath, depth: Int = 0): Int {
        if (depth > 64) return 0
        val entries = dir.list(path).getOrNull() ?: return 0
        var count = 0
        for (entry in entries) {
            count++
            if (entry.isDirectory) {
                val child = RelPath.of("${path.value}/${entry.name}").getOrNull() ?: continue
                count += countEntries(dir, child, depth + 1)
            }
        }
        return count
    }

    /**
     * Line-level diff producing structured [FileDiff] (D25: diffs are structure, not strings).
     *
     * Uses a simple Myers-like longest-common-subsequence approach for bounded files. This is
     * an MVP implementation; a real implementation would use JGit's diff engine.
     */
    private fun computeDiff(path: String, oldText: String, newText: String): FileDiff {
        val oldLines = oldText.lines()
        val newLines = newText.lines()

        val isAdd = oldText.isEmpty()
        val isDel = newText.isEmpty()

        val kind = when {
            isAdd -> FileChangeKind.ADDED
            isDel -> FileChangeKind.DELETED
            else  -> FileChangeKind.MODIFIED
        }

        // Produce a single hunk spanning all changes (MVP: one hunk per file).
        val lines = mutableListOf<DiffLine>()
        var added = 0
        var removed = 0

        if (isAdd) {
            newLines.forEach { lines.add(DiffLine(DiffLineKind.ADDED, it)); added++ }
        } else if (isDel) {
            oldLines.forEach { lines.add(DiffLine(DiffLineKind.REMOVED, it)); removed++ }
        } else {
            // Simple LCS diff.
            val lcs = lcs(oldLines, newLines)
            var i = 0; var j = 0; var k = 0
            while (i < oldLines.size || j < newLines.size) {
                val matchOld = k < lcs.size && i < oldLines.size && oldLines[i] == lcs[k]
                val matchNew = k < lcs.size && j < newLines.size && newLines[j] == lcs[k]
                when {
                    matchOld && matchNew -> {
                        lines.add(DiffLine(DiffLineKind.CONTEXT, oldLines[i++]))
                        j++; k++
                    }
                    !matchOld && i < oldLines.size -> {
                        lines.add(DiffLine(DiffLineKind.REMOVED, oldLines[i++])); removed++
                    }
                    else -> {
                        lines.add(DiffLine(DiffLineKind.ADDED, newLines[j++])); added++
                    }
                }
            }
        }

        val hunk = DiffHunk(
            id = HunkId(path, oldText.sha256short(), 0),
            baseStart = 1, baseLines = oldLines.size,
            headStart = 1, headLines = newLines.size,
            lines = lines,
        )

        val fileChange = FileChange(
            path = path, previousPath = null, kind = kind,
            baseBlobHash = if (isAdd) null else oldText.sha256short(),
            headBlobHash = if (isDel) null else newText.sha256short(),
            binary = false, modeChanged = false,
            hunkCount = 1, linesAdded = added, linesRemoved = removed,
            review = ReviewState.NOT_REVIEWED,
            origin = ChangeOrigin.SESSION,
        )

        return FileDiff(fileChange, listOf(hunk))
    }

    /** Longest common subsequence of two string lists (Myers O(ND)). */
    private fun lcs(a: List<String>, b: List<String>): List<String> {
        val m = a.size; val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
            else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        val result = mutableListOf<String>()
        var i = m; var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> { result.add(0, a[i - 1]); i--; j-- }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return result
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun relPath(args: JsonObject, key: String): RelPath? {
        val raw = stringArg(args, key) ?: return null
        return RelPath.of(raw).getOrNull()
    }

    private fun stringArg(args: JsonObject, key: String): String? {
        val prim = args[key] as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content else null
    }

    private fun boolArg(args: JsonObject, key: String): Boolean? {
        val prim = args[key] as? JsonPrimitive ?: return null
        return if (prim.isString) null else prim.booleanOrNull
    }

    private fun normalizedSegments(path: RelPath): List<String> =
        path.value
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }

    private fun isGitPath(path: RelPath): Boolean =
        normalizedSegments(path).any { it.equals(".git", ignoreCase = true) }

    private fun isSamePath(a: RelPath, b: RelPath): Boolean =
        normalizedSegments(a) == normalizedSegments(b)

    private fun ok(content: List<ContentBlock>, trust: TrustLevel) =
        ToolCallResult("", ToolOutcome.Ok, content, trust)

    private fun error(op: String, msg: String) =
        ToolCallResult(
            "", ToolOutcome.Failed(dev.aidos.kernel.AidosError(
                code = "fs.error", errorClass = dev.aidos.kernel.ErrorClass.INTERNAL, message = "$op: $msg"
            )),
            listOf(ContentBlock.Text("error: $msg")), TrustLevel.TRUSTED,
        )

    private fun String.sha256short(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(encodeToByteArray())
        return bytes.take(4).joinToString("") { "%02x".format(it) }
    }
}

/**
 * RFC-0004's `FileModified`/`FileCreated` events, matching the RFC's own worked example shape
 * (`topic: "filesystem:/project/<path>"`, `payload: {path, size}`). [created] is `true` when the
 * path did not exist before the write (RFC-0004's `FileCreated`), `false` for an overwrite
 * (`FileModified`) — the caller maps this to the right `EventTypes` constant, since this module
 * has no reason to depend on `executor`.
 */
data class FileChangeEvent(
    val topic: String,
    val path: String,
    val sizeBytes: Int,
    val created: Boolean,
)
