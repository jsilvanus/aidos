package dev.aidos.androidapp.ui.diff

import dev.aidos.api.CommitResult
import dev.aidos.api.DiffRange
import dev.aidos.api.DiffSummary
import dev.aidos.api.FileChange
import dev.aidos.api.RuntimeClient
import dev.aidos.kernel.DiffHunk
import dev.aidos.kernel.FileDiff
import dev.aidos.kernel.HunkId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the diff-and-commit screen (M31, RFC-0032, RFC-0053, D25).
 *
 * M31 done-when: "Read a diff, stage, write a message, commit — comfortably, on a phone
 * screen, with one hand, on a bus. This is the actual product."
 *
 * The screen presents:
 * 1. A file list with hunk counts (working tree changes).
 * 2. Hunk view for the selected file — loaded on demand (D25: structure, not text).
 * 3. A commit message field, pre-filled with an empty string.
 * 4. Staged indicator: which hunks are queued for the next commit.
 * 5. Commit action: stages all selected hunks then commits.
 */
sealed interface DiffUiState {
    data object Loading : DiffUiState

    /**
     * Working tree changes are loaded. [selectedFile] and [selectedFileHunks] are populated
     * when the user taps a file (loaded lazily, one file at a time — D25).
     */
    data class Changes(
        val projectId: String,
        val workingTree: DiffSummary,
        val selectedFile: FileChange? = null,
        val selectedFileHunks: List<DiffHunk>? = null,
        val stagedHunkIds: Set<HunkId> = emptySet(),
        val isLoadingHunks: Boolean = false,
    ) : DiffUiState {
        val hasChanges: Boolean get() = workingTree.filesChanged > 0
        val stagedCount: Int get() = stagedHunkIds.size
    }

    data class Committing(
        val projectId: String,
        val message: String,
    ) : DiffUiState

    data class Committed(
        val projectId: String,
        val commitHash: String,
        val shortMessage: String,
    ) : DiffUiState

    data class Error(
        val projectId: String,
        val message: String,
        val retryable: Boolean = true,
    ) : DiffUiState
}

/**
 * Platform-neutral commit draft (M31).
 *
 * Kept separate from [DiffUiState] so the commit message survives navigation — the user can
 * flip between the hunk view and the message field without losing their draft.
 */
data class CommitDraftState(
    val message: String = "",
    val isValid: Boolean = false,
) {
    companion object {
        fun from(message: String) = CommitDraftState(
            message = message,
            isValid = message.isNotBlank(),
        )
    }
}

/**
 * Platform-neutral presenter for the diff-and-commit screen (M31).
 *
 * State machine:
 *   Loading → Changes (working tree loaded)
 *   Changes → Changes (file selected, hunks loaded)
 *   Changes → Committing (commit started)
 *   Committing → Committed (success)
 *   Committing → Error (failure)
 *
 * Compose binds [state] via `collectAsState()`. JVM tests use `runTest` + `first()`.
 */
class CommitPresenter(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<DiffUiState>(DiffUiState.Loading)
    val state: StateFlow<DiffUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow(CommitDraftState())
    val draft: StateFlow<CommitDraftState> = _draft.asStateFlow()

    fun load(projectId: String) {
        scope.launch {
            _state.value = DiffUiState.Loading
            try {
                val summary = client.diff.changes(projectId, DiffRange.WorkingTree)
                _state.value = DiffUiState.Changes(projectId = projectId, workingTree = summary)
            } catch (e: Exception) {
                _state.value = DiffUiState.Error(projectId, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Selects a file and loads its hunks on demand (D25: structure crosses the wire once,
     * on demand — not as part of the file list).
     */
    fun selectFile(path: String) {
        val current = _state.value as? DiffUiState.Changes ?: return
        _state.value = current.copy(
            selectedFile = current.workingTree.files.find { it.path == path },
            selectedFileHunks = null,
            isLoadingHunks = true,
        )
        scope.launch {
            val result = client.diff.hunks(current.projectId, DiffRange.WorkingTree, path)
            val updated = _state.value as? DiffUiState.Changes ?: return@launch
            result.fold(
                onSuccess = { fileDiff: FileDiff ->
                    _state.value = updated.copy(
                        selectedFileHunks = fileDiff.hunks,
                        isLoadingHunks = false,
                    )
                },
                onFailure = { ex ->
                    _state.value = DiffUiState.Error(
                        projectId = current.projectId,
                        message = ex.message ?: "Failed to load hunks",
                    )
                },
            )
        }
    }

    /**
     * Toggles the staged state of a hunk. All hunks in [stagedHunkIds] will be staged when
     * [commit] is called.
     */
    fun toggleHunk(hunkId: HunkId) {
        val current = _state.value as? DiffUiState.Changes ?: return
        val newSet = if (hunkId in current.stagedHunkIds) {
            current.stagedHunkIds - hunkId
        } else {
            current.stagedHunkIds + hunkId
        }
        _state.value = current.copy(stagedHunkIds = newSet)
    }

    fun updateDraft(message: String) {
        _draft.value = CommitDraftState.from(message)
    }

    /**
     * Stages all selected hunks, then commits with [CommitDraftState.message].
     *
     * Fails fast (without staging) if [CommitDraftState.isValid] is false or if
     * [DiffUiState.Changes.stagedHunkIds] is empty.
     */
    fun commit(projectId: String) {
        val current = _state.value as? DiffUiState.Changes ?: return
        val message = _draft.value.message
        if (message.isBlank()) {
            _state.value = DiffUiState.Error(projectId, "Commit message must not be empty", retryable = false)
            return
        }
        _state.value = DiffUiState.Committing(projectId = projectId, message = message)
        scope.launch {
            // Stage selected hunks first (idempotent if already staged).
            // Convert kernel.HunkId → api.HunkId (same fields, different packages).
            if (current.stagedHunkIds.isNotEmpty()) {
                val apiHunkIds = current.stagedHunkIds.map { kh ->
                    dev.aidos.api.HunkId(kh.path, kh.baseBlobHash, kh.index)
                }
                val stageResult = client.diff.stage(projectId, apiHunkIds)
                if (stageResult.isFailure) {
                    _state.value = DiffUiState.Error(
                        projectId = projectId,
                        message = stageResult.exceptionOrNull()?.message ?: "Stage failed",
                    )
                    return@launch
                }
            }
            when (val result = client.diff.commit(projectId, message)) {
                is CommitResult.Success -> {
                    _state.value = DiffUiState.Committed(
                        projectId = projectId,
                        commitHash = result.commitHash,
                        shortMessage = result.shortMessage,
                    )
                    _draft.value = CommitDraftState()
                }
                is CommitResult.NothingStaged -> {
                    _state.value = DiffUiState.Error(
                        projectId = projectId,
                        message = "Nothing staged. Select at least one hunk before committing.",
                        retryable = false,
                    )
                }
                is CommitResult.Error -> {
                    _state.value = DiffUiState.Error(
                        projectId = projectId,
                        message = result.message,
                    )
                }
            }
        }
    }
}
