package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Connected apps screen (RFC-0103: approval-based access control).
 *
 * Shows pending approval requests and previously-approved connected apps.
 * Display names are resolved from PackageManager using the calling package's verified UID,
 * not self-reported by the client.
 *
 * Session-scoped only in v1 (counters and approvals reset when Engine restarts) — persisted
 * history across restarts is Future Work, since Engine owns no storage that survives a restart
 * beyond the vault and license-acceptance records.
 *
 * TODO(RFC-0103): Implement approval request UI showing:
 * - Pending approval requests at the top (app name, tap to approve/deny)
 * - Previously-approved apps below (with session-scoped request counters)
 * - Revoke button for each approved app
 * - Bind to the ApprovalManager registry and per-client-token request counters
 *   in Engine Core's dispatch path.
 */
@Composable
fun ConnectedAppsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Connected apps")
        Text("Pending approval")
        Text("(Pending requests will appear here)")
        Text("")
        Text("Approved apps")
        Text("(Request counts and last-active time will appear here, session-scoped)")
        // TODO(RFC-0103): bind to the ApprovalManager and per-client-token request counters
        // in Engine Core's dispatch path. Show pending requests with approve/deny buttons,
        // and approved apps with usage stats and revoke options.
    }
}
