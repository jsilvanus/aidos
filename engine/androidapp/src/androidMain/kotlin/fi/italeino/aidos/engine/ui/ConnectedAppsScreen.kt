package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Connected apps screen (RFC-0103).
 *
 * Every request into Engine already carries the bearer token minted for that caller at handshake
 * time, so per-app attribution is free to compute — this screen tallies what's already there.
 * Display names are resolved from PackageManager using the calling package the signature-
 * permission handshake already verified, not self-reported by the client.
 *
 * Session-scoped only in v1 (counters reset when Engine restarts) — persisted history across
 * restarts is Future Work, since Engine owns no storage that survives a restart beyond the vault
 * and license-acceptance records.
 */
@Composable
fun ConnectedAppsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Connected apps")
        Text("(Per-app request counts and last-active time will appear here, session-scoped)")
        // TODO(RFC-0103): bind to the handshake registry and per-client-token request counters
        // in Engine Core's dispatch path.
    }
}
