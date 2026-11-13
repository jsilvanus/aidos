package dev.aidos.policy

import dev.aidos.routes.Route
import dev.aidos.routes.RouteLatency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RoutePlannerPolicyTest {
    private val policy = RoutePlannerPolicy(lanRttThreshold = 50.milliseconds)

    @Test
    fun lanRouteIsAlwaysSafeForSplit() {
        val latency = RouteLatency(Route.Lan, rttMillis = 0)
        assertTrue(policy.isSafeForSplit(latency))
    }

    @Test
    fun lanRouteIsSafeEvenWithHighRtt() {
        val latency = RouteLatency(Route.Lan, rttMillis = 1000)
        assertTrue(policy.isSafeForSplit(latency), "Lan route is always safe, regardless of RTT")
    }

    @Test
    fun directRouteWithLowRttIsSafe() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 25)
        assertTrue(policy.isSafeForSplit(latency))
    }

    @Test
    fun directRouteWithHighRttIsNotSafe() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 100)
        assertFalse(policy.isSafeForSplit(latency))
    }

    @Test
    fun directRouteExactlyAtThresholdIsSafe() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 50)
        assertTrue(policy.isSafeForSplit(latency))
    }

    @Test
    fun directRouteJustAboveThresholdIsNotSafe() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 51)
        assertFalse(policy.isSafeForSplit(latency))
    }

    @Test
    fun sshReverseRouteWithLowRttIsSafe() {
        val latency = RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 30)
        assertTrue(policy.isSafeForSplit(latency))
    }

    @Test
    fun sshReverseRouteWithHighRttIsNotSafe() {
        val latency = RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 200)
        assertFalse(policy.isSafeForSplit(latency))
    }

    @Test
    fun unmeasuredRouteIsNotSafeForSplit() {
        // RTT = -1 indicates failure
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = -1)
        assertFalse(policy.isSafeForSplit(latency))
    }

    @Test
    fun filterLocalRoutesReturnsOnlySafe() {
        val latencies = listOf(
            RouteLatency(Route.Lan, rttMillis = 10),
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),  // Too slow
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 30),   // OK
        )
        val local = policy.filterLocalRoutes(latencies)
        assertEquals(2, local.size)
        assertTrue(local.all { policy.isSafeForSplit(it) })
    }

    @Test
    fun filterLocalRoutesSortsbyRtt() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 40),
            RouteLatency(Route.Lan, rttMillis = 5),
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 25),
        )
        val local = policy.filterLocalRoutes(latencies)
        assertEquals(3, local.size)
        assertEquals(5, local[0].rttMillis)
        assertEquals(25, local[1].rttMillis)
        assertEquals(40, local[2].rttMillis)
    }

    @Test
    fun hasLocalRouteReturnsTrueIfAnyRouteIsSafe() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),  // Too slow
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 30),   // OK
        )
        assertTrue(policy.hasLocalRoute(latencies))
    }

    @Test
    fun hasLocalRouteReturnsFalseIfNoRouteSafe() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 200),
        )
        assertFalse(policy.hasLocalRoute(latencies))
    }

    @Test
    fun selectBestLocalRouteReturnsNullIfNone() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),
        )
        assertEquals(null, policy.selectBestLocalRoute(latencies))
    }

    @Test
    fun selectBestLocalRouteReturnsFastest() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 40),
            RouteLatency(Route.Lan, rttMillis = 5),
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 25),
        )
        val best = policy.selectBestLocalRoute(latencies)
        assertEquals(5, best?.rttMillis)
        assertEquals(Route.Lan, best?.route)
    }

    @Test
    fun routeClassification() {
        assertEquals(RouteClass.Lan, Route.Lan.classify())
        assertEquals(RouteClass.Direct, Route.Direct("host", 123).classify())
        assertEquals(RouteClass.SshTunnel, Route.SshReverse("id", "host", 456).classify())
    }
}

class RemoteAccessPolicyTest {
    private val policy = RemoteAccessPolicy()

    @Test
    fun initializelyNopeersAllowed() {
        assertTrue(policy.getAllAllowedPeers().isEmpty())
    }

    @Test
    fun allowEnablesPeerForRemoteAccess() {
        policy.allow("peer-1")
        assertTrue(policy.isRemoteAccessAllowed("peer-1"))
    }

    @Test
    fun disallowRemovesPeerFromRemoteAccess() {
        policy.allow("peer-1")
        assertTrue(policy.isRemoteAccessAllowed("peer-1"))
        policy.disallow("peer-1")
        assertFalse(policy.isRemoteAccessAllowed("peer-1"))
    }

    @Test
    fun toggleEnablesWhenDisabled() {
        val result = policy.toggle("peer-1")
        assertTrue(result)
        assertTrue(policy.isRemoteAccessAllowed("peer-1"))
    }

    @Test
    fun toggleDisablesWhenEnabled() {
        policy.allow("peer-1")
        val result = policy.toggle("peer-1")
        assertFalse(result)
        assertFalse(policy.isRemoteAccessAllowed("peer-1"))
    }

    @Test
    fun filterAllowedRoutesReturnsAllIfOnlyLan() {
        val latencies = listOf(
            RouteLatency(Route.Lan, rttMillis = 10),
            RouteLatency(Route.Lan, rttMillis = 20),
        )
        val filtered = policy.filterAllowedRoutes("peer-1", latencies)
        assertEquals(2, filtered.size)
    }

    @Test
    fun filterAllowedRoutesReturnsAllIfRemoteAccessAllowed() {
        policy.allow("peer-1")
        val latencies = listOf(
            RouteLatency(Route.Lan, rttMillis = 10),
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),
        )
        val filtered = policy.filterAllowedRoutes("peer-1", latencies)
        assertEquals(2, filtered.size)
    }

    @Test
    fun filterAllowedRoutesFiltersRemoteIfNotAllowed() {
        val latencies = listOf(
            RouteLatency(Route.Lan, rttMillis = 10),
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),
            RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 200),
        )
        val filtered = policy.filterAllowedRoutes("peer-1", latencies)
        assertEquals(1, filtered.size)
        assertEquals(Route.Lan, filtered[0].route)
    }

    @Test
    fun getAllAllowedPeersReturnsSet() {
        policy.allow("peer-1")
        policy.allow("peer-2")
        val allowed = policy.getAllAllowedPeers()
        assertEquals(setOf("peer-1", "peer-2"), allowed)
    }

    @Test
    fun clearAllRemovesAllPermissions() {
        policy.allow("peer-1")
        policy.allow("peer-2")
        policy.clearAll()
        assertTrue(policy.getAllAllowedPeers().isEmpty())
    }

    @Test
    fun remoteAccessAuditEventFormatting() {
        val event = RemoteAccessAuditEvent("peer-1", "Home Desktop", allowed = true)
        val log = event.toLog()
        assertTrue("enabled" in log && "Home Desktop" in log)
    }
}
