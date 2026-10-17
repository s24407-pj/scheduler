package pl.kacosmetology.scheduler.user

import io.mockk.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.R2Properties
import pl.kacosmetology.scheduler.testId
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

class EmployeePhotoServiceTest {
    private val storage = mockk<S3Client>()
    private val properties = R2Properties(bucketName = "test", publicUrl = "https://images.test")
    private val accounts = mockk<CompanyEmployeeRepository>()
    private lateinit var service: EmployeePhotoService
    private lateinit var employee: CompanyEmployee
    private val oldUrl = "https://images.test/legacy/shared.png"

    @BeforeEach
    fun setup() {
        service = EmployeePhotoService(storage, properties, accounts)
        employee =
            CompanyEmployee(
                id = testId(8),
                companyId = testId(1),
                userId = testId(10),
                role = "EMPLOYEE",
                photoUrl = oldUrl,
            )
        every { accounts.findByCompanyIdAndUserIdForUpdate(testId(1), testId(10)) } returns employee
        every { accounts.save(any()) } answers { firstArg() }
        every { accounts.existsByPhotoUrl(any()) } returns false
        every { storage.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
            PutObjectResponse.builder().build()
        every { storage.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun cleanup() {
        TransactionSynchronizationManager.clearSynchronization()
    }

    @Test
    fun `invalid bytes are rejected before storage write`() {
        assertThrows<IllegalArgumentException> {
            service.upload(
                testId(1),
                testId(10),
                MockMultipartFile("photo", "fake.png", "image/png", "not an image".toByteArray()),
            )
        }
        verify(exactly = 0) { storage.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test
    fun `actual bytes determine stored content type and profile remains company local`() {
        val result = service.upload(testId(1), testId(10), png())
        assertSame(employee, result)
        assertTrue(result.photoUrl!!.startsWith("https://images.test/employees/${testId(1)}/${testId(10)}/"))
        verify { storage.putObject(match<PutObjectRequest> { it.contentType() == "image/png" }, any<RequestBody>()) }
        verify(exactly = 0) { storage.deleteObject(any<DeleteObjectRequest>()) }
    }

    @Test
    fun `rollback removes uploaded object and preserves former referenced object`() {
        service.upload(testId(1), testId(10), png())
        TransactionSynchronizationManager.getSynchronizations().forEach {
            it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        }
        verify(exactly = 1) {
            storage.deleteObject(
                match<DeleteObjectRequest> { it.key().startsWith("employees/${testId(1)}/${testId(10)}/") },
            )
        }
        verify(exactly = 0) { storage.deleteObject(match<DeleteObjectRequest> { it.key() == "legacy/shared.png" }) }
    }

    @Test
    fun `committed replacement deletes old unreferenced object only after commit`() {
        service.upload(testId(1), testId(10), png())
        TransactionSynchronizationManager.getSynchronizations().forEach {
            it.afterCommit()
            it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
        }
        verify(exactly = 1) { storage.deleteObject(match<DeleteObjectRequest> { it.key() == "legacy/shared.png" }) }
    }

    @Test
    fun `another company legacy reference prevents object deletion`() {
        every { accounts.existsByPhotoUrl(oldUrl) } returns true
        service.delete(testId(1), testId(10))
        assertNull(employee.photoUrl)
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        verify(exactly = 0) { storage.deleteObject(any<DeleteObjectRequest>()) }
    }

    @Test
    fun `foreign employee identifier is opaque missing before any write`() {
        every { accounts.findByCompanyIdAndUserIdForUpdate(testId(2), testId(10)) } returns null
        assertThrows<NoSuchElementException> { service.upload(testId(2), testId(10), png()) }
        verify(exactly = 0) { storage.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    private fun png(): MockMultipartFile {
        val bytes =
            ByteArrayOutputStream()
                .also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it) }
                .toByteArray()
        return MockMultipartFile("photo", "ignored.jpg", "application/octet-stream", bytes)
    }
}
