package fi.italeino.aidos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aidos.androidapp.approval.ApprovalCard as ApprovalDetails
import dev.aidos.androidapp.ui.diff.CommitPresenter
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.runs.RunListUiState
import dev.aidos.androidapp.ui.sessions.SessionListPresenter
import dev.aidos.androidapp.ui.sessions.SessionListUiState
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Sessions screen showing session summary and run timeline (M28, RFC-0050).
 */
@Composable
fun SessionsScreen(
    projectId: String,
    sessionListPresenter: SessionListPresenter,
    onRunSelected: (runId: String) -> Unit,
) {
    val state by sessionListPresenter.state.collectAsState()

    LaunchedEffect(projectId) {
        sessionListPresenter.load(projectId)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Sessions for project: $projectId",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            when (val current = state) {
                is SessionListUiState.Loading -> {
                    CircularProgressIndicator()
                }
                is SessionListUiState.Error -> {
                    Text(current.message)
                }
                is SessionListUiState.Ready -> {
                    if (current.visibleSessions.isEmpty()) {
                        Text("No sessions yet")
                    } else {
                        current.visibleSessions.forEach { session ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRunSelected(session.id) },
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(session.name, fontWeight = FontWeight.SemiBold)
                                        Text(session.state.name)
                                    }
                                    Text("${session.runCount} runs - ${formatInstant(session.lastActiveAt)}")
                                }
                            }
                        }
                    }
                }
                null -> Text("Loading...")
            }
        }
    }
}

/**
 * Run detail screen showing the single-run glance summary and timeline.
 */
@Composable
fun RunDetailScreen(
    projectId: String,
    sessionId: String,
    runListPresenter: RunListPresenter,
) {
    val state by runListPresenter.state.collectAsState()

    LaunchedEffect(sessionId) {
        runListPresenter.load(sessionId)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Run details for session: $sessionId",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            when (val current = state) {
                is RunListUiState.Loading -> CircularProgressIndicator()
                is RunListUiState.Error -> Text(current.message)
                is RunListUiState.Ready -> {
                    if (current.runs.isEmpty()) {
                        Text("No runs recorded yet")
                    } else {
                        current.runs.forEach { run ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "${run.state} - ${run.stepCount} steps",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text("Started ${formatInstant(run.startedAt)}")
                                    if (run.endedAt != null) {
                                        Text("Ended ${formatInstant(run.endedAt)}")
                                    }
                                }
                            }
                        }
                    }
                }
                null -> Text("Loading...")
            }
        }
    }
}

@Composable
fun ApprovalCard(
    approval: ApprovalDetails? = null,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    isLoading: Boolean = false,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator()
                return@Scaffold
            }

            if (approval == null) {
                Text("Approval needed")
                Text("No approval is pending right now.")
                return@Scaffold
            }

            Text(
                text = if (approval.voiceSafe) "Voice-safe approval" else "Requires eyes on",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(approval.toolName)
            Text(approval.previewDescription)

            if (approval.untrustedSourceDescription != null) {
                Text("Source: ${approval.untrustedSourceDescription}")
            }
            if (approval.providerRetentionStatement != null) {
                Text(approval.providerRetentionStatement)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                    Text("Approve")
                }
                Button(onClick = onReject, modifier = Modifier.weight(1f)) {
                    Text("Reject")
                }
            }
        }
    }
}

private fun formatInstant(instant: Instant): String {
    val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDate.date} ${localDate.time.hour}:${localDate.time.minute.toString().padStart(2, '0')}"
}
