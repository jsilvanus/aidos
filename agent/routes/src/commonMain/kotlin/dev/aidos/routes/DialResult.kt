package dev.aidos.routes

/** Result of attempting to dial a single route. */
sealed class DialResult {
    /** Successfully dialed the route. */
    data class Success(val route: Route, val rttMillis: Long) : DialResult()

    /** Failed to dial the route. */
    data class Failure(val route: Route, val reason: String) : DialResult()
}
