package pl.kacosmetology.scheduler.company

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.util.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.StaffFixture
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.CompanyCustomer
import pl.kacosmetology.scheduler.user.CompanyCustomerRepository
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CompanyEmployeeControllerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var userRepository: CompanyCustomerRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @MockkBean private lateinit var s3Client: S3Client

    private var ownerUserId: UUID = testId(0)
    private lateinit var employeeSession: Cookie
    private lateinit var customerSession: Cookie

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Testowy"))
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))

        val owner = StaffFixture(phoneNumber = "+48100200300", firstName = "Owner", lastName = "Test")
        ownerUserId = owner.id!!
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = company.id!!,
                userId = ownerUserId,
                role = "OWNER",
                firstName = "Owner",
                lastName = "Test",
                loginEmail = "owner@local.test",
            ),
        )

        val employee = StaffFixture(phoneNumber = "+48300200100", firstName = "Employee", lastName = "Test")
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = company.id!!, userId = employee.id!!, role = "EMPLOYEE"),
            )
        employeeSession = testSessions.staff(employee, employeeEmployment)

        val outsider = StaffFixture(phoneNumber = "+48500600700", firstName = "Outsider", lastName = "Test")
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = otherCompany.id!!,
                userId = outsider.id!!,
                role = "EMPLOYEE",
                firstName = "Outsider",
                lastName = "Test",
                loginEmail = "outsider@other.test",
            ),
        )

        val customer =
            userRepository.save(
                CompanyCustomer(
                    companyId = company.id!!,
                    phoneNumber = "+48700800900",
                    firstName = "Jan",
                    lastName = "Nowak",
                ),
            )
        customerSession = testSessions.customer(customer)
    }

    @Test
    fun `GET api-employees should list only own company employees with user ID as id`() {
        mockMvc
            .get("/api/employees") {
                cookie(employeeSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[?(@.firstName == 'Owner')].id") { value(ownerUserId.toString()) }
                jsonPath("$[?(@.firstName == 'Owner')].userId") { value(ownerUserId.toString()) }
                jsonPath("$[?(@.firstName == 'Outsider')]") { isEmpty() }
            }
    }

    @Test
    fun `GET api-employees should return 403 for customer`() {
        mockMvc
            .get("/api/employees") {
                cookie(customerSession)
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `removed company directory alias cannot expose staff data to customers or staff`() {
        for (session in listOf(customerSession, employeeSession)) {
            mockMvc
                .get("/api/company/employees") { cookie(session) }
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.loginEmail") { doesNotExist() }
                    jsonPath("$.contactPhone") { doesNotExist() }
                }
        }
    }

    @Test
    fun `GET api-employees should return 401 without session`() {
        mockMvc.get("/api/employees").andExpect {
            status { isUnauthorized() }
        }
    }
}
