package dev.aidos.routes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    private val _state = MutableStateFlow<DialerState>(DialerState.Idle)
    val state: StateFlow<DialerState> = _state.asStateFlow()

    private val _latencies = MutableStateFlow<List<RouteLatency>>(emptyList())
    val latencies: StateFlow<List<RouteLatency>> = _latencies.asStateFlow()

    private var job: Job? = null

    /** Starts dialing. */
    fun startDial() {
        if (job?.isActive == true) return
        _state.value = DialerState.Dialing(0)
        job = scope.launch { dialSequence() }
    }

    /** Stop dialing. */
    fun stop() {
        job?.cancel()
        job = null
        _state.value = DialerState.Idle
    }

    /** When network changes (Wi-Fi ↔ cellular, online ↔ offline), redial immediately. */
    fun onNetworkChange() {
        if (job?.isActive == true) {
            job?.cancel()
            job = scope.launch { dialSequence() }
        }
    }

    private suspend fun dialSequence() {
        val attempted = mutableListOf<RouteLatency>()
        var routeIndex = 0

        while (routeIndex < hints.hints.size) {
            val route = hints.hints[routeIndex]
            _state.value = DialerState.Dialing(routeIndex)

            val result = tryDial(route)
            when (result) {
                is DialResult.Success -> {
                    val latency = RouteLatency(route, result.rttMillis)
                    attempted.add(latency)
                    _latencies.value = attempted
                    _state.value = DialerState.Connected(route, result.rttMillis)
                    return
                }

                is DialResult.Failure -> {
                    attempted.add(RouteLatency(route, -1))  // -1 marks failure
                    routeIndex++
                }
            }
        }

        // All routes failed
        _latencies.value = attempted
        _state.value = DialerState.AllFailed(hints.hints.size)
    }

    private suspend fun tryDial(route: Route): DialResult {
        return try {
            val startTime = System.currentTimeMillis()
            val rttMs = withTimeoutOrNull(dialTimeoutPerRoute) {
                val dialer = dialers[route::class.java] ?: return@withTimeoutOrNull null
                dialer(route)
            } ?: return DialResult.Failure(route, "timeout")

            DialResult.Success(route, rttMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DialResult.Failure(route, e.message ?: e::class.simpleName ?: "error")
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
