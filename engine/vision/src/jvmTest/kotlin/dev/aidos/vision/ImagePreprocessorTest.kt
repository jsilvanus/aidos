package dev.aidos.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImagePreprocessorTest {
    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int) =
        DecodedImage(w, h, ByteArray(w * h * 3) { listOf(r, g, b)[it % 3].toByte() })

    @Test
    fun wideImageIsScaledToTheTileWidthAndPaddedBelow() {
        val tile = ImagePreprocessor.prepare(solid(256, 128, 255, 0, 0))
        val n = ImagePreprocessor.TILE
        // 256x128 -> 512x256: rows 0..255 are picture, 256.. are padding.
        assertTrue(tile.mask[255 * n + 511])
        assertFalse(tile.mask[256 * n])
        // Red channel at +1, green and blue at -1 (0..255 -> -1..1), padding stays 0.
        assertEquals(1f, tile.pixels[0], 1e-4f)
        assertEquals(-1f, tile.pixels[n * n], 1e-4f)
        assertEquals(-1f, tile.pixels[2 * n * n], 1e-4f)
        assertEquals(0f, tile.pixels[256 * n])
    }

    @Test
    fun tallImageIsPaddedOnTheRight() {
        val tile = ImagePreprocessor.prepare(solid(100, 400, 0, 0, 0))
        val n = ImagePreprocessor.TILE
        // 100x400 -> 128x512
        assertTrue(tile.mask[0 * n + 127])
        assertFalse(tile.mask[0 * n + 128])
        assertTrue(tile.mask[511 * n])
    }

    @Test
    fun shrinkingAveragesInsteadOfSkippingPixels() {
        // A one-pixel-wide black/white column pattern 1024 wide: a stride-2 sampler would see only one colour.
        val w = 1024
        val rgb = ByteArray(w * 2 * 3)
        for (y in 0 until 2) for (x in 0 until w) for (c in 0 until 3) rgb[(y * w + x) * 3 + c] = if (x % 2 == 0) 0 else 255.toByte()
        val tile = ImagePreprocessor.prepare(DecodedImage(w, 2, rgb))
        assertEquals(0f, tile.pixels[10], 0.02f) // mid-grey in -1..1
    }
}
