package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
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
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerBlockIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtService: JwtService
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
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String
    private lateinit var customerToken: String

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
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER"))
        ownerToken = jwtService.generateToken(
            CustomUserDetails(owner, companyId, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            companyId
        )

        employee = userRepository.save(User(phoneNumber = "+48800222001", firstName = "Employee", lastName = "Test"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))
        employeeToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )

        customer = userRepository.save(User(phoneNumber = "+48800333001", firstName = "Klient", lastName = "Test"))
        customerToken = jwtService.generateToken(
            CustomUserDetails(customer, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))),
            null
        )

        val service = serviceRepository.save(
            Offering(companyId = companyId, name = "Usługa", durationMinutes = 30, price = 100)
        )
        serviceId = service.id!!

        // Give customer a reservation in this company so block/unblock is valid
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = serviceId,
                price = 100,
                startTime = LocalDateTime.now().minusDays(1),
                endTime = LocalDateTime.now().minusDays(1).plusMinutes(30),
                status = ReservationStatus.COMPLETED
            )
        )
    }

    @Test
    fun `PATCH block as owner should return 204 and block the customer`() {
        mockMvc.patch("/api/customers/${customer.id}/block") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isNoContent() }
        }

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id)!!
        assertTrue(block.blocked)
    }

    @Test
    fun `PATCH unblock as owner should return 204 and unblock the customer`() {
        // First set up a block record
        companyCustomerBlockRepository.save(
            CompanyCustomerBlock(companyId = companyId, customerId = customer.id, noShowCount = 5, blocked = true)
        )

        mockMvc.patch("/api/customers/${customer.id}/unblock") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isNoContent() }
        }

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id)!!
        assertFalse(block.blocked)
        assertEquals(0, block.noShowCount)
    }

    @Test
    fun `PATCH block as employee should return 403`() {
        mockMvc.patch("/api/customers/${customer.id}/block") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `blocked customer cannot create a reservation`() {
        // Block the customer at this company
        companyCustomerBlockRepository.save(
            CompanyCustomerBlock(companyId = companyId, customerId = customer.id, blocked = true)
        )

        val reservationTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val request = CreateReservationRequest(
            employeeId = employee.id,
            serviceId = serviceId,
            startTime = reservationTime
        )

        mockMvc.post("/api/reservations") {
            header("Authorization", "Bearer $customerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PATCH block for customer with no reservations in company should return 400`() {
        val otherCustomer = userRepository.save(
            User(phoneNumber = "+48800444001", firstName = "Obcy", lastName = "Klient")
        )

        mockMvc.patch("/api/customers/${otherCustomer.id}/block") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `unblock when no block record exists should return 204`() {
        // Customer has a reservation (from setup) but no block record
        assertNull(companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id))

        mockMvc.patch("/api/customers/${customer.id}/unblock") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isNoContent() }
        }
    }
}
