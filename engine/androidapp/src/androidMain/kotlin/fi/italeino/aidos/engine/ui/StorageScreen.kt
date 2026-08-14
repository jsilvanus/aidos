package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Storage screen: the RFC-0022 accounting table, unmodified — total used/free, per-model size
 * and last-used, and the "never run · will not fit" row that names pure waste.
 *
 * Removal is manual only (RFC-0022) — Engine never deletes weights on its own to make room, so
 * this screen's row-tap action is the only path to freeing space.
 */
@Composable
fun StorageScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Storage")
        Text("(Models · X GB of Y GB free — per-model rows will appear here, RFC-0022)")
        // TODO(RFC-0103/RFC-0022): bind to installed_models; tap a row to remove (manual only).
    }
}
