package fi.italeino.aidos.engine.ui

import android.content.Context
import dev.aidos.modelruntime.ModelRunSettings
import org.json.JSONObject

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

    private fun runSettingsKey(modelId: String) = "run_settings:$modelId"

    /** The model's saved run settings (RFC-0103), or defaults when none were saved or the record is unreadable. */
    fun getRunSettings(context: Context, modelId: String): ModelRunSettings {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(runSettingsKey(modelId), null)
            ?: return ModelRunSettings.DEFAULT
        return try {
            val o = JSONObject(raw)
            fun int(k: String) = if (o.has(k)) o.getInt(k) else null
            fun float(k: String) = if (o.has(k)) o.getDouble(k).toFloat() else null
            ModelRunSettings(
                int("contextLength"), int("threads"), int("batchSize"), int("gpuLayers"),
                float("temperature"), float("topP"), int("topK"),
            ).coerced()
        } catch (_: Exception) {
            ModelRunSettings.DEFAULT
        }
    }

    fun setRunSettings(context: Context, modelId: String, settings: ModelRunSettings) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = settings.coerced()
        if (s.isDefault) {
            prefs.edit().remove(runSettingsKey(modelId)).apply()
            return
        }
        val o = JSONObject()
        s.contextLength?.let { o.put("contextLength", it) }
        s.threads?.let { o.put("threads", it) }
        s.batchSize?.let { o.put("batchSize", it) }
        s.gpuLayers?.let { o.put("gpuLayers", it) }
        s.temperature?.let { o.put("temperature", it.toDouble()) }
        s.topP?.let { o.put("topP", it.toDouble()) }
        s.topK?.let { o.put("topK", it) }
        prefs.edit().putString(runSettingsKey(modelId), o.toString()).apply()
    }
}
