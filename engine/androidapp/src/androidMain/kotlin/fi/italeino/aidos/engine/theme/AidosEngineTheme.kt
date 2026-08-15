package fi.italeino.aidos.engine.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 color scheme for Aidos Engine (RFC-0103, Phase D).
 *
 * Warm Technical light-mode theme:
 * - Surface: #FEF8F3 (warm beige/off-white)
 * - Primary: #533786 (deep purple brand)
 * - Secondary Accent: #F1916D (muted terracotta)
 * - Containers: #F8F3EE (slightly darker beige)
 * - Outline: #DED9D4 (soft taupe/gray)
 *
 * Design principles: Material 3, 8px rounded corners, Geist typography.
 * Information-dense technical UI with high contrast purple for active states.
 */

private val WarmBeige = Color(0xFFFEF8F3)
private val DeepPurple = Color(0xFF533786)
private val MutedTerracotta = Color(0xFFF1916D)
private val ContainerBeige = Color(0xFFF8F3EE)
private val SoftTaupe = Color(0xFFDED9D4)

@Composable
fun AidosEngineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = DeepPurple)
    } else {
        lightColorScheme(
            primary = DeepPurple,
            secondary = MutedTerracotta,
            tertiary = MutedTerracotta,
            surface = WarmBeige,
            surfaceVariant = ContainerBeige,
            outline = SoftTaupe,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
