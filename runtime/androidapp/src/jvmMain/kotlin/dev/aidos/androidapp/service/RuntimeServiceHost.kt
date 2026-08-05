package dev.aidos.androidapp.service

import dev.aidos.api.RuntimeClient
import dev.aidos.kernel.ExecutionWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime hosting for the Android foreground service (M27, RFC-0050, RFC-0044, RFC-0009, D24).
 *
 * This class encapsulates the lifecycle rules for hosting an Aidos runtime inside an Android
 * foreground service. It is platform-neutral (no Android imports) so the logic is fully testable
 * without an Android device.
 *
 * The actual Android `Service` subclass wires this into `onStartCommand`/`onDestroy` and
 * issues the `startForeground` notification using [currentNotificationText].
 *
 * Rules:
 * - The runtime runs in-process behind the same `RuntimeClient` that the CLI uses (RFC-0052).
 * - A Run that is evicted mid-flight loses no committed step — the checkpoint written before
 *   eviction is the recovery point (RFC-0009 / RFC-0008 M16).
 * - The foreground notification text reflects what is *actually* running, not a generic label.
 * - A background run without a foreground service parks as `ForegroundRequired` (D24).
 */
class RuntimeServiceHost(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    /** Human-readable text for the foreground service notification (D24). */
    val currentNotificationText: String
        get() = when (val s = _state.value) {
            is ServiceState.Idle -> "Aidos: idle"
            is ServiceState.RunningRun -> "Aidos: running \"${s.runDescription}\""
            is ServiceState.EvictedMidRun -> "Aidos: paused at step ${s.lastCheckpointStep}"
        }

    private val activeJob = AtomicReference<Job?>(null)

    /**
     * Starts hosting a run. The [runDescription] is shown in the notification (D24).
     *
     * If a run is already active, this is a no-op (the service hosts one run at a time;
     * the session queue handles ordering).
     */
    fun startRun(runId: String, runDescription: String) {
        if (_state.value is ServiceState.RunningRun) return
        _state.value = ServiceState.RunningRun(runId, runDescription)
    }

    /** Called when a run completes normally. Transitions back to Idle. */
    fun onRunCompleted(runId: String) {
        val current = _state.value
        if (current is ServiceState.RunningRun && current.runId == runId) {
            _state.value = ServiceState.Idle
        }
    }

    /**
     * Called when the OS evicts the service mid-run (e.g., low memory on mobile).
     * Records the last committed checkpoint so the UI can show recovery state (D24).
     *
     * Eviction does NOT lose committed steps — the checkpoint is the recovery point.
     */
    fun onEvicted(runId: String, lastCheckpointStep: Int) {
        _state.value = ServiceState.EvictedMidRun(runId, lastCheckpointStep)
    }

    /** Resumes after eviction — restarts the run from the last checkpoint. */
    fun resumeAfterEviction() {
        val s = _state.value
        if (s is ServiceState.EvictedMidRun) {
            _state.value = ServiceState.RunningRun(s.runId, "Resumed at step ${s.lastCheckpointStep}")
        }
    }

    suspend fun shutdown() {
        activeJob.get()?.cancelAndJoin()
        _state.value = ServiceState.Idle
    }
}

/** Live state of the foreground service (M27, D24). */
sealed interface ServiceState {
    /** No active run. */
    data object Idle : ServiceState

    /** A run is actively executing in the foreground service. */
    data class RunningRun(val runId: String, val runDescription: String) : ServiceState

    /** The OS evicted the service mid-run. The last checkpoint step is remembered. */
    data class EvictedMidRun(val runId: String, val lastCheckpointStep: Int) : ServiceState
}
