package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aidos.tcr.agent.AgentLocality
import dev.aidos.tcr.agent.TcrGuardianConfigUiModel
import dev.aidos.tcr.agent.TcrTaintApprovalPolicy
import dev.aidos.tcr.engine.HfTokenStatus as TcrHfTokenStatus
import dev.aidos.tcr.engine.RamBaselineState as TcrRamBaselineState
import dev.aidos.tcr.engine.SettingsContent as TcrSettingsContent
import dev.aidos.tcr.engine.SettingsState as TcrSettingsState

/**
 * Settings content (RFC-0103, Phase D): delegates core settings and global model routing /
 * Guardian UI to the authoritative `dev.aidos.tcr.engine.SettingsContent`, while preserving
 * token format validation and optional Engine-wide locality cap controls.
 */
@Composable
fun SettingsContent(
    state: SettingsState,
    dismissedSuggestionCount: Int,
    onStateChange: (SettingsState) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onRestoreSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    tokenStorageNote: String? = null,
    ramBaseline: RamBaselineState? = null,
    onSetRamBaseline: (Int?) -> Unit = {},
    onRefreshRamBaseline: () -> Unit = {},
    engineLocalityLimit: String? = null,
    onSetEngineLocalityLimit: ((String?) -> Unit)? = null,
    engineCacheDir: String? = null,
    globalMainModelId: String = "",
    globalDefaultRunModelId: String = "",
    globalLocality: AgentLocality = AgentLocality.ANY,
    guardianConfig: TcrGuardianConfigUiModel? = null,
    availableLlmModels: List<String> = emptyList(),
    onGlobalMainModelChange: (String) -> Unit = {},
    onGlobalDefaultRunModelChange: (String) -> Unit = {},
    onGlobalLocalityChange: (AgentLocality) -> Unit = {},
    onGlobalGuardianPolicyChange: (TcrTaintApprovalPolicy) -> Unit = {},
    onGlobalDecisionModelChange: (String) -> Unit = {},
    onGlobalGuardianLlmChange: (String) -> Unit = {},
    showModelRoutingSettings: Boolean = (
        guardianConfig != null ||
            globalMainModelId.isNotBlank() ||
            globalDefaultRunModelId.isNotBlank() ||
            globalLocality != AgentLocality.ANY
        ),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TcrSettingsContent(
            state = state.toTcr(),
            dismissedSuggestionCount = dismissedSuggestionCount,
            onStateChange = { updated -> onStateChange(updated.toHost()) },
            onSaveToken = { raw ->
                val token = raw.trim()
                val validationError = validateHfToken(token)
                if (validationError != null) {
                    onStateChange(state.copy(errorMessage = validationError, successMessage = null))
                } else {
                    onSaveToken(token)
                    onStateChange(
                        state.copy(
                            showTokenInput = false,
                            tokenInput = "",
                            hfTokenStatus = HfTokenStatus(
                                isConfigured = true,
                                lastValidatedMs = System.currentTimeMillis(),
                            ),
                            successMessage = "Token saved",
                            errorMessage = null,
                        )
                    )
                }
            },
            onClearToken = {
                onClearToken()
                onStateChange(
                    state.copy(
                        hfTokenStatus = HfTokenStatus(isConfigured = false),
                        showTokenInput = false,
                        tokenInput = "",
                        successMessage = "Token cleared",
                        errorMessage = null,
                    )
                )
            },
            onRestoreSuggestions = onRestoreSuggestions,
            tokenStorageNote = tokenStorageNote.orEmpty(),
            ramBaseline = ramBaseline?.toTcr(),
            onSetRamBaselineNow = ramBaseline?.let { baseline ->
                {
                    onRefreshRamBaseline()
                    onSetRamBaseline(baseline.currentUsedMB)
                }
            },
            onClearRamBaseline = ramBaseline?.let { { onSetRamBaseline(null) } },
            engineCacheDir = engineCacheDir,
            globalMainModelId = globalMainModelId,
            globalDefaultRunModelId = globalDefaultRunModelId,
            globalLocality = globalLocality,
            guardianConfig = guardianConfig,
            availableLlmModels = availableLlmModels,
            onGlobalMainModelChange = onGlobalMainModelChange,
            onGlobalDefaultRunModelChange = onGlobalDefaultRunModelChange,
            onGlobalLocalityChange = onGlobalLocalityChange,
            onGlobalGuardianPolicyChange = onGlobalGuardianPolicyChange,
            onGlobalDecisionModelChange = onGlobalDecisionModelChange,
            onGlobalGuardianLlmChange = onGlobalGuardianLlmChange,
            showModelRoutingSettings = showModelRoutingSettings,
        )

        if (onSetEngineLocalityLimit != null) {
            EngineLocalitySection(
                currentLimit = engineLocalityLimit,
                onSet = onSetEngineLocalityLimit,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun EngineLocalitySection(
    currentLimit: String?,
    onSet: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Inference locality",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Engine-wide cap for where inference may run. Requests and app limits can only narrow this further.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Current: ${when (currentLimit) {
                        "local_only" -> "local only"
                        "trusted_cluster" -> "trusted cluster"
                        "any" -> "any"
                        else -> "none (any)"
                    }}",
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onSet("local_only") }, modifier = Modifier.weight(1f)) { Text("Local only") }
                    OutlinedButton(onClick = { onSet("trusted_cluster") }, modifier = Modifier.weight(1f)) { Text("Cluster") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onSet("any") }, modifier = Modifier.weight(1f)) { Text("Any") }
                    OutlinedButton(onClick = { onSet(null) }, modifier = Modifier.weight(1f)) { Text("Clear cap") }
                }
            }
        }
    }
}

/** Null when [token] looks like a Hugging Face access token, else the message to show. */
fun validateHfToken(token: String): String? = when {
    token.isEmpty() -> "Token cannot be empty"
    !token.startsWith("hf_") || token.length < 12 -> "Token must look like a Hugging Face access token"
    else -> null
}

private fun SettingsState.toTcr(): TcrSettingsState = TcrSettingsState(
    hfTokenStatus = TcrHfTokenStatus(
        isConfigured = hfTokenStatus.isConfigured,
        lastValidatedMs = hfTokenStatus.lastValidatedMs,
    ),
    showTokenInput = showTokenInput,
    tokenInput = tokenInput,
    isLoading = isLoading,
    successMessage = successMessage,
    errorMessage = errorMessage,
)

private fun TcrSettingsState.toHost(): SettingsState = SettingsState(
    hfTokenStatus = HfTokenStatus(
        isConfigured = hfTokenStatus.isConfigured,
        lastValidatedMs = hfTokenStatus.lastValidatedMs,
    ),
    showTokenInput = showTokenInput,
    tokenInput = tokenInput,
    isLoading = isLoading,
    successMessage = successMessage,
    errorMessage = errorMessage,
)

private fun RamBaselineState.toTcr(): TcrRamBaselineState = TcrRamBaselineState(
    baselineUsedMB = baselineUsedMB,
    currentUsedMB = currentUsedMB,
    totalMB = totalMB,
)
