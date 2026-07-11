package pl.kacosmetology.scheduler.company

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CompanySettingsIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var serviceRepository: OfferingRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private var companyId: Long = 0
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Testowy"))
        companyId = company.id!!

        val owner = userRepository.save(User(phoneNumber = "+48100200300", firstName = "Owner", lastName = "Test"))
        val ownerEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER")
        )
        ownerToken = jwtService.generateStaffToken(owner, ownerEmployment)

        val employee =
            userRepository.save(User(phoneNumber = "+48300200100", firstName = "Employee", lastName = "Test"))
        val employeeEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE")
        )
        employeeToken = jwtService.generateStaffToken(employee, employeeEmployment)
    }

    @Test
    fun `GET api-company-settings should return 200 for employee`() {
        mockMvc.get("/api/company/settings") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(companyId) }
            jsonPath("$.openingTime") { exists() }
            jsonPath("$.closingTime") { exists() }
            jsonPath("$.slotIntervalMinutes") { exists() }
        }
    }

    @Test
    fun `PUT api-company-settings should return 200 for owner`() {
        val body = mapOf(
            "openingTime" to "08:00:00",
            "closingTime" to "20:00:00",
            "slotIntervalMinutes" to 15
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isOk() }
            jsonPath("$.openingTime") { value("08:00:00") }
            jsonPath("$.closingTime") { value("20:00:00") }
            jsonPath("$.slotIntervalMinutes") { value(15) }
        }
    }

    @Test
    fun `PUT api-company-settings should return 403 for employee`() {
        val body = mapOf(
            "openingTime" to "08:00:00",
            "closingTime" to "20:00:00",
            "slotIntervalMinutes" to 15
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `PUT api-company-settings should return 400 when closingTime equals openingTime`() {
        val body = mapOf(
            "openingTime" to "10:00:00",
            "closingTime" to "10:00:00",
            "slotIntervalMinutes" to 30
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `GET api-company-employees should return all employees for owner`() {
        mockMvc.get("/api/company/employees") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[*].firstName") { value(org.hamcrest.Matchers.containsInAnyOrder("Owner", "Employee")) }
            jsonPath("$[*].role") { value(org.hamcrest.Matchers.containsInAnyOrder("OWNER", "EMPLOYEE")) }
        }
    }

    @Test
    fun `GET api-company-employees should return 200 for employee too`() {
        mockMvc.get("/api/company/employees") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    fun `GET api-company-employees should return 403 without token`() {
        mockMvc.get("/api/company/employees").andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `PUT api-company-settings should return 400 when slotInterval is not divisible by 5`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 7
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PUT api-company-settings should persist maxNoShows`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 30,
            "maxNoShows" to 5
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isOk() }
            jsonPath("$.maxNoShows") { value(5) }
        }
    }

    @Test
    fun `PUT api-company-settings should persist lastMinuteDiscount fields`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 30,
            "lastMinuteDiscountPercent" to 25,
            "lastMinuteDiscountHours" to 12
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isOk() }
            jsonPath("$.lastMinuteDiscountPercent") { value(25) }
            jsonPath("$.lastMinuteDiscountHours") { value(12) }
        }
    }

    @Test
    fun `PUT api-company-settings should return 400 when lastMinuteDiscountPercent exceeds 100`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 30,
            "lastMinuteDiscountPercent" to 101
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PUT api-company-settings should return 400 when lastMinuteDiscountHours is zero`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 30,
            "lastMinuteDiscountHours" to 0
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PUT api-company-settings should persist minBookingAdvanceMinutes`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 30,
            "minBookingAdvanceMinutes" to 120
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isOk() }
            jsonPath("$.minBookingAdvanceMinutes") { value(120) }
        }
    }

    @Test
    fun `PUT api-company-settings should return 400 when minBookingAdvanceMinutes is negative`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 30,
            "minBookingAdvanceMinutes" to -1
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PUT api-company-settings should return 400 when minBookingAdvanceMinutes exceeds 10080`() {
        val body = mapOf(
            "openingTime" to "09:00:00",
            "closingTime" to "17:00:00",
            "slotIntervalMinutes" to 30,
            "minBookingAdvanceMinutes" to 10081
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PUT api-company-settings should return 400 when closingTime is before openingTime`() {
        val body = mapOf(
            "openingTime" to "18:00:00",
            "closingTime" to "09:00:00",
            "slotIntervalMinutes" to 30
        )

        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
