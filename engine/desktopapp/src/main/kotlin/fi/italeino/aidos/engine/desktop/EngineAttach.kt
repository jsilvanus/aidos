package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.control.HttpLanControl
import fi.italeino.aidos.engine.control.AppsControl
import fi.italeino.aidos.engine.agent.AgentsControl
import fi.italeino.aidos.engine.agent.HttpAgentsControl
import fi.italeino.aidos.engine.control.ApprovalsControl
import fi.italeino.aidos.engine.control.HttpAppsControl
import fi.italeino.aidos.engine.control.HttpModelDetailControl
import fi.italeino.aidos.engine.control.HttpModelsControl
import fi.italeino.aidos.engine.control.ModelDetailControl
import fi.italeino.aidos.engine.control.HttpStatusControl
import fi.italeino.aidos.engine.control.ModelsControl
import fi.italeino.aidos.engine.control.HttpVaultControl
import fi.italeino.aidos.engine.control.VaultControl
import fi.italeino.aidos.engine.control.StatusControl
import fi.italeino.aidos.engine.control.HttpApprovalsControl
import fi.italeino.aidos.engine.control.HttpPeersControl
import fi.italeino.aidos.engine.control.HttpTunnelsControl
import fi.italeino.aidos.engine.control.PeersControl
import fi.italeino.aidos.engine.control.LanControl
import fi.italeino.aidos.engine.control.TunnelsControl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/** A running Engine other than this process, as `engine.json` describes it. */
data class RunningEngine(val baseUrl: String, val token: String, val pid: Long)

/**
 * Chooses between this process's own Engine and an Engine service that is already running (RFC-0115, D61).
 * An Engine is "already running" only when `engine.json` says READY, names a live process, and that process is
 * not this one (the app's own Engine writes the same file). Otherwise the in-process control is used.
 */
object EngineAttach {
    fun runningEngine(
        file: File = DesktopPaths.discoveryFile,
        selfPid: Long = ProcessHandle.current().pid(),
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    ): RunningEngine? {
        if (!file.isFile) return null
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        if (o["status"]?.jsonPrimitive?.contentOrNull != "READY") return null
        val pid = o["pid"]?.jsonPrimitive?.longOrNull ?: return null
        if (pid == selfPid || !isAlive(pid)) return null
        val url = o["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return null
        val token = o["token"]?.jsonPrimitive?.contentOrNull ?: return null
        return RunningEngine(url, token, pid)
    }

    /**
     * The token `engine.json` holds now. A client calls this once after a 401: the Engine mints a new admin token at
     * every start (RFC-0115), so a restarted Engine invalidates the one the client holds. Null when no other
     * process's Engine is running.
     */
    fun reloadToken(): String? = runningEngine()?.token

    fun lanControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): LanControl =
        running?.let { HttpLanControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: LocalLanControl(host.lanSharing)

    fun tunnelsControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): TunnelsControl =
        running?.let { HttpTunnelsControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: LocalTunnelsControl(host)

    fun peersControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): PeersControl =
        running?.let { HttpPeersControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: LocalPeersControl(host)

    fun approvalsControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): ApprovalsControl =
        running?.let { HttpApprovalsControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: LocalApprovalsControl(host)

    fun appsControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): AppsControl =
        running?.let { HttpAppsControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: LocalAppsControl(host)

    /**
     * The app starts its own Engine only when the user wants that ([autoStart]) and no Engine service already owns
     * `engine.json`: a second Engine would fight for the port and overwrite the discovery file (RFC-0115, D61).
     */
    fun shouldStartOwnEngine(autoStart: Boolean, running: RunningEngine? = runningEngine()): Boolean = autoStart && running == null

    fun statusControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): StatusControl =
        running?.let { HttpStatusControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: LocalStatusControl(host)

    fun vaultControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): VaultControl =
        running?.let { HttpVaultControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: host.vaultGate

    fun modelsControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): ModelsControl =
        running?.let { HttpModelsControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: host.modelsControl

    fun modelDetailControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): ModelDetailControl =
        running?.let { HttpModelDetailControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: host.modelDetailControl

    fun agentsControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): AgentsControl =
        running?.let { HttpAgentsControl(it.baseUrl, it.token, reloadToken = ::reloadToken) } ?: host.agentsControl
}
