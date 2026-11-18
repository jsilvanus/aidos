package dev.aidos.ui.generative

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.WorkspaceController

/** What a generative card resolves its actions against: the project's graph and the controller that acts. */
class ManifestActionHost(val snapshot: GraphSnapshot, val controller: WorkspaceController)

/** Provided by the app root (UI-52). Null outside it, so cards draw with actions disabled. */
val LocalManifestActionHost = staticCompositionLocalOf<ManifestActionHost?> { null }

/** Makes [controller] and the current [snapshot] available to every agent card under [content]. */
@Composable
fun ManifestActionsProvider(
    snapshot: GraphSnapshot,
    controller: WorkspaceController,
    content: @Composable () -> Unit,
) {
    val host = remember(snapshot, controller) { ManifestActionHost(snapshot, controller) }
    CompositionLocalProvider(LocalManifestActionHost provides host, content = content)
}
