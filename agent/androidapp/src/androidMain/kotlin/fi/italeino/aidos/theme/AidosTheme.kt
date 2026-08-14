package fi.italeino.aidos.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material 3 color scheme for Aidos (M28, RFC-0050).
 *
 * Follows Material Design 3 guidelines with a minimal, accessible color palette.
 * The app prioritizes readability on phone screens, especially for code and diffs.
 */
@Composable
fun AidosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
