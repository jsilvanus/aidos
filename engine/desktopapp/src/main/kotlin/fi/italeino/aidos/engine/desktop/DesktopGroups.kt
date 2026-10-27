package fi.italeino.aidos.engine.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.DesktopAidin
import dev.aidos.aidin.PairingInvite
import dev.aidos.aidin.PairingKind
import dev.aidos.aidin.PairingStatus
import dev.aidos.aidin.PairingView
import dev.aidos.aidin.PeerView
import dev.aidos.aidin.TrustDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inference groups (RFC-0105 phase 1) on desktop: turn sharing on, pair with or enrol another
 * device by exchanging an invite, compare the six-digit code, and see linked peers. Peers are only
 * linked and tracked in this phase; nothing is served to them yet.
 *
 * Invites are shown as a QR code (for a phone to scan) and as text (for another desktop to paste).
 */
@Composable
fun GroupsDialog(models: DesktopModels, onClose: () -> Unit) {
    val prefs by models.prefs.data.collectAsState()
    val node by models.host.aidin.collectAsState()
    val isRunning by models.host.isRunning.collectAsState()
    val error by models.host.aidinError.collectAsState()

    DialogWindow(
        onCloseRequest = onClose,
        title = "Device groups",
        state = rememberDialogState(size = DpSize(640.dp, 760.dp)),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share with devices on this network", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Lets paired devices and group members find and link to this Engine. Off by default.",
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = prefs.groupSharingEnabled,
                        onCheckedChange = { on ->
                            models.prefs.setGroupSharingEnabled(on)
                            models.scope.launch { models.host.setGroupSharing(on) }
                        },
                    )
                }
                error?.let { Text("Could not start sharing: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                val current = node
                when {
                    !prefs.groupSharingEnabled -> Unit
                    !isRunning -> Text("Start the Engine to use device groups.")
                    current == null -> Text("Starting…")
                    else -> GroupsContent(current, models)
                }
            }
        }
    }
}

@Composable
private fun GroupsContent(node: AidinNode, models: DesktopModels) {
    val peers by node.peers.collectAsState()
    val pairings by node.pairingViews.collectAsState()
    // groups() is not a flow; bump this after anything that can change it.
    var groupsVersion by remember { mutableStateOf(0) }
    val groups = remember(groupsVersion, pairings) { node.groups().map { (id, label) -> Triple(id, label, node.isAdmin(id)) } }
    var actionError by remember { mutableStateOf<String?>(null) }

    fun act(block: () -> Unit) {
        models.scope.launch {
            actionError = try {
                withContext(Dispatchers.IO) { block() }
                null
            } catch (e: Exception) {
                e.message ?: e::class.simpleName
            }
            groupsVersion++
        }
    }

    Text("This device", fontWeight = FontWeight.SemiBold)
    SelectionContainer { Text("${node.nodeId.value} · port ${node.port}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

    pairings.filter { it.status == PairingStatus.WAITING_FOR_CONFIRMATION }.forEach { PairingCard(it, node) }
    pairings.filter { it.status == PairingStatus.FAILED }.lastOrNull()?.let {
        Text("Pairing failed: ${it.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

    HorizontalDivider()
    InviteSection(node, groups.filter { it.third }.map { it.first to it.second }, ::act)

    HorizontalDivider()
    Text("Groups", fontWeight = FontWeight.SemiBold)
    groups.forEach { (id, label, admin) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.ifBlank { id.value } + if (admin) " (admin)" else "", modifier = Modifier.weight(1f))
            TextButton(onClick = { act { node.leaveGroup(id) } }) { Text("Leave") }
        }
    }
    var newGroup by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(newGroup, { newGroup = it }, label = { Text("New group name") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { val name = newGroup.trim(); newGroup = ""; act { node.createGroup(name) } }, enabled = newGroup.isNotBlank()) {
            Text("Create group")
        }
    }

    HorizontalDivider()
    Text("Devices", fontWeight = FontWeight.SemiBold)
    if (peers.isEmpty()) Text("No paired or linked devices yet.", fontSize = 12.sp)
    peers.forEach { PeerRow(it, groups.filter { g -> g.third }.map { g -> g.first }, ::act, node) }
}

@Composable
private fun PairingCard(view: PairingView, node: AidinNode) {
    var label by remember(view.id) { mutableStateOf("") }
    val what = when (view.kind) {
        PairingKind.PAIR -> "Pair with another device"
        PairingKind.ENROL_OTHER -> "Add a device to your group"
        PairingKind.ENROLLED_BY_OTHER -> "Join a group run by another device"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(what, fontWeight = FontWeight.SemiBold)
            Text("Check that the other screen shows the same code:", fontSize = 12.sp)
            Text(view.code.chunked(3).joinToString(" "), fontSize = 28.sp, fontFamily = FontFamily.Monospace)
            if (view.confirmedHere) {
                Text(if (view.confirmedThere) "Finishing…" else "Waiting for the other device to confirm.", fontSize = 12.sp)
            } else {
                OutlinedTextField(label, { label = it }, label = { Text("Name for this device or group") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { node.confirmPairing(view.id, label.trim()) }) { Text("Codes match") }
                    OutlinedButton(onClick = { node.rejectPairing(view.id) }) { Text("They differ") }
                }
            }
        }
    }
}

@Composable
private fun InviteSection(node: AidinNode, adminGroups: List<Pair<dev.aidos.aidin.GroupId, String>>, act: (() -> Unit) -> Unit) {
    var shown by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }

    Text("Connect a device", fontWeight = FontWeight.SemiBold)
    Text(
        "To pair or to be added to a group, show an invite here and scan or paste it on the other device. " +
            "It works once and expires in five minutes.",
        fontSize = 12.sp,
    )
    OutlinedButton(onClick = { act { shown = node.createInvite(DesktopAidin.lanAddresses().ifEmpty { listOf("127.0.0.1") }).toUri() } }) {
        Text("Show invite")
    }
    shown?.let {
        fi.italeino.aidos.engine.ui.QrCode(it)
        SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }

    OutlinedTextField(pasted, { pasted = it }, label = { Text("Paste another device's invite") }, modifier = Modifier.fillMaxWidth())
    val invite = remember(pasted) { PairingInvite.parse(pasted.trim()) }
    if (pasted.isNotBlank() && invite == null) Text("That is not an invite this version understands.", fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { val i = invite!!; pasted = ""; act { node.pairWith(i) } }, enabled = invite != null) { Text("Pair") }
        adminGroups.forEach { (id, label) ->
            OutlinedButton(onClick = { val i = invite!!; pasted = ""; act { node.enrol(i, id) } }, enabled = invite != null) {
                Text("Add to ${label.ifBlank { "group" }}")
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerView, adminGroups: List<dev.aidos.aidin.GroupId>, act: (() -> Unit) -> Unit, node: AidinNode) {
    val via = peer.domains.joinToString { if (it is TrustDomain.Group) "group" else "paired" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(peer.label?.ifBlank { null } ?: peer.nodeId.value.take(10))
            Text(
                listOfNotNull(
                    peer.state.name.lowercase(),
                    if (peer.connected) "linked" else "not linked",
                    via.ifBlank { null },
                    peer.lastHeartbeat?.engineVersion,
                ).joinToString(" · "),
                fontSize = 12.sp,
            )
        }
        if (peer.label != null) TextButton(onClick = { act { node.removePair(peer.nodeId) } }) { Text("Unpair") }
        // Peer domains only show while linked; a group admin removes members it can see.
        peer.domains.filterIsInstance<TrustDomain.Group>().map { it.groupId }.filter { it in adminGroups }.forEach { g ->
            TextButton(onClick = { act { node.revoke(g, peer.nodeId) } }) { Text("Remove from group") }
        }
    }
}
