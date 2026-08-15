package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Connected Apps screen (RFC-0103, Phase D).
 *
 * Displays apps currently connected to Aidos Engine and those pending approval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedAppsScreen() {
    val state = remember {
        mutableStateOf(
            ConnectedAppsState(
                connectedApps = listOf(
                    ConnectedApp(
                        packageName = "fi.italeino.aidos",
                        displayName = "Aidos Agent",
                        lastActiveMs = System.currentTimeMillis() - 5_000,
                        requestMetrics = RequestMetrics(chatCompletions = 47, embeddings = 12),
                    ),
                    ConnectedApp(
                        packageName = "com.example.testapp",
                        displayName = "Test App",
                        lastActiveMs = System.currentTimeMillis() - 60_000,
                        requestMetrics = RequestMetrics(chatCompletions = 5),
                    ),
                )
            )
        )
    }
    
    val pendingApps = remember {
        mutableStateListOf(
            ConnectedApp(
                packageName = "com.unknown.app",
                displayName = "New Assistant",
                lastActiveMs = System.currentTimeMillis(),
                requestMetrics = RequestMetrics(),
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connected Apps") })
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pendingApps.isNotEmpty()) {
                item {
                    Text(
                        "Approval Pending",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEAB308), // Amber
                    )
                }
                items(pendingApps) { app ->
                    PendingAppCard(
                        app = app,
                        onApprove = { pendingApps.remove(app) },
                        onDeny = { pendingApps.remove(app) },
                    )
                }
            }

            item {
                Text(
                    "Connected",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.value.connectedApps.isEmpty()) {
                item {
                    Text(
                        "No apps currently connected",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.value.connectedApps) { app ->
                    ConnectedAppCard(app)
                }
            }
        }
    }
}

@Composable
private fun PendingAppCard(
    app: ConnectedApp,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
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
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                ) {
                    Text("Approve", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Deny", fontSize = 12.sp, color = Color(0xFFEF4444))
                }
            }
        }
    }
}

@Composable
private fun ConnectedAppCard(app: ConnectedApp) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        app.packageName,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    formatLastActive(app.lastActiveMs),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Text(
                "Usage Breakdown",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                UsageRow("Chat Completions", app.requestMetrics.chatCompletions, "qwen-3b")
                if (app.requestMetrics.embeddings > 0) {
                    UsageRow("Embeddings", app.requestMetrics.embeddings, "nomic-embed")
                }
            }

            Text(
                "Last Activity",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Text(
                "Completed chat request at ${formatTimestamp(app.lastActiveMs)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageRow(label: String, count: Int, model: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "$count calls ($model)",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatLastActive(lastActiveMs: Long): String {
    val elapsedMs = System.currentTimeMillis() - lastActiveMs
    return when {
        elapsedMs < 1000 -> "just now"
        elapsedMs < 60_000 -> "${elapsedMs / 1000}s ago"
        elapsedMs < 3600_000 -> "${elapsedMs / 60_000}m ago"
        else -> "${elapsedMs / 3600_000}h ago"
    }
}

private fun formatTimestamp(ms: Long): String {
    val date = java.util.Date(ms)
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(date)
}
