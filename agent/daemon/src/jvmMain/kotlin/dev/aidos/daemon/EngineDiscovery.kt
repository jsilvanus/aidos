package dev.aidos.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * How the Agent runtime finds a running Engine (RFC-0115 phase 1): the Engine writes `~/.aidos/engine.json`
 * (owner-only) while it is up. The environment variables `AIDOS_ENGINE_URL` and `AIDOS_ENGINE_TOKEN` still win,
 * so an explicit setting is never overridden by a stale file.
 *
 * A file whose `pid` is no longer a live process is ignored: the Engine crashed and did not clean up.
 */
class EngineDiscovery(
    private val file: File = File(System.getProperty("user.home"), ".aidos/engine.json"),
    private val env: (String) -> String? = System::getenv,
    private val isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
) {
    private fun read(): JsonObject? {
        if (!file.isFile) return null
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        if (o["status"]?.jsonPrimitive?.contentOrNull != "READY") return null
        val pid = o["pid"]?.jsonPrimitive?.longOrNull
        if (pid != null && !isAlive(pid)) return null
        return o
    }

    fun baseUrl(): String? = env("AIDOS_SYSTEMONE_URL") ?: env("AIDOS_ENGINE_URL") ?: read()?.get("baseUrl")?.jsonPrimitive?.contentOrNull

    fun token(): String? = env("AIDOS_ENGINE_TOKEN") ?: read()?.get("token")?.jsonPrimitive?.contentOrNull
}
