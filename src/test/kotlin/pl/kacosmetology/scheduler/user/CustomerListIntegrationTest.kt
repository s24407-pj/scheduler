package pl.kacosmetology.scheduler.user

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
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.security.JwtService
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerListIntegrationTest {

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
    @Autowired
    private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository
    @Autowired
    private lateinit var companyCustomerRepository: CompanyCustomerRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var owner: User
    private lateinit var employee: User
    private lateinit var customer: User
    private var companyId: Long = 0
    private var offeringId: Long = 0
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String
    private lateinit var customerToken: String

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

        owner = userRepository.save(User(phoneNumber = "+48811111001", firstName = "Owner", lastName = "List"))
        val ownerEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER")
        )
        ownerToken = jwtService.generateStaffToken(owner, ownerEmployment)

        employee = userRepository.save(User(phoneNumber = "+48811222001", firstName = "Employee", lastName = "List"))
        val employeeEmployment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE")
        )
        employeeToken = jwtService.generateStaffToken(employee, employeeEmployment)

        customer = userRepository.save(User(phoneNumber = "+48811333001", firstName = "Anna", lastName = "Kowalska"))
        customerToken = jwtService.generateCustomerToken(customer)

        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Usługa", durationMinutes = 30, price = 100)
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
                status = ReservationStatus.COMPLETED
            )
        )
    }

    @Test
    fun `GET customers as owner should return customer list with phone number`() {
        mockMvc.get("/api/customers") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(customer.id!!) }
            jsonPath("$[0].firstName") { value("Anna") }
            jsonPath("$[0].lastName") { value("Kowalska") }
            jsonPath("$[0].phoneNumber") { value("+48811333001") }
            jsonPath("$[0].blocked") { value(false) }
            jsonPath("$[0].noShowCount") { value(0) }
        }
    }

    @Test
    fun `GET customers as employee should return customer list`() {
        mockMvc.get("/api/customers") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
        }
    }

    @Test
    fun `GET customers includes block status when customer is blocked`() {
        companyCustomerBlockRepository.save(
            CompanyCustomerBlock(companyId = companyId, customerId = customer.id!!, noShowCount = 2, blocked = true)
        )

        mockMvc.get("/api/customers") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].blocked") { value(true) }
            jsonPath("$[0].noShowCount") { value(2) }
        }
    }

    @Test
    fun `GET customers includes notes when set`() {
        companyCustomerRepository.save(
            CompanyCustomer(companyId = companyId, userId = customer.id!!, notes = "VIP klientka")
        )

        mockMvc.get("/api/customers") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].notes") { value("VIP klientka") }
        }
    }

    @Test
    fun `GET customers returns empty list when no reservations exist`() {
        reservationRepository.deleteAll()

        mockMvc.get("/api/customers") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `GET customers as unauthenticated user should return 403`() {
        mockMvc.get("/api/customers").andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `GET customers as customer role should return 403`() {
        mockMvc.get("/api/customers") {
            header("Authorization", "Bearer $customerToken")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `GET customers does not include customers from other companies`() {
        val otherCompany = companyRepository.save(Company(name = "Other Salon"))
        val otherCustomer = userRepository.save(
            User(phoneNumber = "+48811444001", firstName = "Obcy", lastName = "Klient")
        )
        val otherOffering = offeringRepository.save(
            Offering(companyId = otherCompany.id!!, name = "Usługa", durationMinutes = 30, price = 100)
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
                status = ReservationStatus.COMPLETED
            )
        )

        mockMvc.get("/api/customers") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(customer.id!!) }
        }
    }
}
