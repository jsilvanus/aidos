package dev.aidos.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Stable
class AdaptiveLayoutState(
    initialWidthDp: Float = 360f,
    initialHeightDp: Float = 800f,
) {
    var widthDp: Float by mutableFloatStateOf(initialWidthDp)
        private set

    var heightDp: Float by mutableFloatStateOf(initialHeightDp)
        private set

    val formFactor: FormFactor
        get() = computeFormFactor(widthDp)

    val isHandheldCompact: Boolean
        get() = formFactor == FormFactor.HANDHELD_COMPACT

    val isHandheldExpanded: Boolean
        get() = formFactor == FormFactor.HANDHELD_EXPANDED

    val isDesktopDocked: Boolean
        get() = formFactor == FormFactor.DESKTOP_DOCKED

    fun updateDimensions(width: Float, height: Float) {
        widthDp = width
        heightDp = height
    }
}
