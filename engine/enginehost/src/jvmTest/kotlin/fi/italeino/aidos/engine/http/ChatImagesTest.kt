package fi.italeino.aidos.engine.http

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The `images` field of a chat message: base64 or data URL in, kernel image blocks out. */
class ChatImagesTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test
    fun plainBase64IsSniffedForItsMimeType() {
        val b64 = Base64.getEncoder()
        assertEquals("image/png", decodeImage(b64.encodeToString(png)).mimeType)
        assertEquals("image/jpeg", decodeImage(b64.encodeToString(jpeg)).mimeType)
        assertContentEquals(png, decodeImage(b64.encodeToString(png)).data)
    }

    @Test
    fun dataUrlsKeepTheirDeclaredType() {
        val image = decodeImage("data:image/webp;base64," + Base64.getEncoder().encodeToString(png))
        assertEquals("image/webp", image.mimeType)
        assertContentEquals(png, image.data)
    }

    @Test
    fun garbageAndEmptyEntriesAreRejectedWithAnError() {
        assertFailsWith<IllegalArgumentException> { decodeImage("not base64 !!!") }
        assertFailsWith<IllegalArgumentException> { decodeImage("") }
    }
}
