package pl.kacosmetology.scheduler.offering

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import pl.kacosmetology.scheduler.config.ImageValidator
import pl.kacosmetology.scheduler.config.R2Properties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** Transaction-safe offering galleries with capacity serialized by the tenant-owned parent row. */
@Service
class OfferingImageService(
    private val s3Client: S3Client,
    private val r2Props: R2Properties,
    private val offeringImageRepository: OfferingImageRepository,
    private val offeringRepository: OfferingRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Uploads validated bytes; a rolled-back insertion compensates the newly uploaded object. */
    @Transactional
    fun upload(companyId: UUID, offeringId: UUID, file: MultipartFile): OfferingImage {
        offeringRepository.findByIdAndCompanyIdForUpdate(offeringId, companyId)
            ?: throw NoSuchElementException("Usługa nie istnieje")
        check(offeringImageRepository.countByOfferingId(offeringId) < 5) { "Usługa może mieć maksymalnie 5 zdjęć" }
        val image = ImageValidator.validate(file)
        val key = "offerings/$companyId/$offeringId/${UUID.randomUUID()}.${image.extension}"
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(r2Props.bucketName)
                .key(key)
                .contentType(image.contentType)
                .contentLength(image.bytes.size.toLong())
                .build(),
            RequestBody.fromBytes(image.bytes),
        )
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) deleteObject(key)
                }
            },
        )
        return offeringImageRepository.save(
            OfferingImage(offeringId = offeringId, imageUrl = "${r2Props.publicUrl}/$key"),
        )
    }

    /** Removes the database reference first; object deletion runs only after commit. */
    @Transactional
    fun delete(imageId: UUID, offeringId: UUID) {
        val image =
            offeringImageRepository.findById(imageId).orElseThrow { NoSuchElementException("Zdjęcie nie istnieje") }
        if (image.offeringId != offeringId) throw NoSuchElementException("Zdjęcie nie istnieje")
        offeringImageRepository.delete(image)
        val key = image.imageUrl.removePrefix("${r2Props.publicUrl}/")
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = deleteObject(key)
            },
        )
    }

    /** Batch-fetches images for the given offering IDs. */
    fun findByOfferingIds(offeringIds: List<UUID>): List<OfferingImage> =
        if (offeringIds.isEmpty()) emptyList() else offeringImageRepository.findAllByOfferingIdIn(offeringIds)

    private fun deleteObject(key: String) {
        runCatching { s3Client.deleteObject(DeleteObjectRequest.builder().bucket(r2Props.bucketName).key(key).build()) }
            .onFailure { log.warn("Could not remove offering image object", it) }
    }
}
