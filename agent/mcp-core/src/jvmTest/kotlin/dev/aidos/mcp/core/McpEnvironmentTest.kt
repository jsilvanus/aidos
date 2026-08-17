package dev.aidos.mcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [scrubbedEnvironment]'s filtering logic (RFC-0031 Security §3), proven in isolation from ProcessBuilder. */
class McpEnvironmentTest {

    private val fakeAmbient = mapOf(
        "PATH" to "/usr/bin:/bin",
        "HOME" to "/home/user",
        "ANTHROPIC_API_KEY" to "sk-ant-should-never-leak",
        "AIDOS_INTERNAL_SECRET" to "should-never-leak-either",
        "RANDOM_LOCAL_VAR" to "not-on-the-allowlist",
    )

    @Test
    fun `allowlisted ambient vars pass through`() {
        val result = scrubbedEnvironment(ambient = fakeAmbient)
        assertEquals("/usr/bin:/bin", result["PATH"])
        assertEquals("/home/user", result["HOME"])
    }

    @Test
    fun `ambient vars off the allowlist never reach the child`() {
        val result = scrubbedEnvironment(ambient = fakeAmbient)
        assertFalse(result.containsKey("ANTHROPIC_API_KEY"), "an unrelated provider credential must not leak into an MCP server's environment")
        assertFalse(result.containsKey("AIDOS_INTERNAL_SECRET"), "no runtime-internal variable reaches a spawned server (RFC-0031: no token, no socket path)")
        assertFalse(result.containsKey("RANDOM_LOCAL_VAR"), "the allowlist is deny-by-default, not a denylist of known-bad names")
    }

    @Test
    fun `an explicit secret_ref env var is included even though it is not ambient`() {
        val result = scrubbedEnvironment(extra = mapOf("GITHUB_TOKEN" to "resolved-from-vault"), ambient = fakeAmbient)
        assertEquals("resolved-from-vault", result["GITHUB_TOKEN"])
    }

    @Test
    fun `an explicit value wins over an ambient one of the same name`() {
        val result = scrubbedEnvironment(extra = mapOf("PATH" to "/custom/path"), ambient = fakeAmbient)
        assertEquals("/custom/path", result["PATH"])
    }

    @Test
    fun `with no extra vars and an empty ambient map, the result is empty`() {
        assertTrue(scrubbedEnvironment(ambient = emptyMap()).isEmpty())
    }
}
