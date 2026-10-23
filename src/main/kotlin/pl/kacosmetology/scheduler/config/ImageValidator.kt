package pl.kacosmetology.scheduler.config

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.springframework.web.multipart.MultipartFile

/** Verified image payload; media type and extension come from bytes rather than request metadata. */
class ValidatedImage(val bytes: ByteArray, val contentType: String, val extension: String)

/** Checks size, actual image format and decodability before any upload is persisted. */
object ImageValidator {
    private const val MAX_SIZE_BYTES = 5 * 1024 * 1024L
    private const val MAX_PIXELS = 25_000_000L

    /** Accepts decodable JPEG, PNG or WebP images, including a safe limit on decoded dimensions. */
    fun validate(file: MultipartFile): ValidatedImage {
        require(file.size in 1..MAX_SIZE_BYTES) { "Plik musi mieć od 1 bajta do 5 MB" }
        val bytes = file.bytes
        require(bytes.size.toLong() in 1..MAX_SIZE_BYTES) { "Plik musi mieć od 1 bajta do 5 MB" }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Nieprawidłowe zdjęcie. Dozwolone formaty: JPEG, PNG, WebP" }
            val reader = readers.next()
            try {
                reader.input = input
                val extension =
                    when (reader.formatName.lowercase()) {
                        "jpeg",
                        "jpg" -> "jpg"
                        "png" -> "png"
                        "webp" -> "webp"
                        else -> throw IllegalArgumentException("Niedozwolony format zdjęcia")
                    }
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) {
                    "Nieprawidłowe wymiary zdjęcia"
                }
                requireNotNull(reader.read(0)) { "Nieprawidłowa zawartość zdjęcia" }
                val contentType = if (extension == "jpg") "image/jpeg" else "image/$extension"
                return ValidatedImage(bytes, contentType, extension)
            } catch (ex: java.io.IOException) {
                throw IllegalArgumentException("Nieprawidłowa zawartość zdjęcia", ex)
            } finally {
                reader.dispose()
            }
        }
    }
}
