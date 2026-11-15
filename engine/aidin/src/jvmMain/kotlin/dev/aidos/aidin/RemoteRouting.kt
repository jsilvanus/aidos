package dev.aidos.aidin

import dev.aidos.policy.RemoteAccessAuditEvent
import dev.aidos.policy.RemoteAccessPolicy
import dev.aidos.routes.DialerState
import dev.aidos.routes.Route
import dev.aidos.routes.RouteDialer
import dev.aidos.routes.RouteHints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** One entry for the Engine's audit trail: a remote-access decision, or a link over a remote route. */
sealed interface RemoteRoutingAudit {
    data class SwitchChanged(val event: RemoteAccessAuditEvent) : RemoteRoutingAudit
    data class LinkDecision(val peer: NodeId, val route: LinkRoute, val admitted: Boolean) : RemoteRoutingAudit
    data class Dialed(val peer: NodeId, val route: Route, val rttMillis: Long) : RemoteRoutingAudit
}

/**
 * RFC-0108 on a JVM Engine: route hints per peer, the per-peer "Allow remote access" switch, and the
 * redial loop that uses them.
 *
 * - Remote access is **off for every peer** until [setRemoteAccess]. A link that arrives over a tunnel
 *   or an overlay address for a peer that is not allowed is closed at the listener (see [AidinNode.startRemoteIngress]).
 * - Hints are `direct` addresses the user adds ([addHint]): a Tailscale/WireGuard address, a forwarded port,
 *   or the public end of a tunnel. The dialer tries them in order, only for peers that are allowed and
 *   not currently connected, and verifies the peer's node key like any link, so a hint cannot make anything trusted.
 * - Turning the switch off closes a remote link at once.
 * - Both the switch and every remote-link decision go to [audit].
 */
class RemoteRouting(
    private val node: AidinNode,
    private val scope: CoroutineScope,
    private val file: File? = null,
    private val audit: (RemoteRoutingAudit) -> Unit = {},
    private val redialEveryMillis: Long = 15_000,
) {
    @Serializable
    private data class Stored(val allowed: List<String> = emptyList(), val hints: Map<String, List<String>> = emptyMap())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    val policy = RemoteAccessPolicy()
    private val hints = ConcurrentHashMap<String, List<Route.Direct>>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching {
                val s = json.decodeFromString(Stored.serializer(), f.readText())
                s.allowed.forEach { runCatching { policy.allow(it) } }
                s.hints.forEach { (peer, list) -> hints[peer] = list.mapNotNull(::parseDirect) }
            }
        }
        node.remoteAccessAllowed = { policy.isRemoteAccessAllowed(it.value) }
        node.onRemoteLinkDecision = { peer, route, admitted -> audit(RemoteRoutingAudit.LinkDecision(peer, route, admitted)) }
    }

    fun hintsFor(peer: NodeId): List<Route.Direct> = hints[peer.value].orEmpty()

    fun isRemoteAccessAllowed(peer: NodeId): Boolean = policy.isRemoteAccessAllowed(peer.value)

    /** Adds a `host:port` the peer can be dialled at. Order is preference order. */
    fun addHint(peer: NodeId, host: String, port: Int) {
        val route = Route.Direct(host, port) // validates host and port
        hints.merge(peer.value, listOf(route)) { old, new -> (old + new).distinct() }
        save()
    }

    fun removeHint(peer: NodeId, host: String, port: Int) {
        hints.computeIfPresent(peer.value) { _, old -> (old - Route.Direct(host, port)).ifEmpty { null } }
        save()
    }

    /** The switch. Audited; turning it off closes a live remote link. */
    fun setRemoteAccess(peer: NodeId, allowed: Boolean, peerName: String? = null) {
        if (allowed) policy.allow(peer.value) else policy.disallow(peer.value)
        audit(RemoteRoutingAudit.SwitchChanged(RemoteAccessAuditEvent(peer.value, peerName, allowed)))
        if (!allowed) node.dropRemoteLink(peer)
        save()
    }

    /** Starts the redial loop. Peers that are connected, not allowed, or without hints are left alone. */
    fun start() {
        scope.launch {
            while (isActive) {
                redialOnce()
                delay(redialEveryMillis)
            }
        }
    }

    /** One pass of the loop; public so a network-change listener can trigger it at once. */
    suspend fun redialOnce() {
        for ((peerId, list) in hints.entries.toList()) {
            val peer = runCatching { NodeId(peerId) }.getOrNull() ?: continue
            if (list.isEmpty() || !policy.isRemoteAccessAllowed(peerId)) continue
            if (node.peers.value.any { it.nodeId == peer && it.connected }) continue
            val dialer = RouteDialer(
                hints = RouteHints(listOf(Route.Lan) + list),
                // LAN is mDNS's job: nothing to dial here, so it fails fast and the hints follow.
                dialers = mapOf(
                    Route.Lan::class.java to { _ -> error("lan is found by discovery") },
                    Route.Direct::class.java to { r -> dial(peer, r as Route.Direct) },
                ),
                scope = scope,
                dialTimeoutPerRoute = 10.seconds,
            )
            dialer.startDial()
            val end = dialer.state.first { it is DialerState.Connected || it is DialerState.AllFailed }
            if (end is DialerState.Connected) audit(RemoteRoutingAudit.Dialed(peer, end.route, end.rttMillis))
            dialer.stop()
        }
    }

    private fun dial(expected: NodeId, route: Route.Direct): Long {
        val started = System.nanoTime()
        val reached = node.connect(route.host, route.port)
        // A hint can point at anything; only the peer we meant to reach counts.
        check(reached == expected) { "reached ${reached.value}, not the expected peer" }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun parseDirect(s: String): Route.Direct? {
        val i = s.lastIndexOf(':')
        if (i <= 0) return null
        return runCatching { Route.Direct(s.substring(0, i), s.substring(i + 1).toInt()) }.getOrNull()
    }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val s = Stored(
            allowed = policy.allowedPeers.value.sorted(),
            hints = hints.mapValues { (_, v) -> v.map { "${it.host}:${it.port}" } },
        )
        f.writeText(json.encodeToString(Stored.serializer(), s))
    }
}
