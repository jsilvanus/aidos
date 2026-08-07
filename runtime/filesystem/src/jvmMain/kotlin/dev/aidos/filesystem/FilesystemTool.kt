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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Filesystem tool for sessions (RFC-0034, M12).
 *
 * Every operation goes through [ResourceHandle] — escape is prevented by construction
 * ([RelPath.of] rejects `..`, absolute paths, and drive-letter prefixes). Tools never receive
 * a client-side absolute path. Every `Mutate` returns a real [Preview.Diff].
 *
 * Operations: `fs:read`, `fs:write`, `fs:list`, `fs:search`.
 */
object FilesystemTool : Tool {

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
            "fs:write" -> previewWrite(dir, arguments)
            else       -> Result.failure(UnsupportedOperationException("no preview for $operation"))
        }
    }

    override suspend fun cancel(operationId: String) = Unit

    // ── Operations ─────────────────────────────────────────────────────────────

    private suspend fun read(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:read", "missing or invalid 'path'")
        val bytes = dir.read(path).getOrElse { return error("fs:read", it.message ?: "read failed") }
        val text = bytes.decodeToString()
        return ok(listOf(ContentBlock.Text(text)))
    }

    private suspend fun write(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:write", "missing or invalid 'path'")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return error("fs:write", "missing 'content'")
        dir.write(path, content.encodeToByteArray())
            .getOrElse { return error("fs:write", it.message ?: "write failed") }
        return ok(listOf(ContentBlock.Text("wrote ${path.value}")))
    }

    private suspend fun list(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:list", "missing or invalid 'path'")
        val entries = dir.list(path).getOrElse { return error("fs:list", it.message ?: "list failed") }
        val text = entries.joinToString("\n") { e -> "${if (e.isDirectory) "d" else "f"} ${e.name}" }
        return ok(listOf(ContentBlock.Text(text)))
    }

    private suspend fun search(dir: DirHandle, args: JsonObject): ToolCallResult {
        val path = relPath(args, "path") ?: return error("fs:search", "missing or invalid 'path'")
        val pattern = args["pattern"]?.jsonPrimitive?.content
            ?: return error("fs:search", "missing 'pattern'")

        // Collect all matching lines by recursively reading files under the path.
        val matches = mutableListOf<String>()
        searchRecursive(dir, path, pattern, matches)
        val text = if (matches.isEmpty()) "no matches" else matches.joinToString("\n")
        return ok(listOf(ContentBlock.Text(text)))
    }

    private suspend fun searchRecursive(
        dir: DirHandle,
        path: RelPath,
        pattern: String,
        matches: MutableList<String>,
    ) {
        val entries = dir.list(path).getOrNull() ?: return
        for (entry in entries) {
            val entryPath = RelPath.of("${path.value}/${entry.name}").getOrNull() ?: continue
            if (entry.isDirectory) {
                searchRecursive(dir, entryPath, pattern, matches)
            } else {
                val bytes = dir.read(entryPath).getOrNull() ?: continue
                val text = bytes.decodeToString()
                text.lines().forEachIndexed { lineNum, line ->
                    if (line.contains(pattern)) {
                        matches.add("${entryPath.value}:${lineNum + 1}: $line")
                    }
                }
            }
        }
    }

    // ── Preview ────────────────────────────────────────────────────────────────

    /**
     * Computes a [Preview.Diff] for a write by reading the current content and diffing it
     * against what would be written. Returns [Preview.NoChange] for a no-op write.
     */
    private suspend fun previewWrite(dir: DirHandle, args: JsonObject): Result<Preview> {
        val path = relPath(args, "path")
            ?: return Result.failure(IllegalArgumentException("missing or invalid 'path'"))
        val newContent = args["content"]?.jsonPrimitive?.content
            ?: return Result.failure(IllegalArgumentException("missing 'content'"))

        val oldBytes = if (dir.exists(path)) dir.read(path).getOrElse { ByteArray(0) } else ByteArray(0)
        val oldText = oldBytes.decodeToString()
        val newText = newContent

        if (oldText == newText) return Result.success(Preview.NoChange)

        val fileDiff = computeDiff(path.value, oldText, newText)
        return Result.success(Preview.Diff(fileDiff))
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
        val raw = args[key]?.jsonPrimitive?.content ?: return null
        return RelPath.of(raw).getOrNull()
    }

    private fun ok(content: List<ContentBlock>) =
        ToolCallResult("", ToolOutcome.Ok, content, TrustLevel.TRUSTED)

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
