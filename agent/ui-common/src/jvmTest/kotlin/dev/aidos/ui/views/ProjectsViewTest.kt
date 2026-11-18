package dev.aidos.ui.views

import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.ProjectCommands
import dev.aidos.api.ProjectDetail
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.ProjectSummary
import dev.aidos.ui.frontdoors.projects.NewProjectDraft
import dev.aidos.ui.frontdoors.projects.ProjectHubIntent
import dev.aidos.ui.frontdoors.projects.ProjectHubPresenter
import dev.aidos.ui.frontdoors.projects.ProjectSlugs
import dev.aidos.ui.frontdoors.projects.formatLastActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeProjectCommands(
    var projects: List<ProjectSummary> = emptyList(),
    var createResult: ProjectResult? = null,
) : ProjectCommands {
    val createRequests = mutableListOf<CreateProjectRequest>()

    override suspend fun create(request: CreateProjectRequest): ProjectResult {
        createRequests += request
        return createResult ?: ProjectResult.Success(summary("new-id", request.name, "/root/${(request.location as ProjectLocation.RuntimeManaged).slug}"))
    }

    override suspend fun open(projectId: String): ProjectResult = error("not used")
    override suspend fun close(projectId: String) = Unit
    override suspend fun list(): List<ProjectSummary> = projects
    override suspend fun get(projectId: String): ProjectDetail? = null
    override suspend fun delete(projectId: String, confirm: Boolean) = Unit
}

private val EPOCH = Instant.parse("2026-11-18T10:00:00Z")

private fun summary(id: String, name: String, path: String) = ProjectSummary(
    id = id,
    name = name,
    description = "",
    projectPath = path,
    createdAt = EPOCH,
    lastActiveAt = EPOCH,
    sessionCount = 0,
)

class ProjectsViewTest {

    @Test
    fun `slug maps Finnish letters and collapses punctuation`() {
        assertEquals("aanestys-2026", ProjectSlugs.from("Äänestys 2026"))
        assertEquals("oljy-ja-a", ProjectSlugs.from("Öljy ja Å"))
        assertEquals("hello-world", ProjectSlugs.from("  Hello,   world!!  "))
        assertEquals("a-b", ProjectSlugs.from("a---b"))
    }

    @Test
    fun `slug is capped at 40 characters without a trailing hyphen`() {
        val slug = ProjectSlugs.from("a".repeat(39) + " bbbb")
        assertTrue(slug.length <= ProjectSlugs.MAX_LENGTH)
        assertFalse(slug.endsWith("-"))
    }

    @Test
    fun `slug falls back to project when nothing is left`() {
        assertEquals("project", ProjectSlugs.from("!!!"))
    }

    @Test
    fun `duplicate slugs get numbered suffixes`() {
        assertEquals("aanestys-2026", ProjectSlugs.unique("aanestys-2026", listOf("/root/other")))
        assertEquals("aanestys-2026-2", ProjectSlugs.unique("aanestys-2026", listOf("/root/aanestys-2026")))
        assertEquals(
            "aanestys-2026-3",
            ProjectSlugs.unique("aanestys-2026", listOf("C:\\root\\aanestys-2026", "/root/aanestys-2026-2/")),
        )
    }

    @Test
    fun `last active is shown relative, then as a date`() {
        assertEquals("just now", formatLastActive(EPOCH, EPOCH))
        assertEquals("5 min ago", formatLastActive(EPOCH, Instant.parse("2026-11-18T10:05:00Z")))
        assertEquals("3 h ago", formatLastActive(EPOCH, Instant.parse("2026-11-18T13:00:00Z")))
        assertEquals("2 d ago", formatLastActive(EPOCH, Instant.parse("2026-11-20T10:00:00Z")))
        assertEquals("2026-09-01", formatLastActive(Instant.parse("2026-09-01T08:00:00Z"), EPOCH))
    }

    @Test
    fun `blank name is refused without calling the runtime`() = runTest {
        val commands = FakeProjectCommands()
        val presenter = ProjectHubPresenter(commands, backgroundScope, Dispatchers.Unconfined)
        presenter.send(ProjectHubIntent.OpenNewProject)
        presenter.send(ProjectHubIntent.EditNewProject(NewProjectDraft(name = "   ")))
        presenter.send(ProjectHubIntent.SubmitNewProject)

        assertEquals("Name is required", presenter.state.value.createError)
        assertTrue(commands.createRequests.isEmpty())
        assertNotNull(presenter.state.value.newProject)
    }

    @Test
    fun `create success sends a managed slug and reports the new project for switching`() = runTest {
        val commands = FakeProjectCommands(projects = listOf(summary("p1", "Old", "/root/aanestys-2026")))
        val presenter = ProjectHubPresenter(commands, backgroundScope, Dispatchers.Unconfined)
        presenter.send(ProjectHubIntent.OpenNewProject)
        presenter.send(ProjectHubIntent.EditNewProject(NewProjectDraft("Äänestys 2026", "Kirkkovaltuusto", "code")))
        presenter.send(ProjectHubIntent.SubmitNewProject)

        val request = commands.createRequests.single()
        assertEquals("Äänestys 2026", request.name)
        assertEquals(ProjectLocation.RuntimeManaged("aanestys-2026-2"), request.location)
        assertTrue(request.initGit)
        assertEquals("code", request.projectType)

        val state = presenter.state.value
        assertEquals("new-id", state.createdProjectId)
        assertNull(state.newProject)
        assertNull(state.createError)

        presenter.send(ProjectHubIntent.ConsumeCreated)
        assertNull(presenter.state.value.createdProjectId)
    }

    @Test
    fun `create error stays in the dialog`() = runTest {
        val commands = FakeProjectCommands(
            createResult = ProjectResult.Error(code = "exists", message = "Storage is full"),
        )
        val presenter = ProjectHubPresenter(commands, backgroundScope, Dispatchers.Unconfined)
        presenter.send(ProjectHubIntent.OpenNewProject)
        presenter.send(ProjectHubIntent.EditNewProject(NewProjectDraft("Life", "", "personal")))
        presenter.send(ProjectHubIntent.SubmitNewProject)

        val state = presenter.state.value
        assertEquals("Storage is full", state.createError)
        assertNotNull(state.newProject)
        assertNull(state.createdProjectId)
        assertFalse(state.creating)
        assertFalse(commands.createRequests.single().initGit)
    }
}
