package fi.italeino.aidos.engine.ui

import android.content.Context

private const val PREFS = "aidos_prefs"
private const val KEY_PREFERRED_CONTEXT = "preferred_context_length"

private const val KEY_RAM_BASELINE_USED_MB = "ram_baseline_used_mb"

object SettingsStore {
    /** RAM in use (MB) once the OS and the user's usual apps run, with no model loaded; null if unset. */
    fun getRamBaselineUsedMb(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_RAM_BASELINE_USED_MB, 0).takeIf { it > 0 }
    }

    fun setRamBaselineUsedMb(context: Context, mb: Int?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (mb != null && mb > 0) edit.putInt(KEY_RAM_BASELINE_USED_MB, mb) else edit.remove(KEY_RAM_BASELINE_USED_MB)
        edit.apply()
    }

    fun getPreferredContextLength(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PREFERRED_CONTEXT, 4096)
    }

    fun setPreferredContextLength(context: Context, tokens: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_PREFERRED_CONTEXT, tokens).apply()
    }
}
