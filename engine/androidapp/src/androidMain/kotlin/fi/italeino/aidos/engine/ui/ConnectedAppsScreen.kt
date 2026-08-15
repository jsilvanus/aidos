package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Connected Apps screen (RFC-0103, Phase D).
 *
 * Which apps are currently using Aidos Engine:
 * - List of connected apps by name
 * - Per app: request counts by type (chat completions vs. embeddings), last-active time
 * - Session-scoped data (resets when Aidos Engine restarts) — no historical charts needed
 *
 * Reachable from HomeScreen menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedAppsScreen() {
    val state = remember {
        ConnectedAppsState(
            connectedApps = listOf(
                ConnectedApp(
                    packageName = "com.example.aidosagent",
                    displayName = "Aidos Agent",
                    lastActiveMs = System.currentTimeMillis() - 5_000,
                    requestMetrics = RequestMetrics(
                        chatCompletions = 47,
                        embeddings = 12,
                        transcriptions = 0
                    )
                ),
                ConnectedApp(
                    packageName = "com.example.testapp",
                    displayName = "Test App",
                    lastActiveMs = System.currentTimeMillis() - 60_000,
                    requestMetrics = RequestMetrics(
                        chatCompletions = 5,
                        embeddings = 0,
                        transcriptions = 0
                    )
                ),
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connected Apps") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Session-scoped metrics (reset when Aidos Engine restarts)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (state.connectedApps.isEmpty()) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "No apps currently connected",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.connectedApps) { app ->
                        ConnectedAppCard(app)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedAppCard(app: ConnectedApp) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        app.packageName,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    formatLastActive(app.lastActiveMs),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RequestMetricRow("Chat Completions", app.requestMetrics.chatCompletions)
                if (app.requestMetrics.embeddings > 0) {
                    RequestMetricRow("Embeddings", app.requestMetrics.embeddings)
                }
                if (app.requestMetrics.transcriptions > 0) {
                    RequestMetricRow("Transcriptions", app.requestMetrics.transcriptions)
                }
            }
        }
    }
}

@Composable
private fun RequestMetricRow(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            "$count",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
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

