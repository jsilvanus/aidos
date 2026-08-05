package dev.aidos.git

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ChangeOrigin
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DiffHunk
import dev.aidos.kernel.DiffLine
import dev.aidos.kernel.DiffLineKind
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.FileChange
import dev.aidos.kernel.FileChangeKind
import dev.aidos.kernel.FileDiff
import dev.aidos.kernel.HunkId
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Git tool backed by JGit (RFC-0032, RFC-0053, D4, M13).
 *
 * JGit is pure JVM — no native dependency, works on Android.
 *
 * Operations: git:status, git:diff, git:add, git:commit, git:branch, git:log, git:checkout.
 * `git:push` is UNSAFE and declares it — RFC-0032 says so, and the broker will not retry it.
 *
 * Reconciliation: any operation that checks the working tree re-reads the index and status
 * on every call, so changes made outside Aidos between two steps are always reflected.
 */
class GitTool(private val repoDir: File) : Tool {

    override val id = "git"
    override val version = "0.1.0"

    private fun openGit(): Git = Git.open(repoDir)

    private val ALL_PROFILES = setOf(
        PlatformProfile.MOBILE, PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER
    )
    private val UNIVERSAL = ToolAvailability(ALL_PROFILES, AvailabilityTier.UNIVERSAL)
    private val NETWORKED = ToolAvailability(ALL_PROFILES, AvailabilityTier.NETWORKED, requiresNetwork = true)

    override fun operations(): List<ToolDescriptor> = listOf(
        op("git:status", "Status", "Show working-tree status", EffectKind.Read, Permission.GIT_READ, RecoveryClass.PURE, UNIVERSAL),
        op("git:diff", "Diff", "Show working-tree diff as structured hunks", EffectKind.Read, Permission.GIT_READ, RecoveryClass.PURE, UNIVERSAL),
        op("git:add", "Add", "Stage a path", EffectKind.Mutate(MutationScope.IN_PROJECT), Permission.GIT_WRITE, RecoveryClass.IDEMPOTENT, UNIVERSAL),
        op("git:commit", "Commit", "Create a commit", EffectKind.Mutate(MutationScope.IN_PROJECT), Permission.GIT_WRITE, RecoveryClass.IDEMPOTENT, UNIVERSAL),
        op("git:branch", "Branch", "Create or list branches", EffectKind.Mutate(MutationScope.IN_PROJECT), Permission.GIT_WRITE, RecoveryClass.IDEMPOTENT, UNIVERSAL),
        op("git:log", "Log", "Show commit history", EffectKind.Read, Permission.GIT_READ, RecoveryClass.PURE, UNIVERSAL),
        op("git:checkout", "Checkout", "Switch branch — discards uncommitted changes", EffectKind.Mutate(MutationScope.IN_PROJECT, reversible = false), Permission.GIT_WRITE, RecoveryClass.CHECKABLE, UNIVERSAL),
        op("git:push", "Push", "Push to remote — UNSAFE, cannot be retried", EffectKind.Egress("remote"), Permission.NETWORK_EGRESS, RecoveryClass.UNSAFE, NETWORKED),
    )

    private fun op(
        name: String, title: String, description: String,
        effect: EffectKind, permission: Permission, recovery: RecoveryClass,
        availability: ToolAvailability,
    ) = ToolDescriptor(
        name = name, title = title, description = description,
        inputSchema = buildJsonObject { put("type", "object") },
        effect = effect, requiredPermission = permission,
        recoveryClass = recovery, availability = availability,
    )

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult =
        when (operation) {
            "git:status"   -> runCatching { gitStatus() }.fold({ ok(it) }, { err(operation, it) })
            "git:diff"     -> runCatching { gitDiff() }.fold({ ok(it) }, { err(operation, it) })
            "git:add"      -> runCatching { gitAdd(arguments) }.fold({ ok(it) }, { err(operation, it) })
            "git:commit"   -> runCatching { gitCommit(arguments) }.fold({ ok(it) }, { err(operation, it) })
            "git:branch"   -> runCatching { gitBranch(arguments) }.fold({ ok(it) }, { err(operation, it) })
            "git:log"      -> runCatching { gitLog(arguments) }.fold({ ok(it) }, { err(operation, it) })
            "git:checkout" -> runCatching { gitCheckout(arguments) }.fold({ ok(it) }, { err(operation, it) })
            "git:push"     -> runCatching { gitPush() }.fold({ ok(it) }, { err(operation, it) })
            else           -> err(operation, "unknown operation: $operation")
        }

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        when (operation) {
            "git:add", "git:commit", "git:checkout" -> runCatching { previewMutation(operation, arguments) }
            else -> Result.failure(UnsupportedOperationException("no preview for $operation"))
        }

    override suspend fun cancel(operationId: String) = Unit

    // ── Operations ─────────────────────────────────────────────────────────────

    private fun gitStatus(): List<ContentBlock> {
        // Re-read status on every call — reconciliation for user edits outside Aidos.
        val git = openGit()
        return git.use { g ->
            val status = g.status().call()
            val lines = mutableListOf<String>()
            status.added.forEach { lines.add("A  $it") }
            status.changed.forEach { lines.add("M  $it") }
            status.removed.forEach { lines.add("D  $it") }
            status.untracked.forEach { lines.add("?  $it") }
            status.modified.forEach { if (!status.changed.contains(it)) lines.add(" M $it") }
            val text = if (lines.isEmpty()) "nothing to commit, working tree clean" else lines.joinToString("\n")
            listOf(ContentBlock.Text(text))
        }
    }

    private fun gitDiff(): List<ContentBlock> {
        val git = openGit()
        return git.use { g ->
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { formatter ->
                formatter.setRepository(g.repository)
                formatter.isDetectRenames = true
                // Scan working tree for changes vs HEAD (unstaged).
                // Use the working-tree-to-HEAD scan directly through the formatter.
                val headId = g.repository.resolve("HEAD")
                val diffs: List<DiffEntry> = if (headId != null) {
                    val reader = g.repository.newObjectReader()
                    val headTree = CanonicalTreeParser()
                    headTree.reset(reader, g.repository.resolve("HEAD^{tree}"))
                    val workTree = FileTreeIterator(g.repository)
                    formatter.scan(headTree, workTree)
                } else {
                    emptyList()
                }
                for (entry in diffs) formatter.format(entry)
            }
            val text = out.toString(Charsets.UTF_8.name())
            listOf(ContentBlock.Text(if (text.isBlank()) "no diff" else text))
        }
    }

    private fun gitAdd(args: JsonObject): List<ContentBlock> {
        val path = args["path"]?.jsonPrimitive?.content ?: "."
        val git = openGit()
        git.use { g -> g.add().addFilepattern(path).call() }
        return listOf(ContentBlock.Text("staged: $path"))
    }

    private fun gitCommit(args: JsonObject): List<ContentBlock> {
        val message = args["message"]?.jsonPrimitive?.content ?: "commit"
        val author = args["author"]?.jsonPrimitive?.content ?: "Aidos Session"
        val git = openGit()
        val rev = git.use { g ->
            g.commit()
                .setMessage(message)
                .setAuthor(author, "session@aidos.dev")
                .setCommitter("Aidos", "aidos@aidos.dev")
                .call()
        }
        return listOf(ContentBlock.Text("committed: ${rev.name.take(8)} $message"))
    }

    private fun gitBranch(args: JsonObject): List<ContentBlock> {
        val name = args["name"]?.jsonPrimitive?.content
        val git = openGit()
        return git.use { g ->
            if (name != null) {
                g.branchCreate().setName(name).call()
                listOf(ContentBlock.Text("created branch: $name"))
            } else {
                val branches = g.branchList().call().map { it.name.removePrefix("refs/heads/") }
                listOf(ContentBlock.Text(branches.joinToString("\n")))
            }
        }
    }

    private fun gitLog(args: JsonObject): List<ContentBlock> {
        val maxCount = args["maxCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
        val git = openGit()
        return git.use { g ->
            val log = g.log().setMaxCount(maxCount).call()
            val lines = log.map { "${it.name.take(8)} ${it.shortMessage}" }
            listOf(ContentBlock.Text(lines.joinToString("\n").ifBlank { "no commits" }))
        }
    }

    private fun gitCheckout(args: JsonObject): List<ContentBlock> {
        val name = args["branch"]?.jsonPrimitive?.content
            ?: return listOf(ContentBlock.Text("error: missing 'branch'"))
        val git = openGit()
        git.use { g ->
            g.checkout().setName(name).call()
        }
        return listOf(ContentBlock.Text("switched to branch: $name"))
    }

    /** `push` is UNSAFE: cannot be retried and cannot be un-done. Declared as Egress. */
    private fun gitPush(): List<ContentBlock> {
        val git = openGit()
        val results = git.use { g -> g.push().call() }
        val messages = results.flatMap { r -> r.remoteUpdates.map { u -> "${u.remoteName}: ${u.status}" } }
        return listOf(ContentBlock.Text(messages.joinToString("\n").ifBlank { "pushed" }))
    }

    // ── Preview ────────────────────────────────────────────────────────────────

    /**
     * For mutations, produce a [Preview.Diff] showing what would change.
     * For checkout, list the files that would be lost.
     */
    private fun previewMutation(operation: String, args: JsonObject): Preview {
        val git = openGit()
        return git.use { g ->
            when (operation) {
                "git:checkout" -> {
                    val status = g.status().call()
                    if (status.modified.isEmpty() && status.added.isEmpty()) {
                        Preview.NoChange
                    } else {
                        Preview.Description("Checkout will discard ${status.modified.size + status.added.size} file(s) with uncommitted changes")
                    }
                }
                else -> {
                    // Show current staged diff as a structured FileDiff.
                    val diffs = g.diff().setCached(true).call()
                    if (diffs.isEmpty()) return Preview.NoChange
                    val fileDiffs = diffs.map { entry -> toDiff(g.repository, entry) }
                    Preview.Diff(fileDiffs.first())
                }
            }
        }
    }

    private fun toDiff(repo: Repository, entry: DiffEntry): FileDiff {
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(repo)
            formatter.format(entry)
        }
        val unified = out.toString(Charsets.UTF_8.name())
        val path = entry.newPath.takeUnless { it == "/dev/null" } ?: entry.oldPath
        val kind = when (entry.changeType!!) {
            DiffEntry.ChangeType.ADD    -> FileChangeKind.ADDED
            DiffEntry.ChangeType.MODIFY -> FileChangeKind.MODIFIED
            DiffEntry.ChangeType.DELETE -> FileChangeKind.DELETED
            DiffEntry.ChangeType.RENAME -> FileChangeKind.RENAMED
            DiffEntry.ChangeType.COPY   -> FileChangeKind.COPIED
        }
        // Parse unified diff into structured hunks (D25).
        val hunks = parseUnifiedDiff(path, unified)
        val linesAdded = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADDED } }
        val linesRemoved = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.REMOVED } }
        val fileChange = FileChange(
            path = path, previousPath = if (kind == FileChangeKind.RENAMED) entry.oldPath else null,
            kind = kind, baseBlobHash = entry.oldId.name(), headBlobHash = entry.newId.name(),
            binary = false, modeChanged = false,
            hunkCount = hunks.size, linesAdded = linesAdded, linesRemoved = linesRemoved,
            review = ReviewState.NOT_REVIEWED, origin = ChangeOrigin.SESSION,
        )
        return FileDiff(fileChange, hunks)
    }

    /** Parses a unified diff text into structured [DiffHunk] list. */
    private fun parseUnifiedDiff(path: String, unified: String): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        var hunkIndex = 0
        var baseStart = 0; var headStart = 0
        val lines = mutableListOf<DiffLine>()
        val headerRegex = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

        for (line in unified.lines()) {
            val match = headerRegex.find(line)
            if (match != null) {
                if (lines.isNotEmpty()) {
                    hunks.add(makeHunk(path, hunkIndex++, baseStart, headStart, lines.toList()))
                    lines.clear()
                }
                baseStart = match.groupValues[1].toInt()
                headStart = match.groupValues[2].toInt()
            } else when {
                line.startsWith("+") && !line.startsWith("+++") ->
                    lines.add(DiffLine(DiffLineKind.ADDED, line.drop(1)))
                line.startsWith("-") && !line.startsWith("---") ->
                    lines.add(DiffLine(DiffLineKind.REMOVED, line.drop(1)))
                line.startsWith(" ") ->
                    lines.add(DiffLine(DiffLineKind.CONTEXT, line.drop(1)))
            }
        }
        if (lines.isNotEmpty()) {
            hunks.add(makeHunk(path, hunkIndex, baseStart, headStart, lines))
        }
        return hunks
    }

    private fun makeHunk(path: String, index: Int, baseStart: Int, headStart: Int, lines: List<DiffLine>) =
        DiffHunk(
            id = HunkId(path, path.hashCode().toString(), index),
            baseStart = baseStart, baseLines = lines.count { it.kind != DiffLineKind.ADDED },
            headStart = headStart, headLines = lines.count { it.kind != DiffLineKind.REMOVED },
            lines = lines,
        )

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun ok(content: List<ContentBlock>) =
        ToolCallResult("", ToolOutcome.Ok, content, TrustLevel.TRUSTED)

    private fun err(op: String, msg: String) =
        ToolCallResult(
            "", ToolOutcome.Failed(dev.aidos.kernel.AidosError(
                code = "git.error", errorClass = ErrorClass.INTERNAL, message = "$op: $msg"
            )),
            listOf(ContentBlock.Text("error: $msg")), TrustLevel.TRUSTED,
        )

    private fun err(op: String, t: Throwable) = err(op, t.message ?: t::class.simpleName ?: "unknown")
}
