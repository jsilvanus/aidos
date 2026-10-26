package dev.aidos.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

/** One model a provider offers. [id] is the provider's own name for it (`llama3`, `qwen2.5:7b`). */
@Serializable
data class ProviderModel(
    val id: String,
    val contextWindow: Int = 8192,
)

/**
 * An OpenAI-compatible HTTP provider the Engine may call (RFC-0021), e.g. a local Ollama.
 *
 * [treatAsLocal] is a developer toggle: "serve this provider's models as `local` placement" so
 * that local-only requests (the default, D47) can use a provider running on this machine. It is
 * honoured only when [baseUrl] points at this machine ([isLoopbackHost]); a toggle on a LAN or
 * cloud URL is ignored and the models stay `remote`, because the toggle must not become a way to
 * label someone else's server as this device (RFC-0104).
 */
@Serializable
data class ProviderConfig(
    /** Lowercase letters, digits and `-`; the prefix of the public model ids (`ollama:llama3`). */
    val id: String,
    val baseUrl: String,
    val apiKey: String = "",
    val treatAsLocal: Boolean = false,
    val models: List<ProviderModel> = emptyList(),
) {
    init {
        require(Regex("[a-z0-9-]+").matches(id)) { "provider id must be lowercase letters, digits and '-': '$id'" }
    }

    /** Whether the toggle is requested and allowed. */
    val servedLocally: Boolean get() = treatAsLocal && isLoopbackHost(baseUrl)

    /** The id this provider's [model] has in the Engine's catalog. */
    fun publicId(model: ProviderModel): String = "$id:${model.id}"
}

@Serializable
private data class ProvidersFile(val providers: List<ProviderConfig> = emptyList())

object ProviderConfigs {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<ProviderConfig> = json.decodeFromString<ProvidersFile>(text).providers

    /** An absent file means no providers; an unreadable one is an error the host should surface. */
    fun load(file: File): List<ProviderConfig> = if (file.isFile) parse(file.readText()) else emptyList()
}

/** True when [baseUrl]'s host is this machine: `localhost`, `127.0.0.0/8` or `::1`. Anything unparseable is not. */
fun isLoopbackHost(baseUrl: String): Boolean {
    val host = try {
        URI(baseUrl.trim()).host
    } catch (_: Exception) {
        null
    } ?: return false
    val h = host.trim('[', ']').lowercase()
    return h == "localhost" || h == "::1" || Regex("127(\\.\\d{1,3}){3}").matches(h)
}
