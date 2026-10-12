package dev.aidos.vision

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The real model, end to end through the real ONNX graphs. Set AIDOS_VISION_MODEL_DIR to a package
 * directory (`node scripts/vision/fetch-smolvlm.mjs <dir>`); without it the test is skipped.
 */
class LoadedSmolVlmTest {
    private fun packageDir(): File {
        val dir = System.getenv("AIDOS_VISION_MODEL_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("set AIDOS_VISION_MODEL_DIR to a SmolVLM package", dir != null && VisionPackage.isPackage(dir))
        return dir!!
    }

    private fun picture(color: Color): ByteArray {
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, 320, 240)
        g.color = color; g.fillRect(60, 40, 200, 160)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun ask(model: SmolVlmModel, png: ByteArray, question: String): String =
        model.generate(
            listOf(Turn.User(listOf(ContentBlock.Image("image/png", png), ContentBlock.Text(question)), TrustLevel.TRUSTED)),
            maxNewTokens = 24,
        ).filterIsInstance<VisionStep.Text>().joinToString("") { it.delta }

    @Test
    fun namesTheColourOfAShape() {
        VisionPackage.open("smolvlm", packageDir(), JvmImageDecoder).use { model ->
            val red = ask(model, picture(Color(220, 20, 20)), "What color is the rectangle?").lowercase()
            val blue = ask(model, picture(Color(20, 40, 220)), "What color is the rectangle?").lowercase()
            println("vision: red -> \"$red\", blue -> \"$blue\"")
            assertTrue("red" in red, "expected red in: $red")
            assertTrue("blue" in blue, "expected blue in: $blue")
        }
    }
}
