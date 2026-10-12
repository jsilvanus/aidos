package dev.aidos.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color

/**
 * BitmapFactory-backed [ImageDecoder] for the Android host. Transparency is flattened onto white,
 * matching the desktop decoder.
 *
 * UNVERIFIED ON A DEVICE: the Android targets do not compile in the cloud environment.
 */
object AndroidImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): DecodedImage {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("not a decodable image (${bytes.size} bytes)")
        val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
        val w = flat.width
        val h = flat.height
        val argb = IntArray(w * h)
        flat.getPixels(argb, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in argb.indices) {
            rgb[i * 3] = ((argb[i] shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((argb[i] shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (argb[i] and 0xFF).toByte()
        }
        if (flat !== decoded) decoded.recycle()
        flat.recycle()
        return DecodedImage(w, h, rgb)
    }
}
