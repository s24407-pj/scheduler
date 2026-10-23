package pl.kacosmetology.scheduler.employeeoffering

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.time.LocalDateTime
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.*
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingImage
import pl.kacosmetology.scheduler.offering.OfferingImageRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EmployeeOfferingIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var offeringRepository: OfferingRepository

    @Autowired private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @Autowired private lateinit var offeringImageRepository: OfferingImageRepository

    @MockkBean private lateinit var s3Client: S3Client

    private var companyId: UUID = testId(0)
    private var employeeId: UUID = testId(0)
    private var offeringId: UUID = testId(0)
    private lateinit var ownerSession: Cookie
    private lateinit var employeeSession: Cookie

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        assignmentRepository.deleteAll()
        offeringImageRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Usługowy"))
        companyId = company.id!!

        val owner = userRepository.save(User(phoneNumber = "+48100100100", firstName = "Owner", lastName = "Test"))
        val ownerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER"),
            )
        ownerSession = testSessions.staff(owner, ownerEmployment)

        val employee = userRepository.save(User(phoneNumber = "+48200200200", firstName = "Emp", lastName = "Test"))
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE"),
            )
        employeeId = employee.id!!
        employeeSession = testSessions.staff(employee, employeeEmployment)

        val offering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 60),
            )
        offeringId = offering.id!!
    }

    @Test
    fun `POST assign offering should return 201`() {
        mockMvc
            .post("/api/employees/$employeeId/offerings/$offeringId") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.offeringId") { value(offeringId.toString()) }
                jsonPath("$.employeeId") { value(employeeId.toString()) }
            }

        assertEquals(1, assignmentRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `POST assign offering should be idempotent`() {
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId,
            ),
        )

        mockMvc
            .post("/api/employees/$employeeId/offerings/$offeringId") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isCreated() }
            }

        assertEquals(1, assignmentRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `DELETE remove assignment should return 204`() {
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId,
            ),
        )

        mockMvc
            .delete("/api/employees/$employeeId/offerings/$offeringId") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNoContent() }
            }

        assertEquals(0, assignmentRepository.findAllByEmployeeId(employeeId).size)
    }

    @Test
    fun `DELETE remove assignment should return 404 when assignment does not exist`() {
        mockMvc
            .delete("/api/employees/$employeeId/offerings/$offeringId") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET assignments should return assignments for employee in owner company`() {
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId,
            ),
        )

        mockMvc
            .get("/api/employees/$employeeId/offerings") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].offeringId") { value(offeringId.toString()) }
                jsonPath("$[0].employeeId") { value(employeeId.toString()) }
            }
    }

    @Test
    fun `GET assignments should return 403 for customer`() {
        val customer =
            userRepository.save(User(phoneNumber = "+48300300300", firstName = "Customer", lastName = "Test"))
        val customerSession = testSessions.customer(customer)

        mockMvc
            .get("/api/employees/$employeeId/offerings") {
                cookie(customerSession)
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `GET assignments should return 404 for employee outside owner company`() {
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))
        val otherEmployee =
            userRepository.save(User(phoneNumber = "+48400400400", firstName = "Other", lastName = "Employee"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = otherEmployee.id!!, role = "EMPLOYEE"),
        )

        mockMvc
            .get("/api/employees/${otherEmployee.id!!}/offerings") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET public employee offerings should return only assigned active offerings with images`() {
        companyEmployeeRepository.save(
            companyEmployeeRepository.findByCompanyIdAndUserId(companyId, employeeId)!!.apply {
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES
            },
        )
        offeringRepository.save(
            Offering(companyId = companyId, name = "Koloryzacja", durationMinutes = 60, price = 150),
        )
        val inactive =
            offeringRepository.save(
                Offering(
                    companyId = companyId,
                    name = "Stara usługa",
                    durationMinutes = 30,
                    price = 50,
                    active = false,
                ),
            )
        assignmentRepository.save(
            EmployeeOfferingAssignment(companyId = companyId, employeeId = employeeId, offeringId = offeringId),
        )
        assignmentRepository.save(
            EmployeeOfferingAssignment(companyId = companyId, employeeId = employeeId, offeringId = inactive.id!!),
        )
        offeringImageRepository.save(OfferingImage(offeringId = offeringId, imageUrl = "https://cdn.test/cut.jpg"))

        mockMvc.get("/api/offerings/public/company/$companyId/employee/$employeeId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(offeringId.toString()) }
            jsonPath("$[0].images[0].imageUrl") { value("https://cdn.test/cut.jpg") }
        }
    }

    @Test
    fun `GET public employee offerings should return every active company offering when employee has no assignments`() {
        offeringRepository.save(
            Offering(companyId = companyId, name = "Koloryzacja", durationMinutes = 60, price = 150),
        )

        mockMvc.get("/api/offerings/public/company/$companyId/employee/$employeeId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    fun `GET public employee offerings should return 404 when employee is not employed at the company`() {
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))

        mockMvc.get("/api/offerings/public/company/${otherCompany.id!!}/employee/$employeeId").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `GET public employee offerings should be scoped to the requested salon for multi-salon employee`() {
        val otherCompany = companyRepository.save(Company(name = "Drugi Salon"))
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = otherCompany.id!!,
                userId = employeeId,
                role = "EMPLOYEE",
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES,
            ),
        )
        val otherOffering =
            offeringRepository.save(
                Offering(companyId = otherCompany.id!!, name = "Masaż", durationMinutes = 60, price = 200),
            )
        offeringRepository.save(
            Offering(companyId = otherCompany.id!!, name = "Peeling", durationMinutes = 30, price = 90),
        )
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = otherCompany.id!!,
                employeeId = employeeId,
                offeringId = otherOffering.id!!,
            ),
        )

        mockMvc.get("/api/offerings/public/company/$companyId/employee/$employeeId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(offeringId.toString()) }
        }
        mockMvc.get("/api/offerings/public/company/${otherCompany.id!!}/employee/$employeeId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(otherOffering.id!!.toString()) }
        }
    }

    @Test
    fun `POST reservation should return 409 when employee has assignments but not for requested offering`() {
        companyEmployeeRepository.save(
            companyEmployeeRepository.findByCompanyIdAndUserId(companyId, employeeId)!!.apply {
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES
            },
        )
        val otherOffering =
            offeringRepository.save(
                Offering(companyId = companyId, name = "Koloryzacja", durationMinutes = 60, price = 150),
            )
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = offeringId,
            ),
        )

        val customer = userRepository.save(User(phoneNumber = "+48999888777", firstName = "Klient", lastName = "Test"))

        val body =
            mapOf(
                "employeeId" to employeeId,
                "serviceId" to otherOffering.id!!,
                "startTime" to
                    LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0).toString(),
                "customerPhone" to customer.phoneNumber,
                "customerFirstName" to "Klient",
                "customerLastName" to "Test",
            )

        mockMvc
            .post("/api/reservations/staff") {
                with(csrf())
                header("Idempotency-Key", "unqualified-staff-booking")
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isConflict() }
            }
    }

    @Test
    fun `foreign offering assignment deletion cannot delete multi-salon employees assignment`() {
        val otherCompany = companyRepository.save(Company(name = "Inna firma"))
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = otherCompany.id!!,
                userId = employeeId,
                role = "EMPLOYEE",
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES,
            ),
        )
        val foreignOffering =
            offeringRepository.save(
                Offering(companyId = otherCompany.id!!, name = "Inna usługa", durationMinutes = 30, price = 100),
            )
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = otherCompany.id!!,
                employeeId = employeeId,
                offeringId = foreignOffering.id!!,
            ),
        )
        mockMvc
            .delete("/api/employees/$employeeId/offerings/${foreignOffering.id!!}") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect { status { isNotFound() } }
        assertEquals(1, assignmentRepository.findAllByCompanyIdAndEmployeeId(otherCompany.id!!, employeeId).size)
    }

    @Test
    fun `strict integral JSON rejects fractional price duration and identifier`() {
        listOf(
                """{"name":"Test","durationMinutes":30,"price":100.9}""",
                """{"name":"Test","durationMinutes":30.9,"price":100}""",
                """{"name":"Test","durationMinutes":30,"price":100,"unknown":true}""",
            )
            .forEach { payload ->
                mockMvc
                    .post("/api/offerings") {
                        with(csrf())
                        cookie(ownerSession)
                        contentType = MediaType.APPLICATION_JSON
                        content = payload
                    }
                    .andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("MALFORMED_REQUEST") }
                    }
            }
        mockMvc
            .post("/api/reservations/staff") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"employeeId":$employeeId.5,"serviceId":"$offeringId","startTime":"2030-01-01T10:00:00","customerPhone":"+48123456789"}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("MALFORMED_REQUEST") }
            }
    }

    @Test
    fun `appointment body and query reject Z and numeric offsets`() {
        listOf("Z", "+01:00", "+02:00").forEach { offset ->
            mockMvc
                .post("/api/reservations/staff") {
                    with(csrf())
                    cookie(ownerSession)
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"employeeId":"$employeeId","serviceId":"$offeringId","startTime":"2030-01-01T10:00:00$offset","customerPhone":"+48123456789"}"""
                }
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("MALFORMED_REQUEST") }
                }
            mockMvc
                .get("/api/reservations/employee") {
                    cookie(employeeSession)
                    param("start", "2030-01-01T10:00:00$offset")
                    param("end", "2030-01-02T10:00:00")
                }
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_PARAMETER") }
                }
        }
    }

    @Test
    fun `staff session cannot use customer reservation endpoints`() {
        mockMvc.get("/api/reservations/me") { cookie(employeeSession) }.andExpect { status { isForbidden() } }
        mockMvc
            .post("/api/reservations") {
                with(csrf())
                header("Idempotency-Key", "staff-customer-realm")
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"employeeId":"$employeeId","serviceId":"$offeringId","startTime":"2030-01-01T10:00:00"}"""
            }
            .andExpect { status { isForbidden() } }
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/reservations/${testId(1)}/cancel",
                    )
                    .with(csrf())
                    .header("If-Match", "0")
                    .cookie(employeeSession),
            )
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden)
    }

    @Test
    fun `diagnostics remain private even for company owners`() {
        listOf("/actuator/metrics", "/actuator/prometheus", "/actuator/info", "/actuator/health/readiness").forEach {
            route ->
            mockMvc.get(route) { cookie(ownerSession) }.andExpect { status { isForbidden() } }
        }
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.components") { doesNotExist() }
            jsonPath("$.details") { doesNotExist() }
        }
    }

    @Test
    fun `public category route exposes only requested salon categories`() {
        val category =
            mockMvc
                .post("/api/offering-categories") {
                    with(csrf())
                    cookie(ownerSession)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Pielęgnacja"}"""
                }
                .andExpect { status { isCreated() } }
                .andReturn()
        val categoryId = UUID.fromString(objectMapper.readTree(category.response.contentAsString)["id"].asString())
        val foreignCompany = companyRepository.save(Company(name = "Inny salon"))
        mockMvc.get("/api/offering-categories/public/company/$companyId").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(categoryId.toString()) }
            jsonPath("$[0].name") { value("Pielęgnacja") }
        }
        mockMvc.get("/api/offering-categories/public/company/${foreignCompany.id!!}").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `explicit specific empty mode exposes no services and disabled employee disappears`() {
        mockMvc
            .patch("/api/employees/$employeeId/offering-eligibility") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"mode":"SPECIFIC_SERVICES"}"""
            }
            .andExpect { status { isNoContent() } }
        mockMvc.get("/api/offerings/public/company/$companyId/employee/$employeeId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
        mockMvc
            .get("/api/employees/public/company/$companyId") { param("offeringId", offeringId.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$[?(@.id == '$employeeId')]") { doesNotExist() }
            }
        mockMvc
            .patch("/api/employees/$employeeId/offering-eligibility") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"mode":"ALL_SERVICES"}"""
            }
            .andExpect { status { isForbidden() } }
        val account = companyEmployeeRepository.findByCompanyIdAndUserId(companyId, employeeId)!!
        account.enabled = false
        companyEmployeeRepository.save(account)
        mockMvc.get("/api/offerings/public/company/$companyId/employee/$employeeId").andExpect {
            status { isNotFound() }
        }
    }
}
