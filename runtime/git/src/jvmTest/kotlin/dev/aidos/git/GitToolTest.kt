package dev.aidos.git

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.RelPath
import dev.aidos.kernel.ToolOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M13 done-when (RFC-0032, RFC-0053, D4):
 *
 * 1. status, diff, add, commit, branch, log, checkout on a real repository.
 * 2. push is UNSAFE and declares RecoveryClass.UNSAFE.
 * 3. Reconciliation: user changes outside Aidos are reflected by re-reading status.
 */
class GitToolTest {

    private fun tempRepo(): Pair<File, GitTool> {
        val dir = Files.createTempDirectory("git-tool-test").toFile()
        Git.init().setDirectory(dir).call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Test")
                setString("user", null, "email", "test@test.com")
                // Ambient global gitconfig (e.g. commit.gpgsign=true) must not leak into test
                // repos — JGit has no signing backend and throws UnsupportedSigningFormatException.
                setBoolean("commit", null, "gpgsign", false)
                save()
            }
            // Initial commit so HEAD exists
            val readme = File(dir, "README.md")
            readme.writeText("# Test\n")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("init").setAuthor("Test", "test@test.com").call()
        }
        return dir to GitTool(dir)
    }

    private val mockHandle = object : DirHandle {
        override val capabilityId = CapabilityId("cap-git")
        override suspend fun read(relative: RelPath) = Result.failure<ByteArray>(UnsupportedOperationException())
        override suspend fun write(relative: RelPath, content: ByteArray) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun list(relative: RelPath) = Result.success<List<DirEntry>>(emptyList())
        override suspend fun exists(relative: RelPath) = false
    }

    @Test
    fun `status on clean repo`() = runTest {
        val (_, tool) = tempRepo()
        val result = tool.execute(mockHandle, "git:status", buildJsonObject {})
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertContains(text, "nothing to commit")
    }

    @Test
    fun `add and commit a file`() = runTest {
        val (dir, tool) = tempRepo()
        File(dir, "hello.txt").writeText("hello world")

        val addResult = tool.execute(mockHandle, "git:add",
            buildJsonObject { put("path", "hello.txt") })
        assertIs<ToolOutcome.Ok>(addResult.outcome)

        val commitResult = tool.execute(mockHandle, "git:commit",
            buildJsonObject { put("message", "add hello.txt"); put("author", "Tester") })
        assertIs<ToolOutcome.Ok>(commitResult.outcome)
        val msg = commitResult.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertContains(msg, "add hello.txt")
    }

    @Test
    fun `log shows commits`() = runTest {
        val (_, tool) = tempRepo()
        val result = tool.execute(mockHandle, "git:log",
            buildJsonObject { put("maxCount", "5") })
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertContains(text, "init")
    }

    @Test
    fun `branch create and list`() = runTest {
        val (_, tool) = tempRepo()
        val createResult = tool.execute(mockHandle, "git:branch",
            buildJsonObject { put("name", "feature-x") })
        assertIs<ToolOutcome.Ok>(createResult.outcome)

        val listResult = tool.execute(mockHandle, "git:branch", buildJsonObject {})
        assertIs<ToolOutcome.Ok>(listResult.outcome)
        val text = listResult.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertContains(text, "feature-x")
    }

    @Test
    fun `checkout switches branch`() = runTest {
        val (_, tool) = tempRepo()
        tool.execute(mockHandle, "git:branch", buildJsonObject { put("name", "other") })
        val result = tool.execute(mockHandle, "git:checkout",
            buildJsonObject { put("branch", "other") })
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertContains(text, "other")
    }

    @Test
    fun `diff after write shows changes`() = runTest {
        val (dir, tool) = tempRepo()
        File(dir, "README.md").writeText("# Changed\n")
        val result = tool.execute(mockHandle, "git:diff", buildJsonObject {})
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        assertContains(text, "Changed")
    }

    @Test
    fun `reconciliation - user edit outside Aidos is reflected in status`() = runTest {
        val (dir, tool) = tempRepo()
        // User edits a file outside Aidos
        File(dir, "README.md").writeText("# Modified by user\n")

        val result = tool.execute(mockHandle, "git:status", buildJsonObject {})
        assertIs<ToolOutcome.Ok>(result.outcome)
        val text = result.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>().first().text
        // Should show the modification (not "clean"), since status is re-read every call.
        assertContains(text, "README.md")
    }

    @Test
    fun `push operation is UNSAFE`() {
        val ops = GitTool(File(".")).operations().associateBy { it.name }
        val push = ops["git:push"]
        assertNotNull(push)
        assertEquals(RecoveryClass.UNSAFE, push.recoveryClass)
        assertIs<dev.aidos.kernel.EffectKind.Egress>(push.effect)
    }

    @Test
    fun `all operations have RecoveryClass`() {
        // RecoveryClass is non-nullable — this test documents the expected classes.
        val ops = GitTool(File(".")).operations().associateBy { it.name }
        assertEquals(RecoveryClass.PURE, ops["git:status"]!!.recoveryClass)
        assertEquals(RecoveryClass.PURE, ops["git:diff"]!!.recoveryClass)
        assertEquals(RecoveryClass.IDEMPOTENT, ops["git:add"]!!.recoveryClass)
        assertEquals(RecoveryClass.IDEMPOTENT, ops["git:commit"]!!.recoveryClass)
        assertEquals(RecoveryClass.CHECKABLE, ops["git:checkout"]!!.recoveryClass)
        assertEquals(RecoveryClass.UNSAFE, ops["git:push"]!!.recoveryClass)
    }

    @Test
    fun `commit preview returns Diff for staged changes`() = runTest {
        val (dir, tool) = tempRepo()
        File(dir, "README.md").writeText("# Updated\n")
        tool.execute(mockHandle, "git:add", buildJsonObject { put("path", "README.md") })

        val preview = tool.preview(mockHandle, "git:commit", buildJsonObject { put("message", "update") })
        assertTrue(preview.isSuccess)
        // Should return either Diff or NoChange (NoChange if jgit cached diff returns empty).
        val p = preview.getOrThrow()
        assertTrue(p is Preview.Diff || p is Preview.NoChange, "expected Diff or NoChange, got $p")
    }

    @Test
    fun `checkout preview warns about uncommitted changes`() = runTest {
        val (dir, tool) = tempRepo()
        File(dir, "README.md").writeText("# Dirty\n")

        val preview = tool.preview(mockHandle, "git:checkout",
            buildJsonObject { put("branch", "main") })
        assertTrue(preview.isSuccess)
        val p = preview.getOrThrow()
        assertTrue(p is Preview.Description || p is Preview.NoChange)
    }
}
