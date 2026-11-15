package fi.italeino.aidos.engine.ui

import android.content.Context
import dev.aidos.modelruntime.ModelRunSettings
import fi.italeino.aidos.engine.agent.AgentRoutingConfigDto
import fi.italeino.aidos.engine.agent.AgentRunLaunchConfigDto
import fi.italeino.aidos.engine.inference.Locality
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "aidos_prefs"
private const val PREFS_PROVIDERS = "aidos_engine_provider_state"
private const val KEY_PREFERRED_CONTEXT = "preferred_context_length"
private const val KEY_RAM_BASELINE_USED_MB = "ram_baseline_used_mb"
private const val KEY_HF_TOKEN = "hf_token"
private const val KEY_HF_TOKEN_VALIDATED_MS = "hf_token_validated_ms"
private const val KEY_GROUP_SHARING = "group_sharing_enabled"
private const val KEY_ENGINE_LOCALITY_LIMIT = "engine_locality_limit"
private const val KEY_GLOBAL_MAIN_MODEL_ID = "global_main_model_id"
private const val KEY_GLOBAL_DEFAULT_RUN_MODEL_ID = "global_default_run_model_id"
private const val KEY_GLOBAL_LOCALITY = "global_locality"
private const val KEY_GLOBAL_GUARDIAN_POLICY = "global_guardian_policy"
private const val KEY_GLOBAL_DECISION_MODEL_ID = "global_decision_model_id"
private const val KEY_GLOBAL_GUARDIAN_LLM_MODEL_ID = "global_guardian_llm_model_id"
private const val KEY_PROJECT_MAIN_MODEL_ID = "project_main_model_id"
private const val KEY_PROJECT_DEFAULT_RUN_MODEL_ID = "project_default_run_model_id"
private const val KEY_PROJECT_ALLOW_MAIN_MODEL_RUN_OVERRIDE = "project_allow_main_model_run_override"
private const val KEY_PROJECT_ENFORCED_LOCALITY = "project_enforced_locality"
private const val KEY_PROJECT_GUARDIAN_POLICY = "project_guardian_policy"
private const val KEY_PROJECT_DECISION_MODEL_ID = "project_decision_model_id"
private const val KEY_PROJECT_GUARDIAN_LLM_MODEL_ID = "project_guardian_llm_model_id"
private const val KEY_RUN_GUARDIAN_POLICY_OVERRIDE = "run_guardian_policy_override"
private const val KEY_RUN_MODEL_OVERRIDE = "run_model_override"
private const val KEY_RUN_LOCALITY_OVERRIDE = "run_locality_override"

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
    /** Engine-wide RFC-0104 cap. Null means no extra cap (effectively `any`). */
    fun getEngineLocalityLimit(context: Context): Locality =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE_LOCALITY_LIMIT, null)
            ?.let(Locality::parse)
            ?: Locality.ANY

    fun getEngineLocalityLimitRaw(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE_LOCALITY_LIMIT, null)

    fun setEngineLocalityLimit(context: Context, localityWire: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (localityWire == null) prefs.edit().remove(KEY_ENGINE_LOCALITY_LIMIT).apply()
        else prefs.edit().putString(KEY_ENGINE_LOCALITY_LIMIT, localityWire).apply()
    }

    private fun normalizePolicy(raw: String?): String =
        if (raw?.trim().equals("GUARDIAN", ignoreCase = true)) "GUARDIAN" else "MANUAL"

    private fun normalizeLocalityWire(raw: String?): String =
        raw?.let(Locality::parse)?.wire ?: "any"

    fun getGlobalMainModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_MAIN_MODEL_ID, "").orEmpty()

    fun setGlobalMainModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GLOBAL_MAIN_MODEL_ID, modelId.trim()).apply()
    }

    fun getGlobalDefaultRunModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_DEFAULT_RUN_MODEL_ID, "").orEmpty()

    fun setGlobalDefaultRunModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GLOBAL_DEFAULT_RUN_MODEL_ID, modelId.trim()).apply()
    }

    fun getGlobalLocalityWire(context: Context): String =
        normalizeLocalityWire(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GLOBAL_LOCALITY, "any")
        )

    fun setGlobalLocalityWire(context: Context, localityWire: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GLOBAL_LOCALITY, normalizeLocalityWire(localityWire)).apply()
    }

    fun getGlobalGuardianPolicy(context: Context): String =
        normalizePolicy(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GLOBAL_GUARDIAN_POLICY, "MANUAL")
        )

    fun setGlobalGuardianPolicy(context: Context, policy: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GLOBAL_GUARDIAN_POLICY, normalizePolicy(policy)).apply()
    }

    fun getGlobalDecisionModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_DECISION_MODEL_ID, "laya-multilingual")
            .orEmpty()
            .ifBlank { "laya-multilingual" }

    fun setGlobalDecisionModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GLOBAL_DECISION_MODEL_ID, modelId.trim().ifEmpty { "laya-multilingual" })
            .apply()
    }

    fun getGlobalGuardianLlmModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_GUARDIAN_LLM_MODEL_ID, "").orEmpty()

    fun setGlobalGuardianLlmModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GLOBAL_GUARDIAN_LLM_MODEL_ID, modelId.trim()).apply()
    }

    fun getProjectMainModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT_MAIN_MODEL_ID, "")
            .orEmpty()
            .ifBlank { getGlobalMainModelId(context) }

    fun setProjectMainModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECT_MAIN_MODEL_ID, modelId.trim()).apply()
    }

    fun getProjectDefaultRunModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT_DEFAULT_RUN_MODEL_ID, "")
            .orEmpty()
            .ifBlank { getGlobalDefaultRunModelId(context) }

    fun setProjectDefaultRunModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECT_DEFAULT_RUN_MODEL_ID, modelId.trim()).apply()
    }

    fun getProjectAllowMainModelRunOverride(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROJECT_ALLOW_MAIN_MODEL_RUN_OVERRIDE, true)

    fun setProjectAllowMainModelRunOverride(context: Context, allow: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PROJECT_ALLOW_MAIN_MODEL_RUN_OVERRIDE, allow).apply()
    }

    fun getProjectEnforcedLocalityWire(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT_ENFORCED_LOCALITY, null)
        return raw?.let(::normalizeLocalityWire) ?: getGlobalLocalityWire(context)
    }

    fun setProjectEnforcedLocalityWire(context: Context, localityWire: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECT_ENFORCED_LOCALITY, normalizeLocalityWire(localityWire)).apply()
    }

    fun getProjectGuardianPolicy(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT_GUARDIAN_POLICY, null)
        return raw?.let(::normalizePolicy) ?: getGlobalGuardianPolicy(context)
    }

    fun setProjectGuardianPolicy(context: Context, policy: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECT_GUARDIAN_POLICY, normalizePolicy(policy)).apply()
    }

    fun getProjectDecisionModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT_DECISION_MODEL_ID, "")
            .orEmpty()
            .ifBlank { getGlobalDecisionModelId(context) }

    fun setProjectDecisionModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECT_DECISION_MODEL_ID, modelId.trim()).apply()
    }

    fun getProjectGuardianLlmModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECT_GUARDIAN_LLM_MODEL_ID, "")
            .orEmpty()
            .ifBlank { getGlobalGuardianLlmModelId(context) }

    fun setProjectGuardianLlmModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROJECT_GUARDIAN_LLM_MODEL_ID, modelId.trim()).apply()
    }

    fun getRunGuardianPolicyOverride(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RUN_GUARDIAN_POLICY_OVERRIDE, null)
            ?.let(::normalizePolicy)

    fun setRunGuardianPolicyOverride(context: Context, policy: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (policy == null) edit.remove(KEY_RUN_GUARDIAN_POLICY_OVERRIDE)
        else edit.putString(KEY_RUN_GUARDIAN_POLICY_OVERRIDE, normalizePolicy(policy))
        edit.apply()
    }

    fun getRunModelOverride(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RUN_MODEL_OVERRIDE, "").orEmpty()

    fun setRunModelOverride(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RUN_MODEL_OVERRIDE, modelId.trim()).apply()
    }

    fun getRunLocalityOverrideWire(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RUN_LOCALITY_OVERRIDE, null)
            ?.let(Locality::parse)
            ?.wire

    fun setRunLocalityOverrideWire(context: Context, localityWire: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val parsed = localityWire?.let(Locality::parse)?.wire
        if (parsed == null) edit.remove(KEY_RUN_LOCALITY_OVERRIDE)
        else edit.putString(KEY_RUN_LOCALITY_OVERRIDE, parsed)
        edit.apply()
    }

    fun buildAgentRoutingConfig(context: Context): AgentRoutingConfigDto = AgentRoutingConfigDto(
        mainModelId = getProjectMainModelId(context),
        defaultRunModelId = getProjectDefaultRunModelId(context),
        allowMainModelRunOverride = getProjectAllowMainModelRunOverride(context),
        enforcedLocality = getProjectEnforcedLocalityWire(context),
        guardianPolicy = getProjectGuardianPolicy(context),
        decisionModelId = getProjectDecisionModelId(context),
        guardianLlmModelId = getProjectGuardianLlmModelId(context),
    )

    fun buildAgentRunLaunchConfig(context: Context): AgentRunLaunchConfigDto = AgentRunLaunchConfigDto(
        guardianPolicyOverride = getRunGuardianPolicyOverride(context),
        runModelOverride = getRunModelOverride(context),
        localityOverride = getRunLocalityOverrideWire(context),
    )

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
                if (o.has("useMmap")) o.getBoolean("useMmap") else null,
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
        s.useMmap?.let { o.put("useMmap", it) }
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
