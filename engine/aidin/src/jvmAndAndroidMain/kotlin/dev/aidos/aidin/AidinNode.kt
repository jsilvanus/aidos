package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * One Engine's AIDIN presence (RFC-0105 phase 1): the peer link server, discovery, heartbeats,
 * liveness, trust state and pairing/enrolment flows. Off until [start]; the host starts it only when
 * the user turned group sharing on.
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
) {
    val nodeId: NodeId = key.nodeId

    private val lock = Any()
    private var state: AidinState = stateStore.load()
    private val trust = TrustStore(nodeId)
    private val links = ConcurrentHashMap<NodeId, PeerLink>()
    private val lastBeats = ConcurrentHashMap<NodeId, Heartbeat>()
    private val invites = mutableListOf<Pair<PairingInvite, Long>>()
    private val pairings = LinkedHashMap<String, PairingSession>()

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
        discovery?.close()
        server?.close()
        server = null
        links.values.forEach { it.close() }
        links.clear()
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

    /** For callers that check a scope before an action (phase 2 delegation, phase 3 plans). */
    fun authorize(nodeId: NodeId, scope: Scope): TrustDecision {
        val link = links[nodeId] ?: return TrustDecision.Denied(listOf("not connected"))
        if (link.isPairing) return TrustDecision.Denied(listOf("pairing link"))
        return synchronized(lock) { trust.authorize(link.peer, scope, clock()) }
    }

    /** Connect to a known peer directly (no mDNS on the network, or a typed address). */
    fun connect(host: String, port: Int): NodeId {
        val link = endpoint.connect(host, port)
        onLink(link)
        return link.nodeId
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
        } catch (e: IOException) {
            // Not reachable or not trusting us (yet); the next advert retries.
        }
    }

    private fun onLink(link: PeerLink) {
        if (link.isPairing) {
            startPairingSession(link, initiatedHere = false, kind = PairingKind.PAIR, groupId = null, roles = emptySet())
            return
        }
        links.put(link.nodeId, link)?.takeIf { it !== link }?.close()
        synchronized(lock) { tracker.onConnected(link.nodeId, clock()) }
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
                // Pairing messages are only valid on pairing links.
                else -> Unit
            }
        }
        link.close()
        if (links.remove(link.nodeId, link)) {
            synchronized(lock) { tracker.onDisconnected(link.nodeId, clock()) }
            publishPeers()
        }
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
        publishPeers()
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
        val beat = heartbeat()
        links.values.forEach { link ->
            try {
                link.send(LinkMessage.Beat(beat))
            } catch (e: IOException) {
                link.close()
            }
        }
        val left = synchronized(lock) { tracker.tick(clock()) }.filter { it.to == PeerState.LEFT }
        left.forEach { links.remove(it.nodeId)?.close() }
        publishPeers()
    }

    private fun broadcast(message: LinkMessage) {
        links.values.forEach { runCatching { it.send(message) } }
    }

    /** After trust shrank (revocation, unpairing, leaving): close links that are no longer admitted. */
    private fun dropUntrustedLinks() {
        val now = clock()
        links.values.filter { link -> synchronized(lock) { !trust.authorizeAny(link.peer, now).isAllowed } }.forEach {
            links.remove(it.nodeId, it)
            it.close()
        }
        publishPeers()
    }

    private fun heartbeat(): Heartbeat {
        val serials = synchronized(lock) { trust.memberships.mapNotNull { m -> m.revocations?.let { m.groupId.value to it.serial } }.toMap() }
        return heartbeatContent().copy(protocolVersion = AIDIN_PROTOCOL_VERSION, revocationSerials = serials)
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
                PeerView(
                    nodeId = id,
                    label = state.pairs.firstOrNull { it.nodeId == id }?.label,
                    state = states[id] ?: PeerState.LEFT,
                    domains = link?.let { trust.authorizeAny(it.peer, now) as? TrustDecision.Allowed }?.domains.orEmpty(),
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
        _pairings.value = synchronized(lock) { pairings.values.map { it.view() } }
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
        private var label: String? = null
        private var confirmedHere = false
        private var confirmedThere = false
        private var status = PairingStatus.WAITING_FOR_CONFIRMATION
        private var error: String? = null

        fun view() = PairingView(id, link.nodeId, code, kind, groupId, confirmedHere, confirmedThere, status, error)

        fun confirmHere(label: String) {
            synchronized(lock) {
                if (status != PairingStatus.WAITING_FOR_CONFIRMATION || confirmedHere) return
                confirmedHere = true
                this.label = label
            }
            // Both sides name the group in an enrolment, so neither can confirm something else.
            val group = if (kind == PairingKind.PAIR) null else groupId?.value
            send(LinkMessage.PairConfirm(Scope.ALL.map { it.wire }, group))
            progress()
        }

        fun readLoop() {
            while (true) {
                when (val m = link.receive()) {
                    null, is LinkMessage.Bye -> {
                        synchronized(lock) { if (status == PairingStatus.WAITING_FOR_CONFIRMATION) fail("other device left") }
                        break
                    }
                    is LinkMessage.PairOffer -> {
                        synchronized(lock) {
                            if (invite == null && !confirmedHere && m.group != null) {
                                kind = PairingKind.ENROLLED_BY_OTHER
                                groupId = runCatching { GroupId(m.group) }.getOrNull()
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
                } catch (e: IOException) {
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
            } catch (e: IOException) {
                fail("other device left")
            }
        }
    }
}
