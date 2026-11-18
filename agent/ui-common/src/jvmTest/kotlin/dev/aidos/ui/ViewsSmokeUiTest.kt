package dev.aidos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.RealRuntimeClient
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.layout.ViewModeCatalog
import dev.aidos.ui.workspace.WorkspaceBootstrap
import dev.aidos.ui.workspace.WorkspaceKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

// RFC-0124 (UI-60): every view renders live runtime data, and the graph views open the inspector.
// Runs headless on the JVM (decision U15); the Android emulator run (0124.10) is separate.
@OptIn(ExperimentalTestApi::class)
class ViewsSmokeUiTest {

    /** Builds the Workspace project with a goal, a human task, an agent task and a criterion. */
    private fun seed(client: RealRuntimeClient): String = runBlocking {
        val projectId = WorkspaceBootstrap.ensureProject(client, WorkspaceKind.WORKSPACE)
        val goal = client.intents.createNode(
            CreateIntentNodeRequest(projectId = projectId, title = "Ship v1", type = IntentNodeType.GOAL),
        )
        val review = client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = projectId, title = "Review copy", type = IntentNodeType.ATOMIC_TASK,
                actorKind = IntentActorKind.HUMAN, parentId = goal.id,
            ),
        )
        val writeTests = client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = projectId, title = "Write tests", type = IntentNodeType.ATOMIC_TASK,
                actorKind = IntentActorKind.AGENT, parentId = goal.id,
            ),
        )
        // "Write tests" depends on "Review copy": the dependent is the edge source (see NodeInspectorPresenter).
        client.intents.addEdge(projectId, writeTests.id, review.id, IntentEdgeKind.DEPENDS_ON)
        client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = projectId, title = "Tests pass", type = IntentNodeType.ACCEPTANCE_CRITERION,
                actorKind = IntentActorKind.HUMAN, parentId = writeTests.id,
            ),
        )
        projectId
    }

    @Composable
    private fun Sized(width: Int, height: Int, content: @Composable () -> Unit) {
        Box(Modifier.size(width.dp, height.dp)) { content() }
    }

    private fun SemanticsNodeInteractionCollection.exists(): Boolean = fetchSemanticsNodes().isNotEmpty()

    /**
     * Infinite animations (loading spinners, pulsing pills) never let Compose go idle, so with the
     * default auto-advancing clock `setContent` and `waitForIdle` spin forever. The clock is driven
     * by hand instead: advance one frame, re-check, give the runtime's coroutines a moment.
     */
    private fun ComposeUiTest.awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            mainClock.advanceTimeByFrame()
            Thread.sleep(20)
        }
    }

    @Test
    fun everyViewRendersLiveDataAndGraphViewsOpenTheInspector() {
        val client = RealRuntimeClient()
        val projectId = seed(client)

        for (mode in ViewModeCatalog.all) {
            try {
                runComposeUiTest {
                    mainClock.autoAdvance = false
                    setContent {
                        Sized(1280, 800) {
                            AidosUniversalApp(
                                runtimeClient = client,
                                initialDestinationId = mode.id,
                                initialProjectId = projectId,
                            )
                        }
                    }
                    when (mode.id) {
                        // Their own headers are the proof of rendering; they show no node titles.
                        "gates" -> awaitTrue("the Gates header") {
                            onAllNodesWithText("Approval & Egress Gates").exists()
                        }
                        "projects" -> awaitTrue("the Projects header") {
                            onAllNodesWithText("Workspaces & Projects").exists()
                        }
                        // The Living Document shows the selected node's text, or "Notes" when nothing is
                        // selected, which is the state a fresh open is in. It is not a node list.
                        "livingdoc" -> awaitTrue("the Notes document title") {
                            onAllNodesWithText("Notes").exists()
                        }
                        // The canvas paints node titles with DrawScope.drawText, which has no semantics, so
                        // only the render and the navigation entry are checked here. Follow-up: give canvas
                        // nodes semantics so this view joins the inspector check.
                        // Radar titles appear only in the zone the node sits in. "Review copy" has no unmet
                        // dependencies, so it is in the Strike zone: open that zone and find it there.
                        "radar" -> {
                            awaitTrue("the radar zone labels") {
                                onAllNodesWithText("Strike zone", substring = true).exists()
                            }
                            onAllNodesWithText("Strike zone", substring = true).filter(hasClickAction()).onFirst().performClick()
                            // The row reads "Review copy  ·  status", so match the title as a substring.
                            awaitTrue("the radar to list the ready task") {
                                onAllNodesWithText("Review copy", substring = true).exists()
                            }
                        }
                        "canvas" -> {
                            repeat(5) { mainClock.advanceTimeByFrame() }
                            awaitTrue("the Canvas navigation entry") {
                                onAllNodesWithText("Canvas").exists()
                            }
                        }
                        else -> {
                            awaitTrue("live node titles") {
                                onAllNodesWithText("Review copy").exists() || onAllNodesWithText("Ship v1").exists()
                            }
                            onAllNodesWithText("Review copy").filter(hasClickAction()).onFirst().performClick()
                            waitForIdle()
                            assertTrue(onAllNodes(hasSetTextAction() and hasText("Review copy")).exists(), "inspector title")
                        }
                    }
                }
            } catch (t: Throwable) {
                throw AssertionError("view '${mode.id}' failed its smoke check: ${t.message}", t)
            }
        }
    }

    @Test
    fun compactPhoneBottomBarHoldsAtMostFiveItemsIncludingGates() {
        val client = RealRuntimeClient()
        val projectId = seed(client)
        val primary = ViewModeCatalog.primary(FormFactor.HANDHELD_COMPACT)

        assertTrue(primary.size <= ViewModeCatalog.maxPrimary(FormFactor.HANDHELD_COMPACT))
        assertTrue(primary.any { it.id == "gates" }, "Gates must be a primary view on a phone")

        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                Sized(400, 800) {
                    AidosUniversalApp(
                        runtimeClient = client,
                        initialDestinationId = "inbox",
                        initialProjectId = projectId,
                    )
                }
            }
            awaitTrue("live node titles") { onAllNodesWithText("Review copy").exists() || onAllNodesWithText("Ship v1").exists() }
            for (mode in primary) {
                assertTrue(onAllNodesWithText(mode.label, substring = true).exists(), "bottom bar shows ${mode.label}")
            }
        }
    }
}
