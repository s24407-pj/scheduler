package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDateTime

/**
 * Integration tests for [ReservationController.getReservations] — the owner dashboard endpoint
 * `GET /api/reservations?employeeId=&start=&end=`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ReservationDashboardIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

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
    private lateinit var reservationRepository: ReservationRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var owner: User
    private lateinit var employee: User
    private lateinit var customer: User
    private var companyId: Long = 0
    private var offeringId: Long = 0
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String

    private val rangeStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0)
    private val rangeEnd = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0)

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Dashboard Salon"))
        companyId = company.id!!

        owner = userRepository.save(User(phoneNumber = "+48100000001", firstName = "Owner", lastName = "Test"))
        employee = userRepository.save(User(phoneNumber = "+48100000002", firstName = "Employee", lastName = "Test"))
        customer = userRepository.save(User(phoneNumber = "+48100000003", firstName = "Customer", lastName = "Test"))

        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))

        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
        )
        offeringId = offering.id!!

        // Reservation for the employee today at 10:00
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = offeringId,
                price = 80,
                startTime = rangeStart.withHour(10),
                endTime = rangeStart.withHour(11),
                status = ReservationStatus.CONFIRMED
            )
        )

        ownerToken = jwtService.generateToken(
            CustomUserDetails(owner, companyId, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            companyId
        )
        employeeToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )
    }

    @Test
    fun `owner should be able to query any employee's reservations`() {
        mockMvc.get("/api/reservations") {
            header("Authorization", "Bearer $ownerToken")
            param("employeeId", employee.id.toString())
            param("start", rangeStart.toString())
            param("end", rangeEnd.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].employeeId") { value(employee.id) }
            jsonPath("$[0].customerId") { value(customer.id) }
            jsonPath("$[0].status") { value("CONFIRMED") }
        }
    }

    @Test
    fun `employee should be able to query own reservations`() {
        mockMvc.get("/api/reservations") {
            header("Authorization", "Bearer $employeeToken")
            param("employeeId", employee.id.toString())
            param("start", rangeStart.toString())
            param("end", rangeEnd.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
        }
    }

    @Test
    fun `employee should get 403 when querying another employee's reservations`() {
        val otherEmployee = userRepository.save(
            User(phoneNumber = "+48199999999", firstName = "Other", lastName = "Employee")
        )
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = companyId,
                userId = otherEmployee.id,
                role = "EMPLOYEE"
            )
        )

        mockMvc.get("/api/reservations") {
            header("Authorization", "Bearer $employeeToken")
            param("employeeId", otherEmployee.id.toString())
            param("start", rangeStart.toString())
            param("end", rangeEnd.toString())
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `results should be filtered by date range`() {
        // Add a reservation outside the range (next week)
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = offeringId,
                price = 80,
                startTime = rangeStart.plusDays(7).withHour(9),
                endTime = rangeStart.plusDays(7).withHour(10),
                status = ReservationStatus.PENDING
            )
        )

        // Query only today — should return 1 (the one from @BeforeEach)
        mockMvc.get("/api/reservations") {
            header("Authorization", "Bearer $ownerToken")
            param("employeeId", employee.id.toString())
            param("start", rangeStart.toString())
            param("end", rangeEnd.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
        }
    }

    @Test
    fun `dashboard results should include reservations overlapping range start`() {
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = offeringId,
                price = 80,
                startTime = rangeStart.minusMinutes(30),
                endTime = rangeStart.plusMinutes(30),
                status = ReservationStatus.CONFIRMED
            )
        )

        mockMvc.get("/api/reservations") {
            header("Authorization", "Bearer $ownerToken")
            param("employeeId", employee.id.toString())
            param("start", rangeStart.toString())
            param("end", rangeStart.plusHours(1).toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].startTime") {
                value(
                    org.hamcrest.Matchers.startsWith(
                        rangeStart.minusMinutes(30).toString().substring(0, 16)
                    )
                )
            }
        }
    }

    @Test
    fun `unauthenticated request should be rejected`() {
        mockMvc.get("/api/reservations") {
            param("employeeId", employee.id.toString())
            param("start", rangeStart.toString())
            param("end", rangeEnd.toString())
        }.andExpect {
            // Spring Security returns 403 for unauthenticated requests when no auth entry point is configured
            status { isForbidden() }
        }
    }
}
