package dev.aidos.ui.paradigms.livingdoc

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Title of the per-project GOAL node that holds the document when no node is selected (RFC-0124 decision U8). */
const val NOTES_TITLE = "Notes"

enum class LivingDocSaveState { SAVED, DIRTY, SAVING, ERROR }

data class LivingDocUiState(
    /** The node whose description is edited; null while nothing is selected and "Notes" does not exist yet. */
    val nodeId: String? = null,
    val title: String = NOTES_TITLE,
    val breadcrumb: List<String> = emptyList(),
    val status: DerivedStatus? = null,
    val text: String = "",
    val saveState: LivingDocSaveState = LivingDocSaveState.SAVED,
    val error: String? = null,
    /** Set when the text changed elsewhere while this person had unsaved edits. */
    val conflict: Boolean = false,
    /** Child tasks of the document node, shown after the text as inline tool blocks. */
    val children: List<LivingDocChild> = emptyList(),
)

data class LivingDocChild(val node: IntentNodeRecord, val status: DerivedStatus?)

/** Result of [mergeIncoming]: the text to show, and whether the person must choose. */
data class IncomingMerge(val text: String, val conflict: Boolean)

/** The "Notes" GOAL in [snapshot]: a top-level GOAL titled exactly [NOTES_TITLE], or null. */
fun notesNodeIn(snapshot: GraphSnapshot): IntentNodeRecord? =
    snapshot.nodes.firstOrNull { it.type == IntentNodeType.GOAL && it.title == NOTES_TITLE && it.parentId == null }

/** The selected node when it is in the snapshot, otherwise the "Notes" node (RFC-0124 decision U8). */
fun documentNodeId(selected: String?, snapshot: GraphSnapshot): String? {
    if (selected != null && snapshot.nodes.any { it.id == selected }) return selected
    return notesNodeIn(snapshot)?.id
}

/**
 * A description that changed elsewhere replaces the text, unless the person has unsaved edits of
 * their own: then their text stays and the view asks whether to reload or keep it.
 */
fun mergeIncoming(local: String, remote: String, dirty: Boolean): IncomingMerge = when {
    local == remote -> IncomingMerge(local, conflict = false)
    dirty -> IncomingMerge(local, conflict = true)
    else -> IncomingMerge(remote, conflict = false)
}

/** Inserts [inserted] at [cursor] and returns the new text with the cursor after the insertion. */
fun insertAtCursor(text: String, cursor: Int, inserted: String): Pair<String, Int> {
    val at = cursor.coerceIn(0, text.length)
    return (text.substring(0, at) + inserted + text.substring(at)) to (at + inserted.length)
}

/**
 * The Living Document (RFC-0106 §1.2, RFC-0124 UI-33). The text is the description of the selected
 * node, or of the project's "Notes" node. Edits are saved 800 ms after the last keystroke, through
 * the shared [controller], so the graph store refreshes after each save.
 */
class LivingDocPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    private val controller: WorkspaceController,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 800,
) {
    private val _state = MutableStateFlow(LivingDocUiState())
    val state: StateFlow<LivingDocUiState> = _state.asStateFlow()

    private var projectId = ""
    private var targetId: String? = null
    /** The text the graph is known to hold for [targetId]. */
    private var saved = ""
    /** Text sent in a create or update that the snapshot has not shown yet. */
    private var inFlight: String? = null
    private var notesCreating = false
    /** The remote text behind a conflict, kept for "Reload". */
    private var remoteSeen = ""
    private var saveJob: Job? = null

    init {
        scope.launch {
            combine(snapshot, controller.state.map { it.selectedNodeId }.distinctUntilChanged()) { snap, selected ->
                snap to selected
            }.collect { (snap, selected) -> onGraph(snap, selected) }
        }
        scope.launch {
            controller.state.map { it.message }.distinctUntilChanged().collect { message ->
                if (message != null && message.startsWith("Failed")) onFailed(message)
            }
        }
    }

    fun onTextChanged(text: String) {
        if (text == _state.value.text) return
        _state.update { it.copy(text = text, saveState = if (text == saved) LivingDocSaveState.SAVED else LivingDocSaveState.DIRTY, error = null) }
        saveJob?.cancel()
        val id = targetId
        saveJob = scope.launch {
            delay(debounceMillis)
            val latest = _state.value.text
            if (latest != saved) sendSave(id, latest)
        }
    }

    /** Saves any unsaved text now. Called when the person leaves the view. */
    fun flush() {
        saveJob?.cancel()
        saveJob = null
        val text = _state.value.text
        if (text != saved) sendSave(targetId, text)
    }

    /** The person chose "Reload" after a conflict: take the remote text. */
    fun reloadRemote() {
        if (!_state.value.conflict) return
        saveJob?.cancel()
        saved = remoteSeen
        _state.update { it.copy(text = remoteSeen, conflict = false, saveState = LivingDocSaveState.SAVED, error = null) }
    }

    /** The person chose "Keep mine" after a conflict: their text replaces the remote text on the next save. */
    fun keepMine() {
        if (!_state.value.conflict) return
        saved = remoteSeen
        _state.update { it.copy(conflict = false, saveState = LivingDocSaveState.DIRTY) }
        scheduleSave()
    }

    private fun onGraph(snap: GraphSnapshot, selected: String?) {
        projectId = snap.projectId
        val notes = notesNodeIn(snap)
        val wasCreating = notesCreating
        if (notes != null) notesCreating = false
        val docId = documentNodeId(selected, snap)
        val previous = targetId

        if (docId != previous) {
            if (previous == null && wasCreating && notes != null && docId == notes.id) {
                // The first save just created "Notes" with the text sent. Keep what the person typed since.
                targetId = docId
                saved = notes.description.orEmpty()
                inFlight = null
                val typed = _state.value.text
                publish(snap, docId) {
                    it.copy(saveState = if (typed == saved) LivingDocSaveState.SAVED else LivingDocSaveState.DIRTY, conflict = false)
                }
                if (typed != saved) scheduleSave()
                return
            }
            // Leaving a node: its unsaved text goes to that node first. With no "Notes" yet, the text becomes "Notes".
            val text = _state.value.text
            if (text != saved && (previous != null || notes == null)) {
                saveJob?.cancel()
                sendSave(previous, text)
            }
            targetId = docId
            val remote = remoteDescription(snap, docId)
            saved = remote
            inFlight = null
            remoteSeen = remote
            publish(snap, docId) { it.copy(text = remote, saveState = LivingDocSaveState.SAVED, error = null, conflict = false) }
            return
        }

        val remote = remoteDescription(snap, docId)
        if (docId != null && remote != saved) {
            val pending = inFlight
            if (pending != null && remote == pending) {
                // Our own save has landed.
                saved = remote
                inFlight = null
                publish(snap, docId) {
                    it.copy(saveState = if (it.text == saved) LivingDocSaveState.SAVED else LivingDocSaveState.DIRTY, error = null)
                }
            } else {
                val text = _state.value.text
                val merged = mergeIncoming(text, remote, dirty = text != saved)
                remoteSeen = remote
                if (merged.conflict) {
                    publish(snap, docId) { it.copy(conflict = true) }
                } else {
                    saved = remote
                    publish(snap, docId) { it.copy(text = merged.text, conflict = false, saveState = LivingDocSaveState.SAVED) }
                }
            }
        } else {
            publish(snap, docId) { it }
        }
    }

    private fun onFailed(message: String) {
        if (inFlight == null) return
        inFlight = null
        notesCreating = false
        _state.update { it.copy(saveState = LivingDocSaveState.ERROR, error = message) }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val id = targetId
        saveJob = scope.launch {
            delay(debounceMillis)
            val latest = _state.value.text
            if (latest != saved) sendSave(id, latest)
        }
    }

    /** Sends one create (no node yet: GOAL "Notes", carrying the text) or one description update. */
    private fun sendSave(id: String?, text: String) {
        if (id == null) {
            if (notesCreating || text.isEmpty()) return
            notesCreating = true
            inFlight = text
            controller.createNode(
                CreateIntentNodeRequest(
                    projectId = projectId,
                    title = NOTES_TITLE,
                    type = IntentNodeType.GOAL,
                    description = text,
                ),
            )
        } else {
            inFlight = text
            controller.updateNode(
                UpdateIntentNodeRequest(
                    projectId = projectId,
                    nodeId = id,
                    description = text.ifEmpty { null },
                    clearDescription = text.isEmpty(),
                ),
            )
        }
        _state.update { it.copy(saveState = LivingDocSaveState.SAVING, error = null) }
    }

    private fun remoteDescription(snap: GraphSnapshot, docId: String?): String =
        docId?.let { id -> snap.nodes.firstOrNull { it.id == id } }?.description.orEmpty()

    /** Fills the node-derived fields (title, breadcrumb, status, children) and applies [update] to the rest. */
    private fun publish(snap: GraphSnapshot, docId: String?, update: (LivingDocUiState) -> LivingDocUiState) {
        val node = docId?.let { id -> snap.nodes.firstOrNull { it.id == id } }
        val byId = snap.nodes.associateBy { it.id }
        val crumbs = ArrayList<String>()
        var parent = node?.parentId
        var hops = 0
        while (parent != null && hops++ < 64) {
            val p = byId[parent] ?: break
            crumbs.add(0, p.title)
            parent = p.parentId
        }
        _state.update { current ->
            update(current).copy(
                nodeId = docId,
                title = node?.title ?: NOTES_TITLE,
                breadcrumb = crumbs,
                status = docId?.let { snap.statuses[it]?.derivedStatus },
                children = if (docId == null) {
                    emptyList()
                } else {
                    snap.nodes.filter { it.parentId == docId }
                        .map { LivingDocChild(it, snap.statuses[it.id]?.derivedStatus) }
                },
            )
        }
    }
}
