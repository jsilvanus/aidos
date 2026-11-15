package fi.italeino.aidos.engine.desktop.install

import java.io.File

/**
 * Applies a [ServicePlan] (RFC-0115, D56). Files are written first, then the install commands run; if a command
 * fails the files are removed again so a failed install leaves nothing behind. The runner is injected so tests need
 * no real service manager.
 */
class ServiceInstaller(
    private val run: (List<String>) -> Int = ::runCommand,
    private val isElevated: () -> Boolean = ::processIsElevated,
) {
    sealed interface Result {
        data object Installed : Result
        data class NeedsElevation(val hint: String) : Result
        data class Failed(val command: List<String>, val exitCode: Int) : Result
    }

    fun install(plan: ServicePlan): Result {
        if (plan.needsElevation && !isElevated()) return Result.NeedsElevation("Installing for the whole machine needs an administrator (or root) shell. Run this again from one.")
        plan.files.forEach { f -> File(f.path).apply { parentFile?.mkdirs(); writeText(f.content) } }
        for (cmd in plan.install) {
            val code = run(cmd)
            if (code != 0) {
                plan.removeFiles.forEach { File(it).delete() }
                return Result.Failed(cmd, code)
            }
        }
        return Result.Installed
    }

    /** True when the service is registered: its definition file exists, or (Windows) its task answers a query. */
    fun isInstalled(plan: ServicePlan): Boolean =
        if (plan.files.isNotEmpty()) plan.files.all { File(it.path).isFile }
        else plan.queryCommand?.let { run(it) == 0 } ?: false

    fun uninstall(plan: ServicePlan): Result {
        if (plan.needsElevation && !isElevated()) return Result.NeedsElevation("Removing a machine-wide service needs an administrator (or root) shell.")
        // Stopping an already-stopped service fails harmlessly; carry on to remove the files.
        plan.uninstall.forEach { run(it) }
        plan.removeFiles.forEach { File(it).delete() }
        return Result.Installed
    }

    companion object {
        fun runCommand(cmd: List<String>): Int = try {
            ProcessBuilder(cmd).inheritIO().start().waitFor()
        } catch (_: java.io.IOException) {
            127
        }

        fun processIsElevated(): Boolean = when (OsFamily.current()) {
            OsFamily.WINDOWS -> runCatching { ProcessBuilder("net", "session").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)
            else -> System.getProperty("user.name") == "root"
        }
    }
}
