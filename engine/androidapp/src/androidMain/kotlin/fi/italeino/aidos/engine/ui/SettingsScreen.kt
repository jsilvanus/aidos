package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings screen: credential management (RFC-0103).
 *
 * Hugging Face token entry/status/clear in v1 — the natural home for provider credentials
 * generally if Aidos Engine later executes remote-provider calls (RFC-0103, "Remote providers
 * through Aidos Engine"). Nothing else lives here in v1: no account, no sync, no per-app trust
 * configuration (the trust model is signature-only and not user-configurable).
 */
@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Settings")
        Text("Hugging Face")
        Text("(Token entry, validity status, and clear action will appear here)")
        // TODO(RFC-0103): bind to the vault (EncryptedSharedPreferences-backed VaultEntry store);
        // validate via an HF whoami call before persisting.
    }
}
