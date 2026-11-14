package fi.italeino.aidos.engine.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fi.italeino.aidos.engine.observability.CrashHandler
import fi.italeino.aidos.engine.theme.AidosEngineTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.io.File

/** Entry point of the Windows desktop application and daemon for Aidos Engine (RFC-0103). */
fun main(args: Array<String>) {
    DesktopPaths.ensureCreated()
    val prefs = DesktopPreferences(File(DesktopPaths.stateDir, "preferences.json"))
    val isHeadless = args.any { it == "--daemon" || it == "--headless" || it == "-d" }

    // Wire crash handler early before anything else might fail (RFC-0037)
    val userDbDriver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${File(DesktopPaths.stateDir, "user.db").absolutePath}",
    )
    val crashDir = File(DesktopPaths.stateDir, "crashes")
    initDesktopCrashHandling(userDbDriver, crashDir)

    // Allow overriding port from command line arguments e.g. --port 18080
    val argPort = args.indexOf("--port").takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1].toIntOrNull() }
    val effectivePort = argPort ?: prefs.data.value.serverPort

    val host = DesktopEngineHost(
        runSettings = prefs.runSettingsSource,
        ramBaselineUsedBytes = { prefs.data.value.ramBaselineUsedMb?.let { it * 1024L * 1024L } },
        serverPort = { effectivePort },
        discoveryFile = DesktopPaths.discoveryFile,
        groupSharing = { prefs.data.value.groupSharingEnabled },
    )

    if (isHeadless) {
        println("Starting Aidos Engine in headless daemon mode...")
        runBlocking {
            host.start()
            val port = host.boundPort.value
            if (port != null) {
                println("Aidos Engine is READY on http://127.0.0.1:$port")
                println("Active discovery file: ${DesktopPaths.discoveryFile.absolutePath}")
                println("Press Ctrl+C to stop.")
            } else {
                System.err.println("Failed to start Aidos Engine: ${host.lastError.value}")
                return@runBlocking
            }
            Runtime.getRuntime().addShutdownHook(Thread {
                println("Stopping Aidos Engine...")
                runBlocking { host.close() }
            })
            try {
                awaitCancellation()
            } catch (_: InterruptedException) {
            }
        }
        return
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val models = DesktopModels(host, prefs, scope)

    if (prefs.data.value.autoStartEngine) {
        scope.launch { host.start() }
    }

    application {
        Window(
            onCloseRequest = {
                // Same teardown as EngineService.onDestroy: drain inference and unload models.
                runBlocking { host.close() }
                scope.cancel()
                exitApplication()
            },
            title = "Aidos Engine",
            state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
        ) {
            AidosEngineTheme {
                // Make text selectable/copyable throughout the main window.
                SelectionContainer { DesktopApp(models) }
            }
        }
    }
}

internal fun initDesktopCrashHandling(
    userDbDriver: SqlDriver,
    crashDir: File,
    runtimeVersion: String = "0.1.0",
): CrashHandler {
    userDbDriver.execute(
        identifier = null,
        sql = """
            CREATE TABLE IF NOT EXISTS crash_records (
                id                TEXT PRIMARY KEY,
                occurred_at       TEXT NOT NULL,
                runtime_version   TEXT NOT NULL,
                platform_profile  TEXT NOT NULL,
                error_code        TEXT,
                stack_hash        TEXT NOT NULL,
                detail_path       TEXT NOT NULL,
                in_flight_run_ids TEXT NOT NULL DEFAULT '[]',
                reported          INTEGER NOT NULL DEFAULT 0
            )
        """,
        parameters = 0,
    ) {}
    val crashHandler = CrashHandler(
        driver = userDbDriver,
        runtimeVersion = runtimeVersion,
        platformProfile = "DESKTOP",
        crashDir = crashDir,
    )
    Thread.setDefaultUncaughtExceptionHandler(crashHandler)
    crashHandler.recoverOrphanedRecords(crashDir)
    return crashHandler
}
