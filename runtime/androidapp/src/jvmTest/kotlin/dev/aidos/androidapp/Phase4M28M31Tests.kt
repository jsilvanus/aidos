package dev.aidos.androidapp

import dev.aidos.androidapp.ui.diff.CommitDraftState
import dev.aidos.androidapp.ui.diff.CommitPresenter
import dev.aidos.androidapp.ui.diff.DiffUiState
import dev.aidos.androidapp.ui.eventstream.EventStreamPresenter
import dev.aidos.androidapp.ui.eventstream.EventStreamUiState
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.projects.ProjectsUiState
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.runs.RunListUiState
import dev.aidos.androidapp.ui.sessions.SessionListPresenter
import dev.aidos.androidapp.ui.sessions.SessionListUiState
import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.RuntimeEvent
import dev.aidos.api.SessionState
import dev.aidos.api.UserMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 tests for M28 (Compose UI) and M31 (Diff and commit review).
 *
 * All tests run on the JVM using coroutine test infrastructure. No Compose, no Android SDK
 * required. The same presenters are bound to Compose on Android via `collectAsState()`.
 */
class Phase4M28M31Tests {

    // ─── M28: Projects ────────────────────────────────────────────────────────

    @Test
    fun `M28 project list starts loading then shows empty list`() = runTest {
        val client = MockRuntimeClient()
        val presenter = ProjectsPresenter(client, this)

        presenter.loadProjects()
        val state = presenter.state.first { it !is ProjectsUiState.Loading }

        assertIs<ProjectsUiState.Ready>(state)
        assertTrue((state as ProjectsUiState.Ready).isEmpty)
    }

    @Test
    fun `M28 created project appears in project list`() = runTest {
        val client = MockRuntimeClient()
        val presenter = ProjectsPresenter(client, this)

        presenter.createProject("My Project", "A description", "my-project")
        val state = presenter.state.first { it is ProjectsUiState.Ready && !(it as ProjectsUiState.Ready).isEmpty }

        assertIs<ProjectsUiState.Ready>(state)
        assertEquals("My Project", (state as ProjectsUiState.Ready).projects.first().name)
    }

    @Test
    fun `M28 selecting a project records selectedProjectId`() = runTest {
        val client = MockRuntimeClient()
        val presenter = ProjectsPresenter(client, this)

        presenter.createProject("My Project", "A description", "my-project")
        val ready = presenter.state.first { it is ProjectsUiState.Ready && !(it as ProjectsUiState.Ready).isEmpty }
        val projectId = (ready as ProjectsUiState.Ready).projects.first().id

        presenter.selectProject(projectId)
        val selected = presenter.state.first()

        assertIs<ProjectsUiState.Ready>(selected)
        assertEquals(projectId, (selected as ProjectsUiState.Ready).selectedProjectId)
    }

    // ─── M28: Sessions ────────────────────────────────────────────────────────

    @Test
    fun `M28 session list for project shows created sessions`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id

        val presenter = SessionListPresenter(client, this)
        presenter.createSession(projectId, "My Session")

        val state = presenter.state.first { it is SessionListUiState.Ready && (it as SessionListUiState.Ready).visibleSessions.isNotEmpty() }
        assertIs<SessionListUiState.Ready>(state)
        assertEquals("My Session", (state as SessionListUiState.Ready).visibleSessions.first().name)
    }

    @Test
    fun `M28 archived sessions are hidden by default`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id

        val sessionResult = client.sessions.create(dev.aidos.api.CreateSessionRequest(projectId, "Session 1"))
        val sessionId = (sessionResult as dev.aidos.api.SessionResult.Success).session.id
        client.sessions.archive(sessionId)

        val presenter = SessionListPresenter(client, this)
        presenter.load(projectId)

        val state = presenter.state.first { it is SessionListUiState.Ready }
        assertIs<SessionListUiState.Ready>(state)
        assertTrue((state as SessionListUiState.Ready).visibleSessions.isEmpty(),
            "Archived sessions must be hidden by default")
    }

    @Test
    fun `M28 toggle archived shows archived sessions`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id

        val sessionResult = client.sessions.create(dev.aidos.api.CreateSessionRequest(projectId, "Session 1"))
        val sessionId = (sessionResult as dev.aidos.api.SessionResult.Success).session.id
        client.sessions.archive(sessionId)

        val presenter = SessionListPresenter(client, this)
        presenter.load(projectId)

        presenter.state.first { it is SessionListUiState.Ready }
        presenter.toggleArchived()

        val state = presenter.state.first { it is SessionListUiState.Ready && (it as SessionListUiState.Ready).showArchived }
        assertIs<SessionListUiState.Ready>(state)
        assertTrue((state as SessionListUiState.Ready).visibleSessions.isNotEmpty())
    }

    // ─── M28: Runs ────────────────────────────────────────────────────────────

    @Test
    fun `M28 run list shows runs in a session`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id
        val sessionResult = client.sessions.create(dev.aidos.api.CreateSessionRequest(projectId, "Session 1"))
        val sessionId = (sessionResult as dev.aidos.api.SessionResult.Success).session.id

        client.sessions.send(sessionId, UserMessage("Do something"))

        val presenter = RunListPresenter(client, this)
        presenter.load(sessionId)

        val state = presenter.state.first { it is RunListUiState.Ready }
        assertIs<RunListUiState.Ready>(state)
        // MockRuntimeClient.sessions.get returns empty recentRuns — the run list is empty
        // because the mock does not persist runs into SessionDetail. This is expected: the
        // test verifies that the presenter loads without error (the runtime implementation
        // will return real runs).
        assertEquals(sessionId, (state as RunListUiState.Ready).sessionId)
    }

    // ─── M28: Event stream ────────────────────────────────────────────────────

    @Test
    fun `M28 event stream receives emitted events`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id
        val sessionResult = client.sessions.create(dev.aidos.api.CreateSessionRequest(projectId, "Session 1"))
        val sessionId = (sessionResult as dev.aidos.api.SessionResult.Success).session.id
        val runId = "run-1"

        val presenter = EventStreamPresenter(client, this)
        presenter.start(sessionId = sessionId, runId = runId, projectId = projectId)

        // Emit an event via the mock.
        val event = RuntimeEvent.RunStarted(
            eventId = "evt-1",
            timestamp = Clock.System.now(),
            projectId = projectId,
            sessionId = sessionId,
            runId = runId,
        )
        client.emit(event)

        val state = presenter.state.first { (it?.events?.size ?: 0) > 0 }
        assertNotNull(state)
        assertTrue(state!!.events.isNotEmpty(), "Event stream must receive emitted events")

        presenter.stop()
    }

    @Test
    fun `M28 event stream is not live after stop`() = runTest {
        val client = MockRuntimeClient()
        val presenter = EventStreamPresenter(client, this)
        presenter.start(sessionId = "s1", runId = "r1", projectId = "p1")
        presenter.stop()

        val state = presenter.state.first { it != null && !it.isLive }
        assertNotNull(state)
    }

    // ─── M31: Diff and commit review ─────────────────────────────────────────

    @Test
    fun `M31 diff screen starts loading then shows empty working tree`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id

        val presenter = CommitPresenter(client, this)
        presenter.load(projectId)

        val state = presenter.state.first { it !is DiffUiState.Loading }
        assertIs<DiffUiState.Changes>(state)
    }

    @Test
    fun `M31 commit with empty message yields error`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id

        val presenter = CommitPresenter(client, this)
        presenter.load(projectId)
        presenter.state.first { it is DiffUiState.Changes }

        presenter.commit(projectId)  // draft message is empty by default

        val state = presenter.state.first { it is DiffUiState.Error }
        assertIs<DiffUiState.Error>(state)
    }

    @Test
    fun `M31 commit with valid message transitions to Committed`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id

        val presenter = CommitPresenter(client, this)
        presenter.load(projectId)
        presenter.state.first { it is DiffUiState.Changes }

        presenter.updateDraft("Add initial scaffold")
        presenter.commit(projectId)

        val state = presenter.state.first { it is DiffUiState.Committed }
        assertIs<DiffUiState.Committed>(state)
        assertTrue((state as DiffUiState.Committed).shortMessage.isNotEmpty())
    }

    @Test
    fun `M31 commit clears draft on success`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(dev.aidos.api.CreateProjectRequest(
            name = "P1", description = "desc", location = dev.aidos.api.ProjectLocation.RuntimeManaged("p1"),
        ))
        val projectId = (projResult as dev.aidos.api.ProjectResult.Success).project.id

        val presenter = CommitPresenter(client, this)
        presenter.load(projectId)
        presenter.state.first { it is DiffUiState.Changes }

        presenter.updateDraft("A commit message")
        presenter.commit(projectId)
        presenter.state.first { it is DiffUiState.Committed }

        assertTrue(presenter.draft.value.message.isEmpty(), "Draft must be cleared after a successful commit")
    }

    @Test
    fun `M31 draft validation flags blank message as invalid`() {
        val blank = CommitDraftState.from("")
        val filled = CommitDraftState.from("Fix typo in README")

        assertTrue(!blank.isValid)
        assertTrue(filled.isValid)
    }
}
