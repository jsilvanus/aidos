package dev.aidos.git

import dev.aidos.api.CommitResult
import dev.aidos.api.DiffRange
import dev.aidos.api.FileChangeKind
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JGitDiffServiceTest {

    private val tempDir = Files.createTempDirectory("jgit-diff-service-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `lists working tree changes hunks stages commits and detects base_moved`() = runTest {
        val repoDir = tempDir.resolve("repo")
        Git.init().setDirectory(repoDir).call().use { git ->
            val file = repoDir.resolve("hello.txt")
            file.writeText("line 1\nline 2\n")
            git.add().addFilepattern("hello.txt").call()
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com").call()

            file.writeText("line 1\nline 2 modified\nline 3\n")
        }

        val service = JGitDiffService()
        val working = service.changes(repoDir.absolutePath, DiffRange.WorkingTree)
        assertEquals(1, working.filesChanged)
        assertEquals("hello.txt", working.files.single().path)
        assertEquals(FileChangeKind.MODIFIED, working.files.single().kind)

        val fileDiff = service.hunks(repoDir.absolutePath, DiffRange.WorkingTree, "hello.txt").getOrThrow()
        assertEquals(1, fileDiff.hunks.size)
        val hunk = fileDiff.hunks.single()

        val unified = service.unified(repoDir.absolutePath, DiffRange.WorkingTree, "hello.txt").getOrThrow()
        assertTrue(unified.contains("+line 2 modified"))

        val staleStage = service.stage(
            repoDir.absolutePath,
            listOf(dev.aidos.api.HunkId(hunk.id.path, "0000000000000000000000000000000000000000", hunk.id.index)),
        )
        assertTrue(staleStage.isFailure)
        assertTrue(staleStage.exceptionOrNull()?.message?.contains("diff.base_moved") == true)

        val apiHunkId = dev.aidos.api.HunkId(hunk.id.path, hunk.id.baseBlobHash, hunk.id.index)
        assertTrue(service.stage(repoDir.absolutePath, listOf(apiHunkId)).isSuccess)

        val staged = service.changes(repoDir.absolutePath, DiffRange.Staged)
        assertEquals(1, staged.filesChanged)

        val commitResult = service.commit(repoDir.absolutePath, "Update hello")
        assertTrue(commitResult is CommitResult.Success)

        val emptyCommit = service.commit(repoDir.absolutePath, "Nothing")
        assertEquals(CommitResult.NothingStaged, emptyCommit)
    }
}
