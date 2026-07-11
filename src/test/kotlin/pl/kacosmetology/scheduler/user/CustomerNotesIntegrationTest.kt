package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.JwtService
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerNotesIntegrationTest {

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
    private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @Autowired
    private lateinit var companyCustomerRepository: CompanyCustomerRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var owner: User
    private lateinit var employee: User
    private lateinit var customer: User
    private var companyId: Long = 0
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String
    private lateinit var customerToken: String

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyCustomerRepository.deleteAll()
        companyCustomerBlockRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Notes Test Salon"))
        companyId = company.id!!

        owner = userRepository.save(User(phoneNumber = "+48801111001", firstName = "Owner", lastName = "Notes"))
        val ownerEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER")
        )
        ownerToken = jwtService.generateStaffToken(owner, ownerEmployment)

        employee = userRepository.save(User(phoneNumber = "+48801222001", firstName = "Employee", lastName = "Notes"))
        val employeeEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE")
        )
        employeeToken = jwtService.generateStaffToken(employee, employeeEmployment)

        customer = userRepository.save(User(phoneNumber = "+48801333001", firstName = "Klient", lastName = "Notes"))
        customerToken = jwtService.generateCustomerToken(customer)
    }

    @Test
    fun `PUT notes as owner should return 204 and persist note`() {
        mockMvc.put("/api/customers/${customer.id!!}/notes") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to "VIP klient"))
        }.andExpect {
            status { isNoContent() }
        }

        val record = companyCustomerRepository.findByCompanyIdAndUserId(companyId, customer.id!!)!!
        assertEquals("VIP klient", record.notes)
    }

    @Test
    fun `PUT notes as employee should return 204 and persist note`() {
        mockMvc.put("/api/customers/${customer.id!!}/notes") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to "Regularny klient"))
        }.andExpect {
            status { isNoContent() }
        }

        val record = companyCustomerRepository.findByCompanyIdAndUserId(companyId, customer.id!!)!!
        assertEquals("Regularny klient", record.notes)
    }

    @Test
    fun `GET customer status includes notes`() {
        companyCustomerRepository.save(
            CompanyCustomer(
                companyId = companyId,
                userId = customer.id!!,
                notes = "Notatka testowa"
            )
        )

        mockMvc.get("/api/customers/${customer.id!!}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.notes") { value("Notatka testowa") }
        }
    }

    @Test
    fun `GET customer status returns null notes when no record`() {
        mockMvc.get("/api/customers/${customer.id!!}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.notes") { doesNotExist() }
        }
    }

    @Test
    fun `PUT null notes clears existing note`() {
        companyCustomerRepository.save(
            CompanyCustomer(
                companyId = companyId,
                userId = customer.id!!,
                notes = "Stara notatka"
            )
        )

        mockMvc.put("/api/customers/${customer.id!!}/notes") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to null))
        }.andExpect {
            status { isNoContent() }
        }

        val record = companyCustomerRepository.findByCompanyIdAndUserId(companyId, customer.id!!)!!
        assertNull(record.notes)
    }

    @Test
    fun `PUT blank string clears note stored as null`() {
        mockMvc.put("/api/customers/${customer.id!!}/notes") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to "   "))
        }.andExpect {
            status { isNoContent() }
        }

        val record = companyCustomerRepository.findByCompanyIdAndUserId(companyId, customer.id!!)!!
        assertNull(record.notes)
    }

    @Test
    fun `unauthenticated PUT notes should return 403`() {
        mockMvc.put("/api/customers/${customer.id!!}/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to "Test"))
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `customer-role PUT notes should return 403`() {
        mockMvc.put("/api/customers/${customer.id!!}/notes") {
            header("Authorization", "Bearer $customerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to "Test"))
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `notes exceeding 2000 chars should return 400`() {
        val longNote = "a".repeat(2001)

        mockMvc.put("/api/customers/${customer.id!!}/notes") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to longNote))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PUT notes for non-existent customer should return 404`() {
        mockMvc.put("/api/customers/999999/notes") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("notes" to "Test"))
        }.andExpect {
            status { isNotFound() }
        }
    }
}
