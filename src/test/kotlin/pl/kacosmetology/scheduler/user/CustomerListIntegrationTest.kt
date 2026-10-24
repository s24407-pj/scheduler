package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.LocalDateTime
import java.util.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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
import pl.kacosmetology.scheduler.security.StaffFixture
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerListIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: Clock
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var testSessions: TestSessions
    @Autowired private lateinit var userRepository: CompanyCustomerRepository
    @Autowired private lateinit var companyRepository: CompanyRepository
    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @Autowired private lateinit var offeringRepository: OfferingRepository
    @Autowired private lateinit var reservationRepository: ReservationRepository
    @Autowired private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository
    @Autowired private lateinit var companyCustomerRepository: CompanyCustomerRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var owner: StaffFixture
    private lateinit var employee: StaffFixture
    private lateinit var customer: CompanyCustomer
    private var companyId: UUID = testId(0)
    private var offeringId: UUID = testId(0)
    private lateinit var ownerSession: Cookie
    private lateinit var employeeSession: Cookie
    private lateinit var customerSession: Cookie

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyCustomerBlockRepository.deleteAll()
        companyCustomerRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "List Test Salon"))
        companyId = company.id!!

        owner = StaffFixture(phoneNumber = "+48811111001", firstName = "Owner", lastName = "List")
        val ownerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER"),
            )
        ownerSession = testSessions.staff(owner, ownerEmployment)

        employee = StaffFixture(phoneNumber = "+48811222001", firstName = "Employee", lastName = "List")
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE"),
            )
        employeeSession = testSessions.staff(employee, employeeEmployment)

        customer =
            userRepository.save(
                CompanyCustomer(
                    companyId = companyId,
                    phoneNumber = "+48811333001",
                    firstName = "Anna",
                    lastName = "Kowalska",
                ),
            )
        customerSession = testSessions.customer(customer)
        companyCustomerRepository.save(
            CompanyCustomer(
                id = customer.id,
                companyId = companyId,
                phoneNumber = customer.phoneNumber,
                firstBookingAt = java.time.Instant.now(),
                firstName = customer.firstName,
                lastName = customer.lastName,
            ),
        )

        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Usługa", durationMinutes = 30, price = 100),
            )
        offeringId = offering.id!!

        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id!!,
                employeeId = employee.id!!,
                serviceId = offeringId,
                price = 100,
                startTime = LocalDateTime.now().minusDays(1),
                endTime = LocalDateTime.now().minusDays(1).plusMinutes(30),
                status = ReservationStatus.COMPLETED,
            ),
        )
    }

    @Test
    fun `GET customers as owner should return customer list with phone number`() {
        mockMvc
            .get("/api/customers") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].id") { value(customer.id!!.toString()) }
                jsonPath("$[0].firstName") { value("Anna") }
                jsonPath("$[0].lastName") { value("Kowalska") }
                jsonPath("$[0].phoneNumber") { value("+48811333001") }
                jsonPath("$[0].blocked") { value(false) }
                jsonPath("$[0].noShowCount") { value(0) }
            }
    }

    @Test
    fun `GET customers as employee should return customer list`() {
        mockMvc
            .get("/api/customers") {
                cookie(employeeSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
            }
    }

    @Test
    fun `GET customers includes block status when customer is blocked`() {
        companyCustomerBlockRepository.save(
            CompanyCustomerBlock(companyId = companyId, customerId = customer.id!!, noShowCount = 2, blocked = true),
        )

        repeat(2) { index ->
            val start = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).minusDays(index.toLong() + 2)
            val missed =
                reservationRepository.save(
                    Reservation(
                        companyId = companyId,
                        customerId = customer.id!!,
                        employeeId = employee.id!!,
                        serviceId = offeringId,
                        price = 100,
                        startTime = start,
                        endTime = start.plusMinutes(30),
                        status = ReservationStatus.NO_SHOW,
                    ),
                )
            jdbc.update(
                "INSERT INTO no_show_penalties(reservation_id,company_id,customer_id,appointment_date) VALUES(?,?,?,?)",
                missed.id!!,
                companyId,
                customer.id!!,
                start.toLocalDate(),
            )
        }
        mockMvc
            .get("/api/customers") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].blocked") { value(true) }
                jsonPath("$[0].noShowCount") { value(2) }
            }
    }

    @Test
    fun `GET customers includes notes when set`() {
        companyCustomerRepository.save(
            CompanyCustomer(
                id = companyCustomerRepository.findByCompanyIdAndId(companyId, customer.id!!)!!.id,
                companyId = companyId,
                phoneNumber = customer.phoneNumber,
                firstBookingAt = java.time.Instant.now(),
                firstName = customer.firstName,
                lastName = customer.lastName,
                notes = "VIP klientka",
            ),
        )

        mockMvc
            .get("/api/customers") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].notes") { value("VIP klientka") }
            }
    }

    @Test
    fun `GET customers retains company customer relationship when reservations are archived`() {
        reservationRepository.findAll().forEach {
            it.status = ReservationStatus.CANCELLED
            it.archived = true
            reservationRepository.save(it)
        }

        mockMvc
            .get("/api/customers") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
            }
    }

    @Test
    fun `GET customers as unauthenticated user should return 401`() {
        mockMvc.get("/api/customers").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `GET customers as customer role should return 403`() {
        mockMvc
            .get("/api/customers") {
                cookie(customerSession)
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `GET customers does not include customers from other companies`() {
        val otherCompany = companyRepository.save(Company(name = "Other Salon"))
        val otherCustomer =
            userRepository.save(
                CompanyCustomer(
                    companyId = otherCompany.id!!,
                    firstBookingAt = clock.instant(),
                    phoneNumber = "+48811444001",
                    firstName = "Obcy",
                    lastName = "Klient",
                ),
            )
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = employee.id!!, role = "EMPLOYEE"),
        )
        val otherOffering =
            offeringRepository.save(
                Offering(companyId = otherCompany.id!!, name = "Usługa", durationMinutes = 30, price = 100),
            )
        reservationRepository.save(
            Reservation(
                companyId = otherCompany.id!!,
                customerId = otherCustomer.id!!,
                employeeId = employee.id!!,
                serviceId = otherOffering.id!!,
                price = 100,
                startTime = LocalDateTime.now().minusDays(2),
                endTime = LocalDateTime.now().minusDays(2).plusMinutes(30),
                status = ReservationStatus.COMPLETED,
            ),
        )

        mockMvc
            .get("/api/customers") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].id") { value(customer.id!!.toString()) }
            }
    }
}
