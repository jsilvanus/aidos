package dev.aidos.ui.palette

import dev.aidos.ui.state.MviPresenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CommandItemKind {
    COMMAND,
    INTENT_GOAL,
    TASK,
    FILE_PATH,
}

data class PaletteItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val kind: CommandItemKind,
    val action: () -> Unit,
)

data class CommandPaletteUiState(
    val isOpen: Boolean = false,
    val searchQuery: String = "",
    val results: List<PaletteItem> = emptyList(),
    val selectedIndex: Int = 0,
)

sealed interface CommandPaletteIntent {
    data object Open : CommandPaletteIntent
    data object Close : CommandPaletteIntent
    data class UpdateQuery(val query: String) : CommandPaletteIntent
    data object SelectNext : CommandPaletteIntent
    data object SelectPrevious : CommandPaletteIntent
    data object ExecuteSelected : CommandPaletteIntent
}

class CommandPalettePresenter(
    private val allCommands: List<PaletteItem>,
) : MviPresenter<CommandPaletteUiState, CommandPaletteIntent> {

    private val _state = MutableStateFlow(
        CommandPaletteUiState(results = allCommands)
    )
    override val state: StateFlow<CommandPaletteUiState> = _state.asStateFlow()

    override fun send(intent: CommandPaletteIntent) {
        when (intent) {
            is CommandPaletteIntent.Open -> _state.update { it.copy(isOpen = true) }
            is CommandPaletteIntent.Close -> _state.update { it.copy(isOpen = false, searchQuery = "") }
            is CommandPaletteIntent.UpdateQuery -> {
                val filtered = if (intent.query.isBlank()) {
                    allCommands
                } else {
                    allCommands.filter {
                        it.title.contains(intent.query, ignoreCase = true) ||
                                (it.subtitle?.contains(intent.query, ignoreCase = true) == true)
                    }
                }
                _state.update {
                    it.copy(
                        searchQuery = intent.query,
                        results = filtered,
                        selectedIndex = 0,
                    )
                }
            }
            is CommandPaletteIntent.SelectNext -> {
                _state.update { current ->
                    if (current.results.isEmpty()) current
                    else current.copy(selectedIndex = (current.selectedIndex + 1) % current.results.size)
                }
            }
            is CommandPaletteIntent.SelectPrevious -> {
                _state.update { current ->
                    if (current.results.isEmpty()) current
                    else {
                        val prev = if (current.selectedIndex - 1 < 0) current.results.size - 1 else current.selectedIndex - 1
                        current.copy(selectedIndex = prev)
                    }
                }
            }
            is CommandPaletteIntent.ExecuteSelected -> {
                val selected = _state.value.results.getOrNull(_state.value.selectedIndex)
                selected?.action?.invoke()
                _state.update { it.copy(isOpen = false, searchQuery = "") }
            }
        }
    }
}
