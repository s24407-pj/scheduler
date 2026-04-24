package pl.kacosmetology.scheduler.employeeoffering

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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EmployeeOfferingIntegrationTest {

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
    private lateinit var offeringRepository: OfferingRepository
    @Autowired
    private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository
    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private var companyId: Long = 0
    private var employeeId: Long = 0
    private var offeringId: Long = 0
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        assignmentRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Usługowy"))
        companyId = company.id!!

        val owner = userRepository.save(User(phoneNumber = "+48100100100", firstName = "Owner", lastName = "Test"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER"))
        ownerToken = jwtService.generateToken(
            CustomUserDetails(owner, companyId, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            companyId
        )

        val employee = userRepository.save(User(phoneNumber = "+48200200200", firstName = "Emp", lastName = "Test"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))
        employeeId = employee.id
        employeeToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )

        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 60)
        )
        offeringId = offering.id!!
    }

    @Test
    fun `POST assign offering should return 201`() {
        mockMvc.post("/api/employees/$employeeId/offerings/$offeringId") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.offeringId") { value(offeringId) }
            jsonPath("$.employeeId") { value(employeeId) }
        }

        assertEquals(1, assignmentRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `POST assign offering should be idempotent`() {
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId
            )
        )

        mockMvc.post("/api/employees/$employeeId/offerings/$offeringId") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isCreated() }
        }

        assertEquals(1, assignmentRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `DELETE remove assignment should return 204`() {
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId
            )
        )

        mockMvc.delete("/api/employees/$employeeId/offerings/$offeringId") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isNoContent() }
        }

        assertEquals(0, assignmentRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `DELETE remove assignment should return 404 when assignment does not exist`() {
        mockMvc.delete("/api/employees/$employeeId/offerings/$offeringId") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `GET public employee offerings should return assigned active offerings`() {
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId
            )
        )

        mockMvc.get("/api/offerings/public/employee/$employeeId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(offeringId) }
        }
    }

    @Test
    fun `GET public employee offerings should return empty list when no assignments`() {
        mockMvc.get("/api/offerings/public/employee/$employeeId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `POST reservation should return 400 when employee has assignments but not for requested offering`() {
        val otherOffering = offeringRepository.save(
            Offering(companyId = companyId, name = "Koloryzacja", durationMinutes = 60, price = 150)
        )
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId
            )
        )

        val customer = userRepository.save(User(phoneNumber = "+48999888777", firstName = "Klient", lastName = "Test"))

        val body = mapOf(
            "employeeId" to employeeId,
            "serviceId" to otherOffering.id,
            "startTime" to LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
                .toString(),
            "customerPhone" to customer.phoneNumber
        )

        mockMvc.post("/api/reservations/staff") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
