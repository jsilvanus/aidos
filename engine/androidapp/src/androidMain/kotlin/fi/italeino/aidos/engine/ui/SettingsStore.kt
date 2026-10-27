package fi.italeino.aidos.engine.ui

import android.content.Context
import dev.aidos.modelruntime.ModelRunSettings
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "aidos_prefs"
private const val PREFS_PROVIDERS = "aidos_engine_provider_state"
private const val KEY_PREFERRED_CONTEXT = "preferred_context_length"
private const val KEY_RAM_BASELINE_USED_MB = "ram_baseline_used_mb"
private const val KEY_HF_TOKEN = "hf_token"
private const val KEY_HF_TOKEN_VALIDATED_MS = "hf_token_validated_ms"
private const val KEY_GROUP_SHARING = "group_sharing_enabled"

enum class ProviderConfigStatus {
    ENABLED,
    CONFIGURED_DISABLED,
    NOT_CONFIGURED
}

data class ConfiguredRemoteModel(
    val modelId: String,
    val displayName: String,
    val isEnabled: Boolean = true
)

data class ProviderDetail(
    val id: String,
    val name: String,
    val status: ProviderConfigStatus,
    val apiKey: String? = null,
    val apiKeyValid: Boolean,
    val isEnabled: Boolean,
    val configuredModels: List<ConfiguredRemoteModel>
)

object SettingsStore {
    /** Device groups (RFC-0105): discover and link to other Engines on this network. Off by default. */
    fun isGroupSharingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GROUP_SHARING, false)

    fun setGroupSharingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GROUP_SHARING, enabled).apply()
    }

    /** Hugging Face API access token (RFC-0103 Phase D/E). */
    fun getHfToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun setHfToken(context: Context, token: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (token.isNullOrBlank()) {
            prefs.edit().remove(KEY_HF_TOKEN).remove(KEY_HF_TOKEN_VALIDATED_MS).apply()
        } else {
            prefs.edit()
                .putString(KEY_HF_TOKEN, token.trim())
                .putLong(KEY_HF_TOKEN_VALIDATED_MS, System.currentTimeMillis())
                .apply()
        }
    }

    fun getHfTokenValidatedMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_HF_TOKEN_VALIDATED_MS, 0L).takeIf { it > 0 }
    }

    fun setHfTokenValidatedMs(context: Context, ms: Long?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (ms != null && ms > 0) {
            prefs.edit().putLong(KEY_HF_TOKEN_VALIDATED_MS, ms).apply()
        } else {
            prefs.edit().remove(KEY_HF_TOKEN_VALIDATED_MS).apply()
        }
    }

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

    /** Known standard provider identifiers. */
    val standardProviderIds = listOf("openai", "anthropic", "custom")

    fun getDefaultProvider(providerId: String): ProviderDetail = when (providerId.lowercase()) {
        "openai" -> ProviderDetail(
            id = "openai",
            name = "OpenAI",
            status = ProviderConfigStatus.NOT_CONFIGURED,
            apiKey = null,
            apiKeyValid = false,
            isEnabled = false,
            configuredModels = listOf(
                ConfiguredRemoteModel("gpt-4o", "GPT-4o", true),
                ConfiguredRemoteModel("gpt-4o-mini", "GPT-4o Mini", true),
            ),
        )
        "anthropic" -> ProviderDetail(
            id = "anthropic",
            name = "Anthropic",
            status = ProviderConfigStatus.NOT_CONFIGURED,
            apiKey = null,
            apiKeyValid = false,
            isEnabled = false,
            configuredModels = listOf(
                ConfiguredRemoteModel("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet", true),
                ConfiguredRemoteModel("claude-3-5-haiku-latest", "Claude 3.5 Haiku", true),
            ),
        )
        else -> ProviderDetail(
            id = providerId,
            name = providerId.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            status = ProviderConfigStatus.NOT_CONFIGURED,
            apiKey = null,
            apiKeyValid = false,
            isEnabled = false,
            configuredModels = listOf(
                ConfiguredRemoteModel("default-model", "Default Model", true),
            ),
        )
    }

    /** Loads durable provider configuration from persistent storage. */
    fun getProvider(context: Context, providerId: String): ProviderDetail {
        val prefs = context.getSharedPreferences(PREFS_PROVIDERS, Context.MODE_PRIVATE)
        val default = getDefaultProvider(providerId)
        val apiKey = prefs.getString("${providerId}:api_key", null)
        val isConfigured = !apiKey.isNullOrBlank()
        val enabled = prefs.getBoolean("${providerId}:enabled", false) && isConfigured
        val apiKeyValid = prefs.getBoolean("${providerId}:api_key_valid", isConfigured)

        val modelsJson = prefs.getString("${providerId}:models_json", null)
        val models = if (!modelsJson.isNullOrBlank()) {
            try {
                val array = JSONArray(modelsJson)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    ConfiguredRemoteModel(
                        modelId = obj.getString("modelId"),
                        displayName = obj.getString("displayName"),
                        isEnabled = obj.optBoolean("isEnabled", true),
                    )
                }
            } catch (_: Exception) {
                default.configuredModels
            }
        } else {
            // Check legacy semicolon-separated format
            val legacy = prefs.getString("${providerId}:models", null)
            if (!legacy.isNullOrBlank()) {
                legacy.split(";").mapNotNull { row ->
                    val parts = row.split("|")
                    if (parts.size >= 3) {
                        ConfiguredRemoteModel(parts[0], parts[1], parts[2].toBooleanStrictOrNull() == true)
                    } else null
                }
            } else {
                default.configuredModels
            }
        }

        val status = when {
            enabled -> ProviderConfigStatus.ENABLED
            isConfigured -> ProviderConfigStatus.CONFIGURED_DISABLED
            else -> ProviderConfigStatus.NOT_CONFIGURED
        }

        return default.copy(
            status = status,
            apiKey = apiKey,
            apiKeyValid = apiKeyValid,
            isEnabled = enabled,
            configuredModels = models,
        )
    }

    /** Persists provider detail to persistent storage. */
    fun setProvider(context: Context, providerId: String, provider: ProviderDetail) {
        val prefs = context.getSharedPreferences(PREFS_PROVIDERS, Context.MODE_PRIVATE)
        val modelsArray = JSONArray()
        provider.configuredModels.forEach { model ->
            val obj = JSONObject()
            obj.put("modelId", model.modelId)
            obj.put("displayName", model.displayName)
            obj.put("isEnabled", model.isEnabled)
            modelsArray.put(obj)
        }

        prefs.edit()
            .putBoolean("${providerId}:enabled", provider.isEnabled)
            .putString("${providerId}:api_key", provider.apiKey)
            .putBoolean("${providerId}:api_key_valid", !provider.apiKey.isNullOrBlank())
            .putString("${providerId}:models_json", modelsArray.toString())
            .apply()
    }

    /** Returns all configured providers with durable state. */
    fun getAllProviders(context: Context): List<ProviderDetail> {
        return standardProviderIds.map { getProvider(context, it) }
    }
}
