package fi.italeino.aidos.engine.desktop

import dev.aidos.modelruntime.ModelRunSettings
import dev.aidos.modelruntime.RunSettingsSource
import fi.italeino.aidos.engine.inference.Locality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopPreferencesData(
    val dismissedSuggestions: Set<String> = emptySet(),
    val hfToken: String? = null,
    val hfTokenValidatedMs: Long? = null,
    val preferredContextLength: Int = 4096,
    /** Per-model llama.cpp run settings (RFC-0103), keyed by model id; absent means defaults. */
    val modelRunSettings: Map<String, StoredRunSettings> = emptyMap(),
    /** RAM in use (MB) once the OS and the user's usual apps run, with no model loaded; null if unset. */
    val ramBaselineUsedMb: Int? = null,
    /** Port to bind the loopback HTTP server on. 18080 by default; 0 means dynamic port. */
    val serverPort: Int = 18080,
    /** Automatically start the engine on application launch. */
    val autoStartEngine: Boolean = true,
    /**
     * Inference groups (RFC-0105): discover, pair with and link to other Engines on the local
     * network. Off by default; nothing listens or advertises until the user turns it on.
     */
    val groupSharingEnabled: Boolean = false,
    /** Engine-wide maximum placement allowed across all requests (RFC-0104); null means no limit (`any`). */
    val engineLocalityLimit: String? = null,

    // Routing and Guardian Preferences (RFC-0110)
    val globalMainModelId: String? = null,
    val globalDefaultRunModelId: String? = null,
    val globalLocality: String? = null,
    val globalGuardianPolicy: String? = null,
    val globalDecisionModelId: String? = null,
    val globalGuardianLlmModelId: String? = null,

    val projectMainModelId: String? = null,
    val projectDefaultRunModelId: String? = null,
    val projectAllowMainModelRunOverride: Boolean? = null,
    val projectEnforcedLocality: String? = null,
    val projectGuardianPolicy: String? = null,
    val projectDecisionModelId: String? = null,
    val projectGuardianLlmModelId: String? = null,

    val runGuardianPolicyOverride: String? = null,
    val runModelOverride: String? = null,
    val runLocalityOverride: String? = null,
)

/** [ModelRunSettings] as stored: the runtime module has no serialization plugin, so this mirrors it. */
@Serializable
data class StoredRunSettings(
    val contextLength: Int? = null,
    val threads: Int? = null,
    val batchSize: Int? = null,
    val gpuLayers: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
) {
    fun toSettings() = ModelRunSettings(contextLength, threads, batchSize, gpuLayers, temperature, topP, topK)

    companion object {
        fun of(s: ModelRunSettings) =
            StoredRunSettings(s.contextLength, s.threads, s.batchSize, s.gpuLayers, s.temperature, s.topP, s.topK)
    }
}

/**
 * Desktop stand-in for the Android app's SharedPreferences (SuggestionPreferences, SettingsStore
 * and the Settings screen's token prefs), persisted as one JSON file.
 *
 * The Hugging Face token is stored in plain text. CLAUDE.md asks for secure storage for secrets;
 * this is accepted only because the app is a local debug tool, and the Settings dialog says so.
 * (Like the Android app, nothing sends the token to Hugging Face yet; it is only kept.)
 */
class DesktopPreferences(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val _data = MutableStateFlow(read())
    val data: StateFlow<DesktopPreferencesData> = _data.asStateFlow()

    fun dismissSuggestion(id: String) = update { it.copy(dismissedSuggestions = it.dismissedSuggestions + id) }

    fun restoreSuggestions() = update { it.copy(dismissedSuggestions = emptySet()) }

    fun saveHfToken(token: String) =
        update { it.copy(hfToken = token, hfTokenValidatedMs = System.currentTimeMillis()) }

    fun clearHfToken() = update { it.copy(hfToken = null, hfTokenValidatedMs = null) }

    fun setPreferredContextLength(tokens: Int) = update { it.copy(preferredContextLength = tokens) }

    fun setServerPort(port: Int) = update { it.copy(serverPort = port) }

    fun setAutoStartEngine(enabled: Boolean) = update { it.copy(autoStartEngine = enabled) }

    fun setGroupSharingEnabled(enabled: Boolean) = update { it.copy(groupSharingEnabled = enabled) }

    fun setEngineLocalityLimit(localityWire: String?) = update {
        val normalized = localityWire?.let(Locality::parse)?.wire
        it.copy(engineLocalityLimit = normalized)
    }

    fun setGlobalMainModelId(id: String?) = update { it.copy(globalMainModelId = id) }
    fun setGlobalDefaultRunModelId(id: String?) = update { it.copy(globalDefaultRunModelId = id) }
    fun setGlobalLocality(locality: String?) = update { it.copy(globalLocality = locality) }
    fun setGlobalGuardianPolicy(policy: String?) = update { it.copy(globalGuardianPolicy = policy) }
    fun setGlobalDecisionModelId(id: String?) = update { it.copy(globalDecisionModelId = id) }
    fun setGlobalGuardianLlmModelId(id: String?) = update { it.copy(globalGuardianLlmModelId = id) }

    fun setProjectMainModelId(id: String?) = update { it.copy(projectMainModelId = id) }
    fun setProjectDefaultRunModelId(id: String?) = update { it.copy(projectDefaultRunModelId = id) }
    fun setProjectAllowMainModelRunOverride(allow: Boolean?) = update { it.copy(projectAllowMainModelRunOverride = allow) }
    fun setProjectEnforcedLocality(locality: String?) = update { it.copy(projectEnforcedLocality = locality) }
    fun setProjectGuardianPolicy(policy: String?) = update { it.copy(projectGuardianPolicy = policy) }
    fun setProjectDecisionModelId(id: String?) = update { it.copy(projectDecisionModelId = id) }
    fun setProjectGuardianLlmModelId(id: String?) = update { it.copy(projectGuardianLlmModelId = id) }

    fun setRunGuardianPolicyOverride(override: String?) = update { it.copy(runGuardianPolicyOverride = override) }
    fun setRunModelOverride(override: String?) = update { it.copy(runModelOverride = override) }
    fun setRunLocalityOverride(override: String?) = update { it.copy(runLocalityOverride = override) }

    fun engineLocality(): Locality = _data.value.engineLocalityLimit?.let(Locality::parse) ?: Locality.ANY

    /** What the runtime reads at load time; a model never configured runs with the defaults. */
    val runSettingsSource = RunSettingsSource { id -> runSettingsFor(id) }

    fun runSettingsFor(modelId: String): ModelRunSettings =
        _data.value.modelRunSettings[modelId]?.toSettings()?.coerced() ?: ModelRunSettings.DEFAULT

    fun setRunSettings(modelId: String, settings: ModelRunSettings) = update {
        val next = if (settings.isDefault) it.modelRunSettings - modelId
        else it.modelRunSettings + (modelId to StoredRunSettings.of(settings.coerced()))
        it.copy(modelRunSettings = next)
    }
    fun setRamBaselineUsedMb(mb: Int?) = update { it.copy(ramBaselineUsedMb = mb?.takeIf { v -> v > 0 }) }

    @Synchronized
    private fun update(transform: (DesktopPreferencesData) -> DesktopPreferencesData) {
        val next = transform(_data.value)
        if (next == _data.value) return
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(next))
        _data.value = next
    }

    private fun read(): DesktopPreferencesData = try {
        if (file.isFile) json.decodeFromString(file.readText()) else DesktopPreferencesData()
    } catch (_: Exception) {
        DesktopPreferencesData()
    }
}
