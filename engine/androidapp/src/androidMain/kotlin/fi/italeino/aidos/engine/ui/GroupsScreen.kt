package fi.italeino.aidos.engine.ui

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow

/** An `aidin:` invite link the activity received, waiting for the Groups screen to show it. */
object PendingInvite {
    val value = MutableStateFlow<String?>(null)

    fun offer(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (uri.startsWith("aidin:")) value.value = uri
    }
}

/** Device groups (RFC-0105 phase 1) on Android; the pane is the one the desktop window uses. */
@Composable
fun GroupsScreen() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val engineState by EngineService.state.collectAsState()
    val node by EngineService.aidin.collectAsState()
    val error by EngineService.aidinError.collectAsState()
    var enabled by remember { mutableStateOf(SettingsStore.isGroupSharingEnabled(context)) }
    var invite by remember { mutableStateOf("") }
    val pending by PendingInvite.value.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            invite = it
            PendingInvite.value.value = null
        }
    }

    GroupsPane(
        sharingEnabled = enabled,
        onSharingChange = { on ->
            enabled = on
            SettingsStore.setGroupSharingEnabled(context, on)
            EngineService.instance?.setGroupSharing(on)
        },
        isEngineRunning = engineState == EngineState.READY,
        node = node,
        error = error,
        scope = scope,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        initialInvite = invite,
    )
}
