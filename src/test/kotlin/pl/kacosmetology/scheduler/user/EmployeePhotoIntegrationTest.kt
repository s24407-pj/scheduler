package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.authority.SimpleGrantedAuthority
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
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EmployeePhotoIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

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
    private var companyId: Long = 0

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Test Salon"))
        companyId = company.id!!

        owner = userRepository.save(User(phoneNumber = "+48100000001", firstName = "Owner", lastName = "Test"))
        employee = userRepository.save(User(phoneNumber = "+48100000002", firstName = "Employee", lastName = "Test"))

        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))

        every { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder()
            .build()
        every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()
    }

    private fun ownerToken() = jwtService.generateToken(
        CustomUserDetails(owner, companyId, listOf(SimpleGrantedAuthority("ROLE_OWNER"))), companyId
    )

    private fun employeeToken() = jwtService.generateToken(
        CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))), companyId
    )

    @Test
    fun `POST photo - owner uploads photo and gets 200 with photoUrl`() {
        val file = MockMultipartFile("photo", "photo.jpg", "image/jpeg", ByteArray(1024))

        mockMvc.perform(
            multipart("/api/employees/${employee.id}/photo")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoUrl").isString)
            .andExpect(jsonPath("$.id").value(employee.id))
    }

    @Test
    fun `POST photo - uploading replacement deletes old key and returns new URL`() {
        // First upload
        val file1 = MockMultipartFile("photo", "first.jpg", "image/jpeg", ByteArray(512))
        mockMvc.perform(
            multipart("/api/employees/${employee.id}/photo")
                .file(file1)
                .header("Authorization", "Bearer ${ownerToken()}")
        ).andExpect(status().isOk)

        // Second upload should trigger a delete of the old key
        val file2 = MockMultipartFile("photo", "second.png", "image/png", ByteArray(256))
        mockMvc.perform(
            multipart("/api/employees/${employee.id}/photo")
                .file(file2)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoUrl").isString)

        verify(exactly = 1) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
        verify(exactly = 2) { s3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test
    fun `POST photo - employee (non-owner) gets 403`() {
        val file = MockMultipartFile("photo", "photo.jpg", "image/jpeg", ByteArray(100))

        mockMvc.perform(
            multipart("/api/employees/${employee.id}/photo")
                .file(file)
                .header("Authorization", "Bearer ${employeeToken()}")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST photo - unauthenticated gets 403`() {
        val file = MockMultipartFile("photo", "photo.jpg", "image/jpeg", ByteArray(100))

        mockMvc.perform(
            multipart("/api/employees/${employee.id}/photo").file(file)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `POST photo - employee not in company returns 404`() {
        val outsider =
            userRepository.save(User(phoneNumber = "+48199999999", firstName = "Outside", lastName = "Person"))
        val file = MockMultipartFile("photo", "photo.jpg", "image/jpeg", ByteArray(100))

        mockMvc.perform(
            multipart("/api/employees/${outsider.id}/photo")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST photo - invalid MIME type returns 400`() {
        val file = MockMultipartFile("photo", "doc.pdf", "application/pdf", ByteArray(100))

        mockMvc.perform(
            multipart("/api/employees/${employee.id}/photo")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST photo - file too large returns 400`() {
        val file = MockMultipartFile("photo", "big.jpg", "image/jpeg", ByteArray(6 * 1024 * 1024))

        mockMvc.perform(
            multipart("/api/employees/${employee.id}/photo")
                .file(file)
                .header("Authorization", "Bearer ${ownerToken()}")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `DELETE photo - owner removes existing photo and gets 200 with null photoUrl`() {
        // Persist photo URL directly
        employee.photoUrl = "https://pub.r2.dev/employees/$companyId/${employee.id}/abc.jpg"
        userRepository.save(employee)

        mockMvc.delete("/api/employees/${employee.id}/photo") {
            header("Authorization", "Bearer ${ownerToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.photoUrl") { value(null) }
        }

        verify(exactly = 1) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }

    @Test
    fun `DELETE photo - no-op when employee has no photo, returns 200 with null photoUrl`() {
        mockMvc.delete("/api/employees/${employee.id}/photo") {
            header("Authorization", "Bearer ${ownerToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.photoUrl") { value(null) }
        }

        verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
    }
}
