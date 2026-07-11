package pl.kacosmetology.scheduler.offering

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
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

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var offeringRepository: OfferingRepository

    @Autowired
    private lateinit var offeringImageRepository: OfferingImageRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var owner: User
    private lateinit var employee: User
    private lateinit var ownerEmployment: CompanyEmployee
    private lateinit var employeeEmployment: CompanyEmployee
    private var companyId: Long = 0

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

        owner = userRepository.save(User(phoneNumber = "+48100000001", firstName = "Owner", lastName = "Test"))
        employee = userRepository.save(User(phoneNumber = "+48100000002", firstName = "Employee", lastName = "Test"))

        ownerEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER")
        )
        employeeEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE")
        )

        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder()
            .build()
        every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
    }

    private fun ownerToken() = jwtService.generateStaffToken(owner, ownerEmployment)

    private fun employeeToken() = jwtService.generateStaffToken(employee, employeeEmployment)

    @Test
    fun `POST image - owner uploads image and gets 200 with imageUrl`() {
        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
        )
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(1024))

        mockMvc.perform(
            multipart("/api/offerings/${offering.id}/image")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.images.length()").value(1))
            .andExpect(jsonPath("$.images[0].imageUrl").isString)
    }

    @Test
    fun `POST image - employee gets 403`() {
        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120)
        )
        val file = MockMultipartFile("image", "photo.png", "image/png", ByteArray(100))

        mockMvc.perform(
            multipart("/api/offerings/${offering.id}/image")
                .file(file)
                .header("Authorization", "Bearer ${employeeToken()}")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST image - unauthenticated gets 403`() {
        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120)
        )
        val file = MockMultipartFile("image", "photo.png", "image/png", ByteArray(100))

        mockMvc.perform(
            multipart("/api/offerings/${offering.id}/image").file(file)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST image - unknown offering returns 404`() {
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(100))

        mockMvc.perform(
            multipart("/api/offerings/99999/image")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST image - invalid MIME type returns 400`() {
        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120)
        )
        val file = MockMultipartFile("image", "doc.pdf", "application/pdf", ByteArray(100))

        mockMvc.perform(
            multipart("/api/offerings/${offering.id}/image")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST image - exceeding 5 image limit returns 409`() {
        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Masaż", durationMinutes = 60, price = 120)
        )
        repeat(5) { i ->
            offeringImageRepository.save(
                OfferingImage(
                    offeringId = offering.id!!,
                    imageUrl = "http://example.com/img$i.jpg"
                )
            )
        }
        val file = MockMultipartFile("image", "extra.jpg", "image/jpeg", ByteArray(100))

        mockMvc.perform(
            multipart("/api/offerings/${offering.id}/image")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `DELETE image - owner deletes image and response has empty images list`() {
        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
        )
        val image = offeringImageRepository.save(
            OfferingImage(offeringId = offering.id!!, imageUrl = "http://pub.r2.dev/offerings/1/1/abc.jpg")
        )

        mockMvc.delete("/api/offerings/${offering.id}/image/${image.id}") {
            header("Authorization", "Bearer ${ownerToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.images.length()") { value(0) }
        }
    }

    @Test
    fun `DELETE image - unknown image returns 404`() {
        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
        )

        mockMvc.delete("/api/offerings/${offering.id}/image/99999") {
            header("Authorization", "Bearer ${ownerToken()}")
        }.andExpect {
            status { isNotFound() }
        }
    }
}
