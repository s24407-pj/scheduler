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
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignment
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PublicBookingIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var offeringRepository: OfferingRepository

    @Autowired private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @MockkBean private lateinit var s3Client: S3Client

    private var companyId: Long = 0
    private var employeeId: Long = 0
    private var offeringId: Long = 0

    @BeforeEach
    fun setup() {
        assignmentRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company =
            companyRepository.save(
                Company(
                    name = "Salon Test",
                    lastMinuteDiscountPercent = 10,
                    lastMinuteDiscountHours = 24,
                ),
            )
        companyId = company.id!!

        val employee =
            userRepository.save(
                User(
                    phoneNumber = "+48999000111",
                    firstName = "Anna",
                    lastName = "Kowalska",
                    photoUrl = "https://example.com/a.jpg",
                ),
            )
        employeeId = employee.id!!

        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = companyId,
                userId = employeeId,
                role = "EMPLOYEE",
                firstName = "Anna",
                lastName = "Kowalska",
                photoUrl = "https://example.com/a.jpg",
                eligibilityMode = EmployeeEligibilityMode.ALL_SERVICES,
            ),
        )

        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Manicure", durationMinutes = 60, price = 120),
            )
        offeringId = offering.id!!
    }

    @Test
    fun `GET public company profile should return salon info without auth`() {
        mockMvc.get("/api/company/public/$companyId").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(companyId) }
            jsonPath("$.name") { value("Salon Test") }
            jsonPath("$.lastMinuteDiscountPercent") { value(10) }
            jsonPath("$.lastMinuteDiscountHours") { value(24) }
            jsonPath("$.maxNoShows") { doesNotExist() }
        }
    }

    @Test
    fun `GET public company profile should return 404 for unknown company`() {
        mockMvc.get("/api/company/public/99999").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `GET public employees returns all offerings only for ALL_SERVICES mode`() {
        mockMvc
            .get("/api/employees/public/company/$companyId") {
                param("offeringId", offeringId.toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].id") { value(employeeId) }
                jsonPath("$[0].firstName") { value("Anna") }
                jsonPath("$[0].photoUrl") { value("https://example.com/a.jpg") }
            }
    }

    @Test
    fun `GET public employees should filter by offering assignment`() {
        companyEmployeeRepository.save(
            companyEmployeeRepository.findByCompanyIdAndUserId(companyId, employeeId)!!.apply {
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES
            },
        )
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId,
            ),
        )

        val otherOffering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Pedicure", durationMinutes = 45, price = 90),
            )

        mockMvc
            .get("/api/employees/public/company/$companyId") {
                param("offeringId", offeringId.toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
            }

        mockMvc
            .get("/api/employees/public/company/$companyId") {
                param("offeringId", otherOffering.id!!.toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    @Test
    fun `GET public employees should return 404 when offering belongs to another company`() {
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))
        val otherOffering =
            offeringRepository.save(
                Offering(companyId = otherCompany.id!!, name = "Inna usługa", durationMinutes = 30, price = 50),
            )

        mockMvc
            .get("/api/employees/public/company/$companyId") {
                param("offeringId", otherOffering.id!!.toString())
            }
            .andExpect {
                status { isNotFound() }
            }
    }
}
