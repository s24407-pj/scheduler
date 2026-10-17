package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.reservation.dto.CreateReservationRequest
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerBlockIntegrationTest {

    @Autowired private lateinit var clock: Clock
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var profiles: CompanyCustomerRepository

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var serviceRepository: OfferingRepository

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @Autowired private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var owner: User
    private lateinit var employee: User
    private lateinit var customer: User
    private var companyId: UUID = testId(0)
    private var serviceId: UUID = testId(0)
    private lateinit var ownerSession: Cookie
    private lateinit var employeeSession: Cookie
    private lateinit var customerSession: Cookie

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyCustomerBlockRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Block Test Salon", maxNoShows = 3))
        companyId = company.id!!

        owner = userRepository.save(User(phoneNumber = "+48800111001", firstName = "Owner", lastName = "Test"))
        val ownerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER"),
            )
        ownerSession = testSessions.staff(owner, ownerEmployment)

        employee = userRepository.save(User(phoneNumber = "+48800222001", firstName = "Employee", lastName = "Test"))
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE"),
            )
        employeeSession = testSessions.staff(employee, employeeEmployment)

        customer = userRepository.save(User(phoneNumber = "+48800333001", firstName = "Klient", lastName = "Test"))
        customerSession = testSessions.customer(customer)

        val service =
            serviceRepository.save(
                Offering(companyId = companyId, name = "Usługa", durationMinutes = 30, price = 100),
            )
        serviceId = service.id!!

        profiles.save(
            CompanyCustomer(
                companyId = companyId,
                userId = customer.id!!,
                firstName = "Klient",
                lastName = "Test",
            ),
        )

        // Give customer a reservation in this company so block/unblock is valid
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id!!,
                employeeId = employee.id!!,
                serviceId = serviceId,
                price = 100,
                startTime = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).minusDays(1),
                endTime = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).minusDays(1).plusMinutes(30),
                status = ReservationStatus.COMPLETED,
            ),
        )
    }

    @Test
    fun `PATCH block as owner should return 204 and block the customer`() {
        mockMvc
            .patch("/api/customers/${customer.id!!}/block") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNoContent() }
            }

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id!!)!!
        assertTrue(block.blocked)
    }

    @Test
    fun `owner unblock preserves durable no-show penalties and clears both block sources`() {
        penalties(3)
        companyCustomerBlockRepository.save(
            CompanyCustomerBlock(
                companyId = companyId,
                customerId = customer.id!!,
                noShowCount = 3,
                blocked = true,
                manualBlocked = true,
                automaticBlocked = true,
            ),
        )

        mockMvc
            .patch("/api/customers/${customer.id!!}/unblock") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNoContent() }
            }

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id!!)!!
        assertFalse(block.blocked)
        assertFalse(block.manualBlocked)
        assertFalse(block.automaticBlocked)
        assertEquals(3, block.noShowCount)
        mockMvc
            .get("/api/customers/${customer.id}") { cookie(ownerSession) }
            .andExpect {
                status { isOk() }
                jsonPath("$.noShowCount") { value(3) }
                jsonPath("$.blocked") { value(false) }
            }
        assertEquals(
            3,
            jdbc.queryForObject(
                "SELECT count(*) FROM no_show_penalties WHERE company_id=? AND customer_id=? AND active",
                Int::class.java,
                companyId,
                customer.id!!,
            ),
        )
    }

    @Test
    fun `PATCH block as employee should return 403`() {
        mockMvc
            .patch("/api/customers/${customer.id!!}/block") {
                with(csrf())
                cookie(employeeSession)
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `blocked customer cannot create a reservation`() {
        // Block the customer at this company
        companyCustomerBlockRepository.save(
            CompanyCustomerBlock(companyId = companyId, customerId = customer.id!!, blocked = true),
        )

        val reservationTime =
            LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE)
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val request =
            CreateReservationRequest(
                employeeId = employee.id!!,
                serviceId = serviceId,
                startTime = reservationTime,
            )

        mockMvc
            .post("/api/reservations") {
                with(csrf())
                header("Idempotency-Key", "blocked-customer-attempt")
                cookie(customerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("CUSTOMER_BLOCKED") }
            }
    }

    @Test
    fun `foreign customer without a local profile gets opaque 404`() {
        val otherCustomer =
            userRepository.save(
                User(phoneNumber = "+48800444001", firstName = "Obcy", lastName = "Klient"),
            )

        mockMvc
            .patch("/api/customers/${otherCustomer.id!!}/block") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `unblock when no block record exists should return 204`() {
        // Customer has a reservation (from setup) but no block record
        assertNull(companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id!!))

        mockMvc
            .patch("/api/customers/${customer.id!!}/unblock") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNoContent() }
            }
    }

    @Test
    fun `blocking cancels future local visits preserves ongoing visits and unblock restores nothing`() {
        val now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE)
        val future =
            reservationRepository.save(
                Reservation(
                    companyId = companyId,
                    customerId = customer.id!!,
                    employeeId = employee.id!!,
                    serviceId = serviceId,
                    price = 100,
                    startTime = now.plusDays(1),
                    endTime = now.plusDays(1).plusMinutes(30),
                ),
            )
        val ongoing =
            reservationRepository.save(
                Reservation(
                    companyId = companyId,
                    customerId = customer.id!!,
                    employeeId = employee.id!!,
                    serviceId = serviceId,
                    price = 100,
                    startTime = now.minusMinutes(15),
                    endTime = now.plusMinutes(15),
                ),
            )
        mockMvc
            .patch("/api/customers/${customer.id}/block") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect { status { isNoContent() } }
        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(future.id!!).orElseThrow().status)
        assertEquals(ReservationStatus.BOOKED, reservationRepository.findById(ongoing.id!!).orElseThrow().status)
        mockMvc
            .patch("/api/customers/${customer.id}/unblock") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect { status { isNoContent() } }
        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(future.id!!).orElseThrow().status)
        mockMvc.get("/api/users/me") { cookie(customerSession) }.andExpect { status { isOk() } }
    }

    private fun penalties(count: Int) {
        val now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE)
        repeat(count) { index ->
            val start = now.minusDays(index.toLong() + 2)
            val visit =
                reservationRepository.save(
                    Reservation(
                        companyId = companyId,
                        customerId = customer.id!!,
                        employeeId = employee.id!!,
                        serviceId = serviceId,
                        price = 100,
                        startTime = start,
                        endTime = start.plusMinutes(30),
                        status = ReservationStatus.NO_SHOW,
                    ),
                )
            jdbc.update(
                "INSERT INTO no_show_penalties(reservation_id,company_id,customer_id,appointment_date) VALUES(?,?,?,?)",
                visit.id!!,
                companyId,
                customer.id!!,
                start.toLocalDate(),
            )
        }
    }
}
