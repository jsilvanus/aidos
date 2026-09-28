package fi.italeino.aidos.engine.suggestions

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which suggested models the user has dismissed. Process-wide so that "Restore suggestions" in
 * Settings is reflected on the Models screen without a restart.
 */
class SuggestionPreferences private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _dismissed = MutableStateFlow(prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    fun dismiss(id: String) = save(_dismissed.value + id)

    fun restoreAll() = save(emptySet())

    private fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_DISMISSED, ids).apply()
        _dismissed.value = ids
    }

    companion object {
        private const val PREFS_NAME = "aidos_engine_ui_state"
        private const val KEY_DISMISSED = "dismissed_model_suggestions"

        @Volatile private var instance: SuggestionPreferences? = null

        fun get(context: Context): SuggestionPreferences =
            instance ?: synchronized(this) {
                instance ?: SuggestionPreferences(context.applicationContext).also { instance = it }
            }
    }
}
