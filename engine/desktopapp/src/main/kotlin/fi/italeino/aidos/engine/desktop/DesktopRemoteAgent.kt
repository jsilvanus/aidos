package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.NodeId
import dev.aidos.aidin.Scope
import fi.italeino.aidos.engine.agent.AgentCandidate
import fi.italeino.aidos.engine.agent.AgentPeerEndpoints
import fi.italeino.aidos.engine.agent.AgentRunApi
import fi.italeino.aidos.engine.agent.AgentRuntime
import fi.italeino.aidos.engine.agent.OriginToolAudit
import fi.italeino.aidos.engine.agent.OriginToolHost
import fi.italeino.aidos.engine.agent.RemoteAgentClient
import fi.italeino.aidos.engine.agent.RoutedAgentRuntime
import fi.italeino.aidos.engine.agent.ToolApprovalQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RFC-0107 on the desktop: the user-facing switches for hosting agent loops for a peer, and for
 * sending this Engine's own agent turns to one.
 *
 * - **Hosting** needs two things on this side: the peer is paired, and the user has granted it the
 *   `agent` scope ([setHostingFor]). Pairing alone never grants it.
 * - **Using a peer** ([setPeerCandidates]) names the peers to prefer for `aidos:agent` turns, in order;
 *   the local loop is always the last resort. Only turns whose locality allows the cluster ever leave
 *   this device.
 * - **Tools stay here.** Which of an app's tools a peer's loop may use is an explicit per-peer, per-app
 *   allowlist ([allowTools]); the default is none.
 */
class DesktopRemoteAgent(
    private val stateDir: File,
    private val aidin: () -> AidinNode?,
    private val scope: CoroutineScope,
    /** Also told of each tool decision (for a live activity list); the append-only file is always written. */
    private val onAuditLine: (String) -> Unit = {},
    /**
     * Decides an effectful tool call on a tainted remote run (RFC-0107). Defaults to the on-screen prompt in
     * [approvals]: the user answers, and no answer within a minute is a refusal.
     */
    val approvals: ToolApprovalQueue = ToolApprovalQueue(),
) {
    private val audit: (OriginToolAudit) -> Unit = { a ->
        appendAudit(stateDir, a)
        onAuditLine("tool ${a.outcome}: ${a.tool} for ${a.app} from ${a.peer.value.take(8)}")
    }
    private val approve: suspend (peer: NodeId, app: String, tool: String) -> Boolean = { p, app, tool -> approvals.request(p, app, tool) }

    @Serializable
    private data class Config(
        val peers: List<String> = emptyList(),
        /** `"<peerNodeId>|<app>"` to the tool names the peer may use for that app. */
        val toolAllowlist: Map<String, List<String>> = emptyMap(),
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(stateDir, "remote-agent.json")
    private val clients = ConcurrentHashMap<NodeId, RemoteAgentClient>()

    @Volatile
    private var config: Config = runCatching { json.decodeFromString(Config.serializer(), file.readText()) }.getOrDefault(Config())

    private val toolHost = OriginToolHost(
        allowedTools = { peer, app -> config.toolAllowlist["${peer.value}|$app"]?.toSet() },
        approve = approve,
        audit = audit,
    )

    private fun save(c: Config) {
        stateDir.mkdirs()
        file.writeText(json.encodeToString(Config.serializer(), c))
        config = c
    }

    // ── Hosting ────────────────────────────────────────────────────────────────

    /** The peer-link handler for the agent endpoints; mount it on the Engine HTTP server. */
    fun peerEndpoints(runtime: AgentRuntime): suspend (NodeId, dev.aidos.aidin.LinkMessage.DelegateRequest) -> dev.aidos.aidin.LinkMessage.DelegateResponse {
        val endpoints = AgentPeerEndpoints(runtime)
        return { from, request -> endpoints.handle(from, request) }
    }

    /** Grants or withdraws the `agent` scope for a paired peer, leaving its other scopes as they are. */
    fun setHostingFor(peer: NodeId, enabled: Boolean): Boolean {
        val node = aidin() ?: return false
        val current = node.pairScopes(peer) ?: return false
        return node.setPairScopes(peer, if (enabled) current + Scope.AGENT else current - Scope.AGENT)
    }

    fun isHostingFor(peer: NodeId): Boolean = aidin()?.pairScopes(peer)?.contains(Scope.AGENT) == true

    // ── Using a peer ───────────────────────────────────────────────────────────

    fun setPeerCandidates(peers: List<NodeId>) {
        save(config.copy(peers = peers.map { it.value }))
    }

    fun peerCandidates(): List<NodeId> = config.peers.mapNotNull { runCatching { NodeId(it) }.getOrNull() }

    fun allowTools(peer: NodeId, app: String, tools: Set<String>) {
        val key = "${peer.value}|$app"
        save(config.copy(toolAllowlist = if (tools.isEmpty()) config.toolAllowlist - key else config.toolAllowlist + (key to tools.sorted())))
    }

    fun allowedTools(peer: NodeId, app: String): Set<String> = config.toolAllowlist["${peer.value}|$app"]?.toSet().orEmpty()

    /** Every (app, tools) allowlist set for [peer], for display. */
    fun allowlistsFor(peer: NodeId): Map<String, List<String>> =
        config.toolAllowlist.filterKeys { it.startsWith("${peer.value}|") }.mapKeys { it.key.substringAfter('|') }

    /** The runtime the `/v1/agent` routes should use: the local loop, with the configured peers ahead of it. */
    fun routed(local: AgentRuntime): AgentRunApi = RoutedAgentRuntime(
        local = local,
        candidates = { peerCandidates().map { AgentCandidate.Peer(it) } + AgentCandidate.Local },
        remoteFor = { peer -> clients.getOrPut(peer) { RemoteAgentClient({ aidin() }, peer, toolHost, scope) } },
        peerUsable = { peer ->
            val node = aidin()
            node != null && node.peers.value.any { it.nodeId == peer && it.connected } &&
                node.authorizeKnownPeer(peer, Scope.SERVE).isAllowed
        },
    )

    private companion object {
        fun appendAudit(dir: File, a: OriginToolAudit) {
            runCatching {
                dir.mkdirs()
                val line = buildString {
                    append("{\"at\":\"").append(java.time.Instant.now()).append("\",\"run\":\"").append(a.runId)
                    append("\",\"peer\":\"").append(a.peer.value).append("\",\"app\":").append(Json.encodeToString(String.serializer(), a.app))
                    append(",\"tool\":").append(Json.encodeToString(String.serializer(), a.tool))
                    append(",\"outcome\":\"").append(a.outcome).append("\"}\n")
                }
                File(dir, "remote-agent-audit.jsonl").appendText(line)
            }
        }
    }
}
