package pl.kacosmetology.scheduler.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.R2Properties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

/** Handles uploading and deleting employee profile photos in Cloudflare R2. */
@Service
class EmployeePhotoService(
    private val s3Client: S3Client,
    private val r2Props: R2Properties,
    private val userRepository: UserRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository
) {

    companion object {
        private val ALLOWED_CONTENT_TYPES = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp"
        )
        private const val MAX_SIZE_BYTES = 5 * 1024 * 1024L  // 5 MB
    }

    /**
     * Uploads or replaces the profile photo for [employeeId] within [companyId].
     * If the employee already has a photo, the old R2 object is deleted first.
     *
     * @throws NoSuchElementException when the employee does not belong to [companyId] or does not exist.
     * @throws IllegalArgumentException when the file type or size is invalid.
     */
    @Transactional
    fun upload(companyId: Long, employeeId: Long, file: MultipartFile): User {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }

        val contentType = file.contentType
            ?: throw IllegalArgumentException("Brak nagłówka Content-Type dla pliku")
        val ext = ALLOWED_CONTENT_TYPES[contentType]
            ?: throw IllegalArgumentException("Niedozwolony typ pliku. Dozwolone formaty: JPEG, PNG, WebP")

        if (file.size > MAX_SIZE_BYTES) {
            throw IllegalArgumentException("Plik jest za duży. Maksymalny rozmiar: 5 MB")
        }

        val user = userRepository.findById(employeeId)
            .orElseThrow { NoSuchElementException("Użytkownik $employeeId nie istnieje") }

        user.photoUrl?.let { oldUrl ->
            val oldKey = oldUrl.removePrefix("${r2Props.publicUrl}/")
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(r2Props.bucketName)
                    .key(oldKey)
                    .build()
            )
        }

        val key = "employees/$companyId/$employeeId/${UUID.randomUUID()}.$ext"
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(r2Props.bucketName)
                .key(key)
                .contentType(contentType)
                .contentLength(file.size)
                .build(),
            RequestBody.fromInputStream(file.inputStream, file.size)
        )

        user.photoUrl = "${r2Props.publicUrl}/$key"
        return userRepository.save(user)
    }

    /**
     * Removes the profile photo for [employeeId] within [companyId].
     * If the employee has no photo, this is a no-op.
     *
     * @throws NoSuchElementException when the employee does not belong to [companyId] or does not exist.
     */
    @Transactional
    fun delete(companyId: Long, employeeId: Long): User {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }

        val user = userRepository.findById(employeeId)
            .orElseThrow { NoSuchElementException("Użytkownik $employeeId nie istnieje") }

        user.photoUrl?.let { url ->
            val key = url.removePrefix("${r2Props.publicUrl}/")
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(r2Props.bucketName)
                    .key(key)
                    .build()
            )
            user.photoUrl = null
            userRepository.save(user)
        }

        return user
    }
}
