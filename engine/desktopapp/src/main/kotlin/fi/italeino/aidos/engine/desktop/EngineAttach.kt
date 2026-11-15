package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.control.HttpLanControl
import fi.italeino.aidos.engine.control.LanControl
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

    fun lanControl(host: DesktopEngineHost, running: RunningEngine? = runningEngine()): LanControl =
        running?.let { HttpLanControl(it.baseUrl, it.token) } ?: LocalLanControl(host.lanSharing)
}
