package fi.italeino.aidos.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.GroupId
import dev.aidos.aidin.PairingInvite
import dev.aidos.aidin.PairingKind
import dev.aidos.aidin.PairingStatus
import dev.aidos.aidin.PairingView
import dev.aidos.aidin.PeerView
import dev.aidos.aidin.SplitHosting
import dev.aidos.aidin.TrustDomain
import dev.aidos.aidin.lanAddresses
import dev.aidos.tcr.engine.GroupPairingKind
import dev.aidos.tcr.engine.GroupPairingUi
import dev.aidos.tcr.engine.GroupPeerUi
import dev.aidos.tcr.engine.GroupSplitUi
import dev.aidos.tcr.engine.GroupUi
import dev.aidos.tcr.engine.GroupsActions
import dev.aidos.tcr.engine.GroupsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.aidos.tcr.engine.GroupsPane as TcrGroupsPane

private val noPeers: StateFlow<List<PeerView>> = MutableStateFlow(emptyList())
private val noPairings: StateFlow<List<PairingView>> = MutableStateFlow(emptyList())
private val noStatus: StateFlow<String?> = MutableStateFlow(null)

/**
 * Inference groups (RFC-0105 Phase 1-3), shared by the desktop window and the Android screen. The
 * TCR `GroupsPane` owns all rendering (D46); this binds it to the host's [AidinNode]: it collects the
 * node's flows into a [GroupsUiState] and turns the pane's callbacks into node calls that run on
 * [scope] off the main thread, so the pane itself never sees an AIDIN type.
 *
 * Invites are shown as a QR code (for a phone to scan) and as text (for another device to paste).
 *
 * @param initialInvite an invite to prefill, e.g. from an `aidin:` link the user opened.
 * @param split layer split on this node (RFC-0105 phase 3), or null where the host offers none.
 * @param splitModels installed models this device could serve across a group.
 * @param onStartSplit starts serving a model across a group and returns the result in words; it
 *   blocks while the model loads and is called off the main thread.
 */
@Composable
fun GroupsPane(
    sharingEnabled: Boolean,
    onSharingChange: (Boolean) -> Unit,
    isEngineRunning: Boolean,
    node: AidinNode?,
    error: String?,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    initialInvite: String = "",
    split: SplitHosting? = null,
    splitModels: List<String> = emptyList(),
    onStartSplit: ((GroupId, String) -> String)? = null,
) {
    val peers by remember(node) { node?.peers ?: noPeers }.collectAsState()
    val pairings by remember(node) { node?.pairingViews ?: noPairings }.collectAsState()
    val splitStatus by remember(split) { split?.status ?: noStatus }.collectAsState()
    // groups() is not a flow; bump this after anything that can change it.
    var groupsVersion by remember { mutableStateOf(0) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var shownInvite by remember(node) { mutableStateOf<String?>(null) }

    val groups = remember(node, groupsVersion, pairings) {
        node?.let { n -> n.groups().map { (id, label) -> Triple(id, label, n.isAdmin(id)) } }.orEmpty()
    }
    val adminGroupIds = groups.filter { it.third }.map { it.first }.toSet()

    fun act(block: () -> Unit) {
        scope.launch {
            actionError = try {
                withContext(Dispatchers.IO) { block() }
                null
            } catch (e: Exception) {
                e.message ?: e::class.simpleName
            }
            groupsVersion++
        }
    }

    val state = GroupsUiState(
        sharingEnabled = sharingEnabled,
        isEngineRunning = isEngineRunning,
        nodeStarted = node != null,
        error = error,
        actionError = actionError,
        thisDevice = node?.let { "${it.nodeId.value} · port ${it.port}" }.orEmpty(),
        pairings = pairings.filter { it.status == PairingStatus.WAITING_FOR_CONFIRMATION }.map { it.toTcr() },
        lastPairingFailure = pairings.lastOrNull { it.status == PairingStatus.FAILED }?.error,
        groups = groups.map { (id, label, admin) -> GroupUi(id.value, label, admin) },
        peers = peers.map { it.toTcr(adminGroupIds) },
        shownInvite = shownInvite,
        split = split?.let {
            // activeModelId is a plain getter; reading it after collecting the status flow keeps it fresh.
            GroupSplitUi(available = it.available, activeModelId = it.activeModelId, status = splitStatus, models = splitModels)
        },
    )

    val actions = GroupsActions(
        onSharingChange = onSharingChange,
        onConfirmPairing = { id, label -> node?.confirmPairing(id, label) },
        onRejectPairing = { id -> node?.rejectPairing(id) },
        onShowInvite = {
            node?.let { n -> act { shownInvite = n.createInvite(lanAddresses().ifEmpty { listOf("127.0.0.1") }).toUri() } }
        },
        isValidInvite = { PairingInvite.parse(it.trim()) != null },
        onPair = { text -> node?.let { n -> PairingInvite.parse(text.trim())?.let { i -> act { n.pairWith(i) } } } },
        onEnrol = { text, group ->
            node?.let { n -> PairingInvite.parse(text.trim())?.let { i -> act { n.enrol(i, GroupId(group)) } } }
        },
        onLeaveGroup = { group -> node?.let { n -> act { n.leaveGroup(GroupId(group)) } } },
        onCreateGroup = { name -> node?.let { n -> act { n.createGroup(name) } } },
        onUnpair = { peer -> node?.let { n -> act { n.removePair(dev.aidos.aidin.NodeId(peer)) } } },
        onRemoveFromGroup = { group, peer ->
            node?.let { n -> act { n.revoke(GroupId(group), dev.aidos.aidin.NodeId(peer)) } }
        },
        onStartSplit = onStartSplit?.let { start ->
            { group: String, model: String -> withContext(Dispatchers.IO) { start(GroupId(group), model) } }
        },
        onStopSplit = { split?.let { s -> withContext(Dispatchers.IO) { s.stop() } } },
    )

    TcrGroupsPane(
        state = state,
        actions = actions,
        modifier = modifier,
        initialInvite = initialInvite,
        qrCode = { QrCode(it) },
    )
}

private fun PairingView.toTcr() = GroupPairingUi(
    id = id,
    kind = when (kind) {
        PairingKind.PAIR -> GroupPairingKind.PAIR
        PairingKind.ENROL_OTHER -> GroupPairingKind.ENROL_OTHER
        PairingKind.ENROLLED_BY_OTHER -> GroupPairingKind.ENROLLED_BY_OTHER
    },
    code = code,
    confirmedHere = confirmedHere,
    confirmedThere = confirmedThere,
)

private fun PeerView.toTcr(adminGroupIds: Set<GroupId>): GroupPeerUi {
    val hb = lastHeartbeat
    val groupDomains = domains.filterIsInstance<TrustDomain.Group>()
    return GroupPeerUi(
        id = nodeId.value,
        label = label,
        shortId = nodeId.value.take(10),
        connected = connected,
        stateName = state.name,
        viaGroup = groupDomains.isNotEmpty(),
        viaPaired = domains.any { it !is TrustDomain.Group },
        draining = hb?.draining == true,
        batteryPercent = hb?.batteryPercent,
        charging = hb?.charging,
        engineVersion = hb?.engineVersion,
        ramOfferedBytes = hb?.contributionRamBytes,
        modelIds = hb?.models?.map { it.id }.orEmpty(),
        // Peer domains only show while linked; a group admin removes members it can see.
        removableFromGroupIds = groupDomains.map { it.groupId }.filter { it in adminGroupIds }.map { it.value },
    )
}
