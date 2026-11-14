package dev.aidos.routes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            assertEquals(2, dialCalls)
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
            val connected = dialer.state.first { it is DialerState.Connected } as DialerState.Connected
            assertEquals(fastRoute, connected.route)
            assertEquals(1, dialer.failures.value.size)
            assertEquals(slowRoute, dialer.failures.value[0].route)
            assertEquals("timeout", dialer.failures.value[0].reason)
        }
    }

    @Test
    fun dialReportsAllFailedWhenEveryRouteTimesOut() = runBlocking {
        val directRoute = Route.Direct("peer", 8766)
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to {
                delay(10.seconds)
                10
            },
            Route.Direct::class.java to {
                delay(10.seconds)
                20
            },
        )

        val hints = RouteHints(listOf(Route.Lan, directRoute))
        val dialer = RouteDialer(hints, dialers, scope, 50.milliseconds)
        dialer.startDial()

        withTimeout(10.seconds) {
            val failed = dialer.state.first { it is DialerState.AllFailed } as DialerState.AllFailed
            assertEquals(2, failed.routeCount)
            assertEquals(listOf(-1L, -1L), dialer.latencies.value.map { it.rttMillis })
            assertEquals(listOf("timeout", "timeout"), dialer.failures.value.map { it.reason })
        }
    }

    @Test
    fun cancellationExceptionPropagatesWithoutFallbackOrFailureState() = runBlocking {
        val lanEntered = CompletableDeferred<Unit>()
        var directAttempted = false
        val directRoute = Route.Direct("peer", 8766)

        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to {
                lanEntered.complete(Unit)
                throw CancellationException("caller cancelled")
            },
            Route.Direct::class.java to {
                directAttempted = true
                10
            },
        )

        val hints = RouteHints(listOf(Route.Lan, directRoute))
        val dialer = RouteDialer(hints, dialers, scope, 1.seconds)
        dialer.startDial()

        withTimeout(5.seconds) {
            lanEntered.await()
        }
        delay(100.milliseconds)

        assertFalse(directAttempted, "CancellationException must not fall through to next route")
        assertTrue(dialer.state.value is DialerState.Dialing, "Must not transition to Connected or AllFailed")
        assertTrue(dialer.failures.value.isEmpty(), "CancellationException must not be recorded as dial failure")
    }

    @Test
    fun missingDialerRecordsExplicitCauseImmediately() = runBlocking {
        val directRoute = Route.Direct("peer", 8766)
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Direct::class.java to { 15 },
        )

        val dialer = RouteDialer(RouteHints(listOf(Route.Lan, directRoute)), dialers, scope, 10.seconds)
        dialer.startDial()

        withTimeout(2.seconds) {
            val connected = dialer.state.first { it is DialerState.Connected } as DialerState.Connected
            assertEquals(directRoute, connected.route)
            assertEquals(1, dialer.failures.value.size)
            assertTrue(dialer.failures.value[0].reason.contains("no dialer"))
        }
    }

    @Test
    fun negativeRttIsRejectedAsDialFailure() = runBlocking {
        val directRoute = Route.Direct("peer", 8766)
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to { -5 },
            Route.Direct::class.java to { 12 },
        )

        val dialer = RouteDialer(RouteHints(listOf(Route.Lan, directRoute)), dialers, scope, 1.seconds)
        dialer.startDial()

        withTimeout(5.seconds) {
            val connected = dialer.state.first { it is DialerState.Connected } as DialerState.Connected
            assertEquals(directRoute, connected.route)
            assertEquals(-1L, dialer.latencies.value[0].rttMillis)
            assertTrue(dialer.failures.value[0].reason.contains("negative RTT"))
        }
    }

    @Test
    fun invalidDialTimeoutRejected() {
        assertFailsWith<IllegalArgumentException> {
            RouteDialer(RouteHints.lanOnly(), emptyMap(), scope, 0.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            RouteDialer(RouteHints.lanOnly(), emptyMap(), scope, (-1).seconds)
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
            val failed = dialer.state.first { it is DialerState.AllFailed } as DialerState.AllFailed
            assertEquals(1, failed.routeCount)
            assertEquals(listOf("no LAN"), dialer.failures.value.map { it.reason })
        }
    }

    @Test
    fun stopCancelsDialing() = runBlocking {
        val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
            Route.Lan::class.java to {
                delay(1.seconds)
                10
            },
        )

        val dialer = RouteDialer(RouteHints.lanOnly(), dialers, scope, 5.seconds)
        dialer.startDial()
        delay(50.milliseconds)
        dialer.stop()

        assertEquals(DialerState.Idle, dialer.state.value)
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
        assertEquals(1, Route.Direct("example.com", 1).port)
        assertEquals(65535, Route.Direct("example.com", 65535).port)

        assertFailsWith<IllegalArgumentException> { Route.Direct("example.com", 0) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("example.com", -1) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("example.com", 65536) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("", 8766) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("   ", 8766) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("bad host", 8766) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("bad\nhost", 8766) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("bad\u0000host", 8766) }
        assertFailsWith<IllegalArgumentException> { Route.Direct("a".repeat(256), 8766) }
    }

    @Test
    fun sshReverseRouteValidation() {
        val ok = Route.SshReverse("home", "home.example.com", 47100)
        assertEquals("home", ok.tunnelId)
        assertEquals("home.example.com", ok.serverHost)
        assertEquals(47100, ok.serverPort)

        assertFailsWith<IllegalArgumentException> { Route.SshReverse("", "home.example.com", 47100) }
        assertFailsWith<IllegalArgumentException> { Route.SshReverse("bad id", "home.example.com", 47100) }
        assertFailsWith<IllegalArgumentException> { Route.SshReverse("bad:id", "home.example.com", 47100) }
        assertFailsWith<IllegalArgumentException> { Route.SshReverse("home", "   ", 47100) }
        assertFailsWith<IllegalArgumentException> { Route.SshReverse("home", "bad host", 47100) }
        assertFailsWith<IllegalArgumentException> { Route.SshReverse("home", "bad\rhost", 47100) }
        assertFailsWith<IllegalArgumentException> { Route.SshReverse("home", "home.example.com", 0) }
        assertFailsWith<IllegalArgumentException> { Route.SshReverse("home", "home.example.com", 65536) }
    }

    @Test
    fun dialResultTypes() {
        val success = DialResult.Success(Route.Lan, 42)
        val failure = DialResult.Failure(Route.Lan, "no LAN available")

        assertEquals(Route.Lan, success.route)
        assertEquals(42, success.rttMillis)
        assertEquals(Route.Lan, failure.route)
        assertEquals("no LAN available", failure.reason)
        assertFailsWith<IllegalArgumentException> { DialResult.Success(Route.Lan, -1) }
    }
}
