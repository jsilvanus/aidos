package fi.italeino.aidos.engine.desktop

import dev.aidos.tcr.engine.BackgroundServiceUi
import dev.aidos.tcr.engine.BackgroundUiState
import fi.italeino.aidos.engine.desktop.install.InstallCli
import fi.italeino.aidos.engine.desktop.install.OsFamily
import fi.italeino.aidos.engine.desktop.install.ServiceDefinitions
import fi.italeino.aidos.engine.desktop.install.ServiceInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presenter for the "Run in background" pane (RFC-0115, D56): registers or removes the per-user Engine service and
 * tray icon through the same installer the command line uses. Machine-wide installs stay on the command line.
 */
class DesktopBackgroundModel(
    private val scope: CoroutineScope,
    private val installer: ServiceInstaller = ServiceInstaller(),
    private val os: OsFamily = OsFamily.current(),
    private val names: List<String> = listOf("engine", "tray"),
) {
    private val _state = MutableStateFlow(BackgroundUiState())
    val state: StateFlow<BackgroundUiState> = _state.asStateFlow()

    private fun plan(name: String) = ServiceDefinitions.plan(InstallCli.specFor(name, emptyList()), os)

    private fun statuses() = names.map { BackgroundServiceUi(label(it), installer.isInstalled(plan(it))) }

    init { refresh() }

    fun refresh() {
        scope.launch {
            val services = withContext(Dispatchers.IO) { statuses() }
            _state.update { it.copy(services = services, busy = false) }
        }
    }

    fun setEnabled(on: Boolean) {
        _state.update { it.copy(busy = true, error = null, message = null) }
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                var problem: String? = null
                for (n in if (on) names else names.reversed()) {
                    val p = plan(n)
                    val r = if (on) installer.install(p) else installer.uninstall(p)
                    if (r is ServiceInstaller.Result.Failed) { problem = "Could not ${if (on) "register" else "remove"} ${label(n)}: ${r.command.joinToString(" ")} exited ${r.exitCode}"; break }
                    if (r is ServiceInstaller.Result.NeedsElevation) { problem = r.hint; break }
                }
                problem
            }
            val services = withContext(Dispatchers.IO) { statuses() }
            _state.update {
                it.copy(
                    services = services, busy = false, error = failure,
                    message = if (failure == null) (if (on) "Registered. It starts the next time you sign in." else "Removed.") else null,
                )
            }
        }
    }

    private fun label(n: String) = when (n) { "engine" -> "Engine"; "tray" -> "Notification icon"; else -> n }
}
