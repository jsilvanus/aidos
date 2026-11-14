package dev.aidos.filesystem

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.FileChangeKind
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RelPath
import dev.aidos.kernel.ToolOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M12 done-when (RFC-0034):
 *
 * 1. Read, write, list, search — all through ResourceHandle.
 * 2. Every Mutate (write) returns a real Preview.Diff.
 * 3. Escape attempts are denied by the handle (RelPath.of), not by a check inside the tool.
 */
class FilesystemToolTest {

    private val capId: CapabilityId = CapabilityId("cap-1")
    private val handle = InMemoryDirHandle(capId)
    private val tool = FilesystemTool()

    @Test
    fun `read returns file content`() = runTest {
        handle.seed("src/main.kt", "fun main() {}")
        val result = tool.execute(
            handle, "fs:read",
            buildJsonObject { put("path", "src/main.kt") }
        )
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertEquals("fun main() {}", text)
    }

    @Test
    fun `write stores content and returns ok`() = runTest {
        val result = tool.execute(
            handle, "fs:write",
            buildJsonObject { put("path", "new-file.txt"); put("content", "hello") }
        )
        assertIs<ToolOutcome.Ok>(result.outcome)
        assertEquals("hello", handle.readText("new-file.txt"))
    }

    @Test
    fun `list returns entries`() = runTest {
        handle.seed("src/a.kt", "")
        handle.seed("src/b.kt", "")
        val result = tool.execute(
            handle, "fs:list",
            buildJsonObject { put("path", "src") }
        )
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertTrue(text.contains("a.kt"))
        assertTrue(text.contains("b.kt"))
    }

    @Test
    fun `search finds matching lines`() = runTest {
        handle.seed("src/foo.kt", "class Foo {}\nclass Bar {}\n")
        val result = tool.execute(
            handle, "fs:search",
            buildJsonObject { put("path", "src"); put("pattern", "Foo") }
        )
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertTrue(text.contains("Foo"), "expected match: $text")
    }

    @Test
    fun `write preview returns structured FileDiff not a string`() = runTest {
        handle.seed("README.md", "# Old title\n")
        val preview = tool.preview(
            handle, "fs:write",
            buildJsonObject { put("path", "README.md"); put("content", "# New title\n") }
        )
        assertTrue(preview.isSuccess)
        val p = preview.getOrThrow()
        assertIs<Preview.Diff>(p, "expected Preview.Diff, got $p")
        assertNotNull(p.fileDiff)
        assertTrue(p.fileDiff.hunks.isNotEmpty(), "diff must have at least one hunk")
    }

    @Test
    fun `write preview for new file returns Added diff`() = runTest {
        val preview = tool.preview(
            handle, "fs:write",
            buildJsonObject { put("path", "brand-new.txt"); put("content", "line one\nline two\n") }
        )
        assertTrue(preview.isSuccess)
        val p = preview.getOrThrow()
        assertIs<Preview.Diff>(p)
        assertEquals(dev.aidos.kernel.FileChangeKind.ADDED, p.fileDiff.file.kind)
    }

    @Test
    fun `write preview for identical content returns NoChange`() = runTest {
        handle.seed("same.txt", "unchanged")
        val preview = tool.preview(
            handle, "fs:write",
            buildJsonObject { put("path", "same.txt"); put("content", "unchanged") }
        )
        assertTrue(preview.isSuccess)
        assertIs<Preview.NoChange>(preview.getOrThrow())
    }

    @Test
    fun `path traversal is rejected before reaching the tool`() {
        // RelPath.of rejects ".." — the tool never sees the path.
        val badPath = RelPath.of("../outside.txt")
        assertTrue(badPath.isFailure, ".. must be rejected by RelPath.of")

        val absPath = RelPath.of("/etc/passwd")
        assertTrue(absPath.isFailure, "absolute path must be rejected by RelPath.of")
    }

    @Test
    fun `unknown operation returns Failed`() = runTest {
        val result = tool.execute(
            handle, "fs:unknown",
            buildJsonObject {}
        )
        assertIs<ToolOutcome.Failed>(result.outcome)
    }

    @Test
    fun `operations all have RecoveryClass`() {
        // RecoveryClass is non-nullable in ToolDescriptor — this test asserts all operations
        // are registered and the recovery class is correctly typed per RFC-0034's table.
        val ops = tool.operations().associateBy { it.name }
        assertNotNull(ops["fs:read"])
        assertNotNull(ops["fs:write"])
        assertNotNull(ops["fs:list"])
        assertNotNull(ops["fs:search"])
        assertEquals(dev.aidos.kernel.RecoveryClass.PURE, ops["fs:read"]!!.recoveryClass)
        assertEquals(dev.aidos.kernel.RecoveryClass.IDEMPOTENT, ops["fs:write"]!!.recoveryClass)
    }

    @Test
    fun `write of a new path fires onWrite with created true`() = runTest {
        val events = mutableListOf<FileChangeEvent>()
        val watchedTool = FilesystemTool(onWrite = { events.add(it) })

        watchedTool.execute(handle, "fs:write", buildJsonObject { put("path", "brand-new.txt"); put("content", "hello") })

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("filesystem:/project/brand-new.txt", event.topic)
        assertEquals("brand-new.txt", event.path)
        assertEquals(5, event.sizeBytes)
        assertTrue(event.created, "a write to a path that didn't exist before should report created=true")
    }

    @Test
    fun `write of an existing path fires onWrite with created false`() = runTest {
        handle.seed("existing.txt", "old content")
        val events = mutableListOf<FileChangeEvent>()
        val watchedTool = FilesystemTool(onWrite = { events.add(it) })

        watchedTool.execute(handle, "fs:write", buildJsonObject { put("path", "existing.txt"); put("content", "new content") })

        assertEquals(1, events.size)
        assertTrue(!events.single().created, "overwriting an existing path should report created=false")
    }

    @Test
    fun `failed write does not fire onWrite`() = runTest {
        val events = mutableListOf<FileChangeEvent>()
        val watchedTool = FilesystemTool(onWrite = { events.add(it) })

        // Missing 'content' — the tool returns Failed before ever calling dir.write().
        val result = watchedTool.execute(handle, "fs:write", buildJsonObject { put("path", "no-content.txt") })
        assertIs<ToolOutcome.Failed>(result.outcome)
        assertTrue(events.isEmpty(), "a failed write must not fire onWrite")
    }

    @Test
    fun `delete preview returns Deleted diff for file without mutating`() = runTest {
        handle.seed("obsolete.txt", "line 1\nline 2\n")
        val preview = tool.preview(
            handle, "fs:delete",
            buildJsonObject { put("path", "obsolete.txt") }
        )
        assertTrue(preview.isSuccess, "expected success, got $preview")
        val p = assertIs<Preview.Diff>(preview.getOrThrow())
        assertEquals(FileChangeKind.DELETED, p.fileDiff.file.kind)
        assertEquals("obsolete.txt", p.fileDiff.file.path)
        assertEquals("line 1\nline 2\n", handle.readText("obsolete.txt"))
    }

    @Test
    fun `delete preview returns Deleted diff for empty file`() = runTest {
        handle.seed("empty.txt", "")
        val preview = tool.preview(
            handle, "fs:delete",
            buildJsonObject { put("path", "empty.txt") }
        )
        assertTrue(preview.isSuccess)
        val p = assertIs<Preview.Diff>(preview.getOrThrow())
        assertEquals(FileChangeKind.DELETED, p.fileDiff.file.kind)
    }

    @Test
    fun `delete preview returns Description for directory and NoChange for missing path`() = runTest {
        handle.seed("dir/a.txt", "a")
        handle.seed("dir/sub/b.txt", "b")

        val dirPreview = tool.preview(
            handle, "fs:delete",
            buildJsonObject { put("path", "dir"); put("recursive", true) }
        )
        assertTrue(dirPreview.isSuccess)
        val desc = assertIs<Preview.Description>(dirPreview.getOrThrow())
        assertTrue(desc.text.contains("dir"))
        assertEquals("a", handle.readText("dir/a.txt"))

        val missingPreview = tool.preview(
            handle, "fs:delete",
            buildJsonObject { put("path", "does-not-exist.txt") }
        )
        assertTrue(missingPreview.isSuccess)
        assertIs<Preview.NoChange>(missingPreview.getOrThrow())
    }

    @Test
    fun `move preview returns Renamed diff for file and NoChange for same normalized path`() = runTest {
        handle.seed("src/old.kt", "class Old")
        val preview = tool.preview(
            handle, "fs:move",
            buildJsonObject { put("from", "src/old.kt"); put("to", "src/new.kt") }
        )
        assertTrue(preview.isSuccess, "expected move preview success, got $preview")
        val diff = assertIs<Preview.Diff>(preview.getOrThrow())
        assertEquals(FileChangeKind.RENAMED, diff.fileDiff.file.kind)
        assertEquals("src/old.kt", diff.fileDiff.file.previousPath)
        assertEquals("src/new.kt", diff.fileDiff.file.path)
        assertEquals("class Old", handle.readText("src/old.kt"))
        assertNull(handle.readText("src/new.kt"))

        val samePreview = tool.preview(
            handle, "fs:move",
            buildJsonObject { put("from", "src/old.kt"); put("to", "./src/old.kt"); put("overwrite", true) }
        )
        assertTrue(samePreview.isSuccess)
        assertIs<Preview.NoChange>(samePreview.getOrThrow())
    }

    @Test
    fun `move preview returns Description for directory`() = runTest {
        handle.seed("pkg/a.kt", "class A")
        val preview = tool.preview(
            handle, "fs:move",
            buildJsonObject { put("from", "pkg"); put("to", "pkg2") }
        )
        assertTrue(preview.isSuccess)
        val desc = assertIs<Preview.Description>(preview.getOrThrow())
        assertTrue(desc.text.contains("pkg"))
        assertTrue(desc.text.contains("pkg2"))
        assertEquals("class A", handle.readText("pkg/a.kt"))
    }

    @Test
    fun `mkdir preview returns Description and NoChange if directory already exists`() = runTest {
        val preview = tool.preview(
            handle, "fs:mkdir",
            buildJsonObject { put("path", "newdir/sub") }
        )
        assertTrue(preview.isSuccess)
        val desc = assertIs<Preview.Description>(preview.getOrThrow())
        assertTrue(desc.text.contains("newdir/sub"))

        handle.seed("existingdir/file.txt", "x")
        val existingPreview = tool.preview(
            handle, "fs:mkdir",
            buildJsonObject { put("path", "existingdir") }
        )
        assertTrue(existingPreview.isSuccess)
        assertIs<Preview.NoChange>(existingPreview.getOrThrow())
    }

    @Test
    fun `git paths are rejected across write delete move and mkdir in execute and preview`() = runTest {
        val gitPaths = listOf(
            ".git",
            ".git/config",
            ".GIT/HEAD",
            "./.git/hooks/pre-commit",
            "sub/.git/config",
            "sub\\.git\\HEAD",
        )
        for (p in gitPaths) {
            handle.seed(p, "original")

            val writeRes = tool.execute(handle, "fs:write", buildJsonObject { put("path", p); put("content", "hack") })
            assertIs<ToolOutcome.Failed>(writeRes.outcome, "fs:write must reject $p")
            assertTrue(tool.preview(handle, "fs:write", buildJsonObject { put("path", p); put("content", "hack") }).isFailure,
                "preview fs:write must reject $p")

            val delRes = tool.execute(handle, "fs:delete", buildJsonObject { put("path", p); put("recursive", true) })
            assertIs<ToolOutcome.Failed>(delRes.outcome, "fs:delete must reject $p")
            assertTrue(tool.preview(handle, "fs:delete", buildJsonObject { put("path", p) }).isFailure,
                "preview fs:delete must reject $p")

            val moveToRes = tool.execute(handle, "fs:move", buildJsonObject { put("from", "safe.txt"); put("to", p) })
            assertIs<ToolOutcome.Failed>(moveToRes.outcome, "fs:move to $p must be rejected")
            assertTrue(tool.preview(handle, "fs:move", buildJsonObject { put("from", "safe.txt"); put("to", p) }).isFailure,
                "preview fs:move to $p must be rejected")

            val moveFromRes = tool.execute(handle, "fs:move", buildJsonObject { put("from", p); put("to", "out.txt") })
            assertIs<ToolOutcome.Failed>(moveFromRes.outcome, "fs:move from $p must be rejected")
            assertTrue(tool.preview(handle, "fs:move", buildJsonObject { put("from", p); put("to", "out.txt") }).isFailure,
                "preview fs:move from $p must be rejected")

            val mkdirRes = tool.execute(handle, "fs:mkdir", buildJsonObject { put("path", p) })
            assertIs<ToolOutcome.Failed>(mkdirRes.outcome, "fs:mkdir must reject $p")
            assertTrue(tool.preview(handle, "fs:mkdir", buildJsonObject { put("path", p) }).isFailure,
                "preview fs:mkdir must reject $p")
        }

        // Non-.git dotfiles like .gitignore and .github/ remain writable.
        val gitignoreRes = tool.execute(handle, "fs:write", buildJsonObject { put("path", ".gitignore"); put("content", "*.log\n") })
        assertIs<ToolOutcome.Ok>(gitignoreRes.outcome)
        val githubRes = tool.execute(handle, "fs:mkdir", buildJsonObject { put("path", ".github/workflows") })
        assertIs<ToolOutcome.Ok>(githubRes.outcome)
    }

    @Test
    fun `delete returns Failed on handle failure when force is false and Ok skipped when force is true`() = runTest {
        val failingHandle = object : DirHandle by handle {
            override suspend fun delete(relative: RelPath, recursive: Boolean): Result<Int> =
                Result.failure(IllegalStateException("Directory not empty"))
        }

        val failedRes = tool.execute(
            failingHandle, "fs:delete",
            buildJsonObject { put("path", "nonempty"); put("force", false) }
        )
        assertIs<ToolOutcome.Failed>(failedRes.outcome)
        val errText = failedRes.content.filterIsInstance<ContentBlock.Text>().first().text
        assertFalse(errText.contains("ToolCallResult"), "must not fall through after failure: $errText")

        val forcedRes = tool.execute(
            failingHandle, "fs:delete",
            buildJsonObject { put("path", "nonempty"); put("force", true) }
        )
        assertIs<ToolOutcome.Ok>(forcedRes.outcome)
        val okText = forcedRes.content.filterIsInstance<ContentBlock.Text>().first().text
        assertTrue(okText.startsWith("delete skipped:"), "expected delete skipped message, got: $okText")
    }
}

// ── In-memory DirHandle for tests ─────────────────────────────────────────────

/**
 * An in-memory [DirHandle] backed by a flat path→bytes map.
 *
 * Implements only the operations [FilesystemTool] needs. Escape is prevented by
 * [RelPath] construction, not by this implementation.
 */
class InMemoryDirHandle(override val capabilityId: CapabilityId) : DirHandle {
    private val files = mutableMapOf<String, ByteArray>()

    fun seed(path: String, content: String) {
        files[path] = content.encodeToByteArray()
    }

    fun readText(path: String): String? = files[path]?.decodeToString()

    override suspend fun read(relative: RelPath): Result<ByteArray> {
        val bytes = files[relative.value] ?: return Result.failure(IllegalArgumentException("not found: ${relative.value}"))
        return Result.success(bytes)
    }

    override suspend fun write(relative: RelPath, content: ByteArray): Result<Unit> {
        files[relative.value] = content
        return Result.success(Unit)
    }

    override suspend fun list(relative: RelPath): Result<List<DirEntry>> {
        val prefix = if (relative.value.isEmpty()) "" else "${relative.value}/"
        val direct = files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { !it.contains('/') }
            .map { DirEntry(it, isDirectory = false, sizeBytes = files["$prefix$it"]!!.size.toLong()) }
        // Also collect direct subdirectories.
        val dirs = files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.contains('/') }
            .map { it.substringBefore('/') }
            .distinct()
            .map { DirEntry(it, isDirectory = true, sizeBytes = 0L) }
        return Result.success((direct + dirs).distinctBy { it.name })
    }

    override suspend fun exists(relative: RelPath): Boolean = files.containsKey(relative.value)

    override suspend fun delete(relative: RelPath, recursive: Boolean): Result<Int> {
        val prefix = if (relative.value.isEmpty()) "" else "${relative.value}/"
        val keysToDelete = if (relative.value.isEmpty() || files.containsKey(relative.value)) {
            if (relative.value.isEmpty()) emptyList() else listOf(relative.value)
        } else {
            files.keys.filter { it.startsWith(prefix) }
        }
        
        val count = keysToDelete.size
        keysToDelete.forEach { files.remove(it) }
        return Result.success(count)
    }

    override suspend fun move(from: RelPath, to: RelPath, overwrite: Boolean): Result<Unit> {
        val content = files[from.value] ?: return Result.failure(IllegalArgumentException("not found: ${from.value}"))
        if (!overwrite && files.containsKey(to.value)) {
            return Result.failure(IllegalArgumentException("destination exists: ${to.value}"))
        }
        files.remove(from.value)
        files[to.value] = content
        return Result.success(Unit)
    }

    override suspend fun mkdir(relative: RelPath): Result<Unit> {
        // In-memory filesystem doesn't need to create directories explicitly
        return Result.success(Unit)
    }
}
