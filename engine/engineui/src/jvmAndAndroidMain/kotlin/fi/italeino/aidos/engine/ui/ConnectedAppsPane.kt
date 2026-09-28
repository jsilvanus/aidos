package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStatus

/**
 * Connected Apps pane (RFC-0103, Phase D).
 *
 * Lists every app that has attempted the Engine handshake, grouped by the user's decision, with
 * the actions that decision allows. Callbacks receive the app's package name (on desktop, the
 * debug handshake's identifier).
 */
@Composable
fun ConnectedAppsPane(
    approvals: List<AppApprovalRecord>,
    isEngineRunning: Boolean,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onUndoDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
    notRunningText: String = "The Engine is not running. Start it from Home to manage app access.",
) {
    val pending = approvals.filter { it.status == AppApprovalStatus.PENDING }
    val approved = approvals.filter { it.status == AppApprovalStatus.APPROVED }
    val denied = approvals.filter { it.status == AppApprovalStatus.DENIED }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!isEngineRunning) {
            item { EmptyNote(notRunningText) }
            return@LazyColumn
        }

        if (pending.isNotEmpty()) {
            item { SectionTitle("Approval Pending", Color(0xFFEAB308)) }
            items(pending, key = { it.packageName }) { app ->
                AppCard(app) {
                    Button(
                        onClick = { onApprove(app.packageName) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    ) { Text("Approve", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { onDeny(app.packageName) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Deny", fontSize = 12.sp, color = Color(0xFFEF4444)) }
                }
            }
        }

        item { SectionTitle("Approved", MaterialTheme.colorScheme.primary) }
        if (approved.isEmpty()) {
            item {
                EmptyNote(
                    if (approvals.isEmpty()) "No app has asked to use the Engine yet."
                    else "No approved apps."
                )
            }
        } else {
            items(approved, key = { it.packageName }) { app ->
                AppCard(app) {
                    OutlinedButton(
                        onClick = { onRevoke(app.packageName) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Revoke access", fontSize = 12.sp) }
                }
            }
        }

        if (denied.isNotEmpty()) {
            item { SectionTitle("Denied", Color(0xFFEF4444)) }
            items(denied, key = { it.packageName }) { app ->
                AppCard(app) {
                    OutlinedButton(
                        onClick = { onUndoDeny(app.packageName) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Undo denial", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AppCard(app: AppApprovalRecord, actions: @Composable RowScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(app.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                app.packageName,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Last handshake ${formatIsoAgo(app.lastSeenAt)} · ${app.attemptCount} handshake" +
                    (if (app.attemptCount == 1) "" else "s") +
                    " · first seen ${formatIsoAgo(app.firstSeenAt)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

private fun formatIsoAgo(iso: String): String = try {
    formatElapsed(System.currentTimeMillis() - java.time.Instant.parse(iso).toEpochMilli())
} catch (_: Exception) {
    "unknown"
}
