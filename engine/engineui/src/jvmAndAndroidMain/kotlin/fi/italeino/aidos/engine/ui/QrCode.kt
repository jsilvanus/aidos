package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code for [text], drawn as squares (RFC-0105: device-group invites are scanned from the
 * other device's screen). Always black on white whatever the theme, because scanners need the
 * contrast; the white margin is the quiet zone the format requires.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.background(Color.White).padding(12.dp).size(size)) {
        val cell = this.size.minDimension / matrix.size
        for (y in matrix.indices) {
            for (x in matrix.indices) {
                if (matrix[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}

/** Rows of dark (true) and light modules. Medium error correction: invites are short. */
fun qrMatrix(text: String): List<BooleanArray> {
    val code = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    val m = code.matrix
    return List(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y).toInt() == 1 } }
}
