package fi.italeino.aidos.engine.ui

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
import kotlinx.coroutines.launch

/**
 * Home screen showing status (what Engine is doing now) and the cookbook (what could run),
 * RFC-0103.
 *
 * Two panes with horizontal swipe between them, status first — mirroring Aidos Agent's
 * Inbox-before-Projects ordering: "what's happening" beats "what's possible". Same pager pattern
 * as fi.italeino.aidos.ui.HomeScreen — inherited, not reinvented.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onModelSelected: (modelId: String) -> Unit,
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
                    text = { Text("Status") }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    },
                    text = { Text("Cookbook") }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) { page ->
                when (page) {
                    0 -> StatusPane()
                    1 -> CookbookPane(onModelSelected)
                }
            }
        }
    }
}

/**
 * Status pane: resident models, memory budget, connected apps, in-flight downloads (RFC-0103).
 *
 * What Engine is doing right now — the question someone opening Engine actually has, the same
 * way Aidos Agent's inbox answers "what needs me?".
 */
@Composable
private fun StatusPane() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Status — what's running now")
        Text("(Resident models, memory budget, and connected apps will appear here)")
        // TODO(RFC-0103): bind to Engine Core's in-process admission/eviction state and the
        // handshake registry (Connected apps).
    }
}

/**
 * Cookbook pane: the RFC-0022 cookbook — label, kind, size, verdict against this device.
 */
@Composable
private fun CookbookPane(onModelSelected: (modelId: String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Cookbook — what could run here")
        Text("(Catalogue entries with cookbook verdicts will appear here, per RFC-0022)")
        // TODO(RFC-0103/RFC-0022): bind to the cookbook's computed verdicts; tapping an entry
        // calls onModelSelected(modelId) to navigate to EngineRoute.ModelDetail.
    }
}
