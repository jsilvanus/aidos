package dev.aidos.ui

import dev.aidos.api.UiManifestRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.generative.ManifestAction
import dev.aidos.ui.generative.ManifestActionRegistry
import dev.aidos.ui.generative.needsConfirmation
import dev.aidos.ui.workspace.GraphSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RFC-0124 (UI-52, 0124.4): registered manifest actions and the taint confirmation rule.
class ManifestActionsTest {
    private fun node(id: String, project: String = "p1") = IntentNodeRecord(
        id = id,
        projectId = project,
        type = IntentNodeType.ATOMIC_TASK,
        title = id,
        createdAt = "2026-11-18T00:00:00Z",
        createdByKind = "USER",
        createdById = "test",
        modifiedAt = "2026-11-18T00:00:00Z",
        modifiedByKind = "USER",
        modifiedById = "test",
    )

    private val snapshot = GraphSnapshot(
        projectId = "p1",
        nodes = listOf(node("n1"), node("n2")),
        edges = emptyList(),
        statuses = emptyMap(),
        proposals = emptyList(),
        loadedAt = 0L,
    )

    private fun record(taint: String = "TRUSTED", target: String = "n1", project: String = "p1") =
        UiManifestRecord(
            callId = "c1",
            runId = "r1",
            projectId = project,
            targetNodeId = target,
            manifestJson = "{}",
            runTaint = taint,
            createdAt = null,
        )

    @Test
    fun `each registered id resolves to its host action`() {
        val rec = record()
        assertEquals(ManifestAction.OpenNode("n1"), ManifestActionRegistry.resolve("open_node", rec, "Open", snapshot))
        assertEquals(ManifestAction.CompleteNode("n1"), ManifestActionRegistry.resolve("complete_node", rec, "Done", snapshot))
        assertEquals(ManifestAction.StartNode("n1"), ManifestActionRegistry.resolve("start_node", rec, "Start", snapshot))
        assertEquals(
            ManifestAction.ReplyToAgent("r1", "Yes please"),
            ManifestActionRegistry.resolve("reply_to_agent", rec, "Yes please", snapshot),
        )
    }

    @Test
    fun `suffix names a node in the same project`() {
        assertEquals(
            ManifestAction.OpenNode("n2"),
            ManifestActionRegistry.resolve("open_node:n2", record(), "Open", snapshot),
        )
    }

    @Test
    fun `unknown action id resolves to null`() {
        assertNull(ManifestActionRegistry.resolve("delete_everything", record(), "x", snapshot))
        assertNull(ManifestActionRegistry.resolve("", record(), "x", snapshot))
    }

    @Test
    fun `reply_to_agent takes no node suffix`() {
        assertNull(ManifestActionRegistry.resolve("reply_to_agent:n1", record(), "x", snapshot))
    }

    @Test
    fun `suffix naming a node from another project resolves to null`() {
        val other = snapshot.copy(nodes = snapshot.nodes + node("foreign", project = "p2"))
        assertNull(ManifestActionRegistry.resolve("start_node:foreign", record(), "Start", other))
    }

    @Test
    fun `node missing from the snapshot resolves to null`() {
        assertNull(ManifestActionRegistry.resolve("complete_node:nope", record(), "Done", snapshot))
        assertNull(ManifestActionRegistry.resolve("complete_node", record(target = "gone"), "Done", snapshot))
    }

    @Test
    fun `record from another project resolves to null`() {
        assertNull(ManifestActionRegistry.resolve("open_node", record(project = "p2"), "Open", snapshot))
    }

    @Test
    fun `only a TRUSTED run skips confirmation`() {
        assertFalse(needsConfirmation(record(taint = "TRUSTED")))
        assertTrue(needsConfirmation(record(taint = "PROJECT")))
        assertTrue(needsConfirmation(record(taint = "UNTRUSTED")))
        assertTrue(needsConfirmation(record(taint = "something-else")))
    }

    @Test
    fun `a card with one unknown id is fully disabled`() {
        val ids = listOf("open_node", "bogus")
        assertFalse(ManifestActionRegistry.allResolve(ids, record(), { it }, snapshot))
        assertTrue(ManifestActionRegistry.allResolve(listOf("open_node", "start_node:n2"), record(), { it }, snapshot))
    }
}
