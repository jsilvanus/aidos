package fi.italeino.aidos.engine.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fi.italeino.aidos.engine.theme.AidosEngineTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import java.io.File

/** Entry point of the Windows debug app for Aidos Engine (RFC-0103). */
fun main() {
    DesktopPaths.ensureCreated()
    val host = DesktopEngineHost()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val models = DesktopModels(host, DesktopPreferences(File(DesktopPaths.stateDir, "preferences.json")), scope)

    application {
        Window(
            onCloseRequest = {
                // Same teardown as EngineService.onDestroy: drain inference and unload models.
                runBlocking { host.close() }
                scope.cancel()
                exitApplication()
            },
            title = "Aidos Engine (debug)",
            state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
        ) {
            AidosEngineTheme {
                DesktopApp(models)
            }
        }
    }
}
