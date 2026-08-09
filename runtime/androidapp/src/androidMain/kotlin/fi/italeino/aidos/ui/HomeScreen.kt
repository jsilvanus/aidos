package fi.italeino.aidos.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import kotlinx.coroutines.launch

/**
 * Home screen showing inbox (pending items) and projects (RFC-0050).
 *
 * Two panes with horizontal swipe between them. Inbox shows what needs the user;
 * projects shows what they're working on.
 *
 * This is built first on the critical path — the question a user has when they
 * pick up the phone is "what needs me?" (inbox), then "what am I working on?"
 * (projects).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    projectsPresenter: ProjectsPresenter,
    onProjectSelected: (projectId: String) -> Unit,
) {
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
                    0 -> InboxPane()
                    1 -> ProjectsPane(projectsPresenter, onProjectSelected)
                }
            }
        }
    }
}

/**
 * Inbox pane showing pending items across all projects (RFC-0050).
 *
 * Three items and a count — newest first. Nothing needing the user is dropped to fit three.
 * Tap to open the item's context (approval card, run detail, etc).
 */
@Composable
private fun InboxPane() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Inbox — what needs you")
        Text("(Pending items will appear here when integrated with EventStream)")
    }
}

/**
 * Projects pane showing project list with branch and activity (RFC-0050).
 *
 * Name, branch, Git status, last activity, and pending count.
 * Selection opens project detail screen.
 */
@Composable
private fun ProjectsPane(
    projectsPresenter: ProjectsPresenter,
    onProjectSelected: (projectId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Projects — what you're working on")
        Text("(Projects list will appear here via collectAsState)")
    }
}
