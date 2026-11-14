package fi.italeino.aidos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aidos.androidapp.approval.ApprovalCard as ApprovalDetails
import dev.aidos.androidapp.ui.diff.CommitPresenter
import dev.aidos.androidapp.ui.projects.AgentModelLocality
import dev.aidos.androidapp.ui.projects.AgentTaintPolicy
import dev.aidos.androidapp.ui.projects.ModelRoutingConfigState
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.projects.ProjectsUiState
import dev.aidos.androidapp.ui.runs.RunDisplayMetadata
import dev.aidos.androidapp.ui.runs.RunLaunchConfigState
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.runs.RunListUiState
import dev.aidos.androidapp.ui.sessions.SessionListPresenter
import dev.aidos.androidapp.ui.sessions.SessionListUiState
import dev.aidos.tcr.agent.TcrGuardianConfigUiModel
import dev.aidos.tcr.agent.TcrProjectModelConfigUiModel
import dev.aidos.tcr.agent.TcrRunConfigBar
import dev.aidos.tcr.agent.TcrRunLaunchConfigUiModel
import dev.aidos.tcr.agent.TcrProjectModelRoutingCard
import dev.aidos.tcr.theme.TcrColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(
    projectsPresenter: ProjectsPresenter,
    onBack: () -> Unit,
) {
    val projectsState by projectsPresenter.state.collectAsState()
    val globalConfig by projectsPresenter.globalConfig.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        GlobalSettingsContent(
            projectsPresenter = projectsPresenter,
            globalConfig = globalConfig,
            availableLlmModels = (projectsState as? ProjectsUiState.Ready)?.availableLlmModels.orEmpty(),
            availableDecisionModels = (projectsState as? ProjectsUiState.Ready)?.availableDecisionModels
                ?: listOf("laya-multilingual"),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSettingsScreen(
    projectId: String,
    projectsPresenter: ProjectsPresenter,
    onBack: () -> Unit,
) {
    val projectsState by projectsPresenter.state.collectAsState()
    val globalConfig by projectsPresenter.globalConfig.collectAsState()
    val projectConfigs by projectsPresenter.projectConfigs.collectAsState()
    val projectConfig = projectConfigs[projectId] ?: globalConfig
    val ready = projectsState as? ProjectsUiState.Ready
    val projectName = ready?.projects?.firstOrNull { it.id == projectId }?.name ?: projectId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Project Routing & Guardian") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TcrProjectModelRoutingCard(
                config = projectConfig.toTcrProjectConfig(
                    projectId = projectId,
                    projectName = projectName,
                    availableLlmModels = ready?.availableLlmModels.orEmpty(),
                    availableDecisionModels = ready?.availableDecisionModels ?: listOf("laya-multilingual"),
                ),
                onMainModelChange = { projectsPresenter.updateProjectMainModel(projectId, it) },
                onDefaultRunModelChange = { projectsPresenter.updateProjectDefaultRunModel(projectId, it) },
                onLocalityChange = { projectsPresenter.updateProjectLocality(projectId, it.toCommon()) },
                onGuardianPolicyChange = { projectsPresenter.updateProjectGuardianPolicy(projectId, it.toCommon()) },
                onDecisionModelChange = { projectsPresenter.updateProjectDecisionModel(projectId, it) },
                onGuardianLlmChange = { projectsPresenter.updateProjectGuardianLlm(projectId, it) },
            )

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow Main Model Run Override",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Let the Main Model select the per-run model automatically when unset.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = projectConfig.allowMainModelRunOverride,
                        onCheckedChange = { projectsPresenter.updateProjectAllowMainModelOverride(projectId, it) },
                    )
                }
            }
        }
    }
}

/**
 * Sessions screen showing session summary, project model routing, and run launch controls (M28, RFC-0050).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    projectId: String,
    sessionListPresenter: SessionListPresenter,
    onRunSelected: (runId: String) -> Unit,
    runListPresenter: RunListPresenter? = null,
    projectsPresenter: ProjectsPresenter? = null,
    onOpenProjectSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val state by sessionListPresenter.state.collectAsState()
    val projectsState = projectsPresenter?.state?.collectAsState()?.value as? ProjectsUiState.Ready
    val globalConfig = projectsPresenter?.globalConfig?.collectAsState()?.value ?: ModelRoutingConfigState()
    val projectConfigs = projectsPresenter?.projectConfigs?.collectAsState()?.value ?: emptyMap()
    val projectConfig = projectConfigs[projectId] ?: globalConfig
    val launchConfig = runListPresenter?.launchConfig?.collectAsState()?.value ?: RunLaunchConfigState()

    LaunchedEffect(projectId) {
        sessionListPresenter.load(projectId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (onOpenProjectSettings != null) {
                        TextButton(onClick = onOpenProjectSettings) {
                            Text("Routing & Guardian")
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
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

            if (projectsPresenter != null) {
                TcrProjectModelRoutingCard(
                    config = projectConfig.toTcrProjectConfig(
                        projectId = projectId,
                        projectName = projectsState?.projects?.firstOrNull { it.id == projectId }?.name ?: projectId,
                        availableLlmModels = projectsState?.availableLlmModels.orEmpty(),
                        availableDecisionModels = projectsState?.availableDecisionModels ?: listOf("laya-multilingual"),
                    ),
                    onMainModelChange = { projectsPresenter.updateProjectMainModel(projectId, it) },
                    onDefaultRunModelChange = { projectsPresenter.updateProjectDefaultRunModel(projectId, it) },
                    onLocalityChange = { projectsPresenter.updateProjectLocality(projectId, it.toCommon()) },
                    onGuardianPolicyChange = { projectsPresenter.updateProjectGuardianPolicy(projectId, it.toCommon()) },
                    onDecisionModelChange = { projectsPresenter.updateProjectDecisionModel(projectId, it) },
                    onGuardianLlmChange = { projectsPresenter.updateProjectGuardianLlm(projectId, it) },
                )
            }

            if (runListPresenter != null) {
                TcrRunConfigBar(
                    config = launchConfig.toTcrLaunchConfig(),
                    effectivePolicy = launchConfig.effectivePolicy(projectConfig).toTcr(),
                    effectiveModelId = launchConfig.effectiveModelId(projectConfig),
                    effectiveLocality = launchConfig.effectiveLocality(projectConfig).toTcr(),
                    availableLlmModels = projectsState?.availableLlmModels.orEmpty(),
                    onGuardianPolicyOverride = { policy ->
                        runListPresenter.setGuardianPolicyOverride(policy?.toCommon())
                    },
                    onRunModelOverride = runListPresenter::setRunModelOverride,
                    onLocalityOverride = { locality ->
                        runListPresenter.setLocalityOverride(locality?.toCommon())
                    },
                )
            }

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
            }
        }
    }
}

/**
 * Run detail screen showing the single-run glance summary, per-run launch overrides, and timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    projectId: String,
    sessionId: String,
    runListPresenter: RunListPresenter,
    projectsPresenter: ProjectsPresenter? = null,
    onOpenProjectSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val state by runListPresenter.state.collectAsState()
    val projectsState = projectsPresenter?.state?.collectAsState()?.value as? ProjectsUiState.Ready
    val globalConfig = projectsPresenter?.globalConfig?.collectAsState()?.value ?: ModelRoutingConfigState()
    val projectConfigs = projectsPresenter?.projectConfigs?.collectAsState()?.value ?: emptyMap()
    val projectConfig = projectConfigs[projectId] ?: globalConfig
    var promptText by remember { mutableStateOf("") }

    LaunchedEffect(sessionId) {
        runListPresenter.load(sessionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run Details") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (onOpenProjectSettings != null) {
                        TextButton(onClick = onOpenProjectSettings) {
                            Text("Project Settings")
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
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
                    TcrRunConfigBar(
                        config = current.launchConfig.toTcrLaunchConfig(),
                        effectivePolicy = current.launchConfig.effectivePolicy(projectConfig).toTcr(),
                        effectiveModelId = current.launchConfig.effectiveModelId(projectConfig),
                        effectiveLocality = current.launchConfig.effectiveLocality(projectConfig).toTcr(),
                        availableLlmModels = projectsState?.availableLlmModels.orEmpty(),
                        onGuardianPolicyOverride = { policy ->
                            runListPresenter.setGuardianPolicyOverride(policy?.toCommon())
                        },
                        onRunModelOverride = runListPresenter::setRunModelOverride,
                        onLocalityOverride = { locality ->
                            runListPresenter.setLocalityOverride(locality?.toCommon())
                        },
                    )

                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = promptText,
                                onValueChange = { promptText = it },
                                label = { Text("Message") },
                                placeholder = { Text("Send a prompt with the configured policy & locality...") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    val text = promptText.trim()
                                    if (text.isNotEmpty()) {
                                        runListPresenter.sendMessage(
                                            sessionId = sessionId,
                                            content = text,
                                            projectDefaults = projectConfig,
                                        )
                                        promptText = ""
                                    }
                                },
                                enabled = promptText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Send")
                            }
                            current.sendError?.let { errorText ->
                                Text(
                                    text = errorText,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    if (current.runs.isEmpty()) {
                        Text("No runs recorded yet")
                    } else {
                        current.runs.forEach { run ->
                            val metadata = current.runMetadata[run.id] ?: RunDisplayMetadata(
                                runId = run.id,
                                guardianPolicy = current.launchConfig.effectivePolicy(projectConfig),
                                modelId = current.launchConfig.effectiveModelId(projectConfig),
                                modelChosenByMainModel = false,
                                enforcedLocality = current.launchConfig.effectiveLocality(projectConfig),
                            )
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "${run.state} - ${run.stepCount} steps",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    RunProvenanceBadgeRow(metadata)
                                    Text("Started ${formatInstant(run.startedAt)}")
                                    val endedAt = run.endedAt
                                    if (endedAt != null) {
                                        Text("Ended ${formatInstant(endedAt)}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunProvenanceBadgeRow(metadata: RunDisplayMetadata) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val policyColor = if (metadata.guardianPolicy == AgentTaintPolicy.GUARDIAN) {
            TcrColors.VioletProtocol
        } else {
            TcrColors.AmberWarning
        }
        ProvenanceBadgeChip(
            text = "Policy: ${metadata.guardianPolicy.name}",
            color = policyColor,
        )

        if (metadata.modelId.isNotBlank()) {
            ProvenanceBadgeChip(
                text = "Model: ${metadata.modelId}",
                color = TcrColors.ElectricBlue,
            )
        }

        if (metadata.modelChosenByMainModel) {
            ProvenanceBadgeChip(
                text = "Main Model Chosen",
                color = TcrColors.ElectricBlue,
            )
        }

        metadata.enforcedLocality?.let { locality ->
            val localityColor = when (locality) {
                AgentModelLocality.LOCAL_ONLY -> TcrColors.EmeraldGreen
                AgentModelLocality.TRUSTED_CLUSTER -> TcrColors.ElectricBlue
                AgentModelLocality.ANY -> TcrColors.VioletProtocol
            }
            ProvenanceBadgeChip(
                text = locality.toTcr().label,
                color = localityColor,
            )
        }
    }
}

@Composable
private fun ProvenanceBadgeChip(
    text: String,
    color: Color,
) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
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

@Composable
fun CommitReviewScreen(
    @Suppress("UNUSED_PARAMETER") commitPresenter: CommitPresenter,
    onCommit: (String) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Commit review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Review changes and commit from this screen.")
            Button(onClick = { onCommit("Commit from Android UI") }) {
                Text("Commit")
            }
        }
    }
}

@Composable
fun EditorScreen(
    filePath: String,
    onSave: (String) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(filePath)
            Button(onClick = { onSave("Saved from Android UI editor") }) {
                Text("Save")
            }
        }
    }
}

private fun ModelRoutingConfigState.toTcrProjectConfig(
    projectId: String,
    projectName: String,
    availableLlmModels: List<String>,
    availableDecisionModels: List<String>,
): TcrProjectModelConfigUiModel = TcrProjectModelConfigUiModel(
    projectId = projectId,
    projectName = projectName,
    mainModelId = mainModelId,
    defaultRunModelId = defaultRunModelId,
    allowMainModelRunOverride = allowMainModelRunOverride,
    enforcedLocality = enforcedLocality.toTcr(),
    guardianConfig = TcrGuardianConfigUiModel(
        policy = guardianConfig.policy.toTcr(),
        decisionModelId = guardianConfig.decisionModelId,
        guardianLlmModelId = guardianConfig.guardianLlmModelId,
        availableDecisionModels = availableDecisionModels,
        availableLlmModels = availableLlmModels,
    ),
    availableLlmModels = availableLlmModels,
    availableDecisionModels = availableDecisionModels,
)

private fun RunLaunchConfigState.toTcrLaunchConfig(): TcrRunLaunchConfigUiModel =
    TcrRunLaunchConfigUiModel(
        guardianPolicyOverride = guardianPolicyOverride?.toTcr(),
        runModelOverride = runModelOverride,
        localityOverride = localityOverride?.toTcr(),
    )

private fun formatInstant(instant: Instant): String {
    val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDate.date} ${localDate.time.hour}:${localDate.time.minute.toString().padStart(2, '0')}"
}
