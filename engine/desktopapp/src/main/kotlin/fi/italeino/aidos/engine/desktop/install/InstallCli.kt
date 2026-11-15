package fi.italeino.aidos.engine.desktop.install

import fi.italeino.aidos.engine.desktop.tray.TrayActions

/**
 * `--install-service <engine|tray> [--system --run-as NAME]` and `--uninstall-service <engine|tray> [--system]`
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
        val result = if (uninstall) installer.uninstall(plan) else installer.install(plan)
        return when (result) {
            is ServiceInstaller.Result.Installed -> { out(if (uninstall) "Removed ${spec.name}." else "Installed ${spec.name}; it starts ${if (spec.scope == InstallScope.USER) "when you sign in" else "with the machine"}."); 0 }
            is ServiceInstaller.Result.NeedsElevation -> { out(result.hint); 3 }
            is ServiceInstaller.Result.Failed -> { out("Failed: ${result.command.joinToString(" ")} exited ${result.exitCode}"); 1 }
        }
    }

    fun specFor(name: String?, args: List<String>): ServiceSpec {
        val system = args.contains("--system")
        val runAs = args.getOrNull(args.indexOf("--run-as") + 1)?.takeIf { args.contains("--run-as") }
        return when (name) {
            "engine" -> ServiceSpec(
                "engine", "Aidos Engine", TrayActions.selfCommand(listOf("--daemon")),
                if (system) InstallScope.SYSTEM else InstallScope.USER, runAs,
            )
            "tray" -> {
                require(!system) { "the tray needs your desktop, so it can only be installed for the signed-in user" }
                ServiceSpec("tray", "Aidos tray icon", TrayActions.selfCommand(listOf("--tray")), InstallScope.USER)
            }
            else -> throw IllegalArgumentException("usage: --install-service <engine|tray> [--system --run-as NAME] | --uninstall-service <engine|tray> [--system --run-as NAME]")
        }
    }
}
