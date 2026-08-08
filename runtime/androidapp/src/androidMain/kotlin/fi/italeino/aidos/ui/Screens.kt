package fi.italeino.aidos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aidos.androidapp.ui.diff.CommitPresenter
import dev.aidos.androidapp.ui.diff.DiffUiState
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.sessions.SessionListPresenter

/**
 * Sessions screen showing session summary and run timeline (M28, RFC-0050).
 *
 * Opens on session summary (run counts by state, total files/lines, pending items).
 * Horizontal swipe walks through individual run summaries, newest first.
 * Tap on a run expands to show its steps as a timeline/graph.
 */
@Composable
fun SessionsScreen(
    projectId: String,
    sessionListPresenter: SessionListPresenter,
    onRunSelected: (runId: String) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Sessions for project: $projectId")
            Text("(Session summary and run timeline will appear here)")
        }
    }
}

/**
 * Run detail screen showing step timeline from the execution graph (M28, RFC-0050).
 *
 * Rendered from the same graph as the session view, not a chat transcript.
 * Model prose is collapsed by default (least valuable on a phone screen).
 * A step expands to show detail, tool call, and result.
 * Resume-after-eviction renders for free: the graph shows exactly where execution stopped
 * and what it is waiting for.
 */
@Composable
fun RunDetailScreen(
    projectId: String,
    sessionId: String,
    runListPresenter: RunListPresenter,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Run details for session: $sessionId")
            Text("(Execution graph timeline will appear here)")
        }
    }
}

/**
 * Approval/review card — the single most important UI component (M28, RFC-0050).
 *
 * One change, its reason, keep or reject.
 * This is the same component used for reviewing hunks at commit time (D25) —
 * a Preview.Diff mid-Run and a hunk at commit time are the same decision at different moments.
 *
 * Building it once halves the work and makes the two flows feel identical, which they should,
 * because they are.
 *
 * This component is stateful and handles decision submission through the RuntimeClient.
 */
@Composable
fun ApprovalCard(
    onApprove: () -> Unit,
    onReject: () -> Unit,
    isLoading: Boolean = false,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Text("Approval needed")
                Text("(Diff preview and approval controls will appear here)")
            }
        }
    }
}

/**
 * Diff/Commit review screen for hunk-by-hunk approval (M31, RFC-0050, D25).
 *
 * Read a diff, stage, write a message, commit — comfortably on a phone screen,
 * with one hand, on a bus.
 *
 * Shows the residue of what the user has already approved:
 * - Reviewed changes remain openable
 * - Unreviewed set gets attention
 * - Line-level review via the hunk card stack
 *
 * This screen is not optional — the previous RFC version had diff viewing as *Optional*,
 * which was wrong. It's the most important screen in the product.
 */
@Composable
fun CommitReviewScreen(
    commitPresenter: CommitPresenter,
    onCommit: (message: String) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Commit review")
            Text("(Reviewed/unreviewed changes and hunk stack will appear here)")
        }
    }
}

/**
 * Plain text editor for fixing single lines (M28, RFC-0050).
 *
 * Minimal by design — open, edit, save.
 * No completion, no refactoring, no multi-file operations.
 *
 * Every save is an ordinary Mutate through the broker, audited like any other change,
 * but with the user as subject (no approval asked, because the user is the authority).
 *
 * The editor cannot open a file outside the project (capability handle is project-scoped).
 */
@Composable
fun EditorScreen(
    filePath: String,
    onSave: (content: String) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text("Editor: $filePath")
            Text("(Text editing area will appear here)")
        }
    }
}
