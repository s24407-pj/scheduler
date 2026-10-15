package pl.kacosmetology.scheduler.user

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.ImageValidator
import pl.kacosmetology.scheduler.config.R2Properties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** Company-local staff photos with byte validation and commit-safe object deletion. */
@Service
class EmployeePhotoService(
    private val s3Client: S3Client,
    private val r2Props: R2Properties,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
) {
    private val log = LoggerFactory.getLogger(EmployeePhotoService::class.java)

    /** Replaces one company's photo; the former object remains until commit and until all legacy references end. */
    @Transactional
    fun upload(companyId: Long, employeeId: Long, file: MultipartFile): CompanyEmployee {
        val employee =
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId)
                ?: throw NoSuchElementException("Pracownik nie istnieje")
        val image = ImageValidator.validate(file)
        val oldUrl = employee.photoUrl
        val key = "employees/$companyId/$employeeId/${UUID.randomUUID()}.${image.extension}"
        s3Client.putObject(
            PutObjectRequest.builder().bucket(r2Props.bucketName).key(key).contentType(image.contentType).build(),
            RequestBody.fromBytes(image.bytes),
        )
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    oldUrl?.let(::deleteUnreferenced)
                }

                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) deleteKey(key)
                }
            },
        )
        employee.photoUrl = "${r2Props.publicUrl}/$key"
        return companyEmployeeRepository.save(employee)
    }

    /** Removes only this company-local reference; a rollback retains the referenced object. */
    @Transactional
    fun delete(companyId: Long, employeeId: Long): CompanyEmployee {
        val employee =
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId)
                ?: throw NoSuchElementException("Pracownik nie istnieje")
        val oldUrl = employee.photoUrl ?: return employee
        employee.photoUrl = null
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    deleteUnreferenced(oldUrl)
                }
            },
        )
        return companyEmployeeRepository.save(employee)
    }

    private fun deleteUnreferenced(url: String) {
        if (!url.startsWith("${r2Props.publicUrl}/")) return
        runCatching {
            if (!companyEmployeeRepository.existsByPhotoUrl(url)) deleteKey(url.removePrefix("${r2Props.publicUrl}/"))
        }
            .onFailure { log.warn("Employee photo cleanup deferred after commit") }
    }

    private fun deleteKey(key: String) {
        runCatching { s3Client.deleteObject(DeleteObjectRequest.builder().bucket(r2Props.bucketName).key(key).build()) }
            .onFailure { log.warn("Employee photo object cleanup failed") }
    }
}
