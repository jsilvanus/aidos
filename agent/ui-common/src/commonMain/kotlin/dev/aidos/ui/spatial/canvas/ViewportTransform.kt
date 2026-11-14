package dev.aidos.ui.spatial.canvas

import androidx.compose.ui.geometry.Offset

data class ViewportTransform(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1.0f,
) {
    fun toScreenCoordinates(worldX: Float, worldY: Float): Offset {
        return Offset(
            x = (worldX + panX) * zoom,
            y = (worldY + panY) * zoom,
        )
    }

    fun toWorldCoordinates(screenX: Float, screenY: Float): Offset {
        return Offset(
            x = (screenX / zoom) - panX,
            y = (screenY / zoom) - panY,
        )
    }

    fun isPointInViewport(worldX: Float, worldY: Float, viewportWidth: Float, viewportHeight: Float, margin: Float = 100f): Boolean {
        val screen = toScreenCoordinates(worldX, worldY)
        return screen.x in (-margin..(viewportWidth + margin)) && screen.y in (-margin..(viewportHeight + margin))
    }
}
