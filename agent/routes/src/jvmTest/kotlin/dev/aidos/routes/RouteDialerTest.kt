package dev.aidos.routes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RouteDialerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    @Test
    fun dialTriesLanFirst() = runBlocking {
        val route = Route.Lan
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to { delay(10); 10 },
        )

        val dialer = RouteDialer(RouteHints.lanOnly(), dialers, scope, 5.seconds)
        dialer.startDial()

        withTimeout(10.seconds) {
            val connected = dialer.state.first { it is DialerState.Connected } as DialerState.Connected
            assertEquals(route, connected.route)
        }
    }

    @Test
    fun dialFallsBackToDirectWhenLanFails() = runBlocking {
        val lanRoute = Route.Lan
        val directRoute = Route.Direct("peer-address", 8766)

        var lanAttempts = 0
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to {
                lanAttempts++
                throw Exception("LAN not available")
            },
            Route.Direct::class.java to { delay(5); 5 },
        )

        val hints = RouteHints(listOf(lanRoute, directRoute))
        val dialer = RouteDialer(hints, dialers, scope, 1.seconds)
        dialer.startDial()

        withTimeout(10.seconds) {
            val connected = dialer.state.first { it is DialerState.Connected } as DialerState.Connected
            assertEquals(directRoute, connected.route)
            assertEquals(1, lanAttempts, "LAN was tried and failed")
        }
    }

    @Test
    fun dialMeasuresRtt() = runBlocking {
        val route = Route.Lan
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to { 42 },  // Return RTT directly
        )

        val dialer = RouteDialer(RouteHints.lanOnly(), dialers, scope, 5.seconds)
        dialer.startDial()

        withTimeout(10.seconds) {
            val connected = dialer.state.first { it is DialerState.Connected }
            val c = connected as DialerState.Connected
            assertEquals(42, c.rttMillis)
        }
    }

    @Test
    fun dialRecordsLatencies() = runBlocking {
        val lanRoute = Route.Lan
        val directRoute = Route.Direct("peer", 8766)

        var dialCalls = 0
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to {
                dialCalls++
                throw Exception("LAN failed")
            },
            Route.Direct::class.java to {
                dialCalls++
                50
            },
        )

        val hints = RouteHints(listOf(lanRoute, directRoute))
        val dialer = RouteDialer(hints, dialers, scope, 1.seconds)
        dialer.startDial()

        withTimeout(10.seconds) {
            dialer.state.first { it is DialerState.Connected }
            val latencies = dialer.latencies.value
            assertEquals(2, latencies.size)
            assertEquals(lanRoute, latencies[0].route)
            assertEquals(-1, latencies[0].rttMillis)  // -1 = failure
            assertEquals(directRoute, latencies[1].route)
            assertEquals(50, latencies[1].rttMillis)  // success
        }
    }

    @Test
    fun dialTimesOutPerRoute() = runBlocking {
        val slowRoute = Route.Lan
        val fastRoute = Route.Direct("peer", 8766)

        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to {
                delay(10.seconds)  // Will timeout
                10_000
            },
            Route.Direct::class.java to { 5 },
        )

        val hints = RouteHints(listOf(slowRoute, fastRoute))
        val dialer = RouteDialer(hints, dialers, scope, 100.milliseconds)
        dialer.startDial()

        withTimeout(10.seconds) {
            val connected = dialer.state.first { it is DialerState.Connected }
            val c = connected as DialerState.Connected
            assertEquals(fastRoute, c.route)
        }
    }

    @Test
    fun allFailedWhenNoRoutesWork() = runBlocking {
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to { throw Exception("no LAN") },
        )

        val dialer = RouteDialer(RouteHints.lanOnly(), dialers, scope, 1.seconds)
        dialer.startDial()

        withTimeout(10.seconds) {
            val failed = dialer.state.first { it is DialerState.AllFailed }
            assertTrue(failed is DialerState.AllFailed)
            assertEquals(1, (failed as DialerState.AllFailed).routeCount)
        }
    }

    @Test
    fun stopCancelsDialing() = runBlocking {
        var lanAttempted = false
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to {
                lanAttempted = true
                delay(1.seconds)
                10
            },
        )

        val dialer = RouteDialer(RouteHints.lanOnly(), dialers, scope, 5.seconds)
        dialer.startDial()
        delay(50.milliseconds)
        dialer.stop()

        assertEquals(DialerState.Idle, dialer.state.value)
        // LAN may or may not have been attempted depending on timing
    }

    @Test
    fun routeHintsValidation() {
        // Must start with Lan
        val ok = RouteHints(listOf(Route.Lan, Route.Direct("host", 123)))
        assertEquals(Route.Lan, ok.hints[0])

        // RouteHints() defaults to LAN-only
        val default = RouteHints()
        assertEquals(listOf(Route.Lan), default.hints)
    }
}

class RouteModelsTest {
    @Test
    fun directRouteValidation() {
        val ok = Route.Direct("example.com", 8766)
        assertEquals("example.com", ok.host)
        assertEquals(8766, ok.port)

        val invalidPort = try {
            Route.Direct("example.com", 0)
            null
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(invalidPort == true)
    }

    @Test
    fun sshReverseRouteValidation() {
        val ok = Route.SshReverse("home", "home.example.com", 47100)
        assertEquals("home", ok.tunnelId)
        assertEquals("home.example.com", ok.serverHost)
        assertEquals(47100, ok.serverPort)
    }

    @Test
    fun dialResultTypes() {
        val success = DialResult.Success(Route.Lan, 42)
        val failure = DialResult.Failure(Route.Lan, "no LAN available")

        assertEquals(Route.Lan, success.route)
        assertEquals(42, success.rttMillis)
        assertEquals(Route.Lan, failure.route)
        assertEquals("no LAN available", failure.reason)
    }
}
