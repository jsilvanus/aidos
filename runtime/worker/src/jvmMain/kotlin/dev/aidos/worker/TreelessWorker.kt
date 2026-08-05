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
 * two workers writing the same path concurrently. The ref update is the locking mechanism.
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
                builder.add(entry)
            }
            treeWalk.close()

            // Write new and updated blobs.
            for (change in changes.filterIsInstance<FileChange.Write>()) {
                val blobId = inserter.insert(Constants.OBJ_BLOB, change.content)
                val entry = DirCacheEntry(change.path)
                entry.fileMode = FileMode.REGULAR_FILE
                entry.setObjectId(blobId)
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

            // Update the worker ref.
            val refName = "$WORKER_REF_PREFIX$workerId"
            val refUpdate = repository.updateRef(refName)
            refUpdate.setNewObjectId(newCommitId)
            refUpdate.update()

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
