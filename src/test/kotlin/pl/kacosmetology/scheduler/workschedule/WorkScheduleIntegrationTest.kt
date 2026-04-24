package pl.kacosmetology.scheduler.workschedule

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper
import java.time.DayOfWeek

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WorkScheduleIntegrationTest {

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
    private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository
    @Autowired
    private lateinit var reservationRepository: ReservationRepository
    @Autowired
    private lateinit var serviceRepository: OfferingRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private var companyId: Long = 0
    private var employeeId: Long = 0
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        workScheduleRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Grafik"))
        companyId = company.id!!

        val owner = userRepository.save(User(phoneNumber = "+48111222333", firstName = "Owner", lastName = "Test"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER"))
        ownerToken = jwtService.generateToken(
            CustomUserDetails(owner, companyId, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            companyId
        )

        val employee = userRepository.save(User(phoneNumber = "+48333222111", firstName = "Emp", lastName = "Test"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))
        employeeId = employee.id
        employeeToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )
    }

    @Test
    fun `PUT work-schedule should return 200 for owner and save entries`() {
        val body = mapOf(
            "entries" to listOf(
                mapOf("dayOfWeek" to "MONDAY", "startTime" to "09:00:00", "endTime" to "17:00:00"),
                mapOf("dayOfWeek" to "TUESDAY", "startTime" to "10:00:00", "endTime" to "18:00:00")
            )
        )

        mockMvc.put("/api/employees/$employeeId/work-schedule") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }

        assertEquals(2, workScheduleRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `PUT work-schedule should replace existing schedule idempotently`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = java.time.LocalTime.of(9, 0),
                endTime = java.time.LocalTime.of(17, 0)
            )
        )

        val body = mapOf(
            "entries" to listOf(
                mapOf("dayOfWeek" to "WEDNESDAY", "startTime" to "08:00:00", "endTime" to "16:00:00")
            )
        )

        mockMvc.put("/api/employees/$employeeId/work-schedule") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].dayOfWeek") { value("WEDNESDAY") }
        }

        assertEquals(1, workScheduleRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `PUT work-schedule should replace same day with updated hours without constraint violation`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = java.time.LocalTime.of(9, 0),
                endTime = java.time.LocalTime.of(17, 0)
            )
        )

        val body = mapOf(
            "entries" to listOf(
                mapOf("dayOfWeek" to "MONDAY", "startTime" to "10:00:00", "endTime" to "18:00:00")
            )
        )

        mockMvc.put("/api/employees/$employeeId/work-schedule") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].dayOfWeek") { value("MONDAY") }
            jsonPath("$[0].startTime") { value("10:00:00") }
        }

        assertEquals(1, workScheduleRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `PUT work-schedule should return 403 for employee`() {
        val body = mapOf("entries" to emptyList<Any>())

        mockMvc.put("/api/employees/$employeeId/work-schedule") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `PUT work-schedule should return 400 when duplicate days provided`() {
        val body = mapOf(
            "entries" to listOf(
                mapOf("dayOfWeek" to "MONDAY", "startTime" to "09:00:00", "endTime" to "17:00:00"),
                mapOf("dayOfWeek" to "MONDAY", "startTime" to "10:00:00", "endTime" to "18:00:00")
            )
        )

        mockMvc.put("/api/employees/$employeeId/work-schedule") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PUT work-schedule should return 400 when endTime is not after startTime`() {
        val body = mapOf(
            "entries" to listOf(
                mapOf("dayOfWeek" to "FRIDAY", "startTime" to "17:00:00", "endTime" to "09:00:00")
            )
        )

        mockMvc.put("/api/employees/$employeeId/work-schedule") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `GET work-schedule should return 200 with current schedule`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.THURSDAY,
                startTime = java.time.LocalTime.of(9, 0),
                endTime = java.time.LocalTime.of(17, 0)
            )
        )

        mockMvc.get("/api/employees/$employeeId/work-schedule") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].dayOfWeek") { value("THURSDAY") }
        }
    }
}
