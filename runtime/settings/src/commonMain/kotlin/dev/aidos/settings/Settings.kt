package dev.aidos.settings


/**
 * The declared setting catalogue for the MVP (RFC-0036).
 *
 * Every setting is declared here once. Subsystems import from this object; there is no untyped
 * key-value store and no undeclared keys. An unknown key is an error, not an extension point.
 *
 * MVP scope: the settings needed by M1-G2. Post-MVP settings (workspace scope, aliases,
 * dynamic reconfiguration) are not declared yet — they come with the milestones that need them.
 */
object Settings {

    // ─── SECURITY class — user-scope only ───────────────────────────────────

    /**
     * Whether prompts may be sent to remote models (RFC-0023, RFC-0020).
     *
     * SECURITY because it controls whether user content leaves the device. A project that sets
     * this to ALLOW would silently route all sessions to remote providers — an egress control
     * that a cloned repository could disable.
     */
    val routingRemoteEgress: SettingDescriptor<EgressPolicy> = setting("routing.remote_egress") {
        scopeClass(ScopeClass.SECURITY)
        default(EgressPolicy.ASK)
        mostRestrictive(EgressPolicy.NEVER)
        description("Whether prompts may be sent to remote models. ASK requires explicit approval per Run.")
        codec(EnumCodec(EgressPolicy.entries.toTypedArray()))
    }

    /**
     * Minimum trust level required to allow HTTP (not HTTPS) egress (RFC-0042).
     *
     * SECURITY because HTTP MCP over loopback is safe on desktop and a credential-disclosure
     * path on Android — the schema's value is intentionally absent from project scope
     * (RFC-0055). Defaults to BLOCKED (never allow plaintext).
     */
    val networkAllowPlaintextHttp: SettingDescriptor<Boolean> = setting("network.allow_plaintext_http") {
        scopeClass(ScopeClass.SECURITY)
        default(false)
        mostRestrictive(false)
        description("Whether plaintext HTTP is permitted for egress. Disabled by default; HTTPS only.")
        codec(BooleanCodec)
    }

    // ─── SPEND class — user-scope only ──────────────────────────────────────

    /**
     * Maximum cost units a single Run may spend before it is terminated (RFC-0028).
     *
     * SPEND because a project setting this to MAX_VALUE would make budget enforcement
     * meaningless. Integer micro-currency; null means no ceiling beyond the Run default.
     */
    val budgetRunCostCeiling: SettingDescriptor<Int> = setting("budget.run_cost_ceiling") {
        scopeClass(ScopeClass.SPEND)
        default(10_000)  // conservative; users raise this explicitly
        description("Maximum cost units a single Run may spend before termination.")
        codec(RangedIntCodec(1..Int.MAX_VALUE))
    }

    /** Maximum model calls a single Run may make. */
    val budgetRunModelCallCeiling: SettingDescriptor<Int> = setting("budget.run_model_call_ceiling") {
        scopeClass(ScopeClass.SPEND)
        default(8)
        description("Maximum model calls a single Run may make.")
        codec(RangedIntCodec(1..1000))
    }

    // ─── PROJECT_SAFE class — user, workspace, project ──────────────────────

    /**
     * Paths the knowledge engine never indexes (RFC-0015).
     *
     * PROJECT_SAFE because a project can only ever make indexing more exclusive — it cannot
     * cause Aidos to index paths that the user's own settings excluded.
     */
    val knowledgeExcludePaths: SettingDescriptor<List<String>> = setting("knowledge.exclude_paths") {
        scopeClass(ScopeClass.PROJECT_SAFE)
        default(listOf("node_modules/**", "build/**", ".git/**", ".aidos/**"))
        description("Glob patterns for paths the knowledge engine never indexes.")
        codec(StringListCodec())
    }

    /**
     * Paths the runtime treats as untrusted even though they are in the project (RFC-0027).
     *
     * PROJECT_SAFE because specifying untrusted_paths can only tighten authority — it makes
     * more content untrusted, never less. A project may declare its own test fixtures untrusted
     * without being able to remove user-specified taint roots.
     */
    val trustUntrustedPaths: SettingDescriptor<List<String>> = setting("trust.untrusted_paths") {
        scopeClass(ScopeClass.PROJECT_SAFE)
        default(emptyList())
        description("Paths treated as UNTRUSTED even if present in the project's Git history.")
        codec(StringListCodec())
    }

    // ─── PREFERENCE class — any scope ───────────────────────────────────────

    /** Retention window before aged artifacts are eligible for compaction (RFC-0056). */
    val retentionAgedDays: SettingDescriptor<Int> = setting("retention.aged_days") {
        scopeClass(ScopeClass.PREFERENCE)
        default(30)
        description("Days before an artifact is eligible for compaction.")
        codec(RangedIntCodec(1..3650))
    }

    /** Default model kind for new sessions (RFC-0020). */
    val modelDefaultKind: SettingDescriptor<String> = setting("model.default_kind") {
        scopeClass(ScopeClass.PREFERENCE)
        default("LLM")
        description("Default model kind used when a session does not specify one.")
        codec(StringCodec)
    }

    // ─── SPEECH/VOICE class — preference (M33, RFC-0057) ────────────────────

    /**
     * Which local TTS (text-to-speech) voice to use, if any (RFC-0057, RFC-0049).
     *
     * PREFERENCE because TTS model selection is a user preference, not a security boundary.
     * Empty string means no TTS model is installed or selected. Set to model ID (e.g. UUID) to enable.
     */
    val speechTtsModelId: SettingDescriptor<String> = setting("speech.tts_model_id") {
        scopeClass(ScopeClass.PREFERENCE)
        default("")
        description("Local TTS model ID for spoken summaries. Empty string if no TTS model installed.")
        codec(StringCodec)
    }

    /**
     * Whether to automatically speak a terminal summary when a Run finishes (RFC-0057).
     *
     * PREFERENCE because it controls user experience, not security. Defaults to false.
     */
    val speechSummaryOnFinish: SettingDescriptor<Boolean> = setting("speech.summary_on_finish") {
        scopeClass(ScopeClass.PREFERENCE)
        default(false)
        description("Automatically speak a summary when a Run completes or fails.")
        codec(BooleanCodec)
    }

    /**
     * Whether and how voice may be used to approve requests (RFC-0057, D26).
     *
     * PREFERENCE because voice approval is opt-in. OFF by default (most restrictive);
     * TIER1 allows benign approvals (read-only, in-project). TIER2 currently shares the
     * same benign gate as TIER1; readback verification is reserved for future work.
     */
    val speechVoiceApprovals: SettingDescriptor<VoiceApprovalsLevel> = setting("speech.voice_approvals") {
        scopeClass(ScopeClass.PREFERENCE)
        default(VoiceApprovalsLevel.OFF)
        description("Voice approval level: OFF (disabled), TIER1 (benign only), TIER2 (same benign gate as TIER1).")
        codec(EnumCodec(VoiceApprovalsLevel.entries.toTypedArray()))
    }

    /**
     * Whether to duck audio during voice notifications and pause during Q&A (RFC-0057).
     *
     * PREFERENCE because it controls audio focus behavior. Enabled by default for better
     * user experience while listening to voice summaries and answers.
     */
    val speechDuckOtherAudio: SettingDescriptor<Boolean> = setting("speech.duck_other_audio") {
        scopeClass(ScopeClass.PREFERENCE)
        default(true)
        description("Duck background audio during notifications, pause during voice Q&A.")
        codec(BooleanCodec)
    }

    // ─── Registry ───────────────────────────────────────────────────────────

    /** Every declared setting, for enumeration and CLI diagnostics. */
    val all: List<SettingDescriptor<*>> = listOf(
        routingRemoteEgress,
        networkAllowPlaintextHttp,
        budgetRunCostCeiling,
        budgetRunModelCallCeiling,
        knowledgeExcludePaths,
        trustUntrustedPaths,
        retentionAgedDays,
        modelDefaultKind,
        speechTtsModelId,
        speechSummaryOnFinish,
        speechVoiceApprovals,
        speechDuckOtherAudio,
    )

    fun forKey(key: String): SettingDescriptor<*>? = all.firstOrNull { it.key == key }
}

/**
 * Egress policy values for `routing.remote_egress` (RFC-0023).
 *
 * NEVER is most restrictive; ALLOW is least. `mostRestrictive` on the descriptor is NEVER, so
 * an invalid value in a SECURITY setting fails closed to NEVER, not to ASK.
 */
enum class EgressPolicy {
    /** No remote model calls, ever. Airplane mode by choice. */
    NEVER,

    /** Requires explicit per-Run approval from the user. */
    ASK,

    /** Remote calls are permitted without per-Run approval. */
    ALLOW,
}

/**
 * Voice approval levels for `speech.voice_approvals` (RFC-0057, D26).
 *
 * OFF is most restrictive (no voice approvals at all); TIER2 currently shares the same
 * benign gate as TIER1 and is reserved for future readback-verification enforcement.
 * Tier 3 (egress, tainted, new grants) never approves by voice, regardless of this setting.
 */
enum class VoiceApprovalsLevel {
    /** Voice approvals are disabled. Only tap/UI approval is allowed. */
    OFF,

    /** Benign approvals only: Read or Mutate(IN_PROJECT), not UNSAFE, TRUSTED, already granted. */
    TIER1,

    /** Currently equivalent to TIER1; reserved for future readback-verification enforcement. */
    TIER2,
}
