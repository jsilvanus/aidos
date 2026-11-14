package dev.aidos.androidapp

import dev.aidos.androidapp.ui.projects.AgentModelLocality
import dev.aidos.androidapp.ui.projects.AgentTaintPolicy
import dev.aidos.androidapp.ui.projects.GuardianConfigState
import dev.aidos.androidapp.ui.projects.ModelRoutingConfigState
import dev.aidos.androidapp.ui.projects.ProjectsPresenter
import dev.aidos.androidapp.ui.projects.ProjectsUiState
import dev.aidos.androidapp.ui.runs.RunLaunchConfigState
import dev.aidos.androidapp.ui.runs.RunListPresenter
import dev.aidos.androidapp.ui.runs.RunListUiState
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.RunOptions
import dev.aidos.api.RunResult
import dev.aidos.api.RuntimeClient
import dev.aidos.api.SessionCommands
import dev.aidos.api.SessionResult
import dev.aidos.api.UserMessage
import dev.aidos.settings.SettingsStore
import dev.aidos.settings.SettingsWriter
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingSessionClient(
    private val delegate: MockRuntimeClient = MockRuntimeClient(),
) : RuntimeClient by delegate {
    val sentMessages = mutableListOf<Pair<String, UserMessage>>()

    override val sessions: SessionCommands = object : SessionCommands by delegate.sessions {
        override suspend fun send(sessionId: String, message: UserMessage): RunResult {
            sentMessages += sessionId to message
            return delegate.sessions.send(sessionId, message)
        }
    }
}

class ModelRoutingAndGuardianPresenterTest {

    @Test
    fun `global and project model routing and guardian configs persist via SettingsStore and SettingsWriter`() = runTest {
        val tempRoot = Files.createTempDirectory("aidos-androidapp-routing-test").toFile()
        try {
            val userDb = AidosStorage.openUser(DesktopPaths.userDb(tempRoot.path), "test-1.0") {
                "2026-11-14T12:00:00Z"
            }
            val projectDb = AidosStorage.openProject(DesktopPaths.stateDb(tempRoot.path), "test-1.0") {
                "2026-11-14T12:00:00Z"
            }
            val store = SettingsStore(userDb.driver, projectDb.driver)
            val writer = SettingsWriter(userDb.driver, projectDb.driver)
            val client = MockRuntimeClient()

            val projResult = client.projects.create(
                CreateProjectRequest("Alpha", "Project Alpha", ProjectLocation.RuntimeManaged("alpha"))
            )
            val projectId = (projResult as ProjectResult.Success).project.id

            val presenter = ProjectsPresenter(
                client = client,
                scope = this,
                settingsStore = store,
                settingsWriter = writer,
                nowIso = { "2026-11-14T12:00:00Z" },
            )
            presenter.loadProjects()
            presenter.state.first { it is ProjectsUiState.Ready }

            presenter.updateGlobalMainModel("qwen2.5-14b")
            presenter.updateGlobalDefaultRunModel("qwen2.5-3b")
            presenter.updateGlobalLocality(AgentModelLocality.TRUSTED_CLUSTER)
            presenter.updateGlobalGuardianPolicy(AgentTaintPolicy.GUARDIAN)
            presenter.updateGlobalDecisionModel("laya-multilingual")
            presenter.updateGlobalGuardianLlm("guardian-7b")

            presenter.updateProjectMainModel(projectId, "llama3-8b")
            presenter.updateProjectDefaultRunModel(projectId, "llama3-3b")
            presenter.updateProjectLocality(projectId, AgentModelLocality.LOCAL_ONLY)
            presenter.updateProjectGuardianPolicy(projectId, AgentTaintPolicy.MANUAL)
            presenter.updateProjectDecisionModel(projectId, "laya-strict")
            presenter.updateProjectGuardianLlm(projectId, "guardian-local")
            presenter.updateProjectAllowMainModelOverride(projectId, false)

            val ready = assertIs<ProjectsUiState.Ready>(presenter.state.value)
            assertEquals("qwen2.5-14b", ready.globalConfig.mainModelId)
            assertEquals(AgentModelLocality.TRUSTED_CLUSTER, ready.globalConfig.enforcedLocality)
            assertEquals(AgentTaintPolicy.GUARDIAN, ready.globalConfig.guardianConfig.policy)

            val projectConfig = ready.configForProject(projectId)
            assertEquals("llama3-8b", projectConfig.mainModelId)
            assertEquals("llama3-3b", projectConfig.defaultRunModelId)
            assertEquals(AgentModelLocality.LOCAL_ONLY, projectConfig.enforcedLocality)
            assertEquals(AgentTaintPolicy.MANUAL, projectConfig.guardianConfig.policy)
            assertEquals("laya-strict", projectConfig.guardianConfig.decisionModelId)
            assertEquals("guardian-local", projectConfig.guardianConfig.guardianLlmModelId)
            assertEquals(false, projectConfig.allowMainModelRunOverride)

            // Verify a fresh presenter rehydrates persisted global and project defaults from SQLite.
            val reloaded = ProjectsPresenter(
                client = client,
                scope = this,
                settingsStore = store,
                settingsWriter = writer,
                nowIso = { "2026-11-14T12:05:00Z" },
            )
            reloaded.loadProjects()
            val reloadedReady = assertIs<ProjectsUiState.Ready>(
                reloaded.state.first { it is ProjectsUiState.Ready }
            )
            assertEquals("qwen2.5-14b", reloadedReady.globalConfig.mainModelId)
            assertEquals("qwen2.5-3b", reloadedReady.globalConfig.defaultRunModelId)
            assertEquals(AgentModelLocality.TRUSTED_CLUSTER, reloadedReady.globalConfig.enforcedLocality)
            assertEquals(AgentTaintPolicy.GUARDIAN, reloadedReady.globalConfig.guardianConfig.policy)
            assertEquals("guardian-7b", reloadedReady.globalConfig.guardianConfig.guardianLlmModelId)

            val reloadedProject = reloadedReady.configForProject(projectId)
            assertEquals("llama3-8b", reloadedProject.mainModelId)
            assertEquals(AgentModelLocality.LOCAL_ONLY, reloadedProject.enforcedLocality)
            assertEquals(AgentTaintPolicy.MANUAL, reloadedProject.guardianConfig.policy)

            userDb.driver.close()
            projectDb.driver.close()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `RunLaunchConfigState toRunOptions maps overrides and project defaults cleanly`() {
        val projectDefaults = ModelRoutingConfigState(
            mainModelId = "qwen-main",
            defaultRunModelId = "qwen-worker",
            enforcedLocality = AgentModelLocality.TRUSTED_CLUSTER,
            guardianConfig = GuardianConfigState(policy = AgentTaintPolicy.GUARDIAN),
        )

        // Inherit everything from project defaults when launch overrides are null/blank.
        val inherited = RunLaunchConfigState().toRunOptions(
            base = RunOptions(maxTokens = 2048, requireApprovalBeforeToolUse = true, timeoutSeconds = 120),
            projectDefaults = projectDefaults,
        )
        assertEquals("GUARDIAN", inherited.taintApprovalPolicy)
        assertEquals("qwen-worker", inherited.modelId)
        assertEquals("cluster", inherited.modelLocality)
        assertEquals(2048, inherited.maxTokens)
        assertTrue(inherited.requireApprovalBeforeToolUse)
        assertEquals(120, inherited.timeoutSeconds)

        // Explicit per-run overrides supersede project defaults.
        val overridden = RunLaunchConfigState(
            guardianPolicyOverride = AgentTaintPolicy.MANUAL,
            runModelOverride = "local-override-3b",
            localityOverride = AgentModelLocality.LOCAL_ONLY,
        ).toRunOptions(projectDefaults = projectDefaults)
        assertEquals("MANUAL", overridden.taintApprovalPolicy)
        assertEquals("local-override-3b", overridden.modelId)
        assertEquals("local", overridden.modelLocality)

        // Without project defaults, unset fields stay null.
        val bare = RunLaunchConfigState().toRunOptions()
        assertNull(bare.taintApprovalPolicy)
        assertNull(bare.modelId)
        assertNull(bare.modelLocality)
    }

    @Test
    fun `RunListPresenter sendMessage attaches mapped RunOptions and records run metadata`() = runTest {
        val client = RecordingSessionClient()
        val projectId = (client.projects.create(
            CreateProjectRequest("Beta", "Desc", ProjectLocation.RuntimeManaged("beta"))
        ) as ProjectResult.Success).project.id
        val sessionId = (client.sessions.create(
            dev.aidos.api.CreateSessionRequest(projectId, "Main session")
        ) as SessionResult.Success).session.id

        val projectDefaults = ModelRoutingConfigState(
            mainModelId = "qwen-main",
            defaultRunModelId = "qwen-fast",
            allowMainModelRunOverride = true,
            enforcedLocality = AgentModelLocality.LOCAL_ONLY,
            guardianConfig = GuardianConfigState(policy = AgentTaintPolicy.MANUAL),
        )

        val presenter = RunListPresenter(client, this)
        presenter.load(sessionId)
        presenter.state.first { it is RunListUiState.Ready }

        presenter.setGuardianPolicyOverride(AgentTaintPolicy.GUARDIAN)
        presenter.setLocalityOverride(AgentModelLocality.TRUSTED_CLUSTER)
        presenter.sendMessage(
            sessionId = sessionId,
            content = "Inspect repository security boundaries",
            projectDefaults = projectDefaults,
        )

        val ready = presenter.state.first {
            it is RunListUiState.Ready && it.runMetadata.isNotEmpty()
        } as RunListUiState.Ready

        assertEquals(1, client.sentMessages.size)
        val (sentSessionId, sentMessage) = client.sentMessages.first()
        assertEquals(sessionId, sentSessionId)
        assertEquals("GUARDIAN", sentMessage.runOptions.taintApprovalPolicy)
        assertEquals("qwen-fast", sentMessage.runOptions.modelId)
        assertEquals("cluster", sentMessage.runOptions.modelLocality)

        val meta = ready.runMetadata.values.firstOrNull()
        assertNotNull(meta)
        assertEquals(AgentTaintPolicy.GUARDIAN, meta.guardianPolicy)
        assertEquals("qwen-fast", meta.modelId)
        assertTrue(meta.modelChosenByMainModel)
        assertEquals(AgentModelLocality.TRUSTED_CLUSTER, meta.enforcedLocality)
    }
}
