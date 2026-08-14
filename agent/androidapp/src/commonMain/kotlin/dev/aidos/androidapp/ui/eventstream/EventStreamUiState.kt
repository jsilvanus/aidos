package dev.aidos.androidapp.ui.eventstream

import dev.aidos.api.EventFilter
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the live event stream of a single run (M28, RFC-0052).
 *
 * The stream is **resumable**: [resumeSequence] carries the last seen sequence so a
 * reconnect does not produce a gap. Events are appended in arrival order; the list is
 * not re-sorted. The UI renders newest events at the bottom (scroll-to-bottom on append).
 *
 * [isLive] is true while the [dev.aidos.api.EventSubscriptions.subscribe] flow is active.
 * Compose binds this via [collectAsState()]; JVM tests observe via [kotlinx.coroutines.flow.first].
 */
data class EventStreamUiState(
    val sessionId: String,
    val runId: String,
    val events: List<RuntimeEvent> = emptyList(),
    val isLive: Boolean = false,
    /** Last sequence number received; null before the first event arrives. */
    val resumeSequence: Long? = null,
    val error: String? = null,
) {
    val hasError: Boolean get() = error != null
    val eventCount: Int get() = events.size

    fun withEvent(event: RuntimeEvent, sequence: Long): EventStreamUiState =
        copy(events = events + event, resumeSequence = sequence)
}

/**
 * Platform-neutral presenter for the event stream (M28).
 *
 * [start] opens the event subscription. [stop] cancels it (called when the user navigates away).
 * If the subscription drops, [resume] re-opens it from [EventStreamUiState.resumeSequence] so
 * no event is missed.
 */
class EventStreamPresenter(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<EventStreamUiState?>(null)
    val state: StateFlow<EventStreamUiState?> = _state.asStateFlow()

    private var subscriptionJob: Job? = null

    fun start(sessionId: String, runId: String, projectId: String) {
        _state.value = EventStreamUiState(sessionId = sessionId, runId = runId, isLive = false)
        subscribe(sessionId = sessionId, runId = runId, projectId = projectId, sinceSequence = null)
    }

    fun resume() {
        val current = _state.value ?: return
        if (current.isLive) return
        // Implementation note: the caller (foreground service or Activity) must supply the
        // projectId again. For now the presenter keeps it for the resume path.
        _currentProjectId?.let { subscribe(current.sessionId, current.runId, it, current.resumeSequence) }
    }

    fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        _state.value = _state.value?.copy(isLive = false)
    }

    private var _currentProjectId: String? = null

    private fun subscribe(sessionId: String, runId: String, projectId: String, sinceSequence: Long?) {
        _currentProjectId = projectId
        subscriptionJob?.cancel()
        subscriptionJob = scope.launch {
            val filter = EventFilter(
                projectIds = listOf(projectId),
                sessionIds = listOf(sessionId),
                sinceSequence = sinceSequence,
            )
            _state.value = _state.value?.copy(isLive = true, error = null)
            try {
                // Sequence tracking: the API wraps events in SequencedEvent. The event stream
                // returns raw RuntimeEvent for now — sequence will be plumbed when EventStore
                // returns SequencedEvent through the transport (RFC-0004, EventStore done).
                client.events.subscribe(filter).collect { event ->
                    val current = _state.value ?: return@collect
                    // Use monotonically increasing arrival count as a local proxy for sequence
                    // until the wire protocol carries the real sequence (RFC-0004).
                    val nextSeq = (current.resumeSequence ?: 0L) + 1L
                    _state.value = current.withEvent(event, nextSeq)
                }
            } catch (e: Exception) {
                _state.value = _state.value?.copy(isLive = false, error = e.message)
            }
        }
    }
}
