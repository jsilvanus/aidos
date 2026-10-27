package fi.italeino.aidos.engine.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals

class QrCodeTest {
    /** Render the matrix as pixels with a quiet zone and read it back with ZXing's own decoder. */
    @Test
    fun anInviteSurvivesEncodingAndScanning() {
        val invite = "aidin:pair?v=1&k=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3x9qY0Jt7cL1w5v2f8u0Q1n5M2b9Y4s7A6d3F8h2J1k4L6m9N0p3Q5r8T1v4W7y0" +
            "&s=AAECAwQFBgcICQoLDA0ODw&a=192.168.1.20,10.0.0.5&p=47100"
        val matrix = qrMatrix(invite)
        val scale = 4
        val quiet = 4
        val side = (matrix.size + 2 * quiet) * scale
        val pixels = IntArray(side * side) { 0xFFFFFF }
        for (y in matrix.indices) for (x in matrix.indices) if (matrix[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0x000000
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        assertEquals(invite, QRCodeReader().decode(bitmap).text)
    }
}
