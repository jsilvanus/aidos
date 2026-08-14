package dev.aidos.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.security.MessageDigest

/**
 * A repository's observable state at one point in time (RFC-0053).
 *
 * [indexChecksum] is a SHA-256 of the raw `.git/index` file's bytes — not literally JGit's own
 * internal `DirCache` checksum (that is not exposed by JGit's public API), but the property
 * RFC-0053 actually needs: a value that changes exactly when the index's content changes,
 * independent of what wrote it (JGit or plain `git`).
 */
data class RepoFingerprint(
    val headRef: String,
    val headCommit: String,
    val indexChecksum: String,
    val dirtyPathCount: Int,
    val observedAt: String,
)

/** The five ways a repository's fingerprint can change between two observations (RFC-0053). */
enum class ReconciliationClassification {
    HEAD_MOVED,
    BRANCH_SWITCHED,
    HISTORY_REWRITTEN,
    INDEX_CHANGED,
    WORKTREE_DIRTIED,
}

/**
 * Fingerprint computation and mismatch classification (RFC-0053, M13).
 *
 * Pure JGit — no SQL, no knowledge of `repo_fingerprints`/`reconciliations`/`content_nodes`.
 * The orchestration that reads/writes those (invalidating content nodes, terminating parked
 * Runs, recording the audit trail) lives above this, where a `SqlDriver` is available — see
 * `daemon`'s `GitRunReconciler`, the JVM composition that ties this to the executor's
 * before-a-Run-starts gate.
 */
object Reconciliation {

    fun computeFingerprint(git: Git, observedAt: String): RepoFingerprint {
        val repository = git.repository
        val headRef = repository.fullBranch ?: "HEAD"
        val headCommit = repository.resolve("HEAD")?.name.orEmpty()
        val status = git.status().call()
        val dirty = status.added + status.changed + status.removed + status.modified +
            status.untracked + status.missing
        return RepoFingerprint(
            headRef = headRef,
            headCommit = headCommit,
            indexChecksum = indexChecksum(repository),
            dirtyPathCount = dirty.size,
            observedAt = observedAt,
        )
    }

    private fun indexChecksum(repository: Repository): String {
        val indexFile = File(repository.directory, "index")
        if (!indexFile.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(indexFile.readBytes()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Classifies the difference between two fingerprints of the same repository, per RFC-0053's
     * table. Returns null when the fingerprints are equal (no reconciliation needed).
     *
     * Precedence when more than one facet changed at once (the RFC does not state one, since a
     * branch switch always also moves HEAD): ref identity first (the most specific explanation),
     * then history reachability, then the commit pointer, then the index, then the working tree.
     */
    fun classify(old: RepoFingerprint, new: RepoFingerprint, repository: Repository): ReconciliationClassification? =
        when {
            old.headRef != new.headRef -> ReconciliationClassification.BRANCH_SWITCHED
            old.headCommit != new.headCommit && !isAncestor(repository, old.headCommit, new.headCommit) ->
                ReconciliationClassification.HISTORY_REWRITTEN
            old.headCommit != new.headCommit -> ReconciliationClassification.HEAD_MOVED
            old.indexChecksum != new.indexChecksum -> ReconciliationClassification.INDEX_CHANGED
            old.dirtyPathCount != new.dirtyPathCount -> ReconciliationClassification.WORKTREE_DIRTIED
            else -> null // every substantive field is equal (observedAt is deliberately not compared)
        }

    /** True if [oldCommitName] is reachable from [newCommitName] — i.e. history was not rewritten. */
    private fun isAncestor(repository: Repository, oldCommitName: String, newCommitName: String): Boolean {
        if (oldCommitName.isEmpty()) return true // no prior commit to lose
        if (newCommitName.isEmpty()) return false // repository went headless -- treat as rewritten
        return RevWalk(repository).use { walk ->
            val old = walk.parseCommit(repository.resolve(oldCommitName))
            val new = walk.parseCommit(repository.resolve(newCommitName))
            walk.isMergedInto(old, new)
        }
    }
}
