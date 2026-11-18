package dev.aidos.ui.generative

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.api.UiManifestRecord
import dev.aidos.tcr.a2ui.A2uiDensity
import dev.aidos.tcr.a2ui.A2uiManifest
import dev.aidos.tcr.a2ui.A2uiParseResult
import dev.aidos.tcr.a2ui.A2uiParser
import dev.aidos.tcr.a2ui.A2uiRenderer
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.GraphSnapshot

/** One persisted agent card after validation (RFC-0124 generative layer, UI-51). */
data class AgentCard(val record: UiManifestRecord, val outcome: CardOutcome)

sealed interface CardOutcome {
    /** The manifest passed [A2uiParser] and names the node it is shown under. */
    data class Shown(val manifest: A2uiManifest) : CardOutcome

    /** Failed closed: nothing from the manifest is drawn. [reasons] appear only under "Details". */
    data class Hidden(val reasons: List<String>) : CardOutcome
}

/**
 * Validates one stored `present_ui` record. Fails closed: a manifest that does not parse, or whose own
 * `targetNodeId` disagrees with the record's, is hidden. Pure, so it is testable without Compose.
 */
fun parseAgentCard(record: UiManifestRecord): AgentCard = AgentCard(
    record,
    when (val parsed = A2uiParser.parse(record.manifestJson)) {
        is A2uiParseResult.Rejected -> CardOutcome.Hidden(parsed.reasons)
        is A2uiParseResult.Ok ->
            if (parsed.manifest.targetNodeId == record.targetNodeId) {
                CardOutcome.Shown(parsed.manifest)
            } else {
                CardOutcome.Hidden(listOf("manifest targets another node than the one it was recorded for"))
            }
    },
)

/** The validated cards to show under [nodeId], in the store's order (newest first). */
fun cardModelsFor(nodeId: String, snapshot: GraphSnapshot): List<AgentCard> =
    snapshot.manifests[nodeId].orEmpty()
        .filter { it.targetNodeId == nodeId }
        .map(::parseAgentCard)

/** A card action waiting for the user's confirmation (untrusted runs only, RFC-0027). */
private class PendingManifestAction(val record: UiManifestRecord, val action: ManifestAction)

/**
 * Agent cards drawn inline under their node (RFC-0124: next to the target, never a free-floating panel).
 * Buttons act through [LocalManifestActionHost] (UI-52): a card whose buttons do not all resolve is drawn
 * with actions disabled, and an action from an untrusted run waits for a confirmation.
 */
@Composable
fun GenerativeCards(
    records: List<UiManifestRecord>,
    density: A2uiDensity,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) return
    val host = LocalManifestActionHost.current
    var awaiting by remember { mutableStateOf<PendingManifestAction?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        records.forEach { record ->
            // Parsing is cached per call: a record never changes once the runtime has stored it.
            val card = remember(record.callId, record.manifestJson) { parseAgentCard(record) }
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xxs)) {
                Provenance(record)
                when (val outcome = card.outcome) {
                    is CardOutcome.Shown -> {
                        val buttons = remember(outcome.manifest) { manifestButtons(outcome.manifest) }
                        val enabled = host != null && ManifestActionRegistry.allResolve(
                            actionIds = buttons.keys,
                            record = record,
                            buttonLabel = { buttons[it].orEmpty() },
                            snapshot = host.snapshot,
                        )
                        A2uiRenderer(
                            manifest = outcome.manifest,
                            density = density,
                            onAction = { actionId ->
                                val h = host ?: return@A2uiRenderer
                                val action = ManifestActionRegistry.resolve(
                                    actionId, record, buttons[actionId].orEmpty(), h.snapshot,
                                ) ?: return@A2uiRenderer
                                if (needsConfirmation(record)) {
                                    awaiting = PendingManifestAction(record, action)
                                } else {
                                    h.controller.performManifestAction(action)
                                }
                            },
                            actionsEnabled = enabled,
                        )
                    }
                    is CardOutcome.Hidden -> HiddenCardNote(outcome.reasons)
                }
            }
        }
    }
    awaiting?.let { pending ->
        AlertDialog(
            onDismissRequest = { awaiting = null },
            title = { Text("Confirm agent action") },
            text = { Text(confirmationText(pending.action)) },
            confirmButton = {
                TextButton(onClick = {
                    awaiting = null
                    host?.controller?.performManifestAction(pending.action)
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { awaiting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Provenance(record: UiManifestRecord) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
        Text(
            text = "From agent run ${record.runId.take(SHORT_RUN_ID)}",
            style = AidosTypography.baseTypography.labelSmall,
            color = MutedText,
        )
        if (record.runTaint != TRUSTED) {
            Text(
                text = "Taint: ${record.runTaint.lowercase()}",
                style = AidosTypography.baseTypography.labelSmall,
                color = TaintText,
            )
        }
    }
}

@Composable
private fun HiddenCardNote(reasons: List<String>) {
    var detailsOpen by remember(reasons) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xxs)) {
        Text(
            text = "An agent card could not be shown (unsupported content)",
            style = AidosTypography.baseTypography.labelSmall,
            color = MutedText,
        )
        Text(
            text = if (detailsOpen) "Hide details" else "Details",
            style = AidosTypography.baseTypography.labelSmall,
            color = MutedText,
            modifier = Modifier.clickable { detailsOpen = !detailsOpen },
        )
        if (detailsOpen) {
            reasons.forEach { reason ->
                Text(text = "• $reason", style = AidosTypography.baseTypography.labelSmall, color = MutedText)
            }
        }
    }
}

private const val SHORT_RUN_ID = 8
private const val TRUSTED = "TRUSTED"
private val MutedText = Color(0xFF94A3B8)
private val TaintText = Color(0xFFFBBF24)
