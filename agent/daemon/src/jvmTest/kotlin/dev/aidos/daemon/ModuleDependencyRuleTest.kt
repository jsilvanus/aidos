package dev.aidos.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enforces RFC-0048 §The dependency rule:
 * "Services depend on kernel contracts. Services never depend on other services."
 */
class ModuleDependencyRuleTest {
    private val serviceModules = setOf(
        "amp",
        "filesystem",
        "git",
        "http",
        "memory",
        "mcp-broker",
        "portability",
        "prompt",
        "retention",
        "routing",
        "worker",
    )

    private fun locateAgentDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists() && File(dir, "daemon").exists()) return dir
            val agentSub = File(dir, "agent")
            if (File(agentSub, "settings.gradle.kts").exists() && File(agentSub, "daemon").exists()) return agentSub
            dir = dir.parentFile
        }
        error("Could not locate agent/ directory from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `no service module depends on another service module`() {
        val agentDir = locateAgentDir()
        val projectDepRegex = Regex("""project\(\s*":([a-zA-Z0-9_-]+)"\s*\)""")
        val violations = mutableListOf<String>()

        for (module in serviceModules) {
            val buildFile = File(agentDir, "$module/build.gradle.kts")
            assertTrue(buildFile.exists(), "Expected build.gradle.kts for service module :$module")
            val content = buildFile.readText()
            val deps = projectDepRegex.findAll(content).map { it.groupValues[1] }.toSet()
            val forbidden = deps.intersect(serviceModules - module)
            if (forbidden.isNotEmpty()) {
                violations += ":$module depends on service module(s): $forbidden"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "RFC-0048 dependency rule violated:\n${violations.joinToString("\n")}",
        )
    }
}
