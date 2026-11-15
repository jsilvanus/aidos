package fi.italeino.aidos.engine.desktop.install

/** Where a service runs (RFC-0115, D56): for the signed-in user only, or for the machine as a named user. */
enum class InstallScope { USER, SYSTEM }

enum class OsFamily {
    WINDOWS, MACOS, LINUX;

    companion object {
        fun current(): OsFamily = System.getProperty("os.name").lowercase().let {
            when {
                it.startsWith("windows") -> WINDOWS
                it.startsWith("mac") || it.startsWith("darwin") -> MACOS
                else -> LINUX
            }
        }
    }
}

/**
 * One background service to register: the Engine, the Agent daemon, or the tray. [runAsUser] is required for a
 * [InstallScope.SYSTEM] install (the service never runs as root or SYSTEM, D56). [startLocked] documents D56's rule
 * that a system service starts with the vault locked until the owner unlocks it (it carries no secret itself).
 */
data class ServiceSpec(
    /** Short name, such as `engine`, `agent` or `tray`. */
    val name: String,
    val description: String,
    val command: List<String>,
    val scope: InstallScope,
    val runAsUser: String? = null,
    val home: String? = null,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9-]*"))) { "service name must be lower-case letters, digits and dashes" }
        require(command.isNotEmpty()) { "a service needs a command" }
        require(scope == InstallScope.USER || !runAsUser.isNullOrBlank()) { "a system service runs as a named user, never as root or SYSTEM" }
        require(runAsUser.isNullOrBlank() || runAsUser !in setOf("root", "SYSTEM", "NT AUTHORITY\\SYSTEM", "LocalSystem")) { "refusing to run as a privileged account" }
        require(command.none { it.contains('\n') || it.contains('\r') }) { "a command argument cannot contain a line break" }
    }

    val label: String get() = "fi.italeino.aidos.$name"
}

/** A file to write, and whether it is private to its owner. */
data class ServiceFile(val path: String, val content: String)

/** Everything one install needs: files to write, then commands to run; and the commands that undo it. */
data class ServicePlan(
    val files: List<ServiceFile>,
    val install: List<List<String>>,
    val uninstall: List<List<String>>,
    val removeFiles: List<String>,
    /** True when applying this needs an administrator (or root) shell. */
    val needsElevation: Boolean,
)

/** Generates the per-OS service definitions. Pure: it writes and runs nothing, so it is tested everywhere. */
object ServiceDefinitions {
    fun plan(spec: ServiceSpec, os: OsFamily = OsFamily.current(), home: String = spec.home ?: System.getProperty("user.home"), uid: String = currentUid()): ServicePlan =
        when (os) {
            OsFamily.LINUX -> systemd(spec, home)
            OsFamily.MACOS -> launchd(spec, home, uid)
            OsFamily.WINDOWS -> windows(spec)
        }

    /** The numeric user id launchd's per-user domain needs; "501" (the first macOS user) when `id` is unavailable. */
    fun currentUid(): String = runCatching {
        ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }.getOrNull() ?: "501"

    // ---- Linux: systemd --user, or a system unit with User= ------------------------------------------------------

    private fun systemd(s: ServiceSpec, home: String): ServicePlan {
        val unit = "aidos-${s.name}.service"
        val user = s.scope == InstallScope.USER
        val path = if (user) "$home/.config/systemd/user/$unit" else "/etc/systemd/system/$unit"
        val exec = s.command.joinToString(" ") { systemdQuote(it) }
        val content = buildString {
            appendLine("[Unit]")
            appendLine("Description=${s.description}")
            if (!user) appendLine("After=network-online.target")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=simple")
            if (!user) appendLine("User=${s.runAsUser}")
            appendLine("ExecStart=$exec")
            appendLine("Restart=on-failure")
            appendLine("RestartSec=5")
            appendLine("NoNewPrivileges=true")
            appendLine()
            appendLine("[Install]")
            appendLine(if (user) "WantedBy=default.target" else "WantedBy=multi-user.target")
        }
        val ctl = if (user) listOf("systemctl", "--user") else listOf("systemctl")
        return ServicePlan(
            files = listOf(ServiceFile(path, content)),
            install = listOf(ctl + "daemon-reload", ctl + listOf("enable", "--now", unit)),
            uninstall = listOf(ctl + listOf("disable", "--now", unit), ctl + "daemon-reload"),
            removeFiles = listOf(path),
            needsElevation = !user,
        )
    }

    private fun systemdQuote(a: String) = if (a.any { it.isWhitespace() || it == '"' || it == '\\' }) "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else a

    // ---- macOS: LaunchAgent, or LaunchDaemon with UserName -------------------------------------------------------

    private fun launchd(s: ServiceSpec, home: String, uid: String): ServicePlan {
        val user = s.scope == InstallScope.USER
        val path = if (user) "$home/Library/LaunchAgents/${s.label}.plist" else "/Library/LaunchDaemons/${s.label}.plist"
        val args = s.command.joinToString("\n") { "    <string>${xml(it)}</string>" }
        val content = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${xml(s.label)}</string>
  <key>ProgramArguments</key>
  <array>
$args
  </array>
${if (user) "" else "  <key>UserName</key>\n  <string>${xml(s.runAsUser!!)}</string>\n"}  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <dict>
    <key>SuccessfulExit</key>
    <false/>
  </dict>
</dict>
</plist>
"""
        val domain = if (user) "gui/$uid" else "system"
        return ServicePlan(
            files = listOf(ServiceFile(path, content)),
            install = listOf(listOf("launchctl", "bootstrap", domain, path)),
            uninstall = listOf(listOf("launchctl", "bootout", domain, path)),
            removeFiles = listOf(path),
            needsElevation = !user,
        )
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- Windows: Scheduled Task (at logon for the user, at startup for the machine, as the named user) ----------

    private fun windows(s: ServiceSpec): ServicePlan {
        val user = s.scope == InstallScope.USER
        val task = "Aidos ${s.name}"
        val tr = s.command.joinToString(" ") { winQuote(it) }
        val create = buildList {
            addAll(listOf("schtasks", "/Create", "/F", "/TN", task, "/TR", tr, "/RL", "LIMITED"))
            if (user) addAll(listOf("/SC", "ONLOGON"))
            // At startup as a named user needs that account's password; schtasks asks for it on the console.
            else addAll(listOf("/SC", "ONSTART", "/RU", s.runAsUser!!, "/RP", "*"))
        }
        return ServicePlan(
            files = emptyList(),
            install = listOf(create, listOf("schtasks", "/Run", "/TN", task)),
            uninstall = listOf(listOf("schtasks", "/End", "/TN", task), listOf("schtasks", "/Delete", "/F", "/TN", task)),
            removeFiles = emptyList(),
            needsElevation = !user,
        )
    }

    private fun winQuote(a: String) = if (a.any { it.isWhitespace() } || a.isEmpty()) "\"" + a.replace("\"", "\\\"") + "\"" else a
}
