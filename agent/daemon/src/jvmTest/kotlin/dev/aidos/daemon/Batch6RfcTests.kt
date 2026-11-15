package dev.aidos.daemon

import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.ToolAvailability
import dev.aidos.mcp.McpServerLoadOutcome
import dev.aidos.mcp.McpServerStore
import dev.aidos.mcp.core.McpTransport
import dev.aidos.settings.AvailabilityEvaluator
import dev.aidos.settings.NetworkRequirement
import dev.aidos.settings.ProjectRequirementsParser
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verification for Batch 6 RFCs (RFC-0031, RFC-0048, RFC-0049).
 */
class Batch6RfcTests {

    @Test
    fun `RFC-0031 RuntimeCompositionRoot exposes McpServerRegistry user-scope write path`() {
        val home = Files.createTempDirectory("aidos-rfc0031-daemon").toFile()
        try {
            val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.path), "test") { "2026-11-14T00:00:00Z" }
            val root = testRuntime { userDriver = userDb.driver }
            val registry = root.mcpServerRegistry
            assertNotNull(registry)

            registry.upsert(
                serverId = "github",
                transport = McpTransport.Stdio("npx", listOf("-y", "@modelcontextprotocol/server-github")),
                profiles = setOf(PlatformProfile.DESKTOP),
                autoRestart = true,
                nowIso = "2026-11-14T00:00:00Z",
            )
            val loaded = McpServerStore(userDb.driver).loadAll(PlatformProfile.DESKTOP)
            assertEquals(1, loaded.size)
            assertTrue(loaded.single() is McpServerLoadOutcome.Loaded)

            registry.delete("github")
            assertTrue(McpServerStore(userDb.driver).loadAll(PlatformProfile.DESKTOP).isEmpty())
            userDb.driver.close()
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `RFC-0048 testRuntime and lifecycle scopes compose and dispose deterministically`() {
        val home = Files.createTempDirectory("aidos-rfc0048-lifecycle").toFile()
        try {
            val root = testRuntime { profile = PlatformProfile.HEADLESS_SERVER }
            assertEquals(PlatformProfile.HEADLESS_SERVER, root.platform.profile)

            val runtimeScope = RuntimeScope(root.platform, root)
            val projectDb = AidosStorage.openProject(DesktopPaths.stateDb("${home.path}/proj"), "test") { "2026-11-14T00:00:00Z" }
            var lockReleased = false
            val projectScope = ProjectScope(ProjectId("p1"), projectDb.driver) { lockReleased = true }
            var runCleanedUp = false
            val runScope = RunScope(RunId("r1")) { runCleanedUp = true }

            runScope.close()
            assertTrue(runScope.isDisposed)
            assertTrue(runCleanedUp)

            projectScope.close()
            assertTrue(projectScope.isDisposed)
            assertTrue(lockReleased)

            runtimeScope.close()
            assertTrue(runtimeScope.isDisposed)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `RFC-0049 AvailabilityTier SIDECAR gates on companion app reachability`() {
        val localInference = ToolAvailability(
            profiles = setOf(PlatformProfile.MOBILE),
            tier = AvailabilityTier.SIDECAR,
        )
        assertFalse(localInference.availableOn(PlatformProfile.MOBILE, networkAvailable = true, sidecarAvailable = false))
        assertTrue(localInference.availableOn(PlatformProfile.MOBILE, networkAvailable = false, sidecarAvailable = true))
        assertFalse(localInference.availableOn(PlatformProfile.DESKTOP, networkAvailable = true, sidecarAvailable = true))
    }

    @Test
    fun `RFC-0049 requirements parser and AvailabilityEvaluator classify satisfied, degraded, unsatisfied`() {
        val toml = """
            [requirements]
            tools = ["fs", "git", "shell", "local-model"]
            optional = ["shell"]
            network = "required"
        """.trimIndent()
        val reqs = ProjectRequirementsParser.parse(toml)
        assertEquals(listOf("fs", "git", "shell", "local-model"), reqs.tools)
        assertEquals(setOf("shell"), reqs.optional)
        assertEquals(NetworkRequirement.REQUIRED, reqs.network)

        val mobileOffline = AvailabilityEvaluator.evaluate(
            requirements = reqs,
            profile = PlatformProfile.MOBILE,
            networkAvailable = false,
            sidecarAvailable = false,
        )
        assertEquals(listOf("fs", "git"), mobileOffline.satisfied)
        assertEquals(listOf("shell"), mobileOffline.degraded)
        assertEquals(listOf("local-model", "network"), mobileOffline.unsatisfied)
    }

    @Test
    fun `RFC-0049 daemon RuntimeClientFactory respects PlatformAdapter profile and implements availability()`() = runTest {
        val home = Files.createTempDirectory("aidos-rfc0049-daemon").toFile()
        try {
            val platform = DesktopPlatformAdapter(
                home = home.path,
                profile = PlatformProfile.HEADLESS_SERVER,
                networkCheck = { false },
            )
            val client = RuntimeClientFactory.createRuntimeClient(
                home = home.path,
                installCrashHandler = false,
                platform = platform,
            )
            assertEquals(PlatformProfile.HEADLESS_SERVER, client.runtime.profile())

            val created = client.projects.create(
                CreateProjectRequest(
                    name = "req-proj",
                    description = "Requirements test",
                    location = ProjectLocation.RuntimeManaged("req-proj"),
                )
            )
            assertTrue(created is ProjectResult.Success)
            val projectDir = File(created.project.projectPath)
            File(projectDir, "aidos.toml").writeText(
                """
                [requirements]
                tools = ["fs", "shell", "remote-model"]
                optional = ["remote-model"]
                network = "optional"
                """.trimIndent()
            )

            val report = client.runtime.availability(created.project.id)
            assertEquals(PlatformProfile.HEADLESS_SERVER, report.profile)
            assertFalse(report.networkAvailable)
            assertEquals(listOf("fs", "shell"), report.satisfied)
            assertEquals(listOf("remote-model"), report.degraded)
            assertTrue(report.unsatisfied.isEmpty())
        } finally {
            home.deleteRecursively()
        }
    }
}
