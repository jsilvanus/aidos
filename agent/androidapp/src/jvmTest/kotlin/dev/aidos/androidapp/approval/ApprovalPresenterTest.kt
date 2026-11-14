package dev.aidos.androidapp.approval

import dev.aidos.api.RuntimeEvent
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies strict fail-closed [ApprovalPresenter.voiceSafe] classification across
 * tool capabilities, risk levels, untrusted taint, and explicit-PIN escalations.
 */
class ApprovalPresenterTest {

    private fun event(
        toolName: String,
        previewDescription: String = "Preview action",
    ) = RuntimeEvent.ToolApprovalRequired(
        eventId = "evt-1",
        timestamp = Clock.System.now(),
        projectId = "proj-1",
        sessionId = "sess-1",
        runId = "run-1",
        taskId = "task-1",
        toolName = toolName,
        previewDescription = previewDescription,
    )

    @Test
    fun `low risk read only tools are voice safe`() {
        for (tool in listOf("fs_read", "fs_list", "fs_search", "git_status", "git_diff", "git_log")) {
            val card = ApprovalPresenter.toCard(
                event = event(toolName = tool, previewDescription = "Inspect README.md"),
                capability = "FS_READ",
                riskLevel = "LOW",
            )
            assertEquals(ApprovalKind.MUTATION, card.kind, "tool=$tool")
            assertTrue(card.voiceSafe, "Expected $tool with LOW risk and FS_READ to be voiceSafe")
        }
    }

    @Test
    fun `write delete move tools are never voice safe`() {
        for (tool in listOf("fs_write", "fs_delete", "fs_move", "fs_rename", "file_write", "git_commit")) {
            val card = ApprovalPresenter.toCard(
                event = event(toolName = tool, previewDescription = "Modify src/App.kt"),
                riskLevel = "LOW",
            )
            assertFalse(card.voiceSafe, "Expected $tool to require eyes-on confirmation")
        }
    }

    @Test
    fun `shell network secrets vault and mcp tools are never voice safe`() {
        val blockedTools = listOf(
            "shell_exec",
            "bash",
            "powershell",
            "network_fetch",
            "http_post",
            "mcp_http_claude",
            "mcp_filesystem_read",
            "secrets_read",
            "vault_get",
            "credential_lookup",
        )
        for (tool in blockedTools) {
            val card = ApprovalPresenter.toCard(
                event = event(toolName = tool, previewDescription = "Execute $tool"),
                riskLevel = "LOW",
            )
            assertFalse(card.voiceSafe, "Blocked tool $tool must never be voiceSafe")
        }
    }

    @Test
    fun `dangerous capabilities override safe tool name and stay voice unsafe`() {
        val blockedCapabilities = listOf(
            "SHELL",
            "SHELL_EXEC",
            "NETWORK",
            "NETWORK_EGRESS",
            "FILESYSTEM_WRITE",
            "FS_WRITE",
            "DELETE",
            "MOVE",
            "SECRETS_READ",
            "VAULT",
            "MCP_UNRESTRICTED",
        )
        for (cap in blockedCapabilities) {
            val card = ApprovalPresenter.toCard(
                event = event(toolName = "fs_read", previewDescription = "Inspect file"),
                capability = cap,
                riskLevel = "LOW",
            )
            assertFalse(card.voiceSafe, "Capability $cap must make voiceSafe=false")
        }
    }

    @Test
    fun `high critical medium and unknown risk levels block voice approval`() {
        for (risk in listOf("MEDIUM", "HIGH", "CRITICAL", "UNKNOWN", "")) {
            val card = ApprovalPresenter.toCard(
                event = event(toolName = "fs_read", previewDescription = "Read README.md"),
                capability = "FS_READ",
                riskLevel = risk,
            )
            assertFalse(card.voiceSafe, "Risk '$risk' must not allow voice approval")
        }
        val highCard = ApprovalPresenter.toCard(
            event = event(toolName = "fs_read", previewDescription = "Read README.md"),
            capability = "FS_READ",
            riskLevel = "HIGH",
        )
        assertEquals(ApprovalKind.ESCALATION, highCard.kind)
    }

    @Test
    fun `untrusted taint and explicit pin force escalation and voiceSafe false`() {
        val taintedCard = ApprovalPresenter.toCard(
            event = event(toolName = "fs_read", previewDescription = "Read README.md"),
            capability = "FS_READ",
            riskLevel = "LOW",
            tainted = true,
        )
        assertEquals(ApprovalKind.ESCALATION, taintedCard.kind)
        assertFalse(taintedCard.voiceSafe)
        assertNotNull(taintedCard.untrustedSourceDescription)

        val previewTaintedCard = ApprovalPresenter.toCard(
            event = event(toolName = "fs_read", previewDescription = "UNTRUSTED source requested read"),
        )
        assertEquals(ApprovalKind.ESCALATION, previewTaintedCard.kind)
        assertFalse(previewTaintedCard.voiceSafe)

        val pinCard = ApprovalPresenter.toCard(
            event = event(toolName = "fs_read", previewDescription = "Read README.md"),
            capability = "FS_READ",
            riskLevel = "LOW",
            requiresExplicitPin = true,
        )
        assertEquals(ApprovalKind.ESCALATION, pinCard.kind)
        assertFalse(pinCard.voiceSafe)
    }

    @Test
    fun `unknown tool fails closed to voiceSafe false`() {
        val card = ApprovalPresenter.toCard(
            event = event(toolName = "unlisted_custom_action", previewDescription = "Benign text"),
            capability = "FS_READ",
            riskLevel = "LOW",
        )
        assertFalse(card.voiceSafe, "Unlisted tools must fail closed")
    }
}
