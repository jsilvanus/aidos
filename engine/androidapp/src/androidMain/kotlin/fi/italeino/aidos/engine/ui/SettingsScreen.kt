package fi.italeino.aidos.engine.ui

import android.app.ActivityManager
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
 * Hugging Face token entry/status/clear, and restoring removed model suggestions.
 * All settings are persisted to [SettingsStore] and durable across process restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val suggestionPrefs = remember(context) { SuggestionPreferences.get(context) }
    val dismissedSuggestions by suggestionPrefs.dismissed.collectAsState()
    var ramNow by remember { mutableStateOf(readRam(appContext)) }
    var ramBaselineMb by remember { mutableStateOf(SettingsStore.getRamBaselineUsedMb(appContext)) }
    var state by remember {
        val hfToken = SettingsStore.getHfToken(appContext)
        mutableStateOf(
            SettingsState(
                hfTokenStatus = HfTokenStatus(
                    isConfigured = !hfToken.isNullOrBlank(),
                    lastValidatedMs = SettingsStore.getHfTokenValidatedMs(appContext)
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
                SettingsStore.setHfToken(appContext, token)
            },
            onClearToken = {
                SettingsStore.setHfToken(appContext, null)
            },
            onRestoreSuggestions = { suggestionPrefs.restoreAll() },
            ramBaseline = RamBaselineState(ramBaselineMb, ramNow.usedMB, ramNow.totalMB),
            onSetRamBaseline = {
                SettingsStore.setRamBaselineUsedMb(appContext, it)
                ramBaselineMb = SettingsStore.getRamBaselineUsedMb(appContext)
            },
            onRefreshRamBaseline = { ramNow = readRam(appContext) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

private fun readRam(context: Context): MemoryBudget {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    val mb = 1024L * 1024L
    return MemoryBudget(usedMB = ((info.totalMem - info.availMem) / mb).toInt(), totalMB = (info.totalMem / mb).toInt())
}
