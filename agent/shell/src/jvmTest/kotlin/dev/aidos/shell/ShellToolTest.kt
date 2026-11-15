package dev.aidos.shell

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ShellHandle
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShellToolTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")
    private val cap = CapabilityId("cap-shell")

    private fun project(): File = Files.createTempDirectory("shell-tool").toFile().also {
        File(it, "sub").mkdirs()
        File(it, "marker.txt").writeText("hi")
    }

    private fun handle(dir: String = ".", allowed: List<String>? = null) = ShellHandle(cap, dir, allowed)

    private fun args(command: String, cwd: String? = null, timeout: Int? = null) = buildJsonObject {
        put("command", JsonPrimitive(command))
        cwd?.let { put("working_directory", JsonPrimitive(it)) }
        timeout?.let { put("timeout_seconds", JsonPrimitive(it)) }
    }

    private fun body(r: dev.aidos.kernel.ToolCallResult) =
        Json.parseToJsonElement((r.content.single() as dev.aidos.kernel.ContentBlock.Text).text).jsonObject

    private val sleepCommand get() = if (windows) "ping -n 8 127.0.0.1 >nul" else "sleep 8"
    private val manyLines get() = if (windows) "for /L %i in (1,1,2000) do @echo line%i" else "i=0; while [ \$i -lt 2000 ]; do echo line\$i; i=\$((i+1)); done"
    private val javaExe: String get() = File(System.getProperty("java.home"), "bin/java").path

    @Test
    fun `descriptor is desktop-only, unsafe to retry, and needs the shell permission`() {
        val d = ShellTool(project()).operations().single()
        assertEquals("shell:exec", d.name)
        assertEquals(dev.aidos.kernel.Permission.SHELL_EXEC, d.requiredPermission)
        assertEquals(dev.aidos.kernel.RecoveryClass.UNSAFE, d.recoveryClass)
        assertTrue(d.availability.availableOn(PlatformProfile.DESKTOP, false))
        assertTrue(d.availability.availableOn(PlatformProfile.HEADLESS_SERVER, false))
        assertFalse(d.availability.availableOn(PlatformProfile.MOBILE, false), "the model must never be told shell exists on a phone")
    }

    @Test
    fun `runs a command in the project, captures output and exit code, and marks it untrusted`(): Unit = runBlocking {
        val logs = mutableListOf<ShellLog>()
        val tool = ShellTool(project(), onExecuted = { logs += it })
        val r = tool.execute(handle(), "shell:exec", args("echo hello"))
        assertEquals(ToolOutcome.Ok, r.outcome)
        assertEquals(TrustLevel.UNTRUSTED, r.trustLevel)
        val b = body(r)
        assertEquals(0, b["exit_code"]!!.jsonPrimitive.int)
        assertTrue(b["stdout"]!!.jsonPrimitive.content.contains("hello"))
        assertEquals(1, logs.size)
        assertEquals("echo hello", logs[0].command)
        assertEquals(0, logs[0].exitCode)
    }

    @Test
    fun `a failing command is the command's result, not a tool failure`(): Unit = runBlocking {
        val r = ShellTool(project()).execute(handle(), "shell:exec", args("exit 3"))
        assertEquals(ToolOutcome.Ok, r.outcome)
        assertEquals(3, body(r)["exit_code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `working directory is confined to the capability scope`(): Unit = runBlocking {
        val root = project()
        val tool = ShellTool(root)
        // Scope is the sub directory: the project root is out of reach, even by relative or absolute path.
        for (escape in listOf("..", "../", root.path)) {
            val r = tool.execute(handle("sub"), "shell:exec", args("echo x", cwd = escape))
            assertTrue(r.outcome is ToolOutcome.Failed, "should refuse cwd=$escape")
        }
        val ok = tool.execute(handle("."), "shell:exec", args("echo x", cwd = "sub"))
        assertEquals(ToolOutcome.Ok, ok.outcome)
        // A scope that itself points outside the project is refused too.
        val outside = tool.execute(handle("../"), "shell:exec", args("echo x"))
        assertTrue(outside.outcome is ToolOutcome.Failed)
    }

    @Test
    fun `a symlink out of the scope is not a way out`(): Unit = runBlocking {
        val root = project()
        val outside = Files.createTempDirectory("shell-outside").toFile()
        val link = File(root, "link").toPath()
        val made = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        if (!made) return@runBlocking // symlinks need privileges on some Windows setups
        val r = ShellTool(root).execute(handle("."), "shell:exec", args("echo x", cwd = "link"))
        assertTrue(r.outcome is ToolOutcome.Failed)
    }

    @Test
    fun `allowedCommands runs without a shell and refuses anything else`(): Unit = runBlocking {
        val tool = ShellTool(project())
        val allowed = handle(allowed = listOf("java"))
        val ok = tool.execute(allowed, "shell:exec", args("\"$javaExe\" -version"))
        assertEquals(ToolOutcome.Ok, ok.outcome)
        assertEquals(0, body(ok)["exit_code"]!!.jsonPrimitive.int)

        for (bad in listOf("whoami", "\"$javaExe\" -version && whoami", "\"$javaExe\" -version; whoami", "\"$javaExe\" -version | more", "\"$javaExe\" `whoami`")) {
            val r = tool.execute(allowed, "shell:exec", args(bad))
            assertTrue(r.outcome is ToolOutcome.Failed, "should refuse: $bad")
        }
    }

    @Test
    fun `the child gets a scrubbed environment`(): Unit = runBlocking {
        val tool = ShellTool(project(), ambientEnv = { System.getenv() + mapOf("AIDOS_SECRET_TOKEN" to "s3cr3t-value", "ANTHROPIC_API_KEY" to "sk-live-xyz") })
        val cmd = if (windows) "set" else "env"
        val out = body(tool.execute(handle(), "shell:exec", args(cmd)))["stdout"]!!.jsonPrimitive.content
        assertFalse(out.contains("s3cr3t-value"))
        assertFalse(out.contains("sk-live-xyz"))
        assertFalse(out.contains("AIDOS_SECRET_TOKEN"))
    }

    @Test
    fun `a command that runs past its timeout is killed and reported`(): Unit = runBlocking {
        val started = System.currentTimeMillis()
        val r = ShellTool(project()).execute(handle(), "shell:exec", args(sleepCommand, timeout = 1))
        val failed = r.outcome as ToolOutcome.Failed
        assertEquals(ErrorCodes.TOOL_TIMEOUT, failed.error.code)
        assertTrue(body(r)["timed_out"]!!.jsonPrimitive.boolean)
        assertTrue(System.currentTimeMillis() - started < 7_000, "must not wait for the whole sleep")
    }

    @Test
    fun `output is capped per stream and flagged as truncated`(): Unit = runBlocking {
        val tool = ShellTool(project(), maxOutputBytes = 200)
        val r = tool.execute(handle(), "shell:exec", args(manyLines))
        val b = body(r)
        assertTrue(b["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(b["stdout"]!!.jsonPrimitive.content.length <= 200)
        assertEquals(0, b["exit_code"]!!.jsonPrimitive.int, "the child was drained, not blocked on a full pipe")
    }

    @Test
    fun `secrets are redacted from output and from the logged command`(): Unit = runBlocking {
        val logs = mutableListOf<ShellLog>()
        val tool = ShellTool(project(), redact = { it.replace("hunter2", "[REDACTED]") }, onExecuted = { logs += it })
        val r = tool.execute(handle(), "shell:exec", args("echo hunter2"))
        val text = body(r)["stdout"]!!.jsonPrimitive.content
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("[REDACTED]"))
        assertFalse(logs.single().command.contains("hunter2"))
    }

    @Test
    fun `without a shell capability handle the tool refuses`(): Unit = runBlocking {
        val r = ShellTool(project()).execute(dev.aidos.kernel.BasicResourceHandle(cap), "shell:exec", args("echo x"))
        assertTrue(r.outcome is ToolOutcome.Failed)
        assertNotEquals(TrustLevel.UNTRUSTED, r.trustLevel)
    }

    @Test
    fun `stdin is closed so a command that waits for input does not hang`(): Unit = runBlocking {
        val cmd = if (windows) "more" else "cat"
        val r = ShellTool(project()).execute(handle(), "shell:exec", args(cmd, timeout = 20))
        assertEquals(ToolOutcome.Ok, r.outcome)
    }
}
