package dev.aidos.vision

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** ImageIO-backed [ImageDecoder] for the desktop host: PNG, JPEG, GIF, BMP. Transparency is flattened onto white. */
object JvmImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): DecodedImage {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("not a decodable image (${bytes.size} bytes); PNG, JPEG, GIF and BMP are supported")
        val w = image.width
        val h = image.height
        val rgb = ByteArray(w * h * 3)
        val argb = IntArray(w)
        for (y in 0 until h) {
            image.getRGB(0, y, w, 1, argb, 0, w)
            for (x in 0 until w) {
                val p = argb[x]
                val a = (p ushr 24) / 255.0
                for (c in 0 until 3) {
                    val v = (p shr (16 - 8 * c)) and 0xFF
                    rgb[(y * w + x) * 3 + c] = (v * a + 255 * (1 - a)).toInt().toByte()
                }
            }
        }
        return DecodedImage(w, h, rgb)
    }
}
