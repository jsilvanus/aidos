package fi.italeino.aidos.engine.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fi.italeino.aidos.engine.suggestions.SuggestionPreferences

/**
 * Settings screen (RFC-0103, Phase D).
 *
 * Intentionally minimal: Hugging Face token entry/status/clear, and restoring removed model
 * suggestions.
 * No account, no sync, no per-app trust configuration (trust model is signature-only
 * and not user-configurable).
 *
 * The natural home for provider credentials generally if Aidos Engine later
 * executes remote-provider calls (RFC-0103, "Remote providers through Aidos Engine").
 *
 * The content is the shared [SettingsContent]; this persists to SharedPreferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("aidos_engine_ui_state", Context.MODE_PRIVATE) }
    val suggestionPrefs = remember(context) { SuggestionPreferences.get(context) }
    val dismissedSuggestions by suggestionPrefs.dismissed.collectAsState()
    var state by remember {
        mutableStateOf(
            SettingsState(
                hfTokenStatus = HfTokenStatus(
                    isConfigured = !prefs.getString("hf_token", null).isNullOrBlank(),
                    lastValidatedMs = prefs.getLong("hf_token_validated_ms", 0L).takeIf { it > 0 }
                )
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { innerPadding ->
        SettingsContent(
            state = state,
            dismissedSuggestionCount = dismissedSuggestions.size,
            onStateChange = { state = it },
            onSaveToken = { token ->
                prefs.edit()
                    .putString("hf_token", token)
                    .putLong("hf_token_validated_ms", System.currentTimeMillis())
                    .apply()
            },
            onClearToken = {
                prefs.edit().remove("hf_token").remove("hf_token_validated_ms").apply()
            },
            onRestoreSuggestions = { suggestionPrefs.restoreAll() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}
