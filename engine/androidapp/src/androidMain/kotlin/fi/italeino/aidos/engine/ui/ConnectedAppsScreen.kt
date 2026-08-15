package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSize
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStatus

/**
 * Connected apps screen (RFC-0103).
 *
 * UI for managing which apps are approved to use Aidos Engine.
 * Shows three sections:
 * 1. Approved apps — with request count, last-active time, Revoke button
 * 2. Pending apps — first-time requests, with Approve/Deny buttons
 * 3. Denied apps — sticky denials, with Undo Deny button
 *
 * Updates in real-time as the user taps approve/deny/revoke buttons.
 *
 * RFC-0103: All decisions are persistent (stored via AppApprovalStore).
 * First handshake from an unknown app triggers a notification that deep-links here.
 */
@Composable
fun ConnectedAppsScreen(
    viewModel: ConnectedAppsViewModel,
    onClose: () -> Unit = {}
) {
    val approvals by viewModel.approvals.collectAsState(emptyList())
    val approved = approvals.filter { it.status == AppApprovalStatus.APPROVED }
    val pending = approvals.filter { it.status == AppApprovalStatus.PENDING }
    val denied = approvals.filter { it.status == AppApprovalStatus.DENIED }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Connected Apps",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Pending section: apps requesting access
            if (pending.isNotEmpty()) {
                item {
                    SectionHeader("Requesting Access (${pending.size})")
                }
                items(pending) { app ->
                    PendingAppCard(
                        app = app,
                        onApprove = { viewModel.approveApp(app.packageName) },
                        onDeny = { viewModel.denyApp(app.packageName) }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
            
            // Approved section: apps with active access
            if (approved.isNotEmpty()) {
                item {
                    SectionHeader("Approved (${approved.size})")
                }
                items(approved) { app ->
                    ApprovedAppCard(
                        app = app,
                        onRevoke = { viewModel.revokeApproval(app.packageName) }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
            
            // Denied section: apps user rejected
            if (denied.isNotEmpty()) {
                item {
                    SectionHeader("Denied (${denied.size})")
                }
                items(denied) { app ->
                    DeniedAppCard(
                        app = app,
                        onUndoDeny = { viewModel.undoDenyApp(app.packageName) }
                    )
                }
            }
            
            // Empty state
            if (approvals.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No connected apps yet.\n\nWhen an app requests access to Aidos Engine, it will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun PendingAppCard(
    app: AppApprovalRecord,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            
            Text(
                "This app wants to use Aidos Engine.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text("Approve")
                }
                Button(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Deny")
                }
            }
        }
    }
}

@Composable
private fun ApprovedAppCard(
    app: AppApprovalRecord,
    onRevoke: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                app.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    "Requests: ${app.requestCount}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    "Last active: ${formatTime(app.lastSeenAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            
            Button(
                onClick = onRevoke,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Revoke Access")
            }
        }
    }
}

@Composable
private fun DeniedAppCard(
    app: AppApprovalRecord,
    onUndoDeny: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                app.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Text(
                "Access denied",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Button(
                onClick = onUndoDeny,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Undo Denial")
            }
        }
    }
}

private fun formatTime(timestamp: String): String {
    // TODO: Properly format timestamp. For now, return a friendly abbreviation.
    return "just now"
}

