package fi.italeino.aidos.engine.desktop.install

import fi.italeino.aidos.engine.desktop.tray.TrayActions

/**
 * `--install-service <engine|agent|tray> [--system --run-as NAME]` and `--uninstall-service <engine|agent|tray> [--system]`
 * (RFC-0115, D56). Per-user is the default; `--system` registers a machine-wide service that runs as the named
 * account (never root or SYSTEM). The tray is a per-user service by nature: it needs the user's desktop.
 */
object InstallCli {
    /** Returns the process exit code. [out] receives what the user should read. */
    fun run(args: List<String>, out: (String) -> Unit = ::println, installer: ServiceInstaller = ServiceInstaller(), os: OsFamily = OsFamily.current()): Int {
        val uninstall = args.contains("--uninstall-service")
        val verb = if (uninstall) "--uninstall-service" else "--install-service"
        val name = args.getOrNull(args.indexOf(verb) + 1)
        val spec = try {
            specFor(name, args)
        } catch (e: IllegalArgumentException) {
            out(e.message ?: "invalid arguments"); return 2
        }
        val plan = ServiceDefinitions.plan(spec, os)
        if (args.contains("--dry-run")) {
            // Shows exactly what would be written and run, and changes nothing: for checking a machine before the real install.
            out(describe(spec, plan, uninstall))
            return 0
        }
        val result = if (uninstall) installer.uninstall(plan) else installer.install(plan)
        return when (result) {
            is ServiceInstaller.Result.Installed -> { out(if (uninstall) "Removed ${spec.name}." else "Installed ${spec.name}; it starts ${if (spec.scope == InstallScope.USER) "when you sign in" else "with the machine"}."); 0 }
            is ServiceInstaller.Result.NeedsElevation -> { out(result.hint); 3 }
            is ServiceInstaller.Result.Failed -> { out("Failed: ${result.command.joinToString(" ")} exited ${result.exitCode}"); 1 }
        }
    }

    /** The plan in words: files with their content, then each command, as it would be applied. */
    fun describe(spec: ServiceSpec, plan: ServicePlan, uninstall: Boolean): String = buildString {
        appendLine("${if (uninstall) "Would remove" else "Would install"} ${spec.name} (${spec.scope.name.lowercase()}${spec.runAsUser?.let { ", as $it" } ?: ""})" + if (plan.needsElevation) " - needs an administrator or root shell" else "")
        if (!uninstall) plan.files.forEach { f -> appendLine("write ${f.path}:"); f.content.lines().forEach { appendLine("    $it") } }
        (if (uninstall) plan.uninstall else plan.install).forEach { appendLine("run   " + it.joinToString(" ")) }
        if (uninstall) plan.removeFiles.forEach { appendLine("delete $it") }
    }.trimEnd()

    fun specFor(name: String?, args: List<String>): ServiceSpec {
        val own = args.takeWhile { it != "--exec" }
        val system = own.contains("--system")
        val runAs = own.getOrNull(own.indexOf("--run-as") + 1)?.takeIf { own.contains("--run-as") }
        return when (name) {
            "engine" -> ServiceSpec(
                "engine", "Aidos Engine", TrayActions.selfCommand(listOf("--daemon")),
                if (system) InstallScope.SYSTEM else InstallScope.USER, runAs,
                // A machine-wide service has no login session, so no OS keystore: it uses a passphrase vault and starts locked (D56).
                env = if (system) mapOf("AIDOS_VAULT_MODE" to "passphrase") else emptyMap(),
            )
            "tray" -> {
                require(!system) { "the tray needs your desktop, so it can only be installed for the signed-in user" }
                ServiceSpec("tray", "Aidos tray icon", TrayActions.selfCommand(listOf("--tray")), InstallScope.USER)
            }
            "agent" -> {
                // The Agent daemon is a separate program (dev.aidos.daemon); everything after --exec is its command line.
                val at = args.indexOf("--exec")
                // Removing needs only the name; the command is irrelevant to the remove commands.
                if (args.contains("--uninstall-service") && at < 0) return ServiceSpec("agent", "Aidos Agent daemon", listOf("aidos-daemon"), if (system) InstallScope.SYSTEM else InstallScope.USER, runAs)
                require(at >= 0 && at + 1 < args.size) { "the agent service needs its command: --install-service agent [--system --run-as NAME] --exec <command and arguments>" }
                ServiceSpec(
                    "agent", "Aidos Agent daemon", args.drop(at + 1), if (system) InstallScope.SYSTEM else InstallScope.USER, runAs,
                    env = if (system) mapOf("AIDOS_VAULT_MODE" to "passphrase") else emptyMap(),
                )
            }
            else -> throw IllegalArgumentException("usage: --install-service <engine|agent|tray> [--system --run-as NAME] | --uninstall-service <engine|agent|tray> [--system --run-as NAME]")
        }
    }
}
