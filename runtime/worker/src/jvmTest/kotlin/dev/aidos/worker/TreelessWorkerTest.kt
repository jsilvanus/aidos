package dev.aidos.worker

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
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
        // Ambient global gitconfig (e.g. commit.gpgsign=true) must not leak into test repos —
        // JGit has no signing backend and throws UnsupportedSigningFormatException.
        git.repository.config.apply {
            setBoolean("commit", null, "gpgsign", false)
            save()
        }
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

    /**
     * M24 (RFC-0053, RFC-0049, RFC-0007, D15): the audit's Part 3 finding was that D15's "the
     * worktree is the lock" / compare-and-swap claim had no real concurrency test — the ref
     * update never called `setExpectedOldObjectId`, despite RFC-0007's own text stating "JGit
     * performs a compare-and-swap on the ref... this is what allows treeless workers to commit
     * in parallel." This spins up two real JVM threads racing to write the *same* worker ref
     * (a retried Run, or a bug driving one worker twice — not different workers on different
     * refs, which never contend by construction) and proves genuine parallel-safety: exactly one
     * wins, the loser fails cleanly (not silently overwritten, not a corrupted ref), and the ref
     * ends up pointing at exactly the winner's commit.
     */
    @Test
    fun `two racers writing the same worker ref cannot silently clobber each other`() {
        val (git, dir) = makeRepo()
        val repo = git.repository
        val worker = TreelessWorker(repo)

        val racerCount = 2
        val barrier = CyclicBarrier(racerCount)
        val results = ConcurrentHashMap<Int, Any>()

        val threads = (1..racerCount).map { i ->
            Thread {
                barrier.await()  // force both racers to call commit() at nearly the same instant
                try {
                    val id = worker.commit(
                        workerId = "race-worker",
                        parentRef = Constants.HEAD,
                        changes = listOf(FileChange.Write("racer-$i.kt", "// racer $i".toByteArray())),
                        authorName = "Racer $i",
                        authorEmail = "racer$i@aidos",
                        message = "Racer $i's commit",
                    )
                    results[i] = id
                } catch (e: IllegalStateException) {
                    results[i] = e
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(15_000) }

        assertEquals(racerCount, results.size, "Both racers must finish (no hang, no silent drop)")
        val successes = results.values.filterIsInstance<ObjectId>()
        val rejections = results.values.filterIsInstance<IllegalStateException>()

        assertEquals(1, successes.size, "Exactly one racer must win the ref update; got: $results")
        assertEquals(
            1, rejections.size,
            "Exactly one racer must be rejected by the compare-and-swap, not silently lose its " +
                "write without an error; got: $results",
        )

        val workerRef = repo.findRef("refs/aidos/workers/race-worker")
        assertNotNull(workerRef, "Worker ref must exist after the race")
        assertEquals(
            successes.single(), workerRef.objectId,
            "Ref must point to exactly the winner's commit -- not corrupted, not the loser's",
        )

        dir.deleteRecursively()
    }
}
