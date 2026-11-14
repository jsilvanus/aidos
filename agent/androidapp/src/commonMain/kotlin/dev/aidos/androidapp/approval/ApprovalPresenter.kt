package dev.aidos.androidapp.approval

import dev.aidos.api.RuntimeEvent

/**
 * Approval, preview, and memory review (M30, RFC-0018, RFC-0027, RFC-0030, RFC-0026).
 */
data class ApprovalCard(
    val approvalId: String,
    val toolName: String,
    val previewDescription: String,
    val untrustedSourceDescription: String?,
    val providerRetentionStatement: String?,
    val kind: ApprovalKind,
    val voiceSafe: Boolean = false,
)

enum class ApprovalKind {
    MUTATION,
    EGRESS,
    ESCALATION,
}

sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision
    data object Denied : ApprovalDecision
}

object ApprovalPresenter {

    private val SAFE_READ_ONLY_TOOLS = setOf(
        "fs_read",
        "fs.read",
        "fs:read",
        "read_file",
        "file_read",
        "fs_list",
        "fs.list",
        "fs:list",
        "list_dir",
        "list_files",
        "dir_list",
        "fs_stat",
        "file_stat",
        "fs_search",
        "search_files",
        "grep_search",
        "find_files",
        "glob",
        "git_status",
        "git:status",
        "git_diff",
        "git:diff",
        "git_log",
        "git:log",
        "git_read",
        "git_branch_list",
        "project_read",
        "workspace_read",
    )

    private val SAFE_READ_ONLY_CAPABILITIES = setOf(
        "fs_read",
        "filesystem_read",
        "git_read",
        "read",
        "read_only",
        "readonly",
        "model_query",
        "stt_query",
        "tts_query",
        "notify",
        "event_subscribe",
    )

    private val ALLOWED_VOICE_RISK_LEVELS = setOf("LOW", "MINIMAL")

    private val DENIED_TOOL_TOKENS = setOf(
        "shell", "exec", "bash", "sh", "zsh", "cmd", "powershell", "pwsh", "terminal",
        "command", "process", "sudo", "su",
        "net", "network", "http", "https", "egress", "fetch", "curl", "wget", "socket",
        "ssh", "tunnel", "upload", "download", "webhook", "api", "rpc", "remote", "push",
        "clone", "pull",
        "write", "edit", "patch", "create", "mkdir", "touch", "append", "overwrite",
        "delete", "del", "rm", "unlink", "rmdir", "remove", "move", "mv", "rename",
        "chmod", "chown", "commit", "checkout", "reset", "rebase", "merge", "stash", "apply",
        "secret", "secrets", "vault", "keychain", "keystore", "credential", "credentials",
        "password", "token", "auth", "env", "dotenv",
        "mcp", "pin", "escalat", "escalation", "untrusted", "taint", "grant", "permission",
        "capability", "worker",
    )

    private val DENIED_CAPABILITY_MARKERS = setOf(
        "shell", "exec", "network", "egress", "http", "https", "write", "delete", "remove",
        "move", "rename", "secret", "secrets", "vault", "credential", "credentials",
        "token", "mcp", "worker", "ipc", "admin", "privileged", "sudo", "pin",
        "untrusted", "taint",
    )

    private val DENIED_PREVIEW_PATTERN = Regex(
        "\\b(untrusted|tainted?|explicit[- ]pin|pin[- ]required|requires[- ]explicit[- ]pin|" +
            "high[- ]risk|critical[- ]risk|escalation|destructive|overwrite|delete|remove|" +
            "shell|network|egress|secrets?|vault|credentials?)\\b"
    )

    fun toCard(
        event: RuntimeEvent.ToolApprovalRequired,
        capability: String? = null,
        riskLevel: String? = null,
        tainted: Boolean = false,
        requiresExplicitPin: Boolean = false,
    ): ApprovalCard {
        val normalizedTool = event.toolName.trim().lowercase()
        val normalizedCap = capability?.trim()?.lowercase().orEmpty()
        val normalizedRisk = riskLevel?.trim()?.uppercase()
        val isEgress = normalizedTool.contains("http") ||
            normalizedTool.contains("egress") ||
            normalizedTool.contains("network") ||
            normalizedTool.startsWith("mcp_http") ||
            normalizedCap.contains("network") ||
            normalizedCap.contains("egress")
        val isEscalation = tainted ||
            requiresExplicitPin ||
            normalizedRisk == "HIGH" ||
            normalizedRisk == "CRITICAL" ||
            event.previewDescription.contains("UNTRUSTED", ignoreCase = true) ||
            event.previewDescription.contains("untrusted source", ignoreCase = true)

        val kind = when {
            isEscalation -> ApprovalKind.ESCALATION
            isEgress -> ApprovalKind.EGRESS
            else -> ApprovalKind.MUTATION
        }

        val untrustedSource = if (isEscalation) event.previewDescription else null
        val providerRetention = if (isEgress) "Provider retention policy: see run details" else null
        val safeForVoice = isVoiceSafe(
            toolName = event.toolName,
            previewDescription = event.previewDescription,
            kind = kind,
            capability = capability,
            riskLevel = riskLevel,
            tainted = tainted,
            requiresExplicitPin = requiresExplicitPin,
        )

        return ApprovalCard(
            approvalId = event.taskId,
            toolName = event.toolName,
            previewDescription = event.previewDescription,
            untrustedSourceDescription = untrustedSource,
            providerRetentionStatement = providerRetention,
            kind = kind,
            voiceSafe = safeForVoice,
        )
    }

    /**
     * Strictly determines whether an approval may be confirmed by voice (fail-closed).
     *
     * Returns true ONLY for explicitly allowlisted low-risk read-only actions without
     * shell, network/egress, filesystem write/delete/move, secrets/vault, MCP tools,
     * HIGH/CRITICAL risk, untrusted taint, or explicit PIN/escalation requirements.
     */
    fun isVoiceSafe(
        toolName: String,
        previewDescription: String,
        kind: ApprovalKind = ApprovalKind.MUTATION,
        capability: String? = null,
        riskLevel: String? = null,
        tainted: Boolean = false,
        requiresExplicitPin: Boolean = false,
    ): Boolean {
        if (kind != ApprovalKind.MUTATION) return false
        if (tainted || requiresExplicitPin) return false

        val normalizedRisk = riskLevel?.trim()?.uppercase()
        if (normalizedRisk != null && normalizedRisk !in ALLOWED_VOICE_RISK_LEVELS) {
            return false
        }

        val normalizedCap = capability?.trim()?.lowercase()
        if (normalizedCap != null) {
            if (normalizedCap.isEmpty()) return false
            val capTokens = normalizedCap.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (capTokens.any { it in DENIED_CAPABILITY_MARKERS }) return false
            if (DENIED_CAPABILITY_MARKERS.any { normalizedCap.contains(it) }) return false
            if (normalizedCap !in SAFE_READ_ONLY_CAPABILITIES) return false
        }

        val normalizedTool = toolName.trim().lowercase()
        if (normalizedTool.isEmpty()) return false
        if (normalizedTool.startsWith("mcp")) return false
        val toolTokens = normalizedTool.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (toolTokens.any { it in DENIED_TOOL_TOKENS }) return false
        if (normalizedTool !in SAFE_READ_ONLY_TOOLS) return false

        val previewLower = previewDescription.lowercase()
        if (DENIED_PREVIEW_PATTERN.containsMatchIn(previewLower)) return false

        return true
    }
}
