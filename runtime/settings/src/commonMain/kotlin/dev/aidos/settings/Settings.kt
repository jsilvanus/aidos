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
