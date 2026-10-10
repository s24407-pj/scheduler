package pl.kacosmetology.scheduler.company

import com.ninjasquad.springmockk.MockkBean
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
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CompanyEmployeeControllerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var jwtService: JwtService

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @MockkBean private lateinit var s3Client: S3Client

    private var ownerUserId: Long = 0
    private lateinit var employeeToken: String
    private lateinit var customerToken: String

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Testowy"))
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))

        val owner = userRepository.save(User(phoneNumber = "+48100200300", firstName = "Owner", lastName = "Test"))
        ownerUserId = owner.id!!
        companyEmployeeRepository.save(CompanyEmployee(companyId = company.id!!, userId = ownerUserId, role = "OWNER"))

        val employee =
            userRepository.save(User(phoneNumber = "+48300200100", firstName = "Employee", lastName = "Test"))
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = company.id!!, userId = employee.id!!, role = "EMPLOYEE"),
            )
        employeeToken = jwtService.generateStaffToken(employee, employeeEmployment)

        val outsider =
            userRepository.save(User(phoneNumber = "+48500600700", firstName = "Outsider", lastName = "Test"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = outsider.id!!, role = "EMPLOYEE"),
        )

        val customer = userRepository.save(User(phoneNumber = "+48700800900", firstName = "Jan", lastName = "Nowak"))
        customerToken = jwtService.generateCustomerToken(customer)
    }

    @Test
    fun `GET api-employees should list only own company employees with user ID as id`() {
        mockMvc
            .get("/api/employees") {
                header("Authorization", "Bearer $employeeToken")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[?(@.firstName == 'Owner')].id") { value(ownerUserId.toInt()) }
                jsonPath("$[?(@.firstName == 'Owner')].userId") { value(ownerUserId.toInt()) }
                jsonPath("$[?(@.firstName == 'Outsider')]") { isEmpty() }
            }
    }

    @Test
    fun `GET api-employees should return 403 for customer`() {
        mockMvc
            .get("/api/employees") {
                header("Authorization", "Bearer $customerToken")
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `GET api-employees should return 401 without token`() {
        mockMvc.get("/api/employees").andExpect {
            status { isUnauthorized() }
        }
    }
}
