package dev.aidos.cli

import dev.aidos.portability.CliIo
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0031 `aidos mcp` command surface. */
class McpCliTest {
    private val tmp = Files.createTempDirectory("aidos-mcp-cli").toFile()
    private val now = "2026-11-14T00:00:00Z"

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private class TestIo : CliIo {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        override fun out(line: String) { out += line }
        override fun err(line: String) { err += line }
        override fun password(prompt: String): CharArray? = null
        override fun env(name: String): String? = null
    }

    private fun run(vararg args: String): Pair<Int, TestIo> {
        val io = TestIo()
        val cli = McpCli(
            openDriver = { AidosStorage.openUser(DesktopPaths.userDb(tmp.path), "cli-test") { now }.driver },
            io = io,
            nowIso = { now },
        )
        return cli.run(args.toList()) to io
    }

    @Test
    fun `add stdio then list then remove round-trips`() {
        val (c1, io1) = run(
            "add", "github",
            "--transport", "stdio",
            "--command", "npx",
            "--args", "-y,@modelcontextprotocol/server-github",
            "--secret-ref", "GITHUB_TOKEN=github_token",
            "--auto-restart",
        )
        assertEquals(0, c1, io1.err.toString())
        assertTrue(io1.out.single().contains("registered mcp server 'github'"))

        val (c2, io2) = run("list")
        assertEquals(0, c2)
        assertTrue(io2.out.single().contains("github\tstdio (npx)"))

        val (c3, io3) = run("remove", "github")
        assertEquals(0, c3)
        assertTrue(io3.out.single().contains("removed mcp server 'github'"))

        val (c4, io4) = run("list")
        assertEquals(0, c4)
        assertEquals(listOf("no mcp servers registered"), io4.out)
    }

    @Test
    fun `add http validates url and rejects invalid scheme`() {
        val (badCode, badIo) = run("add", "bad", "--transport", "http", "--url", "ftp://example.com/mcp")
        assertEquals(1, badCode)
        assertTrue(badIo.err.any { it.contains("Unsupported MCP endpoint scheme") })

        val (okCode, okIo) = run(
            "add", "issues",
            "--transport", "http",
            "--url", "https://mcp.example.com/v1",
            "--secret-ref", "Authorization=issues_token",
        )
        assertEquals(0, okCode, okIo.err.toString())
        val (_, listIo) = run("list")
        assertTrue(listIo.out.single().contains("issues\thttp (https://mcp.example.com/v1)"))
    }

    @Test
    fun `missing arguments or unknown subcommand return usage exit 2`() {
        assertEquals(2, run().first)
        assertEquals(2, run("unknown").first)
        assertEquals(2, run("add", "s1").first)
        // stdio on MOBILE is rejected by McpServerRegistry (exit 1)
        val (mobileStdioCode, mobileIo) = run("add", "s1", "--transport", "stdio", "--command", "npx", "--profiles", "MOBILE")
        assertEquals(1, mobileStdioCode)
        assertTrue(mobileIo.err.any { it.contains("MOBILE") })
    }
}
