package dev.aidos.aidin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One peer as the UI shows it. Labels are local; peers never learn them. */
data class PeerView(
    val nodeId: NodeId,
    val label: String?,
    val state: PeerState,
    val domains: Set<TrustDomain>,
    val connected: Boolean,
    val lastHeartbeat: Heartbeat?,
)

enum class PairingKind {
    /** Pairwise trust between two devices. */
    PAIR,
    /** This node, as admin, enrols the other device in [PairingView.groupId]. */
    ENROL_OTHER,
    /** The other device, as admin, enrols this node in its group. */
    ENROLLED_BY_OTHER,
}

enum class PairingStatus { WAITING_FOR_CONFIRMATION, DONE, FAILED }

/** A pairing or enrolment in progress, for the UI: show [code], ask the user to compare and confirm. */
data class PairingView(
    val id: String,
    val peerNodeId: NodeId,
    val code: String,
    val kind: PairingKind,
    val groupId: GroupId?,
    val confirmedHere: Boolean,
    val confirmedThere: Boolean,
    val status: PairingStatus,
    val error: String? = null,
)

/**
 * One Engine's AIDIN presence (RFC-0105 phases 1–3): the peer link server, discovery, heartbeats,
 * liveness, trust state, pairing/enrolment flows, certificate renewal, peer delegation, and
 * layer-split tunnel multiplexing. Off until [start]; the host starts it only when the user turned
 * group sharing on.
 *
 * Threads: each link has a blocking reader thread; the heartbeat loop runs in [scope]. All trust and
 * link state is guarded by [lock].
 */
class AidinNode(
    private val key: SigningKey,
    private val stateStore: AidinStateStore,
    private val scope: CoroutineScope,
    private val engineVersion: String,
    private val discovery: Discovery? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tracker: PeerTracker = PeerTracker(),
    /** Fills the variable part of each heartbeat (models, RAM budget, battery…). */
    private val heartbeatContent: () -> Heartbeat = { Heartbeat(AIDIN_PROTOCOL_VERSION, engineVersion) },
    private val inviteLifetimeMillis: Long = 5 * 60_000,
    /** Renew group certificates when remaining validity drops below this threshold (default 7 days). */
    private val certificateRenewalThresholdMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    val nodeId: NodeId = key.nodeId

    private val lock = Any()
    private var state: AidinState = stateStore.load()
    private val trust = TrustStore(nodeId)
    private val links = ConcurrentHashMap<NodeId, PeerLink>()
    private val knownPeers = ConcurrentHashMap<NodeId, PeerCredentials>()
    private val lastBeats = ConcurrentHashMap<NodeId, Heartbeat>()
    private val invites = mutableListOf<Pair<PairingInvite, Long>>()
    private val pairings = LinkedHashMap<String, PairingSession>()

    // Phase 2 Delegation state
    private val pendingDelegations = ConcurrentHashMap<String, CompletableDeferred<LinkMessage.DelegateResponse>>()
    private val pendingStreams = ConcurrentHashMap<String, Channel<LinkMessage>>()

    /**
     * Handler invoked when a trusted peer sends a [LinkMessage.DelegateRequest] (`/aidin/v1/` endpoints)
     * over the peer link. Set by the Engine HTTP host (`EngineHttpServer`).
     */
    @Volatile
    var delegationHandler: (suspend (
        fromPeer: NodeId,
        request: LinkMessage.DelegateRequest,
        emitStream: suspend (LinkMessage.DelegateStreamEvent) -> Unit,
    ) -> LinkMessage.DelegateResponse)? = null

    // Phase 3 Split-inference supervision state
    @Volatile private var helperSupervisor: HelperProcessSupervisor? = null
    @Volatile private var anchorSupervisor: AnchorProcessSupervisor? = null
    private val pendingPlanAcks = ConcurrentHashMap<String, CompletableFuture<LinkMessage.PlanAck>>()

    private val _peers = MutableStateFlow<List<PeerView>>(emptyList())
    val peers: StateFlow<List<PeerView>> = _peers.asStateFlow()
    private val _pairings = MutableStateFlow<List<PairingView>>(emptyList())
    val pairingViews: StateFlow<List<PairingView>> = _pairings.asStateFlow()

    private val endpoint = PeerLinkEndpoint(
        key,
        admit = { creds -> synchronized(lock) { trust.authorizeAny(creds, clock()) } },
        ownCertificates = { synchronized(lock) { trust.memberships.map { it.ownCertificate } } },
        pendingInviteSecrets = {
            synchronized(lock) {
                val now = clock()
                invites.removeAll { (_, expires) -> expires < now }
                invites.map { it.first.secret }
            }
        },
    )
    private var server: PeerLinkServer? = null
    private var ticker: Job? = null

    init {
        rebuildTrust()
    }

    val port: Int? get() = server?.port

    fun start(bindAddress: String = "0.0.0.0", port: Int = 0) {
        check(server == null) { "already started" }
        val s = endpoint.listen(bindAddress, port) { link -> onLink(link) }
        server = s
        discovery?.let { d ->
            d.announce(advert(s.port))
            d.browse(onFound = ::onDiscovered, onLost = {})
        }
        ticker = scope.launch {
            while (isActive) {
                delay(tracker.heartbeatIntervalMillis)
                beatAndTick()
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        helperSupervisor?.close()
        anchorSupervisor?.close()
        discovery?.close()
        server?.close()
        server = null
        links.values.forEach { it.close() }
        links.clear()
        failAllPendingDelegations("node stopped")
    }

    // ---- trust management -------------------------------------------------------------------

    /** Show this as a QR code; valid for [inviteLifetimeMillis] or until a pairing uses it. */
    fun createInvite(addresses: List<String>): PairingInvite {
        val p = checkNotNull(port) { "start the node before inviting" }
        val invite = PairingInvite.create(key, addresses, p)
        synchronized(lock) { invites += invite to clock() + inviteLifetimeMillis }
        return invite
    }

    fun cancelInvites() = synchronized(lock) { invites.clear() }

    /** Scanned someone's invite: pair with that device. */
    fun pairWith(invite: PairingInvite): String = openPairing(invite, PairingKind.PAIR, null, emptySet())

    /** Scanned a device's invite as the admin of [groupId]: enrol it with [roles]. */
    fun enrol(invite: PairingInvite, groupId: GroupId, roles: Set<Scope> = Scope.ALL): String {
        synchronized(lock) { require(state.administered.any { it.groupId == groupId }) { "not an admin of $groupId" } }
        return openPairing(invite, PairingKind.ENROL_OTHER, groupId, roles)
    }

    /** The user compared the codes and they match. [label] names the other device locally. */
    fun confirmPairing(id: String, label: String) {
        val session = synchronized(lock) { pairings[id] } ?: return
        session.confirmHere(label)
    }

    fun rejectPairing(id: String) {
        val session = synchronized(lock) { pairings[id] } ?: return
        session.fail("rejected here")
    }

    /** Create a group with this node as admin and first member. */
    fun createGroup(label: String): GroupId = synchronized(lock) {
        val groupKey = SigningKey.generate()
        val admin = AdministeredGroup(label, GroupAuthority(groupKey), groupKey = groupKey)
        val now = clock()
        val cert = admin.authority.issue(key.publicKey, Scope.ALL, now)
        val list = admin.authority.revocations(0, emptySet(), now)
        val membership = GroupMembership(admin.authority.groupPublicKey, cert, list)
        update(
            state.copy(
                administered = state.administered + admin,
                memberships = state.memberships + membership,
                groupLabels = state.groupLabels + (admin.groupId to label),
            ),
        )
        admin.groupId
    }

    /**
     * As admin of [groupId]: issue a renewed [MembershipCertificate] for [member] while it is seen
     * and push it over the authenticated peer link (RFC-0105: "default validity 30 days, renewed
     * while the device is seen").
     */
    fun renewMemberCertificate(
        groupId: GroupId,
        member: NodeId,
        roles: Set<Scope>? = null,
        validityMillis: Long = GroupAuthority.DEFAULT_VALIDITY_MILLIS,
    ): MembershipCertificate? {
        val link = links[member]
        val peerCreds = link?.peer ?: knownPeers[member] ?: return null
        val cert = synchronized(lock) {
            val admin = state.administered.firstOrNull { it.groupId == groupId } ?: return null
            if (member in admin.revoked) return null
            val existingRoles = peerCreds.certificates.firstOrNull { it.groupId == groupId }?.roles ?: Scope.ALL
            admin.authority.issue(peerCreds.publicKey, roles ?: existingRoles, clock(), validityMillis)
        }
        val updatedCerts = peerCreds.certificates.filterNot { it.groupId == groupId } + cert
        knownPeers[member] = PeerCredentials(peerCreds.publicKey, updatedCerts)
        link?.let { runCatching { it.send(LinkMessage.CertificateRenewal(cert.toWire())) } }
        return cert
    }

    /** Own current certificate for [groupId], if this node is a member. */
    fun ownCertificate(groupId: GroupId): MembershipCertificate? = synchronized(lock) {
        state.memberships.firstOrNull { it.groupId == groupId }?.ownCertificate
    }

    /** As admin: revoke [member] in [groupId] and push the new list to every connected member. */
    fun revoke(groupId: GroupId, member: NodeId) {
        val list = synchronized(lock) {
            val admin = state.administered.first { it.groupId == groupId }.withRevoked(member)
            val list = admin.authority.revocations(admin.revocationSerial, admin.revoked, clock())
            update(state.copy(administered = state.administered.map { if (it.groupId == groupId) admin else it }))
            trust.acceptRevocations(list)
            persistTrust()
            list
        }
        broadcast(LinkMessage.Revocations(list.toWire()))
        dropUntrustedLinks()
    }

    fun removePair(nodeId: NodeId) {
        synchronized(lock) { update(state.copy(pairs = state.pairs.filterNot { it.nodeId == nodeId })) }
        dropUntrustedLinks()
    }

    fun leaveGroup(groupId: GroupId) {
        synchronized(lock) {
            update(state.copy(memberships = state.memberships.filterNot { it.groupId == groupId }, groupLabels = state.groupLabels - groupId))
        }
        dropUntrustedLinks()
    }

    fun groups(): List<Pair<GroupId, String>> = synchronized(lock) { state.memberships.map { it.groupId to state.groupLabels[it.groupId].orEmpty() } }

    fun isAdmin(groupId: GroupId): Boolean = synchronized(lock) { state.administered.any { it.groupId == groupId } }

    /** Record a peer's credentials (e.g. from a group certificate directory or peer link handshake). */
    fun recordPeerCredentials(credentials: PeerCredentials) {
        knownPeers[credentials.nodeId] = credentials
    }

    /** For callers that check a scope before an action on an active link (phase 2 delegation, phase 3 plans). */
    fun authorize(nodeId: NodeId, scope: Scope): TrustDecision {
        val link = links[nodeId] ?: return TrustDecision.Denied(listOf("not connected"))
        if (link.isPairing) return TrustDecision.Denied(listOf("pairing link"))
        val creds = knownPeers[nodeId] ?: link.peer
        return synchronized(lock) { trust.authorize(creds, scope, clock()) }
    }

    /**
     * Checks whether [peerNodeId] is trusted for [scope] using its active link, known group
     * credentials, or pair record. Used when verifying all nodes in a `POST /aidin/v1/route`
     * split plan (RFC-0105 "Placement mapping for peers").
     */
    fun authorizeKnownPeer(peerNodeId: NodeId, scope: Scope): TrustDecision = synchronized(lock) {
        if (peerNodeId == nodeId) return@synchronized TrustDecision.Allowed(
            setOf(TrustDomain.Pairing) + trust.memberships.map { TrustDomain.Group(it.groupId) }
        )
        val creds = knownPeers[peerNodeId]
            ?: links[peerNodeId]?.takeIf { !it.isPairing }?.peer
            ?: state.pairs.firstOrNull { it.nodeId == peerNodeId }?.let { PairCredentials(it.publicKey) }
            ?: return@synchronized TrustDecision.Denied(listOf("unknown peer $peerNodeId"))
        trust.authorize(creds, scope, clock())
    }

    private fun PairCredentials(publicKey: ByteArray) = PeerCredentials(publicKey, emptyList())

    /**
     * RFC-0105 "Placement mapping for peers":
     * Checks whether the origin trusts [anchorNodeId] (for [Scope.SERVE]) and every helper in
     * [helperNodeIds] (for [Scope.HELP] or group membership) within a common trust domain
     * (either the same group, or the origin's own pair records).
     */
    fun isSplitRouteTrustedByOrigin(anchorNodeId: NodeId, helperNodeIds: Collection<NodeId>): Boolean {
        val anchorDecision = authorizeKnownPeer(anchorNodeId, Scope.SERVE)
        if (anchorDecision !is TrustDecision.Allowed) return false
        if (helperNodeIds.isEmpty()) return true

        var sharedDomains: Set<TrustDomain> = anchorDecision.domains
        for (helperId in helperNodeIds) {
            if (helperId == nodeId) continue
            // A helper is trusted by the origin if the origin authorizes it in the same group or by pairing.
            val helperDecision = authorizeKnownPeer(helperId, Scope.HELP).let { d ->
                if (d is TrustDecision.Allowed) d else authorizeKnownPeer(helperId, Scope.SERVE)
            }
            if (helperDecision !is TrustDecision.Allowed) return false
            sharedDomains = sharedDomains intersect helperDecision.domains
            if (sharedDomains.isEmpty()) return false
        }
        return sharedDomains.isNotEmpty()
    }

    /** Marks a peer as `ACTIVE` (or back to `READY`) in [PeerTracker] when used for delegation or a split plan. */
    fun markPeerActive(peerNodeId: NodeId, active: Boolean) {
        synchronized(lock) { tracker.markActive(peerNodeId, active, clock()) }
        publishPeers()
    }

    /** Marks a peer as suspected/disconnected when a mid-request failure occurs. */
    fun markPeerLost(peerNodeId: NodeId) {
        links.remove(peerNodeId)?.close()
        synchronized(lock) { tracker.onDisconnected(peerNodeId, clock()) }
        publishPeers()
    }

    /** Marks a peer as suspected without closing its TCP link so the next heartbeat can recover it. */
    fun markPeerSuspected(peerNodeId: NodeId) {
        synchronized(lock) { tracker.onDisconnected(peerNodeId, clock()) }
        publishPeers()
    }

    /** Connect to a known peer directly (no mDNS on the network, or a typed address). */
    fun connect(host: String, port: Int): NodeId {
        val link = endpoint.connect(host, port)
        onLink(link)
        return link.nodeId
    }

    // ---- Phase 2 Delegation over PeerLink --------------------------------------------------

    /**
     * Sends a non-streaming `/aidin/v1/` request to [peerNodeId] over the authenticated peer link.
     * Enforces caller-side `Scope.SERVE` authorization before sending any content.
     */
    suspend fun delegate(
        peerNodeId: NodeId,
        endpoint: String,
        bodyJson: String,
        locality: String,
        planVersion: Long? = null,
        timeoutMillis: Long = 30_000L,
    ): LinkMessage.DelegateResponse {
        val auth = authorize(peerNodeId, Scope.SERVE)
        if (!auth.isAllowed) {
            return LinkMessage.DelegateResponse(
                requestId = "",
                status = 403,
                bodyJson = """{"error":{"message":"Peer $peerNodeId is not authorized for scope serve","type":"permission_error","code":"untrusted_peer"}}""",
            )
        }
        val link = links[peerNodeId]
            ?: return LinkMessage.DelegateResponse(
                requestId = "",
                status = 503,
                bodyJson = """{"error":{"message":"Peer $peerNodeId is not connected","type":"peer_lost","code":"peer_unreachable"}}""",
            )

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<LinkMessage.DelegateResponse>()
        pendingDelegations[requestId] = deferred
        try {
            link.send(
                LinkMessage.DelegateRequest(
                    requestId = requestId,
                    endpoint = endpoint,
                    bodyJson = bodyJson,
                    locality = locality,
                    hop = 1,
                    planVersion = planVersion,
                    stream = false,
                )
            )
            return withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: LinkMessage.DelegateResponse(
                    requestId = requestId,
                    status = 504,
                    bodyJson = """{"error":{"message":"Peer $peerNodeId timed out","type":"peer_lost","code":"peer_timeout"}}""",
                )
        } catch (e: IOException) {
            return LinkMessage.DelegateResponse(
                requestId = requestId,
                status = 503,
                bodyJson = """{"error":{"message":"Peer link closed: ${e.message}","type":"peer_lost","code":"peer_lost"}}""",
            )
        } finally {
            pendingDelegations.remove(requestId)
        }
    }

    /**
     * Streams a delegated `/aidin/v1/chat/completions` request from [peerNodeId].
     * If the peer link drops mid-stream, emits `LinkMessage.DelegateStreamEvent` with
     * `{"type":"peer_lost","retryable":true}` and terminates (RFC-0105).
     */
    fun delegateStreaming(
        peerNodeId: NodeId,
        endpoint: String,
        bodyJson: String,
        locality: String,
        planVersion: Long? = null,
    ): Flow<LinkMessage> = flow {
        val auth = authorize(peerNodeId, Scope.SERVE)
        if (!auth.isAllowed) {
            emit(
                LinkMessage.DelegateResponse(
                    requestId = "",
                    status = 403,
                    bodyJson = """{"error":{"message":"Peer $peerNodeId is not authorized for scope serve","type":"permission_error","code":"untrusted_peer"}}""",
                )
            )
            return@flow
        }
        val link = links[peerNodeId]
        if (link == null) {
            emit(
                LinkMessage.DelegateResponse(
                    requestId = "",
                    status = 503,
                    bodyJson = """{"error":{"message":"Peer $peerNodeId is not connected","type":"peer_lost","code":"peer_unreachable"}}""",
                )
            )
            return@flow
        }

        val requestId = UUID.randomUUID().toString()
        val channel = Channel<LinkMessage>(Channel.UNLIMITED)
        pendingStreams[requestId] = channel
        try {
            link.send(
                LinkMessage.DelegateRequest(
                    requestId = requestId,
                    endpoint = endpoint,
                    bodyJson = bodyJson,
                    locality = locality,
                    hop = 1,
                    planVersion = planVersion,
                    stream = true,
                )
            )
            for (msg in channel) {
                emit(msg)
                when (msg) {
                    is LinkMessage.DelegateResponse -> break
                    is LinkMessage.DelegateStreamEvent -> if (msg.done) break
                    else -> Unit
                }
            }
        } catch (_: IOException) {
            emit(
                LinkMessage.DelegateStreamEvent(
                    requestId = requestId,
                    sseData = """{"type":"peer_lost","retryable":true}""",
                    done = true,
                )
            )
        } finally {
            pendingStreams.remove(requestId)?.close()
        }
    }

    // ---- Phase 3 Split-plan & Tunnel hooks -------------------------------------------------

    internal fun attachHelperSupervisor(supervisor: HelperProcessSupervisor) {
        helperSupervisor = supervisor
    }

    internal fun attachAnchorSupervisor(supervisor: AnchorProcessSupervisor) {
        anchorSupervisor = supervisor
    }

    val activeAnchorSupervisor: AnchorProcessSupervisor? get() = anchorSupervisor
    val activeHelperSupervisor: HelperProcessSupervisor? get() = helperSupervisor

    internal fun sendPlanAssignAndAwaitAck(helperId: NodeId, plan: SplitPlan, timeoutMillis: Long = 5_000L): LinkMessage.PlanAck? {
        val link = links[helperId] ?: return null
        val key = "${plan.planId}:${plan.version}:${helperId.value}"
        val future = CompletableFuture<LinkMessage.PlanAck>()
        pendingPlanAcks[key] = future
        return try {
            link.send(LinkMessage.PlanAssign(plan.toWire()))
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            pendingPlanAcks.remove(key)
        }
    }

    internal fun sendTunnelMessage(peerNodeId: NodeId, message: LinkMessage): Boolean {
        val link = links[peerNodeId] ?: return false
        return try {
            link.send(message)
            true
        } catch (_: IOException) {
            false
        }
    }

    // ---- links ----------------------------------------------------------------------------

    private fun onDiscovered(found: DiscoveredNode) {
        val advert = found.advert
        if (advert.nodeId == nodeId || links.containsKey(advert.nodeId)) return
        // One side connects, so two devices that see each other open one link, not two.
        if (nodeId.value > advert.nodeId.value) return
        val known = synchronized(lock) {
            state.pairs.any { it.nodeId == advert.nodeId } ||
                GroupTags.matching(advert, trust.memberships.map { it.groupId }, clock()).isNotEmpty()
        }
        if (!known) return
        try {
            onLink(endpoint.connect(found.host, advert.port))
        } catch (_: IOException) {
            // Not reachable or not trusting us (yet); the next advert retries.
        }
    }

    private fun onLink(link: PeerLink) {
        if (link.isPairing) {
            startPairingSession(link, initiatedHere = false, kind = PairingKind.PAIR, groupId = null, roles = emptySet())
            return
        }
        knownPeers[link.nodeId] = link.peer
        links.put(link.nodeId, link)?.takeIf { it !== link }?.close()
        synchronized(lock) { tracker.onConnected(link.nodeId, clock()) }
        renewPeerCertificatesIfAdmin(link)
        link.send(LinkMessage.Beat(heartbeat()))
        Thread({ readLoop(link) }, "aidin-read-${link.nodeId}").apply { isDaemon = true }.start()
        publishPeers()
    }

    private fun readLoop(link: PeerLink) {
        while (true) {
            when (val message = link.receive()) {
                null, is LinkMessage.Bye -> break
                is LinkMessage.Beat -> onHeartbeat(link, message.heartbeat)
                is LinkMessage.Revocations -> onRevocations(message.list)
                is LinkMessage.CertificateRenewal -> onCertificateRenewal(message.certificate)
                is LinkMessage.DelegateRequest -> onDelegateRequest(link, message)
                is LinkMessage.DelegateResponse -> onDelegateResponse(message)
                is LinkMessage.DelegateStreamEvent -> onDelegateStreamEvent(message)
                is LinkMessage.PlanAssign -> {
                    val ack = helperSupervisor?.onPlanAssign(link.nodeId, message.plan)
                        ?: LinkMessage.PlanAck(message.plan.planId, message.plan.version, accepted = false, reason = "no helper supervisor")
                    runCatching { link.send(ack) }
                }
                is LinkMessage.PlanAck -> {
                    val key = "${message.planId}:${message.version}:${link.nodeId.value}"
                    pendingPlanAcks.remove(key)?.complete(message)
                }
                is LinkMessage.TunnelOpen -> {
                    helperSupervisor?.onTunnelOpen(link.nodeId, message) { reply ->
                        runCatching { link.send(reply) }
                    } ?: runCatching { link.send(LinkMessage.TunnelClose(message.streamId, "no helper supervisor")) }
                }
                is LinkMessage.TunnelData -> {
                    helperSupervisor?.onTunnelData(link.nodeId, message)
                    anchorSupervisor?.onTunnelData(link.nodeId, message)
                }
                is LinkMessage.TunnelClose -> {
                    helperSupervisor?.onTunnelClose(message)
                    anchorSupervisor?.onTunnelClose(message)
                }
                // Pairing messages are only valid on pairing links.
                else -> Unit
            }
        }
        link.close()
        if (links.remove(link.nodeId, link)) {
            synchronized(lock) { tracker.onDisconnected(link.nodeId, clock()) }
            failPeerDelegations(link.nodeId)
            publishPeers()
        }
    }

    private fun onDelegateRequest(link: PeerLink, request: LinkMessage.DelegateRequest) {
        scope.launch {
            // Serving side requires Scope.DELEGATE ("The peer may send me requests").
            val auth = authorize(link.nodeId, Scope.DELEGATE)
            if (!auth.isAllowed) {
                runCatching {
                    link.send(
                        LinkMessage.DelegateResponse(
                            requestId = request.requestId,
                            status = 403,
                            bodyJson = """{"error":{"message":"Caller ${link.nodeId} is not authorized for scope delegate","type":"permission_error","code":"unauthorized_peer"}}""",
                        )
                    )
                }
                return@launch
            }
            // Hop limit 1 (RFC-0104 / RFC-0105): a delegated request is never delegated again.
            if (request.hop > 1) {
                runCatching {
                    link.send(
                        LinkMessage.DelegateResponse(
                            requestId = request.requestId,
                            status = 403,
                            bodyJson = """{"error":{"message":"Delegation hop limit (1) exceeded","type":"locality_violation","code":"hop_limit_exceeded"}}""",
                        )
                    )
                }
                return@launch
            }
            val handler = delegationHandler
            if (handler == null) {
                runCatching {
                    link.send(
                        LinkMessage.DelegateResponse(
                            requestId = request.requestId,
                            status = 503,
                            bodyJson = """{"error":{"message":"Peer delegation handler is not mounted","type":"peer_busy","code":"peer_busy"}}""",
                        )
                    )
                }
                return@launch
            }

            markPeerActive(link.nodeId, true)
            try {
                var streamedAny = false
                val response = handler(link.nodeId, request) { streamEvent ->
                    streamedAny = true
                    runCatching { link.send(streamEvent) }
                }
                if (!request.stream || !streamedAny) {
                    runCatching { link.send(response) }
                }
            } finally {
                markPeerActive(link.nodeId, false)
            }
        }
    }

    private fun onDelegateResponse(response: LinkMessage.DelegateResponse) {
        pendingDelegations.remove(response.requestId)?.complete(response)
        pendingStreams[response.requestId]?.trySend(response)
    }

    private fun onDelegateStreamEvent(event: LinkMessage.DelegateStreamEvent) {
        pendingStreams[event.requestId]?.trySend(event)
    }

    private fun failPeerDelegations(peerNodeId: NodeId) {
        // Any active streaming channels receive a peer_lost event when a link drops mid-stream.
        val lostEvent = """{"type":"peer_lost","retryable":true}"""
        pendingStreams.forEach { (reqId, ch) ->
            ch.trySend(LinkMessage.DelegateStreamEvent(reqId, lostEvent, done = true))
            ch.close()
        }
        pendingDelegations.forEach { (reqId, def) ->
            def.complete(
                LinkMessage.DelegateResponse(
                    requestId = reqId,
                    status = 503,
                    bodyJson = """{"error":{"message":"Peer $peerNodeId disconnected mid-request","type":"peer_lost","code":"peer_lost"}}""",
                )
            )
        }
    }

    private fun failAllPendingDelegations(reason: String) {
        pendingStreams.forEach { (reqId, ch) ->
            ch.trySend(LinkMessage.DelegateStreamEvent(reqId, """{"type":"peer_lost","retryable":true}""", done = true))
            ch.close()
        }
        pendingStreams.clear()
        pendingDelegations.forEach { (reqId, def) ->
            def.complete(
                LinkMessage.DelegateResponse(
                    requestId = reqId,
                    status = 503,
                    bodyJson = """{"error":{"message":"$reason","type":"peer_lost","code":"peer_lost"}}""",
                )
            )
        }
        pendingDelegations.clear()
    }

    private fun onHeartbeat(link: PeerLink, beat: Heartbeat) {
        lastBeats[link.nodeId] = beat
        val newer = synchronized(lock) {
            tracker.onHeartbeat(link.nodeId, beat, clock())
            // Pass on revocation lists the peer has not seen yet.
            trust.memberships.mapNotNull { m ->
                val list = m.revocations ?: return@mapNotNull null
                val theirs = beat.revocationSerials[m.groupId.value]
                if (theirs != null && theirs < list.serial) list else null
            }
        }
        newer.forEach { link.send(LinkMessage.Revocations(it.toWire())) }
        renewPeerCertificatesIfAdmin(link)
        publishPeers()
    }

    private fun renewPeerCertificatesIfAdmin(link: PeerLink) {
        val now = clock()
        val creds = knownPeers[link.nodeId] ?: link.peer
        for (cert in creds.certificates) {
            val remaining = cert.notAfterMillis - now
            if (remaining in 1..certificateRenewalThresholdMillis && isAdmin(cert.groupId)) {
                renewMemberCertificate(cert.groupId, link.nodeId, cert.roles)
            }
        }
    }

    private fun onCertificateRenewal(wire: WireCertificate) {
        val cert = wire.toCertificate() ?: return
        synchronized(lock) {
            val now = clock()
            if (cert.nodeId != nodeId || !cert.isValidAt(now)) return
            val existing = state.memberships.firstOrNull { it.groupId == cert.groupId } ?: return
            if (!cert.groupPublicKey.contentEquals(existing.groupPublicKey)) return
            if (cert.notAfterMillis <= existing.ownCertificate.notAfterMillis) return
            val renewedMembership = GroupMembership(existing.groupPublicKey, cert, existing.revocations)
            update(
                state.copy(
                    memberships = state.memberships.map { if (it.groupId == cert.groupId) renewedMembership else it },
                )
            )
        }
    }

    private fun onRevocations(wire: WireRevocationList) {
        val list = wire.toRevocationList() ?: return
        val accepted = synchronized(lock) {
            trust.acceptRevocations(list).also { if (it) persistTrust() }
        }
        if (accepted) {
            broadcast(LinkMessage.Revocations(wire))
            dropUntrustedLinks()
        }
    }

    private fun beatAndTick() {
        renewOwnAdminCertificatesIfNeeded()
        val beat = heartbeat()
        links.values.forEach { link ->
            try {
                link.send(LinkMessage.Beat(beat))
            } catch (_: IOException) {
                link.close()
            }
        }
        val left = synchronized(lock) { tracker.tick(clock()) }.filter { it.to == PeerState.LEFT }
        left.forEach { links.remove(it.nodeId)?.close() }
        publishPeers()
    }

    private fun renewOwnAdminCertificatesIfNeeded() = synchronized(lock) {
        val now = clock()
        var changed = false
        val updatedMemberships = state.memberships.map { m ->
            val admin = state.administered.firstOrNull { it.groupId == m.groupId }
            val remaining = m.ownCertificate.notAfterMillis - now
            if (admin != null && remaining <= certificateRenewalThresholdMillis) {
                changed = true
                val freshCert = admin.authority.issue(key.publicKey, m.ownCertificate.roles, now)
                m.copy(ownCertificate = freshCert)
            } else {
                m
            }
        }
        if (changed) {
            update(state.copy(memberships = updatedMemberships))
        }
    }

    private fun broadcast(message: LinkMessage) {
        links.values.forEach { runCatching { it.send(message) } }
    }

    /** After trust shrank (revocation, unpairing, leaving): close links that are no longer admitted. */
    private fun dropUntrustedLinks() {
        val now = clock()
        links.values.filter { link ->
            val creds = knownPeers[link.nodeId] ?: link.peer
            synchronized(lock) { !trust.authorizeAny(creds, now).isAllowed }
        }.forEach {
            links.remove(it.nodeId, it)
            it.close()
        }
        publishPeers()
    }

    private fun heartbeat(): Heartbeat {
        val serials = synchronized(lock) { trust.memberships.mapNotNull { m -> m.revocations?.let { m.groupId.value to it.serial } }.toMap() }
        val base = heartbeatContent()
        val splitAdvert = anchorSupervisor?.activePlan?.let { plan ->
            if (anchorSupervisor?.isAvailable == true && base.models.none { it.id == plan.modelId }) {
                listOf(ModelAdvert(id = plan.modelId, kind = "llm", digest = plan.modelDigest, placement = "cluster", loaded = true))
            } else {
                emptyList()
            }
        }.orEmpty()
        return base.copy(
            protocolVersion = AIDIN_PROTOCOL_VERSION,
            models = base.models + splitAdvert,
            revocationSerials = serials,
        )
    }

    private fun advert(port: Int): DiscoveryAdvert {
        val now = clock()
        val tags = synchronized(lock) { trust.memberships.map { GroupTags.tag(it.groupId, now) }.toSet() }
        return DiscoveryAdvert(nodeId, port, tags)
    }

    private fun publishPeers() {
        val now = clock()
        _peers.value = synchronized(lock) {
            val states = tracker.states()
            val ids = (states.keys + state.pairs.map { it.nodeId }).distinct()
            ids.map { id ->
                val link = links[id]
                val creds = knownPeers[id] ?: link?.peer
                PeerView(
                    nodeId = id,
                    label = state.pairs.firstOrNull { it.nodeId == id }?.label,
                    state = states[id] ?: PeerState.LEFT,
                    domains = if (link != null && creds != null) {
                        (trust.authorizeAny(creds, now) as? TrustDecision.Allowed)?.domains.orEmpty()
                    } else {
                        emptySet()
                    },
                    connected = link != null,
                    lastHeartbeat = lastBeats[id],
                )
            }
        }
    }

    // ---- state ----------------------------------------------------------------------------

    /** Caller holds [lock]. */
    private fun update(next: AidinState) {
        state = next
        stateStore.save(next)
        rebuildTrust()
        server?.port?.let { p -> discovery?.announce(advert(p)) }
    }

    /** Copy revocation lists the trust store accepted back into the persisted state. Caller holds [lock]. */
    private fun persistTrust() {
        val byGroup = trust.memberships.associateBy { it.groupId }
        state = state.copy(memberships = state.memberships.map { byGroup[it.groupId] ?: it })
        stateStore.save(state)
    }

    private fun rebuildTrust() {
        val now = clock()
        trust.pairs.forEach { trust.removePair(it.nodeId) }
        trust.memberships.forEach { trust.leave(it.groupId) }
        state.pairs.forEach { trust.addPair(it) }
        state.memberships.forEach { m ->
            // An expired own certificate stays in the persisted state (renewal can replace it) but
            // grants nothing.
            runCatching { trust.join(m, now) }
        }
    }

    // ---- pairing --------------------------------------------------------------------------

    private fun openPairing(invite: PairingInvite, kind: PairingKind, groupId: GroupId?, roles: Set<Scope>): String {
        val link = endpoint.connectForPairing(invite)
        link.send(LinkMessage.PairOffer(if (kind == PairingKind.ENROL_OTHER) groupId?.value else null))
        return startPairingSession(link, initiatedHere = true, kind = kind, groupId = groupId, roles = roles, invite = invite)
    }

    private fun startPairingSession(
        link: PeerLink,
        initiatedHere: Boolean,
        kind: PairingKind,
        groupId: GroupId?,
        roles: Set<Scope>,
        invite: PairingInvite? = null,
    ): String {
        val secret = checkNotNull(link.pairingSecret)
        if (!initiatedHere) synchronized(lock) { invites.removeAll { it.first.secret.contentEquals(secret) } }
        val session = PairingSession(
            id = UUID.randomUUID().toString(),
            link = link,
            code = Sas.code(key.publicKey, link.peer.publicKey, secret),
            kind = kind,
            groupId = groupId,
            roles = roles,
            invite = invite,
        )
        synchronized(lock) { pairings[session.id] = session }
        Thread({ session.readLoop() }, "aidin-pair-${link.nodeId}").apply { isDaemon = true }.start()
        publishPairings()
        return session.id
    }

    private fun publishPairings() {
        _pairings.value = synchronized(lock) { pairings.values.filter { it.offerReceived }.map { it.view() } }
    }

    private inner class PairingSession(
        val id: String,
        val link: PeerLink,
        val code: String,
        var kind: PairingKind,
        var groupId: GroupId?,
        val roles: Set<Scope>,
        /** Set on the side that scanned: where to open the normal link once trust exists. */
        val invite: PairingInvite?,
    ) {
        /**
         * The scanned side learns from the first message whether this is a pairing or an
         * enrolment. Until then the session is hidden and cannot be confirmed, so nobody confirms
         * one thing while the other device asks for another.
         */
        var offerReceived = invite != null
            private set
        private var label: String? = null
        private var confirmedHere = false
        private var confirmedThere = false
        private var status = PairingStatus.WAITING_FOR_CONFIRMATION
        private var error: String? = null

        fun view() = PairingView(id, link.nodeId, code, kind, groupId, confirmedHere, confirmedThere, status, error)

        fun confirmHere(label: String) {
            synchronized(lock) {
                if (status != PairingStatus.WAITING_FOR_CONFIRMATION || confirmedHere || !offerReceived) return
                confirmedHere = true
                this.label = label
            }
            // Both sides name the group in an enrolment, so neither can confirm something else.
            val group = if (kind == PairingKind.PAIR) null else groupId?.value
            send(LinkMessage.PairConfirm(Scope.ALL.map { it.wire }, group))
            progress()
        }

        fun readLoop() {
            var linkEnded = false
            while (true) {
                when (val m = link.receive()) {
                    null, is LinkMessage.Bye -> {
                        linkEnded = true
                        synchronized(lock) { if (status == PairingStatus.WAITING_FOR_CONFIRMATION) fail("other device left") }
                        break
                    }
                    is LinkMessage.PairOffer -> {
                        synchronized(lock) {
                            if (invite == null && !offerReceived) {
                                if (m.group != null) {
                                    kind = PairingKind.ENROLLED_BY_OTHER
                                    groupId = runCatching { GroupId(m.group) }.getOrNull()
                                }
                                offerReceived = true
                            }
                        }
                        publishPairings()
                    }
                    is LinkMessage.PairConfirm -> {
                        // The other side must confirm the same thing this side shows its user.
                        val consistent = synchronized(lock) {
                            confirmedThere = true
                            when (kind) {
                                PairingKind.PAIR -> m.group == null
                                else -> m.group != null && m.group == groupId?.value
                            }
                        }
                        if (consistent) progress() else fail("the other device confirmed something else")
                    }
                    is LinkMessage.Enrolment -> onEnrolment(m)
                    else -> Unit
                }
                if (status != PairingStatus.WAITING_FOR_CONFIRMATION) break
            }
            // The scanning side reconnects over a normal link next, and the other device admits
            // that link only once it has stored the pairing or enrolment. It stores before it closes
            // the pairing link, so wait for its close: reconnecting right after sending Enrolment
            // (or reading the last PairConfirm) raced the other side's store, got refused as "not
            // trusted", and nothing retried without discovery.
            if (invite != null && !linkEnded && synchronized(lock) { status } == PairingStatus.DONE) {
                link.awaitPeerClose(PAIRING_CLOSE_WAIT_MILLIS)
            }
            link.close()
            reconnectIfDone()
        }

        /** The scanning side opens the normal link; the pairing link carried only pairing. */
        private fun reconnectIfDone() {
            val target = invite ?: return
            if (synchronized(lock) { status } != PairingStatus.DONE) return
            for (host in target.addresses) {
                try {
                    if (!links.containsKey(link.nodeId)) onLink(endpoint.connect(host, target.port))
                    return
                } catch (_: IOException) {
                    // Try the next address; discovery retries later anyway.
                }
            }
        }

        private fun progress() {
            val (bothConfirmed, kindNow) = synchronized(lock) { (confirmedHere && confirmedThere) to kind }
            if (!bothConfirmed) {
                publishPairings()
                return
            }
            when (kindNow) {
                PairingKind.PAIR -> finish {
                    update(state.copy(pairs = state.pairs.filterNot { it.nodeId == link.nodeId } +
                        PairRecord(link.peer.publicKey, label.orEmpty(), clock(), Scope.ALL)))
                }
                PairingKind.ENROL_OTHER -> {
                    val (cert, list) = synchronized(lock) {
                        val admin = state.administered.first { it.groupId == groupId }
                        val now = clock()
                        admin.authority.issue(link.peer.publicKey, roles, now) to
                            admin.authority.revocations(admin.revocationSerial, admin.revoked, now)
                    }
                    knownPeers[link.nodeId] = PeerCredentials(link.peer.publicKey, listOf(cert))
                    send(LinkMessage.Enrolment(cert.toWire(), list.toWire()))
                    finish {}
                }
                // Wait for the admin's Enrolment message.
                PairingKind.ENROLLED_BY_OTHER -> publishPairings()
            }
        }

        private fun onEnrolment(m: LinkMessage.Enrolment) {
            val ok = synchronized(lock) {
                if (kind != PairingKind.ENROLLED_BY_OTHER || !confirmedHere) return@synchronized false
                val cert = m.certificate.toCertificate() ?: return@synchronized false
                if (cert.groupId != groupId || cert.nodeId != nodeId) return@synchronized false
                val membership = runCatching {
                    GroupMembership(cert.groupPublicKey, cert, m.revocations?.toRevocationList())
                }.getOrNull() ?: return@synchronized false
                if (!cert.isValidAt(clock())) return@synchronized false
                update(
                    state.copy(
                        memberships = state.memberships.filterNot { it.groupId == cert.groupId } + membership,
                        groupLabels = state.groupLabels + (cert.groupId to label.orEmpty()),
                    ),
                )
                true
            }
            if (ok) finish {} else fail("enrolment did not verify")
        }

        private fun finish(store: () -> Unit) {
            synchronized(lock) {
                store()
                status = PairingStatus.DONE
            }
            publishPairings()
            // The scanned side closes once it has stored: that close is the scanning side's signal
            // that it may open the normal link (see readLoop). finish() can run on the confirming
            // thread while readLoop is blocked in receive(), so closing here also ends that loop.
            if (invite == null) link.close()
        }

        fun fail(reason: String) {
            synchronized(lock) {
                if (status != PairingStatus.WAITING_FOR_CONFIRMATION) return
                status = PairingStatus.FAILED
                error = reason
            }
            link.close()
            publishPairings()
        }

        private fun send(message: LinkMessage) {
            try {
                link.send(message)
            } catch (_: IOException) {
                fail("other device left")
            }
        }
    }
}

/** How long the scanning side waits for the other device to close the pairing link after both confirmed. */
private const val PAIRING_CLOSE_WAIT_MILLIS = 5_000
