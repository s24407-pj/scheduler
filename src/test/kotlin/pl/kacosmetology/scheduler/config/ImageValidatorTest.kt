package pl.kacosmetology.scheduler.config

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile

class ImageValidatorTest {
    @Test
    fun `rejects fake MIME and truncated images`() {
        assertThrows<IllegalArgumentException> {
            ImageValidator.validate(MockMultipartFile("image", "photo.png", "image/png", "not an image".toByteArray()))
        }
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertThrows<IllegalArgumentException> {
            ImageValidator.validate(MockMultipartFile("image", "photo.png", "image/png", png))
        }
    }

    @Test
    fun `detects actual image type rather than trusting MIME`() {
        val bytes =
            ByteArrayOutputStream()
                .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
                .toByteArray()
        val image = ImageValidator.validate(MockMultipartFile("image", "fake.jpg", "image/jpeg", bytes))
        assertEquals("png", image.extension)
        assertEquals("image/png", image.contentType)
    }
}
