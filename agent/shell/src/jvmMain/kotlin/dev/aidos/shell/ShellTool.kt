package dev.aidos.shell

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.ShellHandle
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** One execution, as logged for audit (RFC-0033 "Logging"). The command is redacted before it is built. */
data class ShellLog(
    val command: String,
    val workingDirectory: String,
    val exitCode: Int?,
    val durationMs: Long,
    val outputSizeBytes: Int,
    val timedOut: Boolean,
)

/**
 * `shell:exec` (RFC-0033): run one command in a confined working directory.
 *
 * - **Where it exists.** DESKTOP and HEADLESS_SERVER only (`PLATFORM` tier). On MOBILE the
 *   descriptor is filtered out before the model ever sees it.
 * - **Scope is enforced here**, from the [ShellHandle] the capability manager opens: the working
 *   directory can never leave the scope's directory (symlinks resolved), and when the grant names
 *   `allowedCommands` the command runs *without a shell* and must start with one of them, so a
 *   `;` or `&&` cannot smuggle in another program.
 * - **Minimal environment.** The child inherits only an allowlist of ambient variables, never the
 *   daemon's secrets.
 * - **Limits.** Default 300 s timeout (max 3600 s), 10 MB per output stream, one process tree per
 *   command; on timeout or cancellation the whole tree is killed.
 * - **Result trust.** Output is `UNTRUSTED` (RFC-0027): a command can print anything.
 */
class ShellTool(
    projectRoot: File,
    /** RFC-0035: applied to captured output and to the logged command. */
    private val redact: (String) -> String = { it },
    private val onExecuted: (ShellLog) -> Unit = {},
    private val ambientEnv: () -> Map<String, String> = { System.getenv() },
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    private val defaultTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val maxTimeoutSeconds: Long = MAX_TIMEOUT_SECONDS,
) : Tool {
    private val root: File = projectRoot.canonicalFile

    override val id = "shell"
    override val version = "0.1.0"

    private val desktopOnly = ToolAvailability(
        setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER),
        AvailabilityTier.PLATFORM,
    )

    override fun operations(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = OPERATION,
            title = "Run command",
            description = "Run a command in the project's working directory and return its exit code, stdout and stderr. " +
                "Each call is a separate process; there is no interactive session.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject { put("type", "string") })
                    put("working_directory", buildJsonObject { put("type", "string") })
                    put("timeout_seconds", buildJsonObject { put("type", "integer") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("command")) })
            },
            // A command can touch anything its working directory reaches and cannot be undone or retried safely.
            effect = EffectKind.Mutate(MutationScope.OUT_OF_PROJECT, reversible = false),
            requiredPermission = Permission.SHELL_EXEC,
            recoveryClass = RecoveryClass.UNSAFE,
            availability = desktopOnly,
            resultGuidance = "A non-zero exit_code is the command's own result, not a tool failure. " +
                "Output is truncated at 10 MB per stream; truncated=true means the tail was dropped.",
        ),
    )

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        Result.success(
            Preview.Description(
                "Run in ${arguments["working_directory"]?.jsonPrimitive?.contentOrNull ?: "."}: " +
                    redact(arguments["command"]?.jsonPrimitive?.contentOrNull ?: ""),
            ),
        )

    override suspend fun cancel(operationId: String) = Unit

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult {
        if (operation != OPERATION) return failed("unknown operation: $operation", ErrorCodes.TOOL_UNKNOWN, ErrorClass.INVALID_INPUT)
        val shell = handle as? ShellHandle
            ?: return failed("shell:exec needs a Shell capability", ErrorCodes.TOOL_EXECUTION_FAILED, ErrorClass.DENIED)
        val command = arguments["command"]?.jsonPrimitive?.contentOrNull?.trim()
        if (command.isNullOrEmpty()) return failed("command is required", ErrorCodes.TOOL_INVALID_ARGUMENTS, ErrorClass.INVALID_INPUT)
        val timeout = (arguments["timeout_seconds"]?.jsonPrimitive?.intOrNull?.toLong() ?: defaultTimeoutSeconds)
            .coerceIn(1L, maxTimeoutSeconds)

        val cwd = resolveWorkingDirectory(shell, arguments["working_directory"]?.jsonPrimitive?.contentOrNull)
            ?: return failed("working_directory is outside this capability's scope", ErrorCodes.TOOL_EXECUTION_FAILED, ErrorClass.DENIED)

        val argv = buildArgv(command, shell.allowedCommands)
            ?: return failed(
                "command is not permitted by this capability (allowed: ${shell.allowedCommands?.joinToString(", ")}; shell operators are refused)",
                ErrorCodes.TOOL_EXECUTION_FAILED,
                ErrorClass.DENIED,
            )

        return run(argv, cwd, command, timeout)
    }

    // ── Scope ─────────────────────────────────────────────────────────────────

    private fun resolveWorkingDirectory(shell: ShellHandle, requested: String?): File? {
        val scopeDir = File(root, shell.workingDirectory.ifBlank { "." }).canonicalFile
        if (!isInside(root, scopeDir)) return null
        val target = if (requested.isNullOrBlank()) scopeDir else {
            val f = File(requested)
            (if (f.isAbsolute) f else File(scopeDir, requested)).canonicalFile
        }
        return target.takeIf { isInside(scopeDir, it) && it.isDirectory }
    }

    private fun isInside(parent: File, child: File): Boolean =
        child.path == parent.path || child.path.startsWith(parent.path + File.separator)

    /** `null` means the command is refused. */
    private fun buildArgv(command: String, allowed: List<String>?): List<String>? {
        if (allowed == null) return platformShell(command)
        if (command.any { it in SHELL_METACHARACTERS } || command.contains("\n")) return null
        val tokens = tokenize(command) ?: return null
        val program = tokens.firstOrNull() ?: return null
        val name = File(program).name.removeSuffix(".exe").removeSuffix(".cmd").removeSuffix(".bat")
        val ok = allowed.any { it == program || it == name }
        return if (ok) tokens else null
    }

    private fun platformShell(command: String): List<String> =
        if (System.getProperty("os.name").lowercase().contains("win")) listOf("cmd", "/c", command)
        else listOf("sh", "-c", command)

    /** Whitespace split honouring single and double quotes; null on an unterminated quote. */
    private fun tokenize(s: String): List<String>? {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in s) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; any = true }
                c.isWhitespace() -> if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false }
                else -> cur.append(c)
            }
        }
        if (quote != null) return null
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    private suspend fun run(argv: List<String>, cwd: File, command: String, timeoutSeconds: Long): ToolCallResult {
        val started = System.nanoTime()
        val pb = ProcessBuilder(argv).directory(cwd).redirectInput(ProcessBuilder.Redirect.PIPE)
        pb.environment().apply { clear(); putAll(scrubbedEnvironment(ambientEnv())) }
        val process = try {
            pb.start()
        } catch (e: Exception) {
            return failed("could not start: ${e.message}", ErrorCodes.TOOL_EXECUTION_FAILED, ErrorClass.INVALID_INPUT)
        }
        runCatching { process.outputStream.close() } // no stdin: a command that waits for input gets EOF, not a hang

        var timedOut = false
        var exitCode: Int? = null
        var stdout = Captured("", false)
        var stderr = Captured("", false)
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { capture(process.inputStream) }
                val err = async(Dispatchers.IO) { capture(process.errorStream) }
                val finished = runInterruptible(Dispatchers.IO) { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
                if (!finished) {
                    timedOut = true
                    killTree(process)
                } else {
                    exitCode = process.exitValue()
                }
                stdout = out.await()
                stderr = err.await()
            }
        } catch (e: CancellationException) {
            killTree(process)
            throw e
        } finally {
            if (process.isAlive) killTree(process)
        }

        val durationMs = (System.nanoTime() - started) / 1_000_000
        val outText = redact(stdout.text)
        val errText = redact(stderr.text)
        val size = stdout.text.length + stderr.text.length
        onExecuted(ShellLog(redact(command), cwd.relativeToOrSelf(root).path.ifEmpty { "." }, exitCode, durationMs, size, timedOut))

        val body = buildJsonObject {
            put("exit_code", exitCode?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            put("stdout", outText)
            put("stderr", errText)
            put("duration_ms", durationMs)
            put("truncated", stdout.truncated || stderr.truncated)
            put("timed_out", timedOut)
        }
        val content = listOf(ContentBlock.Text(body.toString()))
        return if (timedOut) {
            ToolCallResult(
                "",
                ToolOutcome.Failed(AidosError(ErrorCodes.TOOL_TIMEOUT, ErrorClass.TRANSIENT, "command timed out after ${timeoutSeconds}s and was killed")),
                content,
                TrustLevel.UNTRUSTED,
            )
        } else {
            ToolCallResult("", ToolOutcome.Ok, content, TrustLevel.UNTRUSTED)
        }
    }

    private class Captured(val text: String, val truncated: Boolean)

    /** Reads to EOF but keeps at most [maxOutputBytes]; the rest is drained so the child never blocks on a full pipe. */
    private fun capture(stream: InputStream): Captured {
        val buf = java.io.ByteArrayOutputStream()
        var truncated = false
        val chunk = ByteArray(8192)
        stream.use { s ->
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                val room = maxOutputBytes - buf.size()
                if (room > 0) buf.write(chunk, 0, minOf(n, room))
                if (n > room) truncated = true
            }
        }
        return Captured(buf.toString(Charsets.UTF_8), truncated)
    }

    private fun killTree(p: Process) {
        p.toHandle().descendants().forEach { runCatching { it.destroyForcibly() } }
        runCatching { p.destroyForcibly() }
        runCatching { p.waitFor(5, TimeUnit.SECONDS) }
    }

    private fun failed(message: String, code: String, cls: ErrorClass) = ToolCallResult(
        "",
        ToolOutcome.Failed(AidosError(code, cls, message)),
        listOf(ContentBlock.Text("error: $message")),
        TrustLevel.TRUSTED,
    )

    companion object {
        const val OPERATION = "shell:exec"
        const val DEFAULT_TIMEOUT_SECONDS = 300L
        const val MAX_TIMEOUT_SECONDS = 3600L
        const val DEFAULT_MAX_OUTPUT_BYTES = 10 * 1024 * 1024
        private val SHELL_METACHARACTERS = setOf(';', '&', '|', '<', '>', '`', '$', '(', ')', '\r')

        private val ALLOWED_AMBIENT = setOf(
            "PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TZ",
            // Windows needs these for cmd and child processes to start at all.
            "SYSTEMROOT", "COMSPEC", "PATHEXT", "TEMP", "TMP",
        )

        /** Allowlist, never a denylist: the child gets nothing from the daemon's environment unless named here. */
        fun scrubbedEnvironment(ambient: Map<String, String>): Map<String, String> =
            ambient.filterKeys { it.uppercase() in ALLOWED_AMBIENT }
    }
}
