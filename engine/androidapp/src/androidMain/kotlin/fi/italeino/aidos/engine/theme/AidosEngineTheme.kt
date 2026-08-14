package fi.italeino.aidos.engine.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 color scheme for Aidos Engine (RFC-0103).
 *
 * Same Material 3 defaults Aidos Agent uses (fi.italeino.aidos.theme.AidosTheme) — no reason to
 * invent a different visual language for a sibling app — but with a distinct seed color, so a
 * screenshot or the recents tray tells the two apps apart at a glance. Everything else about the
 * visual language (typography, shape, component styling) stays default and identical to Agent.
 */
private val EngineSeed = Color(0xFF00696D) // teal, distinct from Agent's default Material purple

@Composable
fun AidosEngineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = EngineSeed)
    } else {
        lightColorScheme(primary = EngineSeed)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
