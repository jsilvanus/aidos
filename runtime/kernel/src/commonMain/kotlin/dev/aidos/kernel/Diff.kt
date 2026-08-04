package dev.aidos.kernel

/**
 * The structured diff shape returned by the Runtime API (RFC-0052, D25).
 *
 * **A diff crosses this boundary as structure, never as a formatted string.** `diff(): String`
 * would push a diff parser into every frontend — starting with the Android app, on the device
 * with the least CPU and the greatest distance from JGit — and each would reimplement it. The
 * raw unified text remains available as an explicit fallback view.
 */

/**
 * A hunk is not a stable object: it is derived from a diff of two states, and the hunks renumber
 * whenever either state moves. Carrying [baseBlobHash] makes staleness **detectable rather than
 * silent** — staging or reverting against a base that has moved fails with `diff.base_moved`
 * ([ErrorClass.CONFLICT]) and the review restarts visibly. Silent renumbering during a partial
 * staging operation is how a user stages the wrong lines.
 *
 * The same key is why review marks do not survive a rebase, an amend, or a branch switch
 * (RFC-0053): the base moves, the key misses, and the change presents as unreviewed. That falls
 * out of the identity rather than needing invalidation code.
 */
data class HunkId(
    val path: String,
    val baseBlobHash: String,
    /** Ordinal within that file's diff, from 0. Meaningful only alongside [baseBlobHash]. */
    val index: Int,
)

/** Which two states a diff is between. */
sealed interface DiffRange {
    /** HEAD → working tree. What the commit screen lists. */
    data object WorkingTree : DiffRange

    /** HEAD → index. */
    data object Staged : DiffRange

    data class Refs(val base: String, val head: String) : DiffRange
}

data class DiffSummary(
    val range: DiffRange,
    val files: List<FileChange>,
    val filesChanged: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
)

/**
 * File-level record with hunk *counts*, not hunk content.
 *
 * A card stack shows one hunk per screen and needs only the file it is currently in. Sending
 * every line of a large fetch to a phone that will display eleven of them is eagerness a mobile
 * client cannot afford.
 */
data class FileChange(
    /** The path after the change. */
    val path: String,
    /** Set on rename. */
    val previousPath: String?,
    val kind: FileChangeKind,
    /** Null when the file is added. */
    val baseBlobHash: String?,
    /** Null when the file is deleted. */
    val headBlobHash: String?,
    /** No hunks; reviewed whole-file or not at all. */
    val binary: Boolean,
    val modeChanged: Boolean,
    val hunkCount: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val review: ReviewState,
    val origin: ChangeOrigin,
)

data class FileDiff(
    val file: FileChange,
    /** Empty when [FileChange.binary]. */
    val hunks: List<DiffHunk>,
)

data class DiffHunk(
    val id: HunkId,
    /** 1-based line numbers, as Git reports them. */
    val baseStart: Int,
    val baseLines: Int,
    val headStart: Int,
    val headLines: Int,
    val lines: List<DiffLine>,
)

data class DiffLine(
    val kind: DiffLineKind,
    /** Without the line terminator. */
    val text: String,
    /** Git's `\ No newline at end of file`. A missing trailing newline reconstructed as present
     *  is a one-byte corruption the user did not author. */
    val noNewlineAtEof: Boolean = false,
)

enum class FileChangeKind { ADDED, MODIFIED, DELETED, RENAMED, COPIED, TYPE_CHANGED }

enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

/**
 * Whether the user has already seen this content.
 *
 * Per-mutation [Preview] is the primary review surface and is required for every
 * [EffectKind.Mutate] for security reasons (RFC-0030), so by commit time most changes have been
 * read once, in context, next to the reason the model gave. The commit screen separates the two
 * sets and directs attention at the second (RFC-0050, D25).
 */
enum class ReviewState {
    /** The user approved this content as a Preview while it was being made. */
    APPROVED_IN_RUN,
    NOT_REVIEWED,
}

/**
 * "Not reviewed" is not one thing. A change pulled from a remote, one the user typed in the
 * editor, and one a session made under disabled per-mutation approval carry different amounts of
 * surprise, and the commit screen says which.
 */
enum class ChangeOrigin { SESSION, USER_EDIT, FETCH, UNKNOWN }
