package fi.italeino.aidos.engine.desktop.tray

import fi.italeino.aidos.engine.control.ApprovalsControl
import fi.italeino.aidos.engine.control.HttpApprovalsControl
import fi.italeino.aidos.engine.control.PendingApproval
import fi.italeino.aidos.engine.desktop.EngineAttach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.AWTException
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The notification-area icon (RFC-0115, D59): a context menu for the Engine service, and quick approvals.
 * A toast says who is asking and what kind of thing, never content; clicking it (or the icon) opens a small popup
 * with Approve and Deny. Every effectful tool call is its own item, so every one gets its own answer.
 * Launch with `--tray`. Exits 2 when the platform has no system tray (a headless server, some Linux desktops).
 */
class TrayApp(
    private val actions: TrayActions = TrayActions(),
    private val pollMillis: Long = 2000,
    private val control: suspend () -> ApprovalsControl? = {
        EngineAttach.runningEngine(selfPid = -1)?.let { HttpApprovalsControl(it.baseUrl, it.token) }
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watcher = ApprovalWatcher(control)
    private var latest: TraySnapshot = TraySnapshot(ServiceState.STOPPED, emptyList(), emptyList(), emptySet())
    private val popups = mutableMapOf<String, JDialog>()

    fun run(): Int {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            System.err.println("This desktop has no system tray; nothing to show. Use the window or the admin API instead.")
            return 2
        }
        val icon = TrayIcon(drawIcon(), "Aidos")
        icon.isImageAutoSize = true
        val menu = PopupMenu()
        fun item(label: String, onClick: () -> Unit) = MenuItem(label).also { m -> m.addActionListener { Thread { onClick() }.start() }; menu.add(m) }
        item("Open Aidos") { actions.openWindow() }
        item("Waiting for approval…") { SwingUtilities.invokeLater { showAll() } }
        menu.addSeparator()
        item("Start Engine") { actions.startEngine() }
        item("Stop Engine") { actions.stopEngine() }
        item("Restart Engine") { actions.stopEngine(); waitStopped(); actions.startEngine() }
        menu.addSeparator()
        item("Quit tray") { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); SystemTray.getSystemTray().remove(icon); System.exit(0) }
        icon.popupMenu = menu
        icon.addActionListener { SwingUtilities.invokeLater { showAll() } }
        try {
            SystemTray.getSystemTray().add(icon)
        } catch (e: AWTException) {
            System.err.println("Could not add the tray icon: ${e.message}")
            return 2
        }
        scope.launch {
            while (true) {
                val snap = watcher.poll()
                latest = snap
                SwingUtilities.invokeLater {
                    icon.toolTip = snap.tooltip()
                    snap.resolvedIds.forEach { popups.remove(it)?.dispose() }
                    snap.raised.forEach { icon.displayMessage("Aidos needs an answer", it.title, TrayIcon.MessageType.INFO) }
                }
                delay(pollMillis)
            }
        }
        // Clicking a toast fires the icon's action listener too, so it opens the popups for what is waiting.
        Thread.currentThread().join()
        return 0
    }

    private fun waitStopped() {
        val end = System.currentTimeMillis() + 10_000
        while (actions.engineRunning() && System.currentTimeMillis() < end) Thread.sleep(200)
    }

    private fun showAll() {
        val items = latest.pending
        if (items.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(null, if (latest.engine == ServiceState.RUNNING) "Nothing is waiting for you." else latest.tooltip(), "Aidos", javax.swing.JOptionPane.INFORMATION_MESSAGE)
            return
        }
        items.forEach { show(it) }
    }

    private fun show(a: PendingApproval) {
        popups[a.id]?.let { it.toFront(); return }
        val dialog = JDialog(null as java.awt.Frame?, "Aidos", false)
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 16, 12, 16)
            add(JLabel("<html><b>${escape(a.title)}</b></html>").apply { font = font.deriveFont(Font.PLAIN, 14f) })
            add(JLabel("<html><div style='width:320px'>${escape(a.detail)}</div></html>").apply { border = BorderFactory.createEmptyBorder(8, 0, 12, 0) })
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(JButton("Deny").also { b -> b.addActionListener { answer(a, false, dialog) } })
                add(JButton("Approve").also { b -> b.addActionListener { answer(a, true, dialog) }; dialog.rootPane.defaultButton = b })
            })
        }
        dialog.contentPane = body
        dialog.isAlwaysOnTop = true
        dialog.pack()
        dialog.minimumSize = Dimension(380, dialog.height)
        dialog.setLocationRelativeTo(null)
        popups[a.id] = dialog
        dialog.isVisible = true
    }

    private fun answer(a: PendingApproval, allow: Boolean, dialog: JDialog) {
        dialog.dispose(); popups.remove(a.id)
        scope.launch { runCatching { control()?.decide(a.id, allow) } }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun drawIcon(): BufferedImage = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { img ->
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color(0x2A, 0x6F, 0xDB); g.fillOval(2, 2, 28, 28)
        g.color = java.awt.Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        g.drawString("A", 9, 24); g.dispose()
    }
}

/** Entry used by `main --tray`. */
fun runTray(): Int = TrayApp().run()
