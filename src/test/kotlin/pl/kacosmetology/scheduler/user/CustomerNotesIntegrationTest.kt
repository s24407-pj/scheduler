package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.StaffFixture
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerNotesIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var userRepository: CompanyCustomerRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @Autowired private lateinit var companyCustomerRepository: CompanyCustomerRepository

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var owner: StaffFixture
    private lateinit var employee: StaffFixture
    private lateinit var customer: CompanyCustomer
    private var companyId: UUID = testId(0)
    private lateinit var ownerSession: Cookie
    private lateinit var employeeSession: Cookie
    private lateinit var customerSession: Cookie

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyCustomerBlockRepository.deleteAll()
        companyCustomerRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Notes Test Salon"))
        companyId = company.id!!

        owner = StaffFixture(phoneNumber = "+48801111001", firstName = "Owner", lastName = "Notes")
        val ownerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER"),
            )
        ownerSession = testSessions.staff(owner, ownerEmployment)

        employee = StaffFixture(phoneNumber = "+48801222001", firstName = "Employee", lastName = "Notes")
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE"),
            )
        employeeSession = testSessions.staff(employee, employeeEmployment)

        customer =
            userRepository.save(
                CompanyCustomer(
                    companyId = companyId,
                    phoneNumber = "+48801333001",
                    firstName = "Klient",
                    lastName = "Notes",
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
    }

    @Test
    fun `PUT notes as owner should return 204 and persist note`() {
        mockMvc
            .put("/api/customers/${customer.id!!}/notes") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to "VIP klient"))
            }
            .andExpect {
                status { isNoContent() }
            }

        val record = companyCustomerRepository.findByCompanyIdAndId(companyId, customer.id!!)!!
        assertEquals("VIP klient", record.notes)
    }

    @Test
    fun `PUT notes as employee should return 204 and persist note`() {
        mockMvc
            .put("/api/customers/${customer.id!!}/notes") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to "Regularny klient"))
            }
            .andExpect {
                status { isNoContent() }
            }

        val record = companyCustomerRepository.findByCompanyIdAndId(companyId, customer.id!!)!!
        assertEquals("Regularny klient", record.notes)
    }

    @Test
    fun `GET customer status includes notes`() {
        companyCustomerRepository.save(
            CompanyCustomer(
                id = companyCustomerRepository.findByCompanyIdAndId(companyId, customer.id!!)!!.id,
                firstName = customer.firstName,
                lastName = customer.lastName,
                companyId = companyId,
                phoneNumber = customer.phoneNumber,
                firstBookingAt = java.time.Instant.now(),
                notes = "Notatka testowa",
            ),
        )

        mockMvc
            .get("/api/customers/${customer.id!!}") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.notes") { value("Notatka testowa") }
            }
    }

    @Test
    fun `GET customer status returns null notes when no record`() {
        mockMvc
            .get("/api/customers/${customer.id!!}") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.notes") { doesNotExist() }
            }
    }

    @Test
    fun `PUT null notes clears existing note`() {
        companyCustomerRepository.save(
            CompanyCustomer(
                id = companyCustomerRepository.findByCompanyIdAndId(companyId, customer.id!!)!!.id,
                firstName = customer.firstName,
                lastName = customer.lastName,
                companyId = companyId,
                phoneNumber = customer.phoneNumber,
                firstBookingAt = java.time.Instant.now(),
                notes = "Stara notatka",
            ),
        )

        mockMvc
            .put("/api/customers/${customer.id!!}/notes") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to null))
            }
            .andExpect {
                status { isNoContent() }
            }

        val record = companyCustomerRepository.findByCompanyIdAndId(companyId, customer.id!!)!!
        assertNull(record.notes)
    }

    @Test
    fun `PUT blank string clears note stored as null`() {
        mockMvc
            .put("/api/customers/${customer.id!!}/notes") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to "   "))
            }
            .andExpect {
                status { isNoContent() }
            }

        val record = companyCustomerRepository.findByCompanyIdAndId(companyId, customer.id!!)!!
        assertNull(record.notes)
    }

    @Test
    fun `unauthenticated PUT notes should return 401`() {
        mockMvc
            .put("/api/customers/${customer.id!!}/notes") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to "Test"))
            }
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `customer-role PUT notes should return 403`() {
        mockMvc
            .put("/api/customers/${customer.id!!}/notes") {
                with(csrf())
                cookie(customerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to "Test"))
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `notes exceeding 2000 chars should return 400`() {
        val longNote = "a".repeat(2001)

        mockMvc
            .put("/api/customers/${customer.id!!}/notes") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to longNote))
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `PUT notes for non-existent customer should return 404`() {
        mockMvc
            .put("/api/customers/${testId(999999)}/notes") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("notes" to "Test"))
            }
            .andExpect {
                status { isNotFound() }
            }
    }
}
