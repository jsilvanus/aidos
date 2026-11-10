package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inference groups (RFC-0105 Phase 1-3), shared by the desktop window and the Android screen: turn
 * sharing on, pair with or enrol another device by exchanging an invite, compare the six-digit
 * code, and inspect linked peers. Linked peers participate in virtual model routing (`aidos:chat`),
 * peer delegation, and cooperative child-process layer splitting.
 *
 * Invites are shown as a QR code (for a phone to scan) and as text (for another device to paste).
 * Node calls that touch the network run on [scope] off the main thread.
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Share with devices on this network", fontWeight = FontWeight.SemiBold)
                Text(
                    "Lets paired devices and group members find and link to this Engine for virtual model resolution (aidos:chat) and cooperative split inference. Off by default.",
                    fontSize = 12.sp,
                )
            }
            Switch(checked = sharingEnabled, onCheckedChange = onSharingChange)
        }
        error?.let { Text("Could not start sharing: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        when {
            !sharingEnabled -> Unit
            !isEngineRunning -> Text("Start the Engine to use device groups.")
            node == null -> Text("Starting…")
            else -> GroupsContent(node, scope, initialInvite, split, splitModels, onStartSplit)
        }
    }
}

@Composable
private fun GroupsContent(
    node: AidinNode,
    scope: CoroutineScope,
    initialInvite: String,
    split: SplitHosting?,
    splitModels: List<String>,
    onStartSplit: ((GroupId, String) -> String)?,
) {
    val peers by node.peers.collectAsState()
    val pairings by node.pairingViews.collectAsState()
    // groups() is not a flow; bump this after anything that can change it.
    var groupsVersion by remember { mutableStateOf(0) }
    val groups = remember(groupsVersion, pairings) { node.groups().map { (id, label) -> Triple(id, label, node.isAdmin(id)) } }
    var actionError by remember { mutableStateOf<String?>(null) }

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

    Text("This device", fontWeight = FontWeight.SemiBold)
    SelectionContainer { Text("${node.nodeId.value} · port ${node.port}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

    pairings.filter { it.status == PairingStatus.WAITING_FOR_CONFIRMATION }.forEach { PairingCard(it, node) }
    pairings.filter { it.status == PairingStatus.FAILED }.lastOrNull()?.let {
        Text("Pairing failed: ${it.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

    HorizontalDivider()
    InviteSection(node, groups.filter { it.third }.map { it.first to it.second }, initialInvite, ::act)

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

    if (split != null) {
        HorizontalDivider()
        SplitSection(split, groups.map { it.first to it.second }, splitModels, onStartSplit, scope)
    }
}

/**
 * Serve one model across a group (RFC-0105 phase 3): this device keeps some layers, group members
 * that offer RAM take the rest, and the model answers through `aidos:chat` for the whole group.
 */
@Composable
private fun SplitSection(
    split: SplitHosting,
    groups: List<Pair<GroupId, String>>,
    models: List<String>,
    onStart: ((GroupId, String) -> String)?,
    scope: CoroutineScope,
) {
    val status by split.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var group by remember(groups) { mutableStateOf(groups.firstOrNull()?.first) }
    var model by remember(models) { mutableStateOf(models.firstOrNull()) }

    Text("Run a model across a group", fontWeight = FontWeight.SemiBold)
    if (!split.available) {
        Text(
            "This device needs the pinned llama-server and ggml-rpc-server to split a model. " +
                "Until they are installed it neither serves nor helps with split models.",
            fontSize = 12.sp,
        )
        return
    }
    val active = split.activeModelId
    if (active != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Serving $active across the group", modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { split.stop() } } }) { Text("Stop") }
        }
    }
    if (onStart == null) return
    if (groups.isEmpty() || models.isEmpty()) {
        Text("Join a group and install a GGUF model to run it across the group.", fontSize = 12.sp)
        return
    }
    ChoiceRow("Group", groups.map { it.first to it.second.ifBlank { it.first.value } }, group) { group = it }
    ChoiceRow("Model", models.map { it to it }, model) { model = it }
    Button(
        onClick = {
            val g = group ?: return@Button
            val m = model ?: return@Button
            busy = true
            scope.launch {
                result = try {
                    withContext(Dispatchers.IO) { onStart(g, m) }
                } catch (e: Exception) {
                    e.message ?: e::class.simpleName
                }
                busy = false
            }
        },
        enabled = !busy && active == null && group != null && model != null,
    ) { Text(if (busy) "Loading…" else "Run across group") }
    (result ?: status)?.let { Text(it, fontSize = 12.sp) }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(56.dp), fontSize = 12.sp)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == selected }?.second ?: "Choose") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
                }
            }
        }
    }
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
private fun InviteSection(node: AidinNode, adminGroups: List<Pair<GroupId, String>>, initialInvite: String, act: (() -> Unit) -> Unit) {
    var shown by remember { mutableStateOf<String?>(null) }
    var pasted by remember(initialInvite) { mutableStateOf(initialInvite) }

    Text("Connect a device", fontWeight = FontWeight.SemiBold)
    Text(
        "To pair or to be added to a group, show an invite here and scan or paste it on the other device. " +
            "It works once and expires in five minutes.",
        fontSize = 12.sp,
    )
    OutlinedButton(onClick = { act { shown = node.createInvite(lanAddresses().ifEmpty { listOf("127.0.0.1") }).toUri() } }) {
        Text("Show invite")
    }
    shown?.let {
        QrCode(it)
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
private fun PeerRow(peer: PeerView, adminGroups: List<GroupId>, act: (() -> Unit) -> Unit, node: AidinNode) {
    val via = peer.domains.joinToString { if (it is TrustDomain.Group) "group" else "paired" }
    val hb = peer.lastHeartbeat
    val batteryInfo = hb?.batteryPercent?.let { pct ->
        "$pct%" + if (hb.charging == true) " ⚡" else ""
    }
    val ramContributed = hb?.contributionRamBytes?.takeIf { it > 0 }?.let { bytes ->
        "${bytes / (1024 * 1024)} MB RAM offered"
    }
    val draining = if (hb?.draining == true) "draining" else null
    val modelsSummary = hb?.models?.takeIf { it.isNotEmpty() }?.let { models ->
        "${models.size} model${if (models.size == 1) "" else "s"}: " +
            models.joinToString(", ") { it.id.substringAfterLast('/') }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(peer.label?.ifBlank { null } ?: peer.nodeId.value.take(10), fontWeight = FontWeight.SemiBold)
                if (peer.connected) {
                    Surface(
                        color = Color(0xFF22C55E).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "LINKED",
                            color = Color(0xFF16A34A),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                listOfNotNull(
                    peer.state.name.lowercase(),
                    if (!peer.connected) "not linked" else null,
                    via.ifBlank { null },
                    draining,
                    batteryInfo,
                    peer.lastHeartbeat?.engineVersion?.let { "v$it" },
                ).joinToString(" · "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ramContributed != null || modelsSummary != null) {
                Text(
                    listOfNotNull(ramContributed, modelsSummary).joinToString(" · "),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (peer.label != null) TextButton(onClick = { act { node.removePair(peer.nodeId) } }) { Text("Unpair") }
        // Peer domains only show while linked; a group admin removes members it can see.
        peer.domains.filterIsInstance<TrustDomain.Group>().map { it.groupId }.filter { it in adminGroups }.forEach { g ->
            TextButton(onClick = { act { node.revoke(g, peer.nodeId) } }) { Text("Remove from group") }
        }
    }
}
