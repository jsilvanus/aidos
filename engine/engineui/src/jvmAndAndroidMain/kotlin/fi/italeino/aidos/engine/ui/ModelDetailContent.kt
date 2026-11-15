package fi.italeino.aidos.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import fi.italeino.aidos.engine.EngineState
import dev.aidos.tcr.engine.ModelDetailActions as TcrModelDetailActions
import dev.aidos.tcr.engine.ModelDetailContent as TcrModelDetailContent

/** User actions from the model detail view. */
class ModelDetailActions(
    val onPreferredContextChange: (Int) -> Unit,
    val onOpenUrl: (String) -> Unit,
    /** Start (or retry) the download; the host clears any previous download error first. */
    val onStartDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onDelete: () -> Unit,
    val onEmbeddingInputChange: (String) -> Unit,
    val onTestEmbeddings: () -> Unit,
    /** Load when not resident, unload when resident. The host owns [ModelLoadingState]. */
    val onToggleLoad: () -> Unit,
    /** Null hides the Test Chat button (hosts without a chat screen). */
    val onTestChat: (() -> Unit)? = null,
    /** Null hides the per-kind test panel (chat, decisions, transcription, vision). */
    val onRunSelfTest: (() -> Unit)? = null,
    val onSelfTestTextChange: (String) -> Unit = {},
    val onSelfTestFileChange: (String) -> Unit = {},
    /** Null hides the run-settings section (hosts that cannot store them). */
    val onRunSettingsChange: ((RunSettingsUi) -> Unit)? = null,
    /** Null hides the "Reload to apply" button (hosts without a reload path). */
    val onReload: (() -> Unit)? = null,
)
/**
 * Model detail / acquire content (RFC-0103, RFC-0022, Phase D/E): binds the host's detail state and
 * [ModelDetailActions] to the TCR `ModelDetailContent`, which owns all rendering (D46). No scaffold:
 * Android wraps it in a screen with a back arrow, desktop in the right-hand panel.
 */
@Composable
fun ModelDetailContent(
    state: ModelDetailState,
    loadingState: ModelLoadingState,
    engineState: EngineState,
    preferredContext: Int,
    actions: ModelDetailActions,
    modifier: Modifier = Modifier,
    licenseAccepted: Boolean = false,
    onAcceptLicense: (() -> Unit)? = null,
) {
    val tcrState = remember(state) { state.toTcr() }
    val tcrLoading = remember(loadingState) { loadingState.toTcr() }
    val tcrActions = remember(actions) { actions.toTcr() }
    TcrModelDetailContent(
        state = tcrState,
        loadingState = tcrLoading,
        engineState = engineState.toTcr(),
        preferredContext = preferredContext,
        actions = tcrActions,
        modifier = modifier,
        licenseAccepted = licenseAccepted,
        onAcceptLicense = onAcceptLicense,
    )
}

private fun ModelDetailActions.toTcr() = TcrModelDetailActions(
    onPreferredContextChange = onPreferredContextChange,
    onOpenUrl = onOpenUrl,
    onStartDownload = onStartDownload,
    onCancelDownload = onCancelDownload,
    onDelete = onDelete,
    onEmbeddingInputChange = onEmbeddingInputChange,
    onTestEmbeddings = onTestEmbeddings,
    onToggleLoad = onToggleLoad,
    onTestChat = onTestChat,
    onRunSelfTest = onRunSelfTest,
    onSelfTestTextChange = onSelfTestTextChange,
    onSelfTestFileChange = onSelfTestFileChange,
    onRunSettingsChange = onRunSettingsChange?.let { change -> { settings: dev.aidos.tcr.engine.RunSettingsUi -> change(settings.toHost()) } },
    onReload = onReload,
)
