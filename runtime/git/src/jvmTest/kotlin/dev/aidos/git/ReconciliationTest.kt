package dev.aidos.git

import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M13 (RFC-0053): fingerprint computation and mismatch classification, independent of any SQL
 * orchestration — that lives in `daemon`'s `GitRunReconciler`, which this class doesn't know
 * about.
 */
class ReconciliationTest {

    private fun tempRepo(): Pair<File, Git> {
        val dir = Files.createTempDirectory("reconciliation-test").toFile()
        val git = Git.init().setDirectory(dir).call()
        git.repository.config.apply {
            setString("user", null, "name", "Test")
            setString("user", null, "email", "test@test.com")
            setBoolean("commit", null, "gpgsign", false)
            save()
        }
        val readme = File(dir, "README.md")
        readme.writeText("# Test\n")
        git.add().addFilepattern("README.md").call()
        git.commit().setMessage("init").setAuthor("Test", "test@test.com").call()
        return dir to git
    }

    @Test
    fun `identical fingerprints classify as no change`() {
        val (_, git) = tempRepo()
        val fp = Reconciliation.computeFingerprint(git, "t1")
        assertNull(Reconciliation.classify(fp, fp.copy(observedAt = "t2"), git.repository))
    }

    @Test
    fun `a new commit on the same branch classifies as HEAD_MOVED`() {
        val (dir, git) = tempRepo()
        val before = Reconciliation.computeFingerprint(git, "t1")

        File(dir, "second.txt").writeText("more\n")
        git.add().addFilepattern("second.txt").call()
        git.commit().setMessage("second").setAuthor("Test", "test@test.com").call()

        val after = Reconciliation.computeFingerprint(git, "t2")
        assertEquals(ReconciliationClassification.HEAD_MOVED, Reconciliation.classify(before, after, git.repository))
    }

    @Test
    fun `switching branches classifies as BRANCH_SWITCHED`() {
        val (_, git) = tempRepo()
        git.branchCreate().setName("feature").call()
        val before = Reconciliation.computeFingerprint(git, "t1")

        git.checkout().setName("feature").call()

        val after = Reconciliation.computeFingerprint(git, "t2")
        assertEquals(ReconciliationClassification.BRANCH_SWITCHED, Reconciliation.classify(before, after, git.repository))
    }

    @Test
    fun `resetting to an unrelated commit classifies as HISTORY_REWRITTEN`() {
        val (dir, git) = tempRepo()
        val firstCommit = git.repository.resolve("HEAD").name

        File(dir, "second.txt").writeText("more\n")
        git.add().addFilepattern("second.txt").call()
        git.commit().setMessage("second").setAuthor("Test", "test@test.com").call()
        val before = Reconciliation.computeFingerprint(git, "t1")

        // Amend-equivalent: reset HEAD to a brand new commit that does not descend from `before`.
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(firstCommit).call()
        File(dir, "third.txt").writeText("rewritten\n")
        git.add().addFilepattern("third.txt").call()
        git.commit().setMessage("rewritten history").setAuthor("Test", "test@test.com").call()

        val after = Reconciliation.computeFingerprint(git, "t2")
        assertEquals(ReconciliationClassification.HISTORY_REWRITTEN, Reconciliation.classify(before, after, git.repository))
    }

    @Test
    fun `staging a file without committing classifies as INDEX_CHANGED`() {
        val (dir, git) = tempRepo()
        val before = Reconciliation.computeFingerprint(git, "t1")

        File(dir, "staged.txt").writeText("staged\n")
        git.add().addFilepattern("staged.txt").call()

        val after = Reconciliation.computeFingerprint(git, "t2")
        assertEquals(ReconciliationClassification.INDEX_CHANGED, Reconciliation.classify(before, after, git.repository))
    }

    @Test
    fun `an untracked edit classifies as WORKTREE_DIRTIED`() {
        val (dir, git) = tempRepo()
        val before = Reconciliation.computeFingerprint(git, "t1")

        File(dir, "untracked.txt").writeText("dirty\n")

        val after = Reconciliation.computeFingerprint(git, "t2")
        assertEquals(ReconciliationClassification.WORKTREE_DIRTIED, Reconciliation.classify(before, after, git.repository))
    }
}
