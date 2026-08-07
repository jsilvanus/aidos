package dev.aidos.filesystem

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RelPath
import dev.aidos.kernel.ToolOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    @Test
    fun `read returns file content`() = runTest {
        handle.seed("src/main.kt", "fun main() {}")
        val result = FilesystemTool.execute(
            handle, "fs:read",
            buildJsonObject { put("path", "src/main.kt") }
        )
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertEquals("fun main() {}", text)
    }

    @Test
    fun `write stores content and returns ok`() = runTest {
        val result = FilesystemTool.execute(
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
        val result = FilesystemTool.execute(
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
        val result = FilesystemTool.execute(
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
        val preview = FilesystemTool.preview(
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
        val preview = FilesystemTool.preview(
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
        val preview = FilesystemTool.preview(
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
        val result = FilesystemTool.execute(
            handle, "fs:unknown",
            buildJsonObject {}
        )
        assertIs<ToolOutcome.Failed>(result.outcome)
    }

    @Test
    fun `operations all have RecoveryClass`() {
        // RecoveryClass is non-nullable in ToolDescriptor — this test asserts all operations
        // are registered and the recovery class is correctly typed per RFC-0034's table.
        val ops = FilesystemTool.operations().associateBy { it.name }
        assertNotNull(ops["fs:read"])
        assertNotNull(ops["fs:write"])
        assertNotNull(ops["fs:list"])
        assertNotNull(ops["fs:search"])
        assertEquals(dev.aidos.kernel.RecoveryClass.PURE, ops["fs:read"]!!.recoveryClass)
        assertEquals(dev.aidos.kernel.RecoveryClass.IDEMPOTENT, ops["fs:write"]!!.recoveryClass)
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
}
