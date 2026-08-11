package dev.aidos.worker

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheBuilder
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/**
 * Treeless worker commits (RFC-0053, M24).
 *
 * A worker builds commits **directly against the object database**, with no git worktree and
 * no second checkout. This is essential on mobile: the phone does not have room for multiple
 * working trees of a large codebase.
 *
 * The mechanism:
 * 1. Read the current tree from HEAD (or a parent commit ref).
 * 2. Apply the desired file changes in memory: blobs are written to the object database.
 * 3. A new tree object is assembled and written.
 * 4. A commit object is written pointing to the new tree, with the given parent.
 * 5. The worker ref (`refs/aidos/workers/<id>`) is updated to point to the new commit.
 *
 * The worktree is the lock (D15): because there is no worktree, there is no possibility of
 * two workers writing the same path concurrently. The ref update is the locking mechanism —
 * genuinely, not just by convention: [commit] reads the worker ref's current value immediately
 * before updating it and passes that as [RefUpdate.setExpectedOldObjectId], the real compare-
 * and-swap RFC-0007 itself describes ("JGit performs a compare-and-swap on the ref... this is
 * what allows treeless workers to commit in parallel," RFC-0007 D15/table). Two calls racing for
 * the *same* [workerId] — a retried Run, a bug driving one worker twice — cannot silently
 * clobber each other: the loser's expected-old no longer matches what's actually on the ref by
 * the time it updates, so JGit rejects it (`RefUpdate.Result.LOCK_FAILURE` or `REJECTED`) instead
 * of a last-write-wins overwrite, and [commit] turns that into a named exception rather than
 * silently returning a commit id that isn't what the ref points to.
 *
 * Security: worker commits land on `refs/aidos/workers/<id>`, never directly on `main` or any
 * user branch. A user must explicitly review and merge the commit (RFC-0032).
 */
class TreelessWorker(private val repository: Repository) {

    /** The canonical prefix for worker refs (RFC-0053, M24). */
    companion object {
        const val WORKER_REF_PREFIX = "refs/aidos/workers/"
    }

    /**
     * Applies [changes] to the repository's object database, starting from [parentRef].
     * Writes the resulting commit to `refs/aidos/workers/[workerId]`.
     *
     * Returns the ObjectId of the new commit.
     *
     * This operation has no working tree side effects — it is safe to call on MOBILE
     * where no second checkout is possible.
     */
    fun commit(
        workerId: String,
        parentRef: String = Constants.HEAD,
        changes: List<FileChange>,
        authorName: String,
        authorEmail: String,
        message: String,
    ): ObjectId {
        val inserter: ObjectInserter = repository.newObjectInserter()
        val revWalk = RevWalk(repository)

        try {
            // Resolve the parent commit.
            val parentId = repository.resolve(parentRef)
                ?: error("Cannot resolve parent ref: $parentRef")
            val parentCommit = revWalk.parseCommit(parentId)
            val parentTree = parentCommit.tree

            // Build a new DirCache from the parent tree.
            val dirCache = DirCache.newInCore()
            val builder: DirCacheBuilder = dirCache.builder()

            // Collect all entries (existing + updated/new) and sort them by path
            // before adding to the builder. DirCacheBuilder requires canonical path order;
            // mixing tree entries with write entries out of order causes an IllegalStateException.
            val allEntries = mutableListOf<DirCacheEntry>()

            // Load the existing tree entries from the parent.
            val treeWalk = org.eclipse.jgit.treewalk.TreeWalk(repository)
            treeWalk.addTree(parentTree)
            treeWalk.isRecursive = true
            val deletedPaths = changes.filter { it is FileChange.Delete }.map { it.path }.toSet()
            val updatedPaths = changes.filterIsInstance<FileChange.Write>().associateBy { it.path }

            while (treeWalk.next()) {
                val path = treeWalk.pathString
                if (path in deletedPaths) continue  // Skip deleted entries.
                if (path in updatedPaths) continue  // Will be replaced below.
                val entry = DirCacheEntry(path)
                entry.fileMode = treeWalk.getFileMode(0)
                entry.setObjectId(treeWalk.getObjectId(0))
                allEntries.add(entry)
            }
            treeWalk.close()

            // Write new and updated blobs.
            for (change in changes.filterIsInstance<FileChange.Write>()) {
                val blobId = inserter.insert(Constants.OBJ_BLOB, change.content)
                val entry = DirCacheEntry(change.path)
                entry.fileMode = FileMode.REGULAR_FILE
                entry.setObjectId(blobId)
                allEntries.add(entry)
            }

            // Sort by path in canonical order before adding to builder (DirCacheBuilder requirement).
            allEntries.sortBy { it.pathString }
            for (entry in allEntries) {
                builder.add(entry)
            }

            builder.finish()

            // Write the new tree.
            val treeId = dirCache.writeTree(inserter)

            // Write the commit.
            val ident = PersonIdent(authorName, authorEmail)
            val commitBuilder = CommitBuilder()
            commitBuilder.author = ident
            commitBuilder.committer = ident
            commitBuilder.message = message
            commitBuilder.setParentId(parentId)
            commitBuilder.setTreeId(treeId)
            val newCommitId = inserter.insert(commitBuilder)
            inserter.flush()

            // Update the worker ref via a real compare-and-swap (RFC-0007, D15) — read the ref's
            // current value immediately before writing, and require it to still hold that value
            // at write time. ObjectId.zeroId() is JGit's own convention for "this ref must not
            // exist yet"; repository.resolve() already returns null for that case.
            val refName = "$WORKER_REF_PREFIX$workerId"
            val expectedOld = repository.resolve(refName) ?: ObjectId.zeroId()
            val refUpdate = repository.updateRef(refName)
            refUpdate.setExpectedOldObjectId(expectedOld)
            refUpdate.setNewObjectId(newCommitId)
            val result = refUpdate.update()
            check(
                result == RefUpdate.Result.NEW ||
                    result == RefUpdate.Result.FAST_FORWARD ||
                    result == RefUpdate.Result.FORCED
            ) {
                "Worker ref $refName was updated concurrently — expected $expectedOld, " +
                    "update result was $result (RFC-0007 compare-and-swap rejected this write)"
            }

            return newCommitId
        } finally {
            inserter.close()
            revWalk.close()
        }
    }
}

/** A change to apply in a treeless commit (RFC-0053). */
sealed interface FileChange {
    val path: String

    /** Write (create or overwrite) a file. */
    data class Write(override val path: String, val content: ByteArray) : FileChange

    /** Delete a file. */
    data class Delete(override val path: String) : FileChange
}
