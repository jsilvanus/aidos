package dev.aidos.ui.spatial

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A node drawn on a Compose [androidx.compose.foundation.Canvas], at its position in that canvas's pixels. */
data class CanvasNodeTarget(val nodeId: String, val title: String, val x: Float, val y: Float)

/** Touch-target size of each invisible target. 48dp is the platform minimum for an accessible target. */
private val TARGET_SIZE = 48.dp

/**
 * RFC-0124 (UI-60 follow-up): Canvas draw calls carry no semantics, so a node painted with `drawText`
 * is invisible to accessibility services and to Compose UI tests. This lays one invisible box over each
 * drawn node. The box carries the node's title as its content description and a click action that
 * opens it in the Inspector. It has no pointer input of its own, so taps still reach the canvas, which
 * keeps doing the hit testing and the gestures.
 *
 * Place it in the same [Box] as the Canvas, after it, so the positions line up with the drawing.
 */
@Composable
fun CanvasNodeTargets(
    targets: List<CanvasNodeTarget>,
    onActivate: (String) -> Unit,
) {
    val half = with(LocalDensity.current) { (TARGET_SIZE / 2).toPx() }
    targets.forEach { target ->
        Box(
            modifier = Modifier
                .offset { IntOffset((target.x - half).roundToInt(), (target.y - half).roundToInt()) }
                .size(TARGET_SIZE)
                .semantics {
                    contentDescription = target.title
                    role = Role.Button
                    onClick(label = "Open in inspector") {
                        onActivate(target.nodeId)
                        true
                    }
                },
        )
    }
}
