package dev.aidos.retention

import dev.aidos.settings.Settings

/**
 * RFC-0056 retention classes. "Skeletons are permanent; flesh is reclaimable."
 */
enum class RetentionClass {
    /** Never deleted. */
    PERMANENT,

    /** Deleted only on explicit user action. */
    DURABLE,

    /** Compacted after a policy window. The only class [Compactor] touches. */
    AGED,

    /** Deleted as soon as it is no longer referenced. */
    EPHEMERAL,

    /** Deleted freely; rebuildable from other data. */
    DERIVED,
}

/**
 * The RFC-0056 default policy table: every kind of stored object and its class.
 *
 * [key] doubles as the `object_class` value in the `retention_policy` and `compactions` tables.
 * Rows with no Run-transcript entry: no transcript table exists yet (RFC-0008 keeps the
 * transcript in `attempts.output_snapshot`, which is covered below).
 */
enum class RetainedObject(val key: String, val retentionClass: RetentionClass) {
    RUN_SKELETON("run_skeleton", RetentionClass.PERMANENT),
    AUDIT_LOG("audit_log", RetentionClass.PERMANENT),
    CAPABILITY_HISTORY("capability_history", RetentionClass.PERMANENT),
    PROVENANCE_EDGES("provenance_edges", RetentionClass.PERMANENT),
    CONTENT_NODE_RECORD("content_node_record", RetentionClass.PERMANENT),
    KEPT_ARTIFACT_CONTENT("kept_artifact_content", RetentionClass.DURABLE),
    GIT_OBJECTS("git_objects", RetentionClass.DURABLE),
    INTENT_GRAPH("intent_graph", RetentionClass.DURABLE),
    ATTEMPT_INPUT_SNAPSHOT("attempt_input_snapshot", RetentionClass.AGED),
    ATTEMPT_OUTPUT_SNAPSHOT("attempt_output_snapshot", RetentionClass.AGED),
    PROMPT_PACKAGE("prompt_package", RetentionClass.AGED),
    EVENT_PAYLOAD("event_payload", RetentionClass.AGED),
    KNOWLEDGE_INDEX("knowledge_index", RetentionClass.DERIVED),
    MODEL_WEIGHTS("model_weights", RetentionClass.DERIVED),
    BUDGET_RESERVATION("budget_reservation", RetentionClass.EPHEMERAL),
    TEMP_FILE("temp_file", RetentionClass.EPHEMERAL),
    ;

    companion object {
        fun forKey(key: String): RetainedObject? = entries.firstOrNull { it.key == key }
        val aged: List<RetainedObject> = entries.filter { it.retentionClass == RetentionClass.AGED }
    }
}

/**
 * The effective retention policy for one project, resolved from settings (RFC-0036) and the
 * `retention_policy` table by [RetentionPolicyResolver]. Defaults are the RFC's.
 *
 * @param agedDays window for every AGED object ("compact after 30 days").
 * @param toolResultDays window for an Attempt output over [toolResultThresholdBytes]
 *   ("tool result payloads over 64KB: truncate-with-hash after 7 days").
 * @param softLimitBytes Stage 1 trigger: project footprint above this compacts AGED content older
 *   than [stage1AgeDays].
 * @param criticalFreeBytes Stage 3 trigger: device free space below this stops new Runs.
 * @param neverCompact "never compact this project", for work under audit obligations.
 * @param perObjectDays `retention_policy` overrides by [RetainedObject.key]; a null value means
 *   never compact that object.
 */
data class RetentionPolicy(
    val agedDays: Int = Settings.retentionAgedDays.default,
    val toolResultDays: Int = Settings.retentionToolResultDays.default,
    val toolResultThresholdBytes: Int = 64 * 1024,
    val softLimitBytes: Long = Settings.retentionSoftLimitMb.default * MB,
    val stage1AgeDays: Int = 7,
    val criticalFreeBytes: Long = 250 * MB,
    val neverCompact: Boolean = Settings.retentionNeverCompact.default,
    val perObjectDays: Map<String, Int?> = emptyMap(),
    /** Rows read, planned and committed together per batch (the redo window on interruption). */
    val batchSize: Int = 150,
    /** Batches per pass; a pass that hits this reports `needsAnotherPass`. */
    val maxBatchesPerPass: Int = 40,
) {
    /** The window for [obj] in days, or null for "never compact". */
    fun daysFor(obj: RetainedObject): Int? {
        if (neverCompact) return null
        return if (perObjectDays.containsKey(obj.key)) perObjectDays[obj.key] else agedDays
    }

    companion object {
        const val MB: Long = 1024L * 1024L
    }
}
