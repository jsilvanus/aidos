package dev.aidos.worker

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M24 done-when (RFC-0053, RFC-0049, RFC-0007):
 *
 * 1. A worker builds commits directly against the object database on
 *    refs/aidos/workers/<id> with no git worktree and no second checkout.
 * 2. The ref is updated to point to the new commit.
 * 3. The working tree is not touched.
 */
class TreelessWorkerTest {

    private fun makeRepo(): Pair<Git, File> {
        val dir = Files.createTempDirectory("treeless-test").toFile()
        val git = Git.init().setDirectory(dir).call()
        // Initial commit.
        val readme = File(dir, "README.md")
        readme.writeText("# Initial\n")
        git.add().addFilepattern("README.md").call()
        git.commit()
            .setAuthor("Test", "test@example.com")
            .setMessage("Initial commit")
            .call()
        return Pair(git, dir)
    }

    @Test
    fun `worker commit creates ref on refs aidos workers`() {
        val (git, dir) = makeRepo()
        val repo = git.repository

        val worker = TreelessWorker(repo)
        val commitId = worker.commit(
            workerId = "worker-01",
            parentRef = Constants.HEAD,
            changes = listOf(
                FileChange.Write("src/main.kt", "fun main() = println(\"hello\")".toByteArray()),
            ),
            authorName = "Worker",
            authorEmail = "worker@aidos",
            message = "Add main.kt",
        )

        assertNotNull(commitId)
        val workerRef = repo.findRef("refs/aidos/workers/worker-01")
        assertNotNull(workerRef, "Worker ref must exist")
        assertEquals(commitId, workerRef.objectId, "Worker ref must point to new commit")

        dir.deleteRecursively()
    }

    @Test
    fun `worker commit does not modify working tree`() {
        val (git, dir) = makeRepo()
        val repo = git.repository

        val workingTreeBefore = dir.listFiles()!!.map { it.name }.sorted()

        val worker = TreelessWorker(repo)
        worker.commit(
            workerId = "worker-no-wt",
            parentRef = Constants.HEAD,
            changes = listOf(
                FileChange.Write("new-file.kt", "// new".toByteArray()),
            ),
            authorName = "Worker",
            authorEmail = "worker@aidos",
            message = "Add new-file.kt without touching working tree",
        )

        val workingTreeAfter = dir.listFiles()!!
            .filter { it.name != ".git" }
            .map { it.name }.sorted()

        // The working tree should be unchanged — no new-file.kt in the directory.
        assertEquals(workingTreeBefore.filter { it != ".git" }, workingTreeAfter,
            "Worker must not touch the working tree")

        dir.deleteRecursively()
    }

    @Test
    fun `worker commit preserves parent tree files`() {
        val (git, dir) = makeRepo()
        val repo = git.repository

        val worker = TreelessWorker(repo)
        val commitId = worker.commit(
            workerId = "worker-preserve",
            parentRef = Constants.HEAD,
            changes = listOf(
                FileChange.Write("src/new.kt", "// new".toByteArray()),
            ),
            authorName = "Worker",
            authorEmail = "worker@aidos",
            message = "Add src/new.kt",
        )

        // Read the tree in the worker commit.
        val revWalk = RevWalk(repo)
        val commit = revWalk.parseCommit(commitId)
        val treeWalk = TreeWalk(repo)
        treeWalk.addTree(commit.tree)
        treeWalk.isRecursive = true

        val paths = mutableListOf<String>()
        while (treeWalk.next()) paths.add(treeWalk.pathString)

        assertTrue("README.md" in paths, "README.md from parent tree must be preserved")
        assertTrue("src/new.kt" in paths, "New file from worker must be present")

        dir.deleteRecursively()
    }

    @Test
    fun `worker delete removes file from tree`() {
        val (git, dir) = makeRepo()
        val repo = git.repository

        val worker = TreelessWorker(repo)
        val commitId = worker.commit(
            workerId = "worker-delete",
            parentRef = Constants.HEAD,
            changes = listOf(FileChange.Delete("README.md")),
            authorName = "Worker",
            authorEmail = "worker@aidos",
            message = "Delete README.md",
        )

        val revWalk = RevWalk(repo)
        val commit = revWalk.parseCommit(commitId)
        val treeWalk = TreeWalk(repo)
        treeWalk.addTree(commit.tree)
        treeWalk.isRecursive = true

        val paths = mutableListOf<String>()
        while (treeWalk.next()) paths.add(treeWalk.pathString)

        assertTrue("README.md" !in paths, "Deleted file must not appear in worker commit tree")

        dir.deleteRecursively()
    }

    @Test
    fun `worker ref prefix is correct`() {
        assertEquals("refs/aidos/workers/", TreelessWorker.WORKER_REF_PREFIX)
    }
}
