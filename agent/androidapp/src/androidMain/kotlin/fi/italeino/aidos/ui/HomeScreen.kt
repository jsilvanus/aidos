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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.projects.ProjectsUiState
import kotlinx.coroutines.launch

/**
 * Home screen showing inbox (pending items) and projects (RFC-0050).
 *
 * The app keeps the two-pane gesture grammar specified by RFC-0050 while wiring the
 * state-backed project list to the actual presenter instead of leaving placeholders.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    projectsPresenter: ProjectsPresenter,
    onProjectSelected: (projectId: String) -> Unit,
    inboxItems: List<InboxItem> = emptyList(),
) {
    val projectsState by projectsPresenter.state.collectAsState()

    LaunchedEffect(Unit) {
        projectsPresenter.loadProjects()
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                    text = { Text("Inbox") }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    },
                    text = { Text("Projects") }
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
                    1 -> ProjectsPane(projectsPresenter, projectsState, onProjectSelected)
                }
            }
        }
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Text(project.name, fontWeight = FontWeight.SemiBold)
                            Text(project.description.ifBlank { "No description" })
                            Text("${project.sessionCount} sessions")
                            Button(
                                onClick = { onProjectSelected(project.id) },
                            ) {
                                Text("Open")
                            }
                        }
                    }
                }
            }
        }
    }
}
