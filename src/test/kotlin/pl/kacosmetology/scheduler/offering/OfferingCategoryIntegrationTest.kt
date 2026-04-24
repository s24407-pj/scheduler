package pl.kacosmetology.scheduler.offering

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
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OfferingCategoryIntegrationTest {

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
    private lateinit var categoryRepository: OfferingCategoryRepository
    @Autowired
    private lateinit var offeringRepository: OfferingRepository
    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private var companyId: Long = 0
    private var offeringId: Long = 0
    private lateinit var ownerToken: String
    private lateinit var employeeToken: String

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        offeringRepository.deleteAll()
        categoryRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Kategorii"))
        companyId = company.id!!

        val owner = userRepository.save(User(phoneNumber = "+48500500500", firstName = "Owner", lastName = "Cat"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER"))
        ownerToken = jwtService.generateToken(
            CustomUserDetails(owner, companyId, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            companyId
        )

        val employee = userRepository.save(User(phoneNumber = "+48600600600", firstName = "Emp", lastName = "Cat"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))
        employeeToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )

        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Farbowanie", durationMinutes = 60, price = 150)
        )
        offeringId = offering.id!!
    }

    @Test
    fun `POST api-offering-categories should return 201 for owner`() {
        val body = mapOf("name" to "Koloryzacja")

        mockMvc.post("/api/offering-categories") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.name") { value("Koloryzacja") }
        }
    }

    @Test
    fun `POST api-offering-categories should return 409 for duplicate name`() {
        categoryRepository.save(OfferingCategory(companyId = companyId, name = "Koloryzacja"))

        val body = mapOf("name" to "Koloryzacja")

        mockMvc.post("/api/offering-categories") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `POST api-offering-categories should return 403 for employee`() {
        val body = mapOf("name" to "Strzyżenie")

        mockMvc.post("/api/offering-categories") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `GET api-offering-categories should return list for employee`() {
        categoryRepository.save(OfferingCategory(companyId = companyId, name = "Kategoria A"))
        categoryRepository.save(OfferingCategory(companyId = companyId, name = "Kategoria B"))

        mockMvc.get("/api/offering-categories") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    fun `DELETE api-offering-categories should return 204 for owner`() {
        val category = categoryRepository.save(OfferingCategory(companyId = companyId, name = "Do Usunięcia"))

        mockMvc.delete("/api/offering-categories/${category.id}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `DELETE api-offering-categories should evict offering cache so public endpoint reflects null categoryId`() {
        val category = categoryRepository.save(OfferingCategory(companyId = companyId, name = "Do Usunięcia"))
        offeringRepository.save(
            Offering(
                id = offeringId,
                companyId = companyId,
                name = "Farbowanie",
                durationMinutes = 60,
                price = 150,
                categoryId = category.id
            )
        )

        // Populate the cache
        mockMvc.get("/api/offerings/public/company/$companyId").andExpect {
            status { isOk() }
            jsonPath("$[0].categoryId") { value(category.id) }
        }

        // Delete the category
        mockMvc.delete("/api/offering-categories/${category.id}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect { status { isNoContent() } }

        // Cache must be evicted — public endpoint must return null categoryId
        mockMvc.get("/api/offerings/public/company/$companyId").andExpect {
            status { isOk() }
            jsonPath("$[0].categoryId") { value(null) }
        }
    }

    @Test
    fun `PATCH api-offerings-category should evict offering cache so public endpoint reflects new categoryId`() {
        val category = categoryRepository.save(OfferingCategory(companyId = companyId, name = "Koloryzacja"))

        // Populate the cache (no category assigned yet)
        mockMvc.get("/api/offerings/public/company/$companyId").andExpect {
            status { isOk() }
            jsonPath("$[0].categoryId") { value(null) }
        }

        // Assign category
        mockMvc.patch("/api/offerings/$offeringId/category") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("categoryId" to category.id))
        }.andExpect { status { isNoContent() } }

        // Cache must be evicted — public endpoint must return updated categoryId
        mockMvc.get("/api/offerings/public/company/$companyId").andExpect {
            status { isOk() }
            jsonPath("$[0].categoryId") { value(category.id) }
        }
    }

    @Test
    fun `PATCH api-offerings-category should assign category to offering`() {
        val category = categoryRepository.save(OfferingCategory(companyId = companyId, name = "Koloryzacja"))

        val body = mapOf("categoryId" to category.id)

        mockMvc.patch("/api/offerings/$offeringId/category") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isNoContent() }
        }

        val updated = offeringRepository.findById(offeringId).get()
        assertEquals(category.id, updated.categoryId)
    }

    @Test
    fun `PATCH api-offerings-category with null should clear category`() {
        val category = categoryRepository.save(OfferingCategory(companyId = companyId, name = "Koloryzacja"))
        offeringRepository.save(
            Offering(
                id = offeringId,
                companyId = companyId,
                name = "Farbowanie",
                durationMinutes = 60,
                price = 150,
                categoryId = category.id
            )
        )

        val body = mapOf("categoryId" to null)

        mockMvc.patch("/api/offerings/$offeringId/category") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isNoContent() }
        }

        val updated = offeringRepository.findById(offeringId).get()
        assertNull(updated.categoryId)
    }
}
