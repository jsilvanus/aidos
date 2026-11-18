package dev.aidos.ui.spatial.schematic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.NodeDispatchPolicy

/**
 * The component symbol on a chip (RFC-0111 Design 5): a toggle switch for a human, a push-button IC
 * for a manual agent, a transistor for an auto-dispatch agent, a relay for a hybrid. Tapping it
 * runs [onClick] only when [enabled]; otherwise it is only a drawing.
 */
@Composable
fun BreadboardActorGlyph(
    actor: IntentActorKind,
    dispatchPolicy: NodeDispatchPolicy,
    isOn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Color(0xFF6EE7B7)
    val lit = Color(0xFF10B981)
    Canvas(
        modifier = modifier
            .size(width = 36.dp, height = 22.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2f)
        when (actor) {
            IntentActorKind.HUMAN -> {
                // Toggle switch: a slot, and a knob at the on or off end.
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(0f, h * 0.2f),
                    size = Size(w, h * 0.6f),
                    cornerRadius = CornerRadius(h * 0.3f),
                    style = stroke,
                )
                val knobX = if (isOn) w - h * 0.4f else h * 0.4f
                drawCircle(color = if (isOn) lit else ink, radius = h * 0.3f, center = Offset(knobX, h / 2f))
            }
            IntentActorKind.AGENT -> if (dispatchPolicy == NodeDispatchPolicy.AUTO_WHEN_READY) {
                // Transistor mark: a base bar, a channel and a collector, no button.
                drawLine(ink, Offset(w * 0.2f, 0f), Offset(w * 0.2f, h), strokeWidth = 2f)
                drawLine(ink, Offset(w * 0.4f, h * 0.2f), Offset(w * 0.4f, h * 0.8f), strokeWidth = 2f)
                drawLine(ink, Offset(w * 0.4f, h * 0.3f), Offset(w, 0f), strokeWidth = 2f)
                drawLine(ink, Offset(w * 0.4f, h * 0.7f), Offset(w, h), strokeWidth = 2f)
            } else {
                // IC with a push-button: a body, and a button that lights when the node is ready.
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3f),
                    style = stroke,
                )
                drawCircle(color = if (enabled) lit else ink, radius = h * 0.25f, center = Offset(w / 2f, h / 2f))
            }
            IntentActorKind.HYBRID -> {
                // Relay: a coil on the left, a switch arm on the right.
                drawRect(
                    color = ink,
                    topLeft = Offset(0f, h * 0.25f),
                    size = Size(w * 0.35f, h * 0.5f),
                    style = stroke,
                )
                drawLine(
                    color = if (isOn) lit else ink,
                    start = Offset(w * 0.55f, h / 2f),
                    end = Offset(w, if (isOn) h * 0.2f else h * 0.8f),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

/** The diode gate of a NEEDS_REVIEW node: a triangle pointing along the current, and a bar across it. */
@Composable
fun BreadboardDiodeGate(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 14.dp, height = 14.dp)) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.8f, h / 2f)
            lineTo(0f, h)
            close()
        }
        drawPath(triangle, color = color)
        drawLine(color, Offset(w * 0.8f, 0f), Offset(w * 0.8f, h), strokeWidth = 2f)
    }
}
