package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Model detail / acquire screen (RFC-0103, RFC-0022).
 *
 * Reachable both from Home's Cookbook pane and as a deep link from client apps — RFC-0103
 * requires this route work standalone, since Aidos Agent (and any other client) deep-links here
 * rather than rendering its own download UI.
 *
 * Shows, in order: the per-context-length fit table RFC-0022 specifies (4k/16k/32k verdicts),
 * the model's license/terms-of-service at the point of deciding to download (not a blanket EULA
 * at first launch), and — if the model needs Hugging Face authentication — an inline token
 * prompt. Download is disabled until the license for this model+version is accepted.
 */
@Composable
fun ModelDetailScreen(modelId: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Model: $modelId")
        Text("(Context-length fit table — 4k/16k/32k verdicts — will appear here, RFC-0022)")
        Text("(License/ToS text for this model+version will appear here)")
        Text("(Hugging Face token prompt appears here only if this model is gated)")
        Button(onClick = { /* TODO(RFC-0103): disabled until license accepted; starts a
            resumable download via engine/downloads, digest-verified before use (RFC-0022) */ }) {
            Text("Download")
        }
        // TODO(RFC-0103): bind to LicenseAcceptance records and the vault (HF token) once
        // Engine Core's storage exists.
    }
}
