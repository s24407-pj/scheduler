package pl.kacosmetology.scheduler.offering

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.StaffFixture
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.CompanyCustomerRepository
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OfferingImageIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var offeringRepository: OfferingRepository

    @Autowired private lateinit var offeringImageRepository: OfferingImageRepository

    @Autowired private lateinit var userRepository: CompanyCustomerRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var imageService: OfferingImageService
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @MockkBean private lateinit var s3Client: S3Client

    private fun validImage(): ByteArray =
        ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

    private lateinit var owner: StaffFixture
    private lateinit var employee: StaffFixture
    private lateinit var ownerEmployment: CompanyEmployee
    private lateinit var employeeEmployment: CompanyEmployee
    private var companyId: UUID = testId(0)

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        offeringImageRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Test Salon"))
        companyId = company.id!!

        owner = StaffFixture(phoneNumber = "+48100000001", firstName = "Owner", lastName = "Test")
        employee = StaffFixture(phoneNumber = "+48100000002", firstName = "Employee", lastName = "Test")

        ownerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER"),
            )
        employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE"),
            )

        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
            PutObjectResponse.builder().build()
        every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
    }

    private fun ownerSession() = testSessions.staff(owner, ownerEmployment)

    private fun employeeSession() = testSessions.staff(employee, employeeEmployment)

    @Test
    fun `POST image - owner uploads image and gets 200 with imageUrl`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80),
            )
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", validImage())

        mockMvc
            .perform(
                multipart("/api/offerings/${offering.id!!}/image").file(file).with(csrf()).cookie(ownerSession()),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.images.length()").value(1))
            .andExpect(jsonPath("$.images[0].imageUrl").isString)
    }

    @Test
    fun `POST image - employee gets 403`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120),
            )
        val file = MockMultipartFile("image", "photo.png", "image/png", ByteArray(100))

        mockMvc
            .perform(
                multipart("/api/offerings/${offering.id!!}/image").file(file).with(csrf()).cookie(employeeSession()),
            )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST image - unauthenticated gets 401`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120),
            )
        val file = MockMultipartFile("image", "photo.png", "image/png", ByteArray(100))

        mockMvc
            .perform(
                multipart("/api/offerings/${offering.id!!}/image").file(file).with(csrf()),
            )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST image - unknown offering returns 404`() {
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(100))

        mockMvc
            .perform(
                multipart("/api/offerings/${testId(99999)}/image").file(file).with(csrf()).cookie(ownerSession()),
            )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST image - invalid MIME type returns 400`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120),
            )
        val file = MockMultipartFile("image", "doc.pdf", "application/pdf", ByteArray(100))

        mockMvc
            .perform(
                multipart("/api/offerings/${offering.id!!}/image").file(file).with(csrf()).cookie(ownerSession()),
            )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST image - exceeding 5 image limit returns 409`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120),
            )
        repeat(5) { i ->
            offeringImageRepository.save(
                OfferingImage(
                    offeringId = offering.id!!,
                    imageUrl = "http://example.com/img$i.jpg",
                ),
            )
        }
        val file = MockMultipartFile("image", "extra.jpg", "image/jpeg", ByteArray(100))

        mockMvc
            .perform(
                multipart("/api/offerings/${offering.id!!}/image").file(file).with(csrf()).cookie(ownerSession()),
            )
            .andExpect(status().isConflict)
    }

    @Test
    fun `DELETE image - owner deletes image and response has empty images list`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80),
            )
        val image =
            offeringImageRepository.save(
                OfferingImage(offeringId = offering.id!!, imageUrl = "http://pub.r2.dev/offerings/1/1/abc.jpg"),
            )

        mockMvc
            .delete("/api/offerings/${offering.id!!}/image/${image.id!!}") {
                with(csrf())
                cookie(ownerSession())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.images.length()") { value(0) }
            }
    }

    @Test
    fun `DELETE image - unknown image returns 404`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80),
            )

        mockMvc
            .delete("/api/offerings/${offering.id!!}/image/${testId(99999)}") {
                with(csrf())
                cookie(ownerSession())
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `concurrent uploads cannot exceed gallery capacity`() {
        val offering =
            offeringRepository.save(Offering(companyId = companyId, name = "Usługa", durationMinutes = 30, price = 80))
        repeat(4) {
            offeringImageRepository.save(
                OfferingImage(offeringId = offering.id!!, imageUrl = "https://example.test/$it.png"),
            )
        }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                pool
                    .invokeAll(
                        (1..2).map {
                            Callable {
                                barrier.await()
                                runCatching {
                                    imageService.upload(
                                        companyId,
                                        offering.id!!,
                                        MockMultipartFile("image", "a.png", "image/png", validImage()),
                                    )
                                }
                                    .isSuccess
                            }
                        },
                    )
                    .map { it.get() }
            assertEquals(1, results.count { it })
            assertEquals(5, offeringImageRepository.countByOfferingId(offering.id!!))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `rolled back deletion keeps referenced R2 object and rolled back upload removes new object`() {
        val offering =
            offeringRepository.save(Offering(companyId = companyId, name = "Usługa", durationMinutes = 30, price = 80))
        val image =
            offeringImageRepository.save(
                OfferingImage(offeringId = offering.id!!, imageUrl = "http://pub.r2.dev/existing.png"),
            )
        TransactionTemplate(transactions).executeWithoutResult { status ->
            imageService.delete(image.id!!, offering.id!!)
            verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
            status.setRollbackOnly()
        }
        assertFalse(offeringImageRepository.findById(image.id!!).isEmpty)
        verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
        TransactionTemplate(transactions).executeWithoutResult { status ->
            imageService.upload(
                companyId,
                offering.id!!,
                MockMultipartFile("image", "a.png", "image/png", validImage()),
            )
            status.setRollbackOnly()
        }
        assertEquals(1, offeringImageRepository.countByOfferingId(offering.id!!))
        verify(exactly = 1) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }

    @Test
    fun `foreign gallery mutation is opaque before validation or object storage`() {
        val foreignCompany = companyRepository.save(Company(name = "Inna firma"))
        val offering =
            offeringRepository.save(
                Offering(companyId = foreignCompany.id!!, name = "Usługa", durationMinutes = 30, price = 80),
            )
        val image =
            offeringImageRepository.save(
                OfferingImage(offeringId = offering.id!!, imageUrl = "http://pub.r2.dev/existing.png"),
            )
        mockMvc
            .perform(
                multipart("/api/offerings/${offering.id!!}/image")
                    .file(MockMultipartFile("image", "fake.png", "image/png", byteArrayOf(1)))
                    .with(csrf())
                    .cookie(ownerSession()),
            )
            .andExpect(status().isNotFound)
        mockMvc
            .delete("/api/offerings/${offering.id!!}/image/${image.id!!}") {
                with(csrf())
                cookie(ownerSession())
            }
            .andExpect { status { isNotFound() } }
        verify(exactly = 0) { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }
}
