package fi.italeino.aidos.engine.inference

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.GroupId
import dev.aidos.aidin.NodeId
import dev.aidos.aidin.PeerState
import dev.aidos.aidin.RouteInfo
import dev.aidos.aidin.RouteQueryRequest
import dev.aidos.aidin.Scope
import dev.aidos.aidin.TrustDecision
import dev.aidos.aidin.TrustDomain
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import dev.aidos.modelruntime.GlobalModelRuntime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One candidate in a virtual model's ordered candidate list (RFC-0105 "Virtual model ids and fallback"):
 * e.g. `[group:<groupId>/qwen2.5-32b, peer:<nodeId>/qwen2.5-14b, local:qwen2.5-3b]`.
 */
sealed interface VirtualCandidate {
    val modelId: String
    val wire: String

    data class Group(val groupId: GroupId, override val modelId: String) : VirtualCandidate {
        override val wire: String get() = "group:${groupId.value}/$modelId"
    }

    data class Peer(val nodeId: NodeId, override val modelId: String) : VirtualCandidate {
        override val wire: String get() = "peer:${nodeId.value}/$modelId"
    }

    data class Local(override val modelId: String) : VirtualCandidate {
        override val wire: String get() = "local:$modelId"
    }

    companion object {
        fun parse(spec: String): VirtualCandidate? {
            val trimmed = spec.trim()
            return when {
                trimmed.startsWith("group:") -> {
                    val rest = trimmed.removePrefix("group:")
                    val slash = rest.indexOf('/')
                    if (slash <= 0 || slash == rest.lastIndex) return null
                    val groupId = runCatching { GroupId(rest.substring(0, slash)) }.getOrNull() ?: return null
                    val model = rest.substring(slash + 1).trim()
                    if (model.isEmpty()) null else Group(groupId, model)
                }
                trimmed.startsWith("peer:") -> {
                    val rest = trimmed.removePrefix("peer:")
                    val slash = rest.indexOf('/')
                    if (slash <= 0 || slash == rest.lastIndex) return null
                    val nodeId = runCatching { NodeId(rest.substring(0, slash)) }.getOrNull() ?: return null
                    val model = rest.substring(slash + 1).trim()
                    if (model.isEmpty()) null else Peer(nodeId, model)
                }
                trimmed.startsWith("local:") -> {
                    val model = trimmed.removePrefix("local:").trim()
                    if (model.isEmpty()) null else Local(model)
                }
                else -> null
            }
        }
    }
}

/** Definition of a virtual model ID such as `aidos:chat`, `aidos:embed`, or `aidos:stt`. */
data class VirtualModelConfig(
    val id: String = VIRTUAL_CHAT_ID,
    val kind: ModelKind = ModelKind.LLM,
    val candidates: List<VirtualCandidate>,
) {
    /**
     * Widest placement among the configured candidates (RFC-0105: "Virtual ids appear in
     * `GET /v1/models` with `placement` set to the widest candidate").
     */
    fun widestPlacement(localDescriptors: Map<String, ModelDescriptor>): Placement {
        if (candidates.isEmpty()) return Placement.LOCAL
        return candidates.maxOf { candidate ->
            when (candidate) {
                is VirtualCandidate.Local -> localDescriptors[candidate.modelId]?.let { placementOf(it) } ?: Placement.LOCAL
                is VirtualCandidate.Peer, is VirtualCandidate.Group -> Placement.CLUSTER
            }
        }
    }

    companion object {
        const val VIRTUAL_CHAT_ID = "aidos:chat"
        const val VIRTUAL_EMBED_ID = "aidos:embed"
        const val VIRTUAL_STT_ID = "aidos:stt"

        val DEFAULT_VIRTUAL_IDS = setOf(VIRTUAL_CHAT_ID, VIRTUAL_EMBED_ID, VIRTUAL_STT_ID)
    }
}

/** Where a resolved candidate will actually execute, along with its verified origin placement and route header. */
sealed interface ResolvedCandidateRoute {
    val modelId: String
    val placement: Placement
    val routeHeader: String

    /** Execute locally on this Engine (`X-Aidos-Route: local`). */
    data class LocalModel(
        override val modelId: String,
        override val placement: Placement,
    ) : ResolvedCandidateRoute {
        override val routeHeader: String get() = "local"
    }

    /** Execute on this node's own supervised `llama-server` split anchor (`X-Aidos-Route: local`). */
    data class LocalSplitAnchor(
        override val modelId: String,
        override val placement: Placement,
        val planVersion: Long,
    ) : ResolvedCandidateRoute {
        override val routeHeader: String get() = "local"
    }

    /** Delegate over the authenticated peer link to [peerNodeId] (`X-Aidos-Route: peer:<nodeId>`). */
    data class PeerDelegation(
        val peerNodeId: NodeId,
        override val modelId: String,
        override val placement: Placement,
        val planVersion: Long? = null,
    ) : ResolvedCandidateRoute {
        override val routeHeader: String get() = "peer:${peerNodeId.value}"
    }
}

private val routeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * RFC-0105 "Placement mapping for peers" (completes RFC-0104 / D47 item 3):
 * - A peer's `local` model, peer trusted by pairing or by a group the origin is in -> `cluster`
 * - A split model whose anchor *and every helper in the current plan* the origin itself trusts -> `cluster`
 * - A split model with any node the origin does not trust itself -> `remote`
 * - A peer's `remote` model -> `remote`
 * - Any peer without a valid credential -> null (never contacted)
 */
fun originPlacementForPeerRoute(
    aidinNode: AidinNode,
    peerNodeId: NodeId,
    routeInfo: RouteInfo,
): Placement? {
    // 1. Verify the serving peer itself holds a valid credential for Scope.SERVE.
    val peerAuth = aidinNode.authorizeKnownPeer(peerNodeId, Scope.SERVE)
    if (!peerAuth.isAllowed) return null

    val peerPlacement = Placement.parse(routeInfo.peerPlacement)
    if (peerPlacement == Placement.REMOTE) {
        return Placement.REMOTE
    }

    val anchorId = runCatching { NodeId(routeInfo.anchorNodeId) }.getOrNull() ?: return Placement.REMOTE
    val helperIds = routeInfo.helperNodeIds.map { raw ->
        runCatching { NodeId(raw) }.getOrNull() ?: return Placement.REMOTE
    }

    // Non-split local model on a trusted peer -> CLUSTER at the origin.
    if (helperIds.isEmpty() && peerPlacement == Placement.LOCAL && anchorId == peerNodeId) {
        return Placement.CLUSTER
    }

    // Split model: origin checks anchor AND every helper in the current plan against its own trust store.
    return if (aidinNode.isSplitRouteTrustedByOrigin(anchorId, helperIds)) {
        Placement.CLUSTER
    } else {
        Placement.REMOTE
    }
}

/**
 * Resolves virtual model IDs (`aidos:chat`, `aidos:embed`, `aidos:stt`), filters candidates by
 * [Locality] first, queries `POST /aidin/v1/route` to verify peer/split placement, and keeps
 * the local fallback candidate warm while a group or peer candidate is in use (RFC-0105).
 */
class VirtualModelRouter(
    private val modelRuntime: ModelRuntime,
    private val aidinNode: () -> AidinNode? = { null },
    private val configuredVirtualModels: () -> List<VirtualModelConfig> = { emptyList() },
    /** Optional override for querying `/aidin/v1/route` on a peer; defaults to `AidinNode.delegate`. */
    private val routeQueryOverride: (suspend (AidinNode, NodeId, String) -> RouteInfo?)? = null,
) {
    /** Temporary quarantine for peers/plans that failed mid-request, cleared when a new heartbeat arrives. */
    private val quarantinedRoutes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markRouteQuarantined(key: String, durationMillis: Long = 5_000L) {
        quarantinedRoutes[key] = System.currentTimeMillis() + durationMillis
    }

    private fun isQuarantined(key: String): Boolean {
        val until = quarantinedRoutes[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            quarantinedRoutes.remove(key)
            return false
        }
        return true
    }

    fun isVirtualModelId(modelId: String): Boolean {
        if (modelId in VirtualModelConfig.DEFAULT_VIRTUAL_IDS) return true
        if (VirtualCandidate.parse(modelId) != null) return true
        return configuredVirtualModels().any { it.id == modelId }
    }

    /**
     * Returns all virtual model configurations to expose in `GET /v1/models` and resolution.
     * If `aidos:chat` is not explicitly configured by the host, builds its default candidate list
     * from any active group split models, trusted peer loaded LLM models, and local installed LLM models.
     */
    suspend fun virtualModels(): List<VirtualModelConfig> {
        val explicit = configuredVirtualModels().associateBy { it.id }.toMutableMap()
        if (VirtualModelConfig.VIRTUAL_CHAT_ID !in explicit) {
            val autoChat = buildAutoVirtualConfig(VirtualModelConfig.VIRTUAL_CHAT_ID, ModelKind.LLM)
            if (autoChat.candidates.isNotEmpty()) {
                explicit[VirtualModelConfig.VIRTUAL_CHAT_ID] = autoChat
            }
        }
        return explicit.values.toList()
    }

    private suspend fun buildAutoVirtualConfig(id: String, kind: ModelKind): VirtualModelConfig {
        val candidates = mutableListOf<VirtualCandidate>()
        val node = aidinNode()
        if (node != null) {
            // 1. Active split model anchored here
            val localPlan = node.activeAnchorSupervisor?.takeIf { it.isAvailable }?.activePlan
            if (localPlan != null && localPlan.domain is TrustDomain.Group) {
                val gId = (localPlan.domain as TrustDomain.Group).groupId
                candidates += VirtualCandidate.Group(gId, localPlan.modelId)
            }
            // 2. Peer-advertised models (group split models first, then peer local models)
            val peers = node.peers.value.filter {
                it.connected && (it.state == PeerState.READY || it.state == PeerState.ACTIVE) &&
                    it.lastHeartbeat?.draining != true &&
                    node.authorize(it.nodeId, Scope.SERVE).isAllowed
            }
            for (peer in peers) {
                val models = peer.lastHeartbeat?.models.orEmpty().filter {
                    it.loaded && it.kind.equals(kind.name, ignoreCase = true)
                }
                for (m in models) {
                    val groupDomain = peer.domains.filterIsInstance<TrustDomain.Group>().firstOrNull()
                    if (m.placement == "cluster" && groupDomain != null) {
                        val c = VirtualCandidate.Group(groupDomain.groupId, m.id)
                        if (c !in candidates) candidates += c
                    } else {
                        val c = VirtualCandidate.Peer(peer.nodeId, m.id)
                        if (c !in candidates) candidates += c
                    }
                }
            }
        }
        // 3. Local installed models of matching kind (loaded ones first)
        val loadedIds = modelRuntime.loaded().toSet()
        val installed = modelRuntime.installed()
            .filter { it.kind == kind && it.isLocal }
            .sortedByDescending { it.id in loadedIds }
        for (m in installed) {
            candidates += VirtualCandidate.Local(m.id)
        }
        return VirtualModelConfig(id = id, kind = kind, candidates = candidates)
    }

    /**
     * Resolves [modelId] under [locality] into an ordered list of permitted, available
     * [ResolvedCandidateRoute]s.
     *
     * Rules (RFC-0105):
     * 1. Filter candidates by [locality] **first**.
     * 2. Take candidates whose node is ACTIVE/READY and whose model is loaded/available.
     * 3. If none remains: throw [LocalityViolationException] (403 `locality_violation`) when
     *    [locality] removed everything, else [ModelUnavailableException] (503 `model_unavailable`).
     * 4. Keep the local fallback candidate loaded (warm) while a group/peer candidate is in use.
     */
    suspend fun resolveRoutes(modelId: String, locality: Locality): List<ResolvedCandidateRoute> {
        val directCandidate = VirtualCandidate.parse(modelId)
        val config = when {
            directCandidate != null -> VirtualModelConfig(modelId, ModelKind.LLM, listOf(directCandidate))
            else -> virtualModels().firstOrNull { it.id == modelId }
                ?: VirtualModelConfig(modelId, ModelKind.LLM, emptyList())
        }

        if (config.candidates.isEmpty()) {
            throw ModelUnavailableException(modelId, "No candidates configured or installed for $modelId")
        }

        val installedById = modelRuntime.installed().associateBy { it.id }
        val catalogById = modelRuntime.catalog().associateBy { it.id }
        val node = aidinNode()

        val permittedRoutes = mutableListOf<ResolvedCandidateRoute>()
        var anyFilteredByLocality = false
        var widestFilteredPlacement: Placement = Placement.CLUSTER
        var anyPermittedByLocality = false

        for (candidate in config.candidates) {
            when (candidate) {
                is VirtualCandidate.Local -> {
                    val descriptor = installedById[candidate.modelId] ?: catalogById[candidate.modelId]
                    val declaredPlacement = if (descriptor != null) placementOf(descriptor) else Placement.LOCAL
                    if (!locality.permits(declaredPlacement)) {
                        anyFilteredByLocality = true
                        if (declaredPlacement > widestFilteredPlacement) widestFilteredPlacement = declaredPlacement
                        continue
                    }
                    anyPermittedByLocality = true
                    if (installedById.containsKey(candidate.modelId)) {
                        permittedRoutes += ResolvedCandidateRoute.LocalModel(candidate.modelId, declaredPlacement)
                    }
                }

                is VirtualCandidate.Peer -> {
                    // A peer candidate is at narrowest Placement.CLUSTER; filter by locality BEFORE contacting the peer.
                    if (!locality.permits(Placement.CLUSTER)) {
                        anyFilteredByLocality = true
                        continue
                    }
                    if (node == null) {
                        anyPermittedByLocality = true
                        continue
                    }
                    val evaluation = evaluatePeerCandidate(node, candidate.nodeId, candidate.modelId, requiredGroup = null)
                    when (evaluation) {
                        is CandidateEval.Available -> {
                            if (!locality.permits(evaluation.route.placement)) {
                                anyFilteredByLocality = true
                                if (evaluation.route.placement > widestFilteredPlacement) {
                                    widestFilteredPlacement = evaluation.route.placement
                                }
                            } else {
                                anyPermittedByLocality = true
                                permittedRoutes += evaluation.route
                            }
                        }
                        is CandidateEval.Unavailable -> {
                            anyPermittedByLocality = true
                        }
                    }
                }

                is VirtualCandidate.Group -> {
                    // A group candidate is at narrowest Placement.CLUSTER; filter by locality first.
                    if (!locality.permits(Placement.CLUSTER)) {
                        anyFilteredByLocality = true
                        continue
                    }
                    if (node == null) {
                        anyPermittedByLocality = true
                        continue
                    }
                    val evaluation = evaluateGroupCandidate(node, candidate.groupId, candidate.modelId)
                    when (evaluation) {
                        is CandidateEval.Available -> {
                            if (!locality.permits(evaluation.route.placement)) {
                                anyFilteredByLocality = true
                                if (evaluation.route.placement > widestFilteredPlacement) {
                                    widestFilteredPlacement = evaluation.route.placement
                                }
                            } else {
                                anyPermittedByLocality = true
                                permittedRoutes += evaluation.route
                            }
                        }
                        is CandidateEval.Unavailable -> {
                            anyPermittedByLocality = true
                        }
                    }
                }
            }
        }

        if (permittedRoutes.isEmpty()) {
            if (anyFilteredByLocality && !anyPermittedByLocality) {
                throw LocalityViolationException(modelId, locality, widestFilteredPlacement)
            }
            throw ModelUnavailableException(modelId)
        }

        // Warm local fallback: if the primary route is a peer or group split model, keep the local
        // fallback candidate loaded in GlobalModelRuntime so falling back does not cost a cold load.
        val primary = permittedRoutes.first()
        if (primary !is ResolvedCandidateRoute.LocalModel && modelRuntime is GlobalModelRuntime) {
            val localFallback = config.candidates.filterIsInstance<VirtualCandidate.Local>().firstOrNull()
            if (localFallback != null && installedById.containsKey(localFallback.modelId)) {
                modelRuntime.ensureWarmFallback(localFallback.modelId)
            }
        }

        return permittedRoutes
    }

    private sealed interface CandidateEval {
        data class Available(val route: ResolvedCandidateRoute) : CandidateEval
        data object Unavailable : CandidateEval
    }

    private suspend fun evaluateGroupCandidate(
        node: AidinNode,
        groupId: GroupId,
        modelId: String,
    ): CandidateEval {
        // 1. Check if this node itself is the anchor of an active split plan for this group & model.
        val localAnchor = node.activeAnchorSupervisor
        val localPlan = localAnchor?.activePlan
        if (localAnchor != null && localAnchor.isAvailable && localPlan != null &&
            localPlan.modelId == modelId && localPlan.domain == TrustDomain.Group(groupId)
        ) {
            val helperIds = localPlan.helpers.map { it.nodeId }
            val originPlacement = if (node.isSplitRouteTrustedByOrigin(node.nodeId, helperIds)) {
                Placement.CLUSTER
            } else {
                Placement.REMOTE
            }
            return CandidateEval.Available(
                ResolvedCandidateRoute.LocalSplitAnchor(modelId, originPlacement, localPlan.version)
            )
        }

        // 2. Otherwise look for a connected peer in `groupId` that advertises `modelId` as loaded.
        val groupPeers = node.peers.value.filter { peer ->
            peer.connected &&
                (peer.state == PeerState.READY || peer.state == PeerState.ACTIVE) &&
                peer.lastHeartbeat?.draining != true &&
                TrustDomain.Group(groupId) in peer.domains &&
                (peer.lastHeartbeat?.models == null || peer.lastHeartbeat?.models?.any { it.id == modelId && it.loaded } == true)
        }
        for (peer in groupPeers) {
            val eval = evaluatePeerCandidate(node, peer.nodeId, modelId, requiredGroup = groupId)
            if (eval is CandidateEval.Available) return eval
        }
        return CandidateEval.Unavailable
    }

    private suspend fun evaluatePeerCandidate(
        node: AidinNode,
        peerNodeId: NodeId,
        modelId: String,
        requiredGroup: GroupId?,
    ): CandidateEval {
        if (isQuarantined("peer:${peerNodeId.value}/$modelId")) return CandidateEval.Unavailable

        // Check peer liveness and Scope.SERVE credential in our TrustStore.
        val peerView = node.peers.value.firstOrNull { it.nodeId == peerNodeId }
        if (peerView == null || !peerView.connected) return CandidateEval.Unavailable
        if (peerView.state != PeerState.READY && peerView.state != PeerState.ACTIVE) return CandidateEval.Unavailable
        if (peerView.lastHeartbeat?.draining == true) return CandidateEval.Unavailable

        val serveAuth = node.authorize(peerNodeId, Scope.SERVE)
        if (serveAuth !is TrustDecision.Allowed) return CandidateEval.Unavailable
        if (requiredGroup != null && TrustDomain.Group(requiredGroup) !in serveAuth.domains) {
            return CandidateEval.Unavailable
        }

        // If the peer's heartbeat lists models, require the requested model to be advertised as loaded.
        val advertisedModels = peerView.lastHeartbeat?.models
        if (advertisedModels != null && advertisedModels.isNotEmpty()) {
            val advert = advertisedModels.firstOrNull { it.id == modelId }
            if (advert == null || !advert.loaded) return CandidateEval.Unavailable
        }

        // Query POST /aidin/v1/route on the peer before sending any content.
        val routeInfo = queryPeerRoute(node, peerNodeId, modelId) ?: return CandidateEval.Unavailable
        val originPlacement = originPlacementForPeerRoute(node, peerNodeId, routeInfo) ?: return CandidateEval.Unavailable

        return CandidateEval.Available(
            ResolvedCandidateRoute.PeerDelegation(
                peerNodeId = peerNodeId,
                modelId = modelId,
                placement = originPlacement,
                planVersion = routeInfo.planVersion,
            )
        )
    }

    private suspend fun queryPeerRoute(node: AidinNode, peerNodeId: NodeId, modelId: String): RouteInfo? {
        routeQueryOverride?.let { return it(node, peerNodeId, modelId) }
        val reqJson = routeJson.encodeToString(RouteQueryRequest(model = modelId))
        val resp = node.delegate(
            peerNodeId = peerNodeId,
            endpoint = "/aidin/v1/route",
            bodyJson = reqJson,
            locality = Locality.ANY.wire,
        )
        if (resp.status != 200) return null
        return runCatching { routeJson.decodeFromString<RouteInfo>(resp.bodyJson) }.getOrNull()
    }
}
