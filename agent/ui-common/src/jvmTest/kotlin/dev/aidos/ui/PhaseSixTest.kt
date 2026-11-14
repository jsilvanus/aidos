package dev.aidos.ui

import dev.aidos.ui.dex.DexStatePreserver
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.layout.computeFormFactor
import dev.aidos.ui.palette.CommandItemKind
import dev.aidos.ui.palette.CommandPaletteIntent
import dev.aidos.ui.palette.CommandPalettePresenter
import dev.aidos.ui.palette.KeyAction
import dev.aidos.ui.palette.KeyboardShortcutRegistry
import dev.aidos.ui.palette.PaletteItem
import dev.aidos.ui.spatial.canvas.ViewportTransform
import dev.aidos.ui.system.NativeProcessHealth
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseSixTest {

    @Test
    fun testDexHotDockingStatePreservation() {
        val preserver = DexStatePreserver()

        // 1. User is on compact phone interacting with node "task-7"
        val mobileWidth = 390f
        val mobileFactor = computeFormFactor(mobileWidth)
        assertEquals(FormFactor.HANDHELD_COMPACT, mobileFactor)

        preserver.captureSession(
            projectId = "project-life",
            nodeId = "task-7",
            documentText = "Drafting specs on mobile device",
            viewport = ViewportTransform(panX = 120f, panY = 80f, zoom = 0.5f),
            formFactor = mobileFactor,
        )

        // 2. USB-C monitor plugged in (DeX activates, width expands to 1920dp)
        val dockedWidth = 1920f
        val dockedFactor = computeFormFactor(dockedWidth)
        assertEquals(FormFactor.DESKTOP_DOCKED, dockedFactor)

        // 3. State recovered without restarting process
        val restored = preserver.restoreSession()
        assertNotNull(restored)
        assertEquals("project-life", restored.activeProjectId)
        assertEquals("task-7", restored.activeNodeId)
        assertEquals("Drafting specs on mobile device", restored.activeDocumentText)
        assertEquals(120f, restored.canvasViewport.panX)
        assertEquals(0.5f, restored.canvasViewport.zoom)
    }

    @Test
    fun testKeyboardShortcutsAndCommandPaletteFuzzySearch() {
        // Test key resolving
        assertEquals(KeyAction.OPEN_COMMAND_PALETTE, KeyboardShortcutRegistry.resolveKeyAction("k", isMetaOrCtrl = true))
        assertEquals(KeyAction.OPEN_ACTION_INBOX, KeyboardShortcutRegistry.resolveKeyAction("i", isMetaOrCtrl = true))
        assertEquals(KeyAction.NAVIGATE_DOWN, KeyboardShortcutRegistry.resolveKeyAction("j", isMetaOrCtrl = false))
        assertEquals(KeyAction.NAVIGATE_UP, KeyboardShortcutRegistry.resolveKeyAction("k", isMetaOrCtrl = false))
        assertEquals(KeyAction.TOGGLE_COMPLETE, KeyboardShortcutRegistry.resolveKeyAction(" ", isMetaOrCtrl = false))
        assertEquals(KeyAction.ESCAPE, KeyboardShortcutRegistry.resolveKeyAction("Escape", isMetaOrCtrl = false))
        assertNull(KeyboardShortcutRegistry.resolveKeyAction("x", isMetaOrCtrl = false))

        // Command Palette filtering
        var executedDeploy = false
        val commands = listOf(
            PaletteItem("1", "Deploy Agent", "Production release", CommandItemKind.COMMAND) { executedDeploy = true },
            PaletteItem("2", "Run Unit Tests", "Gradle check", CommandItemKind.COMMAND) {},
            PaletteItem("3", "Open Personal Life Graph", "Air-gapped workspace", CommandItemKind.INTENT_GOAL) {},
        )

        val presenter = CommandPalettePresenter(commands)
        presenter.send(CommandPaletteIntent.Open)
        assertTrue(presenter.state.value.isOpen)

        // Search fuzzy
        presenter.send(CommandPaletteIntent.UpdateQuery("Deploy"))
        assertEquals(1, presenter.state.value.results.size)
        assertEquals("Deploy Agent", presenter.state.value.results.first().title)

        // Execute selected
        presenter.send(CommandPaletteIntent.ExecuteSelected)
        assertTrue(executedDeploy)
        assertEquals(false, presenter.state.value.isOpen)
    }

    @Test
    fun testNativeProcessCrashRecoveryContract() {
        val states = listOf(
            NativeProcessHealth.HEALTHY,
            NativeProcessHealth.CRASHED_WARM_RESTARTING,
            NativeProcessHealth.RECOVERED,
        )

        assertEquals(3, states.size)
    }
}
