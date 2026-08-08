package dev.aidos.androidapp.service

import dev.aidos.kernel.ExecutionWindow
import kotlinx.coroutines.flow.StateFlow

/**
 * Execution window that gates local model inference on foreground service availability (D24, M27).
 *
 * On MOBILE, a Run may make a local model call only under a foreground service (RFC-0044, decision D24).
 * This implementation checks the [RuntimeServiceHost]'s live state — a Run attempting local inference
 * when the service is Idle or EvictedMidRun will be parked with [RoutingDecision.ForegroundRequired].
 *
 * The window is live: if the app is backgrounded mid-Run, [permitsLocalInference] transitions from
 * true to false, and the next model call will be denied rather than allowed and then interrupted.
 */
class ForegroundServiceExecutionWindow(
    private val serviceState: StateFlow<ServiceState>,
) : ExecutionWindow {

    /**
     * Remaining execution time (not tracked at present; D24 does not time-bound a foreground service).
     *
     * Returns null (unbounded) — the Android foreground service lifecycle, not a timer, is what
     * bounds the work. The executor will respect the OS's background eviction without needing a
     * time budget; checkpointing handles the eviction (RFC-0009).
     */
    override fun remainingMillis(): Long? = null

    /**
     * Whether a local model call may begin now.
     *
     * Returns true only when a Run is actively executing in the foreground service (RunningRun).
     * Returns false when the service is Idle (user backgrounded the app) or EvictedMidRun
     * (OS backgrounded the service mid-flight). In both cases, the Run parks with
     * [RoutingDecision.ForegroundRequired] rather than attempting local inference.
     */
    override fun permitsLocalInference(): Boolean {
        return serviceState.value is ServiceState.RunningRun
    }
}
