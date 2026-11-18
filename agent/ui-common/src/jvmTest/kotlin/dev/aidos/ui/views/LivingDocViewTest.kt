package dev.aidos.ui.views

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.IntentCommands
import dev.aidos.api.RealRuntimeClient
import dev.aidos.api.RuntimeClient
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.paradigms.livingdoc.IncomingMerge
import dev.aidos.ui.paradigms.livingdoc.LivingDocPresenter
import dev.aidos.ui.paradigms.livingdoc.LivingDocSaveState
import dev.aidos.ui.paradigms.livingdoc.NOTES_TITLE
import dev.aidos.ui.paradigms.livingdoc.documentNodeId
import dev.aidos.ui.paradigms.livingdoc.insertAtCursor
import dev.aidos.ui.paradigms.livingdoc.mergeIncoming
import dev.aidos.ui.paradigms.livingdoc.notesNodeIn
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RFC-0106 §1.2, RFC-0124 (UI-33): the Living Document presenter and its pure rules.
class LivingDocViewTest {

    private fun node(
        id: String,
        title: String,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        parentId: String? = null,
        description: String? = null,
    ) = IntentNodeRecord(
        id = id, projectId = "p1", type = type, title = title, description = description, parentId = parentId,
        actorKind = IntentActorKind.AGENT,
        createdAt = "2026-11-18T00:00:00Z", createdByKind = "USER", createdById = "u",
        modifiedAt = "2026-11-18T00:00:00Z", modifiedByKind = "USER", modifiedById = "u",
    )

    private fun snapshot(vararg nodes: IntentNodeRecord) = GraphSnapshot(
        projectId = "p1",
        nodes = nodes.toList(),
        edges = emptyList(),
        statuses = emptyMap(),
        proposals = emptyList(),
        loadedAt = 1L,
    )

    @Test
    fun `the selected node wins over Notes`() {
        val notes = node("n", NOTES_TITLE, IntentNodeType.GOAL)
        val a = node("a", "Task A")
        val snap = snapshot(notes, a)
        assertEquals("a", documentNodeId("a", snap))
        assertEquals("n", documentNodeId(null, snap))
        assertEquals("n", documentNodeId("missing", snap))
    }

    @Test
    fun `Notes is a top-level GOAL titled exactly Notes`() {
        assertEquals("n", notesNodeIn(snapshot(node("n", NOTES_TITLE, IntentNodeType.GOAL)))?.id)
        assertNull(notesNodeIn(snapshot(node("x", NOTES_TITLE, IntentNodeType.ATOMIC_TASK))))
        assertNull(notesNodeIn(snapshot(node("p", "Parent", IntentNodeType.GOAL), node("n", NOTES_TITLE, IntentNodeType.GOAL, parentId = "p"))))
        assertNull(notesNodeIn(snapshot(node("x", "Notes 2", IntentNodeType.GOAL))))
    }

    @Test
    fun `an incoming change keeps unsaved local text and flags a conflict`() {
        assertEquals(IncomingMerge("mine", conflict = true), mergeIncoming("mine", "theirs", dirty = true))
        assertEquals(IncomingMerge("theirs", conflict = false), mergeIncoming("old", "theirs", dirty = false))
        assertEquals(IncomingMerge("same", conflict = false), mergeIncoming("same", "same", dirty = true))
    }

    @Test
    fun `dictation is inserted at the cursor`() {
        assertEquals("hello there world" to 11, insertAtCursor("hello world", 5, " there"))
        assertEquals(" first" to 6, insertAtCursor("", 0, " first"))
    }

    // ---- presenter, against a real runtime client that counts writes ----

    private class CountingIntents(private val inner: IntentCommands) : IntentCommands by inner {
        val creates = mutableListOf<CreateIntentNodeRequest>()
        val updates = mutableListOf<UpdateIntentNodeRequest>()

        override suspend fun createNode(request: CreateIntentNodeRequest): IntentNodeRecord {
            creates += request
            return inner.createNode(request)
        }

        override suspend fun updateNode(request: UpdateIntentNodeRequest): IntentNodeRecord {
            updates += request
            return inner.updateNode(request)
        }
    }

    private class CountingClient(inner: RuntimeClient, override val intents: IntentCommands) : RuntimeClient by inner

    /** Background scopes keep the store's poll and the presenter's collectors out of runTest's way. */
    private class Rig(scope: CoroutineScope) {
        val runtime = RealRuntimeClient()
        val intents = CountingIntents(runtime.intents)
        val controller = WorkspaceController(CountingClient(runtime, intents), scope, "p1", "livingdoc")
        val presenter = LivingDocPresenter(controller.currentStore.value.snapshot, controller, scope)
    }

    @Test
    fun `the first save without Notes creates it with the text`() = runTest {
        val rig = Rig(backgroundScope)
        runCurrent()
        assertNull(rig.presenter.state.value.nodeId)

        rig.presenter.onTextChanged("First note")
        advanceTimeBy(900)
        runCurrent()

        assertEquals(1, rig.intents.creates.size)
        val created = rig.intents.creates.single()
        assertEquals(NOTES_TITLE, created.title)
        assertEquals(IntentNodeType.GOAL, created.type)
        assertEquals("First note", created.description)
        assertTrue(rig.intents.updates.isEmpty())
        // Once the store shows the new node, the view follows it and reports it saved.
        assertTrue(rig.presenter.state.value.nodeId != null)
        assertEquals("First note", rig.presenter.state.value.text)
        assertEquals(LivingDocSaveState.SAVED, rig.presenter.state.value.saveState)
    }

    @Test
    fun `quick edits send one update`() = runTest {
        val rig = Rig(backgroundScope)
        val notes = rig.runtime.intents.createNode(
            CreateIntentNodeRequest(projectId = "p1", title = NOTES_TITLE, type = IntentNodeType.GOAL, description = "Start"),
        )
        runCurrent()
        assertEquals(notes.id, rig.presenter.state.value.nodeId)
        assertEquals("Start", rig.presenter.state.value.text)

        rig.presenter.onTextChanged("S1")
        advanceTimeBy(300)
        rig.presenter.onTextChanged("S12")
        advanceTimeBy(300)
        rig.presenter.onTextChanged("S123")
        advanceTimeBy(900)
        runCurrent()

        assertEquals(listOf("S123"), rig.intents.updates.map { it.description })
        assertEquals(notes.id, rig.intents.updates.single().nodeId)
        assertEquals(LivingDocSaveState.SAVED, rig.presenter.state.value.saveState)
    }

    @Test
    fun `a change elsewhere keeps unsaved text and keep mine saves it`() = runTest {
        val rig = Rig(backgroundScope)
        val notes = rig.runtime.intents.createNode(
            CreateIntentNodeRequest(projectId = "p1", title = NOTES_TITLE, type = IntentNodeType.GOAL, description = "Original"),
        )
        runCurrent()

        rig.presenter.onTextChanged("Mine")
        rig.runtime.intents.updateNode(UpdateIntentNodeRequest(projectId = "p1", nodeId = notes.id, description = "Theirs"))
        rig.controller.storeFor("p1").refreshNow()
        runCurrent()

        val conflicted = rig.presenter.state.value
        assertTrue(conflicted.conflict)
        assertEquals("Mine", conflicted.text)

        rig.presenter.keepMine()
        advanceTimeBy(900)
        runCurrent()
        assertEquals(listOf("Mine"), rig.intents.updates.map { it.description })
    }

    @Test
    fun `reload takes the changed text`() = runTest {
        val rig = Rig(backgroundScope)
        val notes = rig.runtime.intents.createNode(
            CreateIntentNodeRequest(projectId = "p1", title = NOTES_TITLE, type = IntentNodeType.GOAL, description = "Original"),
        )
        runCurrent()

        rig.presenter.onTextChanged("Mine")
        rig.runtime.intents.updateNode(UpdateIntentNodeRequest(projectId = "p1", nodeId = notes.id, description = "Theirs"))
        rig.controller.storeFor("p1").refreshNow()
        runCurrent()

        rig.presenter.reloadRemote()
        runCurrent()
        assertEquals("Theirs", rig.presenter.state.value.text)
        assertTrue(!rig.presenter.state.value.conflict)
        assertTrue(rig.intents.updates.isEmpty())
    }
}
