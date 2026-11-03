package fi.italeino.aidos.engine.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import fi.italeino.aidos.engine.ui.GroupsPane
import kotlinx.coroutines.launch

/** Device groups (RFC-0105 phase 1) in its own non-modal window; the pane itself is shared with Android. */
@Composable
fun GroupsDialog(models: DesktopModels, onClose: () -> Unit) {
    val prefs by models.prefs.data.collectAsState()
    val node by models.host.aidin.collectAsState()
    val isRunning by models.host.isRunning.collectAsState()
    val error by models.host.aidinError.collectAsState()

    // A plain Window, not a DialogWindow: a dialog is modal on Windows and blocks the main window
    // for as long as the sharing window is open.
    Window(
        onCloseRequest = onClose,
        title = "Device groups",
        state = rememberWindowState(size = DpSize(640.dp, 760.dp)),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            SelectionContainer { GroupsPane(
                sharingEnabled = prefs.groupSharingEnabled,
                onSharingChange = { on ->
                    models.prefs.setGroupSharingEnabled(on)
                    models.scope.launch { models.host.setGroupSharing(on) }
                },
                isEngineRunning = isRunning,
                node = node,
                error = error,
                scope = models.scope,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) }
        }
    }
}
