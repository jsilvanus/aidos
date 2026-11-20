package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.control.EngineStatusInfo
import fi.italeino.aidos.engine.control.ResidentModelInfo
import fi.italeino.aidos.engine.control.StatusControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [StatusControl] over this process's host: the in-process path, and what the admin API serves from. */
class LocalStatusControl(private val host: DesktopEngineHost) : StatusControl {
    override suspend fun status(): EngineStatusInfo {
        val runtime = host.modelRuntime
        val resident = if (runtime == null) emptyList() else {
            val installedById = withContext(Dispatchers.IO) { host.catalogManager.listInstalled().getOrNull() }.orEmpty().associateBy { it.modelId }
            val now = System.currentTimeMillis()
            runtime.loaded().map { id ->
                val installed = installedById[id]
                ResidentModelInfo(id, installed?.userLabel ?: id, installed?.quantization ?: "unknown", runtime.loadedAtMillis(id)?.let { now - it } ?: 0L)
            }
        }
        val port = host.boundPort.value
        return EngineStatusInfo(
            running = host.isRunning.value,
            // EngineState has no "stopped" value (Android shows STARTING when off); say OFF here.
            stateName = when {
                host.isRunning.value && port != null -> "${host.state.value.name} · 127.0.0.1:$port"
                host.state.value == EngineState.STARTING && !host.isRunning.value -> "OFF"
                else -> host.state.value.name
            },
            residentModels = resident,
            linkedPeerCount = host.aidin.value?.peers?.value?.count { it.connected } ?: 0,
            usage = host.usageStore?.snapshot(),
        )
    }
}
