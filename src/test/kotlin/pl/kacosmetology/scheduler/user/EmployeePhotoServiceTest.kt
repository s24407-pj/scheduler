package pl.kacosmetology.scheduler.user

import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.R2Properties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.util.*

@ExtendWith(MockKExtension::class)
class EmployeePhotoServiceTest {

    @MockK
    private lateinit var s3Client: S3Client

    @MockK
    private lateinit var r2Props: R2Properties

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @InjectMockKs
    private lateinit var employeePhotoService: EmployeePhotoService

    private val companyId = 1L
    private val employeeId = 10L

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun stubR2Props() {
        every { r2Props.bucketName } returns "test-bucket"
        every { r2Props.publicUrl } returns "https://pub.r2.dev"
    }

    @Test
    fun `upload should store photo and return updated user`() {
        // GIVEN
        stubR2Props()
        val user = User(id = employeeId, phoneNumber = "+48100000001", firstName = "Jan", lastName = "Kowalski")
        val file = MockMultipartFile("photo", "photo.jpg", "image/jpeg", ByteArray(1024))

        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { userRepository.findById(employeeId) } returns Optional.of(user)
        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder()
            .build()
        every { userRepository.save(any()) } answers { firstArg() }
        mockkStatic(TransactionSynchronizationManager::class)
        every { TransactionSynchronizationManager.registerSynchronization(any()) } just Runs

        // WHEN
        val result = employeePhotoService.upload(companyId, employeeId, file)

        // THEN
        assertTrue(result.photoUrl?.startsWith("https://pub.r2.dev/employees/$companyId/$employeeId/") == true)
        verify(exactly = 1) { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
        verify(exactly = 1) { userRepository.save(user) }
    }

    @Test
    fun `upload should delete old R2 object when user already has a photo`() {
        // GIVEN
        stubR2Props()
        val oldUrl = "https://pub.r2.dev/employees/$companyId/$employeeId/old.jpg"
        val user = User(id = employeeId, phoneNumber = "+48100000001", firstName = "Jan", lastName = "Kowalski")
        user.photoUrl = oldUrl
        val file = MockMultipartFile("photo", "new.png", "image/png", ByteArray(512))

        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { userRepository.findById(employeeId) } returns Optional.of(user)
        every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder()
            .build()
        every { userRepository.save(any()) } answers { firstArg() }
        val syncSlot = slot<TransactionSynchronization>()
        mockkStatic(TransactionSynchronizationManager::class)
        every { TransactionSynchronizationManager.registerSynchronization(capture(syncSlot)) } just Runs

        // WHEN
        employeePhotoService.upload(companyId, employeeId, file)
        syncSlot.captured.afterCommit()

        // THEN
        verify(exactly = 1) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
        verify(exactly = 1) { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test
    fun `upload should throw IllegalArgumentException for invalid file type`() {
        // GIVEN
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        val file = MockMultipartFile("photo", "doc.pdf", "application/pdf", ByteArray(100))

        // WHEN & THEN
        val ex = assertThrows<IllegalArgumentException> {
            employeePhotoService.upload(companyId, employeeId, file)
        }
        assertTrue(ex.message!!.contains("Niedozwolony typ pliku"))
        verify(exactly = 0) { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test
    fun `upload should throw IllegalArgumentException when file exceeds 5 MB`() {
        // GIVEN
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        val file = MockMultipartFile("photo", "big.jpg", "image/jpeg", ByteArray(6 * 1024 * 1024))

        // WHEN & THEN
        val ex = assertThrows<IllegalArgumentException> {
            employeePhotoService.upload(companyId, employeeId, file)
        }
        assertTrue(ex.message!!.contains("za duży"))
        verify(exactly = 0) { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test
    fun `upload should throw NoSuchElementException when employee not in company`() {
        // GIVEN
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false
        val file = MockMultipartFile("photo", "photo.jpg", "image/jpeg", ByteArray(100))

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            employeePhotoService.upload(companyId, employeeId, file)
        }
        verify(exactly = 0) { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test
    fun `delete should remove R2 object and clear photoUrl`() {
        // GIVEN
        stubR2Props()
        val url = "https://pub.r2.dev/employees/$companyId/$employeeId/abc.jpg"
        val user = User(id = employeeId, phoneNumber = "+48100000001", firstName = "Jan", lastName = "Kowalski")
        user.photoUrl = url

        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { userRepository.findById(employeeId) } returns Optional.of(user)
        every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
        every { userRepository.save(any()) } answers { firstArg() }
        val syncSlot = slot<TransactionSynchronization>()
        mockkStatic(TransactionSynchronizationManager::class)
        every { TransactionSynchronizationManager.registerSynchronization(capture(syncSlot)) } just Runs

        // WHEN
        val result = employeePhotoService.delete(companyId, employeeId)
        syncSlot.captured.afterCommit()

        // THEN
        assertNull(result.photoUrl)
        verify(exactly = 1) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
        verify(exactly = 1) { userRepository.save(user) }
    }

    @Test
    fun `delete should be no-op when user has no photo`() {
        // GIVEN
        stubR2Props()
        val user = User(id = employeeId, phoneNumber = "+48100000001", firstName = "Jan", lastName = "Kowalski")

        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { userRepository.findById(employeeId) } returns Optional.of(user)

        // WHEN
        val result = employeePhotoService.delete(companyId, employeeId)

        // THEN
        assertNull(result.photoUrl)
        verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `delete should throw NoSuchElementException when employee not in company`() {
        // GIVEN
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            employeePhotoService.delete(companyId, employeeId)
        }
        verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }
}
