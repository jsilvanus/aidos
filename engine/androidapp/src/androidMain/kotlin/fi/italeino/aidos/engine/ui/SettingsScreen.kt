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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aidos.tcr.agent.AgentLocality
import dev.aidos.tcr.agent.TcrGuardianConfigUiModel
import dev.aidos.tcr.agent.TcrTaintApprovalPolicy
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.suggestions.SuggestionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var engineLocalityLimit by remember { mutableStateOf(SettingsStore.getEngineLocalityLimitRaw(appContext)) }
    var globalMainModelId by remember { mutableStateOf(SettingsStore.getGlobalMainModelId(appContext)) }
    var globalDefaultRunModelId by remember { mutableStateOf(SettingsStore.getGlobalDefaultRunModelId(appContext)) }
    var globalLocalityWire by remember { mutableStateOf(SettingsStore.getGlobalLocalityWire(appContext)) }
    var globalGuardianPolicyRaw by remember { mutableStateOf(SettingsStore.getGlobalGuardianPolicy(appContext)) }
    var globalDecisionModelId by remember { mutableStateOf(SettingsStore.getGlobalDecisionModelId(appContext)) }
    var globalGuardianLlmModelId by remember { mutableStateOf(SettingsStore.getGlobalGuardianLlmModelId(appContext)) }
    var availableLlmModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableDecisionModels by remember { mutableStateOf(listOf("laya-multilingual")) }

    LaunchedEffect(Unit) {
        val installed = withContext(Dispatchers.IO) {
            runCatching {
                EngineService.instance?.catalogManager?.listInstalled()?.getOrNull().orEmpty()
            }.getOrDefault(emptyList())
        }
        val llmIds = installed.map { it.modelId }.distinct()
        availableLlmModels = llmIds
        availableDecisionModels = (listOf("laya-multilingual") + installed.filter {
            it.quantization.equals("ONNX", ignoreCase = true) || it.modelId.contains("laya", ignoreCase = true)
        }.map { it.modelId }).distinct()
    }

    val syncRuntimeConfig = {
        EngineService.instance?.agentRuntime?.setRoutingConfig(
            SettingsStore.buildAgentRoutingConfig(appContext)
        )
    }

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
            engineLocalityLimit = engineLocalityLimit,
            onSetEngineLocalityLimit = { limit ->
                SettingsStore.setEngineLocalityLimit(appContext, limit)
                engineLocalityLimit = SettingsStore.getEngineLocalityLimitRaw(appContext)
            },
            globalMainModelId = globalMainModelId,
            globalDefaultRunModelId = globalDefaultRunModelId,
            globalLocality = AgentLocality.fromWire(globalLocalityWire),
            guardianConfig = TcrGuardianConfigUiModel(
                policy = if (globalGuardianPolicyRaw.equals("GUARDIAN", ignoreCase = true)) {
                    TcrTaintApprovalPolicy.GUARDIAN
                } else {
                    TcrTaintApprovalPolicy.MANUAL
                },
                decisionModelId = globalDecisionModelId,
                guardianLlmModelId = globalGuardianLlmModelId,
                availableDecisionModels = availableDecisionModels,
                availableLlmModels = availableLlmModels,
            ),
            availableLlmModels = availableLlmModels,
            onGlobalMainModelChange = { modelId ->
                SettingsStore.setGlobalMainModelId(appContext, modelId)
                globalMainModelId = SettingsStore.getGlobalMainModelId(appContext)
                syncRuntimeConfig()
            },
            onGlobalDefaultRunModelChange = { modelId ->
                SettingsStore.setGlobalDefaultRunModelId(appContext, modelId)
                globalDefaultRunModelId = SettingsStore.getGlobalDefaultRunModelId(appContext)
                syncRuntimeConfig()
            },
            onGlobalLocalityChange = { locality ->
                SettingsStore.setGlobalLocalityWire(appContext, locality.wire)
                globalLocalityWire = SettingsStore.getGlobalLocalityWire(appContext)
                syncRuntimeConfig()
            },
            onGlobalGuardianPolicyChange = { policy ->
                SettingsStore.setGlobalGuardianPolicy(appContext, policy.name)
                globalGuardianPolicyRaw = SettingsStore.getGlobalGuardianPolicy(appContext)
                syncRuntimeConfig()
            },
            onGlobalDecisionModelChange = { modelId ->
                SettingsStore.setGlobalDecisionModelId(appContext, modelId)
                globalDecisionModelId = SettingsStore.getGlobalDecisionModelId(appContext)
                syncRuntimeConfig()
            },
            onGlobalGuardianLlmChange = { modelId ->
                SettingsStore.setGlobalGuardianLlmModelId(appContext, modelId)
                globalGuardianLlmModelId = SettingsStore.getGlobalGuardianLlmModelId(appContext)
                syncRuntimeConfig()
            },
            showModelRoutingSettings = true,
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
