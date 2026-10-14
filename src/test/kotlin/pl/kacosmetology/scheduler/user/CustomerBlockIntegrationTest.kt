package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.reservation.dto.CreateReservationRequest
import pl.kacosmetology.scheduler.security.TestSessions
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerBlockIntegrationTest {

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
    private var companyId: Long = 0
    private var serviceId: Long = 0
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

        // Give customer a reservation in this company so block/unblock is valid
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id!!,
                employeeId = employee.id!!,
                serviceId = serviceId,
                price = 100,
                startTime = LocalDateTime.now().minusDays(1),
                endTime = LocalDateTime.now().minusDays(1).plusMinutes(30),
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
    fun `PATCH unblock as owner should return 204 and unblock the customer`() {
        // First set up a block record
        companyCustomerBlockRepository.save(
            CompanyCustomerBlock(companyId = companyId, customerId = customer.id!!, noShowCount = 5, blocked = true),
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
        assertEquals(0, block.noShowCount)
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

        val reservationTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val request =
            CreateReservationRequest(
                employeeId = employee.id!!,
                serviceId = serviceId,
                startTime = reservationTime,
            )

        mockMvc
            .post("/api/reservations") {
                with(csrf())
                cookie(customerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `PATCH block for customer with no reservations in company should return 400`() {
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
                status { isBadRequest() }
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
}
