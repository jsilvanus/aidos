package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.ui.McpAdoptionFilter
import fi.italeino.aidos.engine.ui.McpServerUiModel
import fi.italeino.aidos.engine.ui.McpToolUiModel
import fi.italeino.aidos.engine.ui.McpTransportFilter
import fi.italeino.aidos.engine.ui.McpUiState
import fi.italeino.aidos.engine.ui.ParsedMcpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presenter for the Tools pane (RFC-0031): the real MCP broker through [McpManager]. Servers are the user's
 * registrations in `user.db` (shared with the CLI and the Agent), a catalog is fetched by really starting or connecting
 * to the server, and adoption is recorded for the Engine's default project. Blocking database and process work runs
 * off the UI thread; a failure shows in the pane's message, never as a crash.
 */
class DesktopMcpModel(
    @Suppress("UNUSED_PARAMETER") private val host: DesktopEngineHost,
    private val scope: CoroutineScope,
    private val manager: McpManager = McpManager.forDesktop(),
) {
    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        scope.launch {
            try {
                val servers = withContext(Dispatchers.IO) { manager.servers() }
                _state.update { it.copy(servers = servers, message = null) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Could not read the registered servers: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    private fun report(what: String, e: Exception) = _state.update { it.copy(isLoadingCatalog = false, message = "$what: ${e.message ?: e::class.simpleName}") }

    fun addServer(config: ParsedMcpServerConfig) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { manager.register(config) }
                refresh()
            } catch (e: Exception) {
                report("Could not register ${config.name}", e)
            }
        }
    }

    fun deleteServer(serverId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { manager.delete(serverId) }
                _state.update { curr ->
                    curr.copy(
                        selectedServerId = if (curr.selectedServerId == serverId) null else curr.selectedServerId,
                        selectedCatalog = if (curr.selectedServerId == serverId) emptyList() else curr.selectedCatalog,
                    )
                }
                refresh()
            } catch (e: Exception) {
                report("Could not remove $serverId", e)
            }
        }
    }

    fun selectServer(serverId: String?) {
        if (serverId == null) {
            _state.update { it.copy(selectedServerId = null, selectedCatalog = emptyList()) }
            return
        }
        _state.update { it.copy(selectedServerId = serverId, selectedCatalog = emptyList(), isLoadingCatalog = true, message = null) }
        scope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) { manager.catalog(serverId) }
                _state.update { curr ->
                    if (curr.selectedServerId != serverId) curr
                    else curr.copy(
                        selectedCatalog = catalog,
                        isLoadingCatalog = false,
                        servers = curr.servers.map { if (it.id == serverId) it.copy(toolCount = catalog.size, adoptedCount = catalog.count { t -> t.isAdopted }) else it },
                    )
                }
            } catch (e: Exception) {
                report("Could not read the tools of $serverId", e)
            }
        }
    }

    fun toggleAdoption(serverId: String, toolName: String, shouldAdopt: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { if (shouldAdopt) manager.adopt(serverId, toolName) else manager.unadopt(serverId, toolName) }
                _state.update { curr -> curr.withAdoption(serverId, shouldAdopt) { it.name == toolName } }
            } catch (e: Exception) {
                report("Could not change adoption of $toolName", e)
            }
        }
    }

    fun adoptAll(serverId: String) = setAll(serverId, true)
    fun unadoptAll(serverId: String) = setAll(serverId, false)

    private fun setAll(serverId: String, adopt: Boolean) {
        scope.launch {
            try {
                val names = _state.value.selectedCatalog.filter { it.serverId == serverId }.map { it.name }
                withContext(Dispatchers.IO) { names.forEach { if (adopt) manager.adopt(serverId, it) else manager.unadopt(serverId, it) } }
                _state.update { curr -> curr.withAdoption(serverId, adopt) { true } }
            } catch (e: Exception) {
                report("Could not change adoption", e)
            }
        }
    }

    private fun McpUiState.withAdoption(serverId: String, adopt: Boolean, match: (McpToolUiModel) -> Boolean): McpUiState {
        val now = java.time.Instant.now().toString()
        val catalog = selectedCatalog.map { if (it.serverId == serverId && match(it)) it.copy(isAdopted = adopt, adoptedAt = if (adopt) now else null) else it }
        val adopted = catalog.count { it.isAdopted }
        return copy(selectedCatalog = catalog, servers = servers.map { if (it.id == serverId) it.copy(adoptedCount = adopted, toolCount = catalog.size) else it })
    }

    fun refreshCatalog(serverId: String) {
        selectServer(serverId)
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setTransportFilter(filter: McpTransportFilter) {
        _state.update { it.copy(transportFilter = filter) }
    }

    fun setAdoptionFilter(filter: McpAdoptionFilter) {
        _state.update { it.copy(adoptionFilter = filter) }
    }
}
