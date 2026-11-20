package fi.italeino.aidos.engine.control

import fi.italeino.aidos.engine.usage.EngineUsageSnapshot
import kotlinx.serialization.Serializable

/**
 * The status group of `EngineControl` (RFC-0115, D61): is the Engine up, on which port, which models are resident,
 * how many peers are linked. Apps come from [AppsControl]; memory and disk are read from the machine the UI is on,
 * which is the same machine for a loopback attach.
 */
interface StatusControl {
    suspend fun status(): EngineStatusInfo
}

@Serializable
data class ResidentModelInfo(val id: String, val displayName: String, val quantization: String, val loadedAgoMs: Long)

/** [stateName] is the lifecycle name shown under the ON/OFF label, already including the port when it is serving. */
@Serializable
data class EngineStatusInfo(
    val running: Boolean,
    val stateName: String,
    val residentModels: List<ResidentModelInfo> = emptyList(),
    val linkedPeerCount: Int = 0,
    val usage: EngineUsageSnapshot? = null,
)
