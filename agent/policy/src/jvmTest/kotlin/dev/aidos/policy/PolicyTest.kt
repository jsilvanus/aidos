package dev.aidos.policy

import dev.aidos.routes.Route
import dev.aidos.routes.RouteLatency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RoutePlannerPolicyTest {
    private val localOnlyPolicy = RoutePlannerPolicy(lanRttThreshold = 50.milliseconds)
    private val clusterPolicy = RoutePlannerPolicy(
        lanRttThreshold = 50.milliseconds,
        defaultLocality = Locality.TRUSTED_CLUSTER,
    )
    private val anyPolicy = RoutePlannerPolicy(
        lanRttThreshold = 50.milliseconds,
        defaultLocality = Locality.ANY,
    )

    @Test
    fun lanRouteIsAlwaysSafeForSplit() {
        val latency = RouteLatency(Route.Lan, rttMillis = 0)
        assertTrue(localOnlyPolicy.isSafeForSplit(latency))
        assertTrue(clusterPolicy.isSafeForSplit(latency))
        assertTrue(anyPolicy.isSafeForSplit(latency))
    }

    @Test
    fun lanRouteIsSafeEvenWithHighRtt() {
        val latency = RouteLatency(Route.Lan, rttMillis = 1000)
        assertTrue(localOnlyPolicy.isSafeForSplit(latency), "Lan route is always safe, regardless of RTT")
    }

    @Test
    fun failedLanRouteIsNotSafeForSplit() {
        val failedLan = RouteLatency(Route.Lan, rttMillis = -1)
        assertFalse(localOnlyPolicy.isSafeForSplit(failedLan))
        assertFalse(clusterPolicy.isSafeForSplit(failedLan))
    }

    @Test
    fun localOnlyBlocksDirectAndTunnelRoutesEvenWithLowRtt() {
        val lowRttDirect = RouteLatency(Route.Direct("peer", 8766), rttMillis = 5)
        val lowRttTunnel = RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 10)
        val lan = RouteLatency(Route.Lan, rttMillis = 20)

        assertFalse(localOnlyPolicy.isSafeForSplit(lowRttDirect))
        assertFalse(localOnlyPolicy.isSafeForSplit(lowRttTunnel))
        assertFalse(localOnlyPolicy.isRoutePermitted(lowRttDirect.route))
        assertFalse(localOnlyPolicy.isRoutePermitted(lowRttTunnel.route))
        assertTrue(localOnlyPolicy.isRoutePermitted(Route.Lan))

        assertEquals(
            listOf(lan),
            localOnlyPolicy.filterLocalRoutes(listOf(lowRttDirect, lowRttTunnel, lan)),
        )
        assertEquals(
            listOf(lan),
            localOnlyPolicy.filterPermittedRoutes(listOf(lowRttDirect, lowRttTunnel, lan)),
        )
        assertFalse(localOnlyPolicy.hasLocalRoute(listOf(lowRttDirect, lowRttTunnel)))
        assertEquals(null, localOnlyPolicy.selectBestLocalRoute(listOf(lowRttDirect, lowRttTunnel)))
    }

    @Test
    fun directRouteWithLowRttIsSafeUnderTrustedCluster() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 25)
        assertTrue(clusterPolicy.isSafeForSplit(latency))
        assertTrue(anyPolicy.isSafeForSplit(latency))
    }

    @Test
    fun directRouteWithHighRttIsNotSafe() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 100)
        assertFalse(localOnlyPolicy.isSafeForSplit(latency))
        assertFalse(clusterPolicy.isSafeForSplit(latency))
        assertFalse(anyPolicy.isSafeForSplit(latency))
    }

    @Test
    fun directRouteExactlyAtThresholdIsSafeUnderTrustedCluster() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 50)
        assertFalse(localOnlyPolicy.isSafeForSplit(latency))
        assertTrue(clusterPolicy.isSafeForSplit(latency))
    }

    @Test
    fun directRouteJustAboveThresholdIsNotSafe() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = 51)
        assertFalse(clusterPolicy.isSafeForSplit(latency))
    }

    @Test
    fun sshReverseRouteIsNeverSafeForSplitEvenWithLowRtt() {
        val lowRttTunnel = RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 30)
        val highRttTunnel = RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 200)

        assertFalse(localOnlyPolicy.isSafeForSplit(lowRttTunnel))
        assertFalse(clusterPolicy.isSafeForSplit(lowRttTunnel))
        assertFalse(anyPolicy.isSafeForSplit(lowRttTunnel))
        assertFalse(anyPolicy.isSafeForSplit(highRttTunnel))

        assertFalse(localOnlyPolicy.isRoutePermitted(lowRttTunnel.route))
        assertFalse(clusterPolicy.isRoutePermitted(lowRttTunnel.route))
        assertTrue(anyPolicy.isRoutePermitted(lowRttTunnel.route))
    }

    @Test
    fun localitySemanticsAcrossRouteClasses() {
        val direct = Route.Direct("peer-tailscale", 8766)
        val tunnel = Route.SshReverse("home", "server.com", 47100)

        assertTrue(localOnlyPolicy.isRoutePermitted(Route.Lan))
        assertFalse(localOnlyPolicy.isRoutePermitted(direct))
        assertFalse(localOnlyPolicy.isRoutePermitted(tunnel))

        assertTrue(clusterPolicy.isRoutePermitted(Route.Lan))
        assertTrue(clusterPolicy.isRoutePermitted(direct))
        assertFalse(clusterPolicy.isRoutePermitted(tunnel))

        assertTrue(anyPolicy.isRoutePermitted(Route.Lan))
        assertTrue(anyPolicy.isRoutePermitted(direct))
        assertTrue(anyPolicy.isRoutePermitted(tunnel))
    }

    @Test
    fun unmeasuredRouteIsNotSafeForSplit() {
        val latency = RouteLatency(Route.Direct("peer", 8766), rttMillis = -1)
        assertFalse(localOnlyPolicy.isSafeForSplit(latency))
        assertFalse(clusterPolicy.isSafeForSplit(latency))
    }

    @Test
    fun filterLocalRoutesReturnsOnlySafe() {
        val latencies = listOf(
            RouteLatency(Route.Lan, rttMillis = 10),
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),  // Too slow
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 30),   // OK under cluster
            RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 20),  // Never split
        )
        val local = clusterPolicy.filterLocalRoutes(latencies)
        assertEquals(2, local.size)
        assertTrue(local.all { clusterPolicy.isSafeForSplit(it) })
    }

    @Test
    fun filterLocalRoutesSortsByRtt() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 40),
            RouteLatency(Route.Lan, rttMillis = 5),
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 25),
        )
        val local = clusterPolicy.filterLocalRoutes(latencies)
        assertEquals(3, local.size)
        assertEquals(5, local[0].rttMillis)
        assertEquals(25, local[1].rttMillis)
        assertEquals(40, local[2].rttMillis)
    }

    @Test
    fun hasLocalRouteReturnsTrueIfAnyRouteIsSafe() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),  // Too slow
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 30),   // OK under cluster
        )
        assertTrue(clusterPolicy.hasLocalRoute(latencies))
        assertFalse(localOnlyPolicy.hasLocalRoute(latencies))
    }

    @Test
    fun hasLocalRouteReturnsFalseIfNoRouteSafe() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 200),
        )
        assertFalse(clusterPolicy.hasLocalRoute(latencies))
    }

    @Test
    fun selectBestLocalRouteReturnsNullIfNone() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),
        )
        assertEquals(null, clusterPolicy.selectBestLocalRoute(latencies))
    }

    @Test
    fun selectBestLocalRouteReturnsFastest() {
        val latencies = listOf(
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 40),
            RouteLatency(Route.Lan, rttMillis = 5),
            RouteLatency(Route.Direct("peer", 8767), rttMillis = 25),
        )
        val best = clusterPolicy.selectBestLocalRoute(latencies)
        assertEquals(5, best?.rttMillis)
        assertEquals(Route.Lan, best?.route)
    }

    @Test
    fun negativeRttThresholdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            RoutePlannerPolicy(lanRttThreshold = (-1).milliseconds)
        }
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
    fun initiallyNoPeersAllowed() {
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
    fun unknownPeerFailsClosedWhenKnownPeersBounded() {
        val bounded = RemoteAccessPolicy(knownPeers = setOf("peer-1"))
        val latencies = listOf(
            RouteLatency(Route.Lan, rttMillis = 10),
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 100),
        )

        assertFalse(bounded.isRemoteAccessAllowed("unknown-peer"))
        assertTrue(bounded.filterAllowedRoutes("unknown-peer", latencies).isEmpty())
        assertFailsWith<IllegalStateException> { bounded.allow("unknown-peer") }
        assertFailsWith<IllegalStateException> { bounded.toggle("unknown-peer") }

        bounded.allow("peer-1")
        assertEquals(2, bounded.filterAllowedRoutes("peer-1", latencies).size)
    }

    @Test
    fun invalidPeerIdsRejectedAndFailClosed() {
        val invalidIds = listOf("", "   ", "peer 1", "peer\n1", "peer/1", "..", ".", "a".repeat(129))
        val latencies = listOf(RouteLatency(Route.Lan, rttMillis = 10))

        for (badId in invalidIds) {
            assertFailsWith<IllegalArgumentException> { policy.allow(badId) }
            assertFailsWith<IllegalArgumentException> { policy.disallow(badId) }
            assertFailsWith<IllegalArgumentException> { policy.toggle(badId) }
            assertFailsWith<IllegalArgumentException> { policy.revoke(badId) }
            assertFailsWith<IllegalArgumentException> { RemoteAccessAuditEvent(badId, null, true) }
            assertFalse(policy.isRemoteAccessAllowed(badId))
            assertTrue(policy.filterAllowedRoutes(badId, latencies).isEmpty())
        }
    }

    @Test
    fun revokedPeerFailsClosedAcrossAllRoutesAndRejectsAllow() {
        val latencies = listOf(
            RouteLatency(Route.Lan, rttMillis = 10),
            RouteLatency(Route.Direct("peer", 8766), rttMillis = 20),
            RouteLatency(Route.SshReverse("home", "server.com", 47100), rttMillis = 30),
        )
        policy.allow("peer-1")
        assertEquals(3, policy.filterAllowedRoutes("peer-1", latencies).size)

        policy.revoke("peer-1")
        assertTrue(policy.isRevoked("peer-1"))
        assertFalse(policy.isRemoteAccessAllowed("peer-1"))
        assertFalse("peer-1" in policy.getAllAllowedPeers())
        assertTrue(policy.filterAllowedRoutes("peer-1", latencies).isEmpty())

        assertFailsWith<IllegalStateException> { policy.allow("peer-1") }
        assertFailsWith<IllegalStateException> { policy.toggle("peer-1") }

        policy.clearAll()
        assertTrue(policy.isRevoked("peer-1"))
        assertTrue(policy.filterAllowedRoutes("peer-1", latencies).isEmpty())

        policy.unrevoke("peer-1")
        assertFalse(policy.isRevoked("peer-1"))
        assertFalse(policy.isRemoteAccessAllowed("peer-1"))
        assertEquals(listOf(Route.Lan), policy.filterAllowedRoutes("peer-1", latencies).map { it.route })
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
