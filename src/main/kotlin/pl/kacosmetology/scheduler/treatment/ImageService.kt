package pl.kacosmetology.scheduler.treatment

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import pl.kacosmetology.scheduler.config.R2Properties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

/** Handles uploading and deleting service images in Cloudflare R2. */
@Service
class ImageService(
    private val s3Client: S3Client,
    private val r2Props: R2Properties,
    private val serviceImageRepository: ServiceImageRepository
) {

    companion object {
        private val ALLOWED_CONTENT_TYPES = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp"
        )
        private const val MAX_SIZE_BYTES = 5 * 1024 * 1024L  // 5 MB
        private const val MAX_IMAGES_PER_SERVICE = 5
    }

    /**
     * Validates, uploads [file] to R2, and persists a [ServiceImage] record.
     *
     * @throws IllegalArgumentException when the file type or size is invalid.
     * @throws IllegalStateException when the service already has [MAX_IMAGES_PER_SERVICE] images.
     */
    fun upload(companyId: Long, serviceId: Long, file: MultipartFile): ServiceImage {
        val currentCount = serviceImageRepository.countByServiceId(serviceId)
        if (currentCount >= MAX_IMAGES_PER_SERVICE) {
            throw IllegalStateException("Usługa może mieć maksymalnie $MAX_IMAGES_PER_SERVICE zdjęć")
        }

        val contentType = file.contentType
            ?: throw IllegalArgumentException("Brak nagłówka Content-Type dla pliku")
        val ext = ALLOWED_CONTENT_TYPES[contentType]
            ?: throw IllegalArgumentException("Niedozwolony typ pliku. Dozwolone formaty: JPEG, PNG, WebP")

        if (file.size > MAX_SIZE_BYTES) {
            throw IllegalArgumentException("Plik jest za duży. Maksymalny rozmiar: 5 MB")
        }

        val key = "services/$companyId/$serviceId/${UUID.randomUUID()}.$ext"

        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(r2Props.bucketName)
                .key(key)
                .contentType(contentType)
                .contentLength(file.size)
                .build(),
            RequestBody.fromInputStream(file.inputStream, file.size)
        )

        return serviceImageRepository.save(
            ServiceImage(serviceId = serviceId, imageUrl = "${r2Props.publicUrl}/$key")
        )
    }

    /**
     * Deletes the image record and the corresponding R2 object.
     *
     * @throws IllegalArgumentException when [imageId] does not exist.
     * @throws IllegalStateException when the image belongs to a different service.
     */
    fun delete(imageId: Long, serviceId: Long) {
        val image = serviceImageRepository.findById(imageId)
            .orElseThrow { IllegalArgumentException("Zdjęcie o ID $imageId nie istnieje") }

        if (image.serviceId != serviceId) {
            throw IllegalStateException("Zdjęcie nie należy do tej usługi")
        }

        val key = image.imageUrl.removePrefix("${r2Props.publicUrl}/")
        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(r2Props.bucketName)
                .key(key)
                .build()
        )

        serviceImageRepository.deleteById(imageId)
    }

    /** Batch-fetches images for the given service IDs, grouped by service. */
    fun findByServiceIds(serviceIds: List<Long>): List<ServiceImage> {
        if (serviceIds.isEmpty()) return emptyList()
        return serviceImageRepository.findAllByServiceIdIn(serviceIds)
    }
}
