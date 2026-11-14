package fi.italeino.aidos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aidos.androidapp.ui.projects.AgentModelLocality
import dev.aidos.androidapp.ui.projects.AgentTaintPolicy
import dev.aidos.androidapp.ui.projects.ModelRoutingConfigState
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.projects.ProjectsUiState
import dev.aidos.tcr.agent.AgentLocality
import dev.aidos.tcr.agent.TcrGuardianConfigUiModel
import dev.aidos.tcr.agent.TcrGuardianModelsCard
import dev.aidos.tcr.agent.TcrLocalitySelector
import dev.aidos.tcr.agent.TcrTaintApprovalPolicy
import kotlinx.coroutines.launch

/**
 * Home screen showing inbox, projects, and global routing / Guardian settings (RFC-0050).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    projectsPresenter: ProjectsPresenter,
    onProjectSelected: (projectId: String) -> Unit,
    inboxItems: List<InboxItem> = emptyList(),
    onProjectSettingsSelected: (projectId: String) -> Unit = onProjectSelected,
    onOpenSettings: () -> Unit = {},
) {
    val projectsState by projectsPresenter.state.collectAsState()
    val globalConfig by projectsPresenter.globalConfig.collectAsState()

    LaunchedEffect(Unit) {
        projectsPresenter.loadProjects()
    }

    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aidos") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Global settings")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    },
                    text = { Text("Inbox") }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    },
                    text = { Text("Projects") }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(2) }
                    },
                    text = { Text("Settings") }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) { page ->
                when (page) {
                    0 -> InboxPane(inboxItems)
                    1 -> ProjectsPane(
                        projectsPresenter = projectsPresenter,
                        projectsState = projectsState,
                        onProjectSelected = onProjectSelected,
                        onProjectSettingsSelected = onProjectSettingsSelected,
                    )
                    2 -> GlobalSettingsContent(
                        projectsPresenter = projectsPresenter,
                        globalConfig = globalConfig,
                        availableLlmModels = (projectsState as? ProjectsUiState.Ready)?.availableLlmModels.orEmpty(),
                        availableDecisionModels = (projectsState as? ProjectsUiState.Ready)?.availableDecisionModels
                            ?: listOf("laya-multilingual"),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun GlobalSettingsContent(
    projectsPresenter: ProjectsPresenter,
    globalConfig: ModelRoutingConfigState,
    availableLlmModels: List<String>,
    availableDecisionModels: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Global Model Routing & Guardian Defaults",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = globalConfig.mainModelId,
                    onValueChange = projectsPresenter::updateGlobalMainModel,
                    label = { Text("Global Main Model") },
                    placeholder = { Text("Auto-routed if empty") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = globalConfig.defaultRunModelId,
                    onValueChange = projectsPresenter::updateGlobalDefaultRunModel,
                    label = { Text("Global Default Run Model") },
                    placeholder = { Text("Inherits Main Model if empty") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TcrLocalitySelector(
                    selected = globalConfig.enforcedLocality.toTcr(),
                    onSelect = { locality ->
                        if (locality != null) {
                            projectsPresenter.updateGlobalLocality(locality.toCommon())
                        }
                    },
                    label = "Default Enforced Running Place",
                )
            }
        }

        TcrGuardianModelsCard(
            config = TcrGuardianConfigUiModel(
                policy = globalConfig.guardianConfig.policy.toTcr(),
                decisionModelId = globalConfig.guardianConfig.decisionModelId,
                guardianLlmModelId = globalConfig.guardianConfig.guardianLlmModelId,
                availableDecisionModels = availableDecisionModels,
                availableLlmModels = availableLlmModels,
            ),
            onPolicyChange = { policy -> projectsPresenter.updateGlobalGuardianPolicy(policy.toCommon()) },
            onDecisionModelChange = projectsPresenter::updateGlobalDecisionModel,
            onGuardianLlmChange = projectsPresenter::updateGlobalGuardianLlm,
            title = "Global Guardian Safety Gate",
        )
    }
}

@Composable
private fun InboxPane(
    items: List<InboxItem> = emptyList(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Inbox — what needs you",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (items.isEmpty()) {
            Text("No pending approvals or alerts")
            return
        }

        items.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.actionLabel,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.summary)
                    }
                    Text(
                        text = "${item.projectName} · ${item.age}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

data class InboxItem(
    val actionLabel: String,
    val summary: String,
    val projectName: String,
    val age: String,
)

@Composable
private fun ProjectsPane(
    projectsPresenter: ProjectsPresenter,
    projectsState: ProjectsUiState,
    onProjectSelected: (projectId: String) -> Unit,
    onProjectSettingsSelected: (projectId: String) -> Unit,
) {
    when (projectsState) {
        is ProjectsUiState.Loading -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is ProjectsUiState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(projectsState.message)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { projectsPresenter.loadProjects() }) {
                    Text("Retry")
                }
            }
        }
        is ProjectsUiState.Ready -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Projects · what you're working on",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (projectsState.projects.isEmpty()) {
                    Text("No projects yet")
                    return
                }

                projectsState.projects.forEach { project ->
                    val projectConfig = projectsState.configForProject(project.id)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(project.name, fontWeight = FontWeight.SemiBold)
                            Text(project.description.ifBlank { "No description" })
                            Text(
                                text = "${project.sessionCount} sessions · ${projectConfig.guardianConfig.policy.name} · ${projectConfig.enforcedLocality.toTcr().label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onProjectSelected(project.id) },
                                ) {
                                    Text("Open")
                                }
                                OutlinedButton(
                                    onClick = { onProjectSettingsSelected(project.id) },
                                ) {
                                    Text("Routing & Guardian")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun AgentTaintPolicy.toTcr(): TcrTaintApprovalPolicy = when (this) {
    AgentTaintPolicy.MANUAL -> TcrTaintApprovalPolicy.MANUAL
    AgentTaintPolicy.GUARDIAN -> TcrTaintApprovalPolicy.GUARDIAN
}

internal fun TcrTaintApprovalPolicy.toCommon(): AgentTaintPolicy = when (this) {
    TcrTaintApprovalPolicy.MANUAL -> AgentTaintPolicy.MANUAL
    TcrTaintApprovalPolicy.GUARDIAN -> AgentTaintPolicy.GUARDIAN
}

internal fun AgentModelLocality.toTcr(): AgentLocality = when (this) {
    AgentModelLocality.LOCAL_ONLY -> AgentLocality.LOCAL_ONLY
    AgentModelLocality.TRUSTED_CLUSTER -> AgentLocality.TRUSTED_CLUSTER
    AgentModelLocality.ANY -> AgentLocality.ANY
}

internal fun AgentLocality.toCommon(): AgentModelLocality = when (this) {
    AgentLocality.LOCAL_ONLY -> AgentModelLocality.LOCAL_ONLY
    AgentLocality.TRUSTED_CLUSTER -> AgentModelLocality.TRUSTED_CLUSTER
    AgentLocality.ANY -> AgentModelLocality.ANY
}
