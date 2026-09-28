package fi.italeino.aidos.engine.ui

import android.content.Context

private const val PREFS = "aidos_prefs"
private const val KEY_PREFERRED_CONTEXT = "preferred_context_length"

object SettingsStore {
    fun getPreferredContextLength(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PREFERRED_CONTEXT, 4096)
    }

    fun setPreferredContextLength(context: Context, tokens: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_PREFERRED_CONTEXT, tokens).apply()
    }
}
