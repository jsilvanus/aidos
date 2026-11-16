package fi.italeino.aidos.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.engine.EngineState
import dev.aidos.tcr.engine.ModelsPaneActions as TcrModelsPaneActions
import dev.aidos.tcr.engine.ModelsPaneState as TcrModelsPaneState
import dev.aidos.tcr.engine.ModelsTabs as TcrModelsTabs

data class ProviderSummary(
    val id: String,
    val name: String,
    val statusText: String,
    val isEnabled: Boolean,
    val isConfigured: Boolean,
    val modelCount: Int,
)
/** What the Models tabs show; each host fills it from its own ViewModel. */
data class ModelsPaneState(
    val engineState: EngineState = EngineState.STARTING,
    val localModels: List<CookbookModel> = emptyList(),
    val isLoading: Boolean = false,
    val cookbookModels: List<CookbookModel> = emptyList(),
    val isSearching: Boolean = false,
    val suggestions: List<SuggestionUi> = emptyList(),
    val providers: List<ProviderSummary> = emptyList(),
)
/** User actions from the Models tabs. Model ids are catalog / Hugging Face repo ids. */
class ModelsPaneActions(
    val onModelSelected: (String) -> Unit,
    val onDeleteModel: (String) -> Unit,
    val onSearch: (query: String, kind: ModelKind?, minContext: Int?, filters: ModelSearchFilters) -> Unit,
    val onInstallSuggestion: (String) -> Unit,
    val onDismissSuggestion: (String) -> Unit,
    val onProviderSelected: (String) -> Unit = {},
)
/**
 * Models tabs (RFC-0103, Phase D): binds the host's [ModelsPaneState] and [ModelsPaneActions] to the
 * TCR `ModelsTabs`, which owns all rendering (D46). Tabs: Local (installed), Cookbook (suggestions
 * plus Hugging Face search with fit scoring) and Providers.
 */
@Composable
fun ModelsTabs(
    state: ModelsPaneState,
    actions: ModelsPaneActions,
    modifier: Modifier = Modifier,
) {
    val tcrState = remember(state) { state.toTcr() }
    val tcrActions = remember(actions) { actions.toTcr() }
    TcrModelsTabs(state = tcrState, actions = tcrActions, modifier = modifier)
}

private fun ModelsPaneState.toTcr() = TcrModelsPaneState(
    engineState = engineState.toTcr(),
    localModels = localModels.map { it.toTcr() },
    isLoading = isLoading,
    cookbookModels = cookbookModels.map { it.toTcr() },
    isSearching = isSearching,
    suggestions = suggestions.map { it.toTcr() },
    providers = providers.map { it.toTcr() },
)

private fun ModelsPaneActions.toTcr() = TcrModelsPaneActions(
    onModelSelected = onModelSelected,
    onDeleteModel = onDeleteModel,
    onSearch = { query, kind, minContext, filters -> onSearch(query, kind?.toHost(), minContext, filters.toHost()) },
    onInstallSuggestion = onInstallSuggestion,
    onDismissSuggestion = onDismissSuggestion,
    onProviderSelected = onProviderSelected,
)
