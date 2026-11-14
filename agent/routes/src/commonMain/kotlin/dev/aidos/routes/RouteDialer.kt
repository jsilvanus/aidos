package dev.aidos.routes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * RFC-0108 Phase 1: Dialer that tries routes in order with fast redial on network change.
 * The caller provides:
 * - Route hints to try
 * - A dial function for each route type
 * - Network change notification
 *
 * The dialer:
 * - Tries routes in order (Lan first)
 * - Measures RTT on success
 * - Fails over to next route on failure
 * - Redials immediately on network change
 * - Maintains state observable for UI/metrics
 */
class RouteDialer(
    private val hints: RouteHints,
    private val dialers: Map<Class<out Route>, suspend (Route) -> Long>,  // returns RTT ms
    private val scope: CoroutineScope,
    private val dialTimeoutPerRoute: Duration = 15.seconds,
) {
    init {
        require(dialTimeoutPerRoute > Duration.ZERO) { "dialTimeoutPerRoute must be positive" }
    }

    private val stateLock = Any()
    private val _state = MutableStateFlow<DialerState>(DialerState.Idle)
    val state: StateFlow<DialerState> = _state.asStateFlow()

    private val _latencies = MutableStateFlow<List<RouteLatency>>(emptyList())
    val latencies: StateFlow<List<RouteLatency>> = _latencies.asStateFlow()

    private val _failures = MutableStateFlow<List<DialResult.Failure>>(emptyList())
    val failures: StateFlow<List<DialResult.Failure>> = _failures.asStateFlow()

    private var job: Job? = null

    /** Starts dialing. */
    fun startDial() {
        synchronized(stateLock) {
            if (job?.isActive == true) return
            _latencies.value = emptyList()
            _failures.value = emptyList()
            _state.value = DialerState.Dialing(0)
            job = scope.launch { dialSequence(previousJob = null) }
        }
    }

    /** Stop dialing. */
    fun stop() {
        synchronized(stateLock) {
            job?.cancel()
            job = null
            _state.value = DialerState.Idle
        }
    }

    /** When network changes (Wi-Fi ↔ cellular, online ↔ offline), redial immediately. */
    fun onNetworkChange() {
        synchronized(stateLock) {
            val previous = job
            if (previous?.isActive == true) {
                previous.cancel()
                _latencies.value = emptyList()
                _failures.value = emptyList()
                _state.value = DialerState.Dialing(0)
                job = scope.launch { dialSequence(previousJob = previous) }
            }
        }
    }

    private suspend fun dialSequence(previousJob: Job?) {
        previousJob?.join()
        val myJob = currentCoroutineContext()[Job]
        val attempted = mutableListOf<RouteLatency>()
        val failed = mutableListOf<DialResult.Failure>()
        var routeIndex = 0

        while (routeIndex < hints.hints.size) {
            currentCoroutineContext().ensureActive()
            val route = hints.hints[routeIndex]
            synchronized(stateLock) {
                if (job !== myJob) return
                _state.value = DialerState.Dialing(routeIndex)
            }

            val result = tryDial(route)
            when (result) {
                is DialResult.Success -> {
                    val latency = RouteLatency(route, result.rttMillis)
                    attempted.add(latency)
                    synchronized(stateLock) {
                        if (job !== myJob) return
                        _latencies.value = attempted.toList()
                        _failures.value = failed.toList()
                        _state.value = DialerState.Connected(route, result.rttMillis)
                    }
                    return
                }

                is DialResult.Failure -> {
                    attempted.add(RouteLatency(route, -1))  // -1 marks failure
                    failed.add(result)
                    synchronized(stateLock) {
                        if (job === myJob) {
                            _latencies.value = attempted.toList()
                            _failures.value = failed.toList()
                        }
                    }
                    routeIndex++
                }
            }
        }

        // All routes failed
        synchronized(stateLock) {
            if (job !== myJob) return
            _latencies.value = attempted.toList()
            _failures.value = failed.toList()
            _state.value = DialerState.AllFailed(hints.hints.size)
        }
    }

    private suspend fun tryDial(route: Route): DialResult {
        val dialer = dialers[route::class.java]
            ?: return DialResult.Failure(route, "no dialer registered for ${route::class.simpleName ?: "route"}")
        return try {
            val rttMs = withTimeoutOrNull(dialTimeoutPerRoute) {
                dialer(route)
            } ?: return DialResult.Failure(route, "timeout")

            if (rttMs < 0) {
                return DialResult.Failure(route, "invalid negative RTT: $rttMs")
            }

            DialResult.Success(route, rttMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "error"
            DialResult.Failure(route, reason)
        }
    }
}

/** Observable state of the dialer. */
sealed interface DialerState {
    data object Idle : DialerState

    /** Trying route at [index]. */
    data class Dialing(val routeIndex: Int) : DialerState

    /** Successfully connected via [route] with [rttMillis] round-trip time. */
    data class Connected(val route: Route, val rttMillis: Long) : DialerState

    /** All [routeCount] routes failed. */
    data class AllFailed(val routeCount: Int) : DialerState
}
