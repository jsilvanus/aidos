package dev.aidos.vision

/** An image as interleaved 8-bit RGB, row-major. Decoded by the host ([ImageDecoder]); this module never touches an image codec. */
class DecodedImage(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(rgb.size == width * height * 3) { "pixel buffer is ${rgb.size} bytes, expected ${width * height * 3}" }
    }
}

/** Decodes PNG/JPEG/... bytes to [DecodedImage]. The JVM host uses ImageIO, Android BitmapFactory. */
fun interface ImageDecoder {
    /** @throws IllegalArgumentException when [bytes] is not an image the platform can decode. */
    fun decode(bytes: ByteArray): DecodedImage
}

/** One 512x512 tile in the layout the vision encoder takes: CHW floats in [-1, 1] and a validity mask. */
class ImageTile(val pixels: FloatArray, val mask: BooleanArray)

/**
 * SmolVLM / Idefics3 image preparation for a single global tile: scale the longest edge to [TILE],
 * keep the aspect ratio, pad to a square with the mask marking real pixels, then rescale to [-1, 1]
 * (mean 0.5, std 0.5 per `preprocessor_config.json`).
 *
 * The reference processor also cuts large pictures into extra 512 px tiles for detail. That multiplies
 * the image tokens (64 each) and the phone's prefill time, so this takes the global view only; a
 * photograph is described from its downscaled whole, small print in it may be lost.
 */
object ImagePreprocessor {
    const val TILE = 512

    fun prepare(image: DecodedImage): ImageTile {
        val scale = TILE.toDouble() / maxOf(image.width, image.height)
        val w = maxOf(1, Math.round(image.width * scale).toInt())
        val h = maxOf(1, Math.round(image.height * scale).toInt())
        val resized = resize(image, w, h)

        val plane = TILE * TILE
        val pixels = FloatArray(3 * plane)
        val mask = BooleanArray(plane)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val src = (y * w + x) * 3
                val dst = y * TILE + x
                mask[dst] = true
                for (c in 0 until 3) {
                    pixels[c * plane + dst] = (resized[src + c] / 255f - 0.5f) / 0.5f
                }
            }
        }
        return ImageTile(pixels, mask)
    }

    /**
     * Area average when shrinking (so thin lines survive instead of aliasing away), bilinear when
     * enlarging. Returns interleaved RGB as 0..255 floats.
     */
    internal fun resize(src: DecodedImage, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 3)
        val sx = src.width.toDouble() / w
        val sy = src.height.toDouble() / h
        fun px(x: Int, y: Int, c: Int): Int = src.rgb[(y * src.width + x) * 3 + c].toInt() and 0xFF

        if (sx > 1.0 || sy > 1.0) {
            for (y in 0 until h) {
                val y0 = y * sy
                val y1 = minOf((y + 1) * sy, src.height.toDouble())
                for (x in 0 until w) {
                    val x0 = x * sx
                    val x1 = minOf((x + 1) * sx, src.width.toDouble())
                    val sum = DoubleArray(3)
                    var weight = 0.0
                    for (yy in y0.toInt() until minOf(Math.ceil(y1).toInt(), src.height)) {
                        val wy = minOf(yy + 1.0, y1) - maxOf(yy.toDouble(), y0)
                        for (xx in x0.toInt() until minOf(Math.ceil(x1).toInt(), src.width)) {
                            val wgt = wy * (minOf(xx + 1.0, x1) - maxOf(xx.toDouble(), x0))
                            for (c in 0 until 3) sum[c] += px(xx, yy, c) * wgt
                            weight += wgt
                        }
                    }
                    for (c in 0 until 3) out[(y * w + x) * 3 + c] = (sum[c] / weight).toFloat()
                }
            }
        } else {
            for (y in 0 until h) {
                val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, src.height - 1.0)
                val y0 = fy.toInt()
                val y1 = minOf(y0 + 1, src.height - 1)
                val ty = fy - y0
                for (x in 0 until w) {
                    val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, src.width - 1.0)
                    val x0 = fx.toInt()
                    val x1 = minOf(x0 + 1, src.width - 1)
                    val tx = fx - x0
                    for (c in 0 until 3) {
                        val top = px(x0, y0, c) * (1 - tx) + px(x1, y0, c) * tx
                        val bottom = px(x0, y1, c) * (1 - tx) + px(x1, y1, c) * tx
                        out[(y * w + x) * 3 + c] = (top * (1 - ty) + bottom * ty).toFloat()
                    }
                }
            }
        }
        return out
    }
}
