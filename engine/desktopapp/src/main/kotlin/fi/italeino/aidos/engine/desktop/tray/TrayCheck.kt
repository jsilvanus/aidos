package fi.italeino.aidos.engine.desktop.tray

import fi.italeino.aidos.engine.desktop.EngineAttach
import fi.italeino.aidos.engine.desktop.install.OsFamily
import java.awt.GraphicsEnvironment
import java.awt.SystemTray

/**
 * `--tray-check`: a read-only report for the first run on a real desktop (RFC-0115): can this session show a tray icon,
 * which services answer, which vaults are locked. It starts nothing and changes nothing. Exit 0 when the tray can run,
 * 2 when it cannot (no desktop, or no notification area).
 */
object TrayCheck {
    fun run(out: (String) -> Unit = ::println): Int {
        out("Aidos tray check")
        out("  operating system: ${System.getProperty("os.name")} (${OsFamily.current()})")
        out("  Java: ${System.getProperty("java.version")}")
        val headless = GraphicsEnvironment.isHeadless()
        val supported = !headless && SystemTray.isSupported()
        out("  graphics: ${if (headless) "headless (no desktop session)" else "available"}")
        out("  system tray: ${if (supported) "supported" else "NOT supported here"}")
        val actions = TrayActions()
        val engine = EngineAttach.runningEngine(selfPid = -1)
        out("  Engine service: ${engine?.let { "running (pid ${it.pid}, ${it.baseUrl})" } ?: "not running"}")
        out("  vault locked: ${if (actions.vaultLocked()) "yes (use the tray's Unlock vault)" else "no, or nothing to ask"}")
        val sources = runCatching { kotlinx.coroutines.runBlocking { TrayControls().current()?.pending()?.size } }.getOrNull()
        out("  waiting for approval: ${sources?.toString() ?: "no service to ask"}")
        if (!supported) {
            out("The tray cannot run in this session. On Linux a desktop needs a status notifier or AppIndicator host.")
            return 2
        }
        out("The tray can run: start it with `--tray`.")
        return 0
    }
}
