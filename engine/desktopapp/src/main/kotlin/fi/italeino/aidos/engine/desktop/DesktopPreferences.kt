package fi.italeino.aidos.engine.desktop

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
)

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
