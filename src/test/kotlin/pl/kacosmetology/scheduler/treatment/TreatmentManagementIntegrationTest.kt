package pl.kacosmetology.scheduler.treatment

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import software.amazon.awssdk.services.s3.S3Client
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.treatment.dto.TreatmentRequest
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TreatmentManagementIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var serviceRepository: TreatmentRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var userA: User
    private lateinit var userB: User

    // Zmieniamy na 'var', żeby nadpisać je IDkami wygenerowanymi przez bazę
    private var companyA_Id: Long = 0
    private var companyB_Id: Long = 0

    @BeforeEach
    fun setup() {
        // Czyścimy wszystko przed startem
        reservationRepository.deleteAll() // <--- DODAJ TĘ LINIJKĘ NA SAMEJ GÓRZE!
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        // 1. TWORZYMY FIRMY W BAZIE!
        val companyA = companyRepository.save(Company(name = "Salon A"))
        val companyB = companyRepository.save(Company(name = "Salon B"))

        companyA_Id = companyA.id!!
        companyB_Id = companyB.id!!

        // 2. Tworzymy użytkowników
        userA = userRepository.save(User(phoneNumber = "+48111222333", firstName = "Admin", lastName = "FirmaA"))
        userB = userRepository.save(User(phoneNumber = "+48999888777", firstName = "Admin", lastName = "FirmaB"))

        // 3. NADAJEMY IM ROLE (Tego brakowało!)
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyA_Id, userId = userA.id, role = "OWNER"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyB_Id, userId = userB.id, role = "OWNER"))
    }

    @Test
    fun `should reject service creation request without JWT token (403)`() {
        val request = TreatmentRequest(name = "Masaż", durationMinutes = 60, price = 200)

        mockMvc.post("/api/services") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            // Spring Security odrzuci request zanim w ogóle dotrze on do ProvidedServiceController
            status { isForbidden() }
        }
    }

    @Test
    fun `should create service and assign to company from token (201)`() {
        // GIVEN - Generujemy poprawny token dla usera z Firmy A (korzystając z PRAWDZIWEGO ID firmy z bazy)
        val userDetailsA = CustomUserDetails(userA, companyA_Id, listOf(SimpleGrantedAuthority("ROLE_OWNER")))
        val tokenA = jwtService.generateToken(userDetailsA, companyA_Id)
        val request = TreatmentRequest(name = "Masaż", durationMinutes = 60, price = 200)

        // WHEN & THEN
        mockMvc.post("/api/services") {
            header("Authorization", "Bearer $tokenA")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Masaż") }
            jsonPath("$.companyId") { value(companyA_Id) } // Udowadniamy, że wyciągnięto ID z JWT!
        }
    }

    @Test
    fun `employee of Company B cannot delete service belonging to Company A (403)`() {
        // GIVEN
        // 1. Zapisujemy w bazie usługę należącą do firmy A (teraz z prawdziwym ID z bazy)
        val serviceOfCompanyA = serviceRepository.save(
            ProvidedService(companyId = companyA_Id, name = "Złota Usługa", durationMinutes = 30, price = 500)
        )

        // 2. Wchodzi pracownik z Firmy B z poprawnym JWT (ale dla innej firmy!)

        val userDetailsB = CustomUserDetails(userB, companyB_Id, listOf(SimpleGrantedAuthority("ROLE_OWNER")))
        val tokenB = jwtService.generateToken(userDetailsB, companyB_Id)

        // WHEN & THEN
        mockMvc.delete("/api/services/${serviceOfCompanyA.id}") {
            header("Authorization", "Bearer $tokenB")
        }.andExpect {
            // Zostanie odrzucony przez wywołanie 'requireCompanyId' i if(existingService.companyId != companyIdFromToken)
            status { isForbidden() }
        }

        // Upewniamy się, że haker nie zdołał usunąć usługi z bazy
        val isStillInDb = serviceRepository.existsById(serviceOfCompanyA.id!!)
        assertTrue(isStillInDb, "Usługa powinna zostać nietknięta w bazie danych!")
    }

    @Test
    fun `should return only active services on public endpoint (200)`() {
        serviceRepository.save(ProvidedService(companyId = companyA_Id, name = "Aktywna", durationMinutes = 30, price = 100))
        serviceRepository.save(ProvidedService(companyId = companyA_Id, name = "Nieaktywna", durationMinutes = 30, price = 100, active = false))

        mockMvc.get("/api/services/public/company/$companyA_Id")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].name") { value("Aktywna") }
            }
    }

    @Test
    fun `should return service by id for authenticated user of the same company (200)`() {
        val service = serviceRepository.save(
            ProvidedService(companyId = companyA_Id, name = "Masaż", durationMinutes = 60, price = 200)
        )
        val tokenA = jwtService.generateToken(
            CustomUserDetails(userA, companyA_Id, listOf(SimpleGrantedAuthority("ROLE_OWNER"))), companyA_Id
        )

        mockMvc.get("/api/services/${service.id}") {
            header("Authorization", "Bearer $tokenA")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(service.id) }
            jsonPath("$.name") { value("Masaż") }
        }
    }

    @Test
    fun `should activate inactive service (204)`() {
        val inactive = serviceRepository.save(
            ProvidedService(companyId = companyA_Id, name = "Stara", durationMinutes = 30, price = 50, active = false)
        )
        val tokenA = jwtService.generateToken(
            CustomUserDetails(userA, companyA_Id, listOf(SimpleGrantedAuthority("ROLE_OWNER"))), companyA_Id
        )

        mockMvc.patch("/api/services/${inactive.id}/activate") {
            header("Authorization", "Bearer $tokenA")
        }.andExpect {
            status { isNoContent() }
        }

        val reactivated = serviceRepository.findById(inactive.id!!).get()
        assertTrue(reactivated.active, "Usługa powinna być aktywna po aktywacji")
    }

    @Test
    fun `employee of Company B cannot activate service belonging to Company A (403)`() {
        val inactive = serviceRepository.save(
            ProvidedService(companyId = companyA_Id, name = "Stara", durationMinutes = 30, price = 50, active = false)
        )
        val tokenB = jwtService.generateToken(
            CustomUserDetails(userB, companyB_Id, listOf(SimpleGrantedAuthority("ROLE_OWNER"))), companyB_Id
        )

        mockMvc.patch("/api/services/${inactive.id}/activate") {
            header("Authorization", "Bearer $tokenB")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `should update service when user is employee of the correct company (200)`() {
        // GIVEN - Mamy usługę w bazie (Salon A)
        val initialService = serviceRepository.save(
            ProvidedService(companyId = companyA_Id, name = "Stara Nazwa", durationMinutes = 30, price = 100)
        )

        // User A ma rolę OWNER w Salonie A, więc ma prawo edytować
        val userDetailsA = CustomUserDetails(userA, companyA_Id, listOf(SimpleGrantedAuthority("ROLE_OWNER")))
        val tokenA = jwtService.generateToken(userDetailsA, companyA_Id)

        // Zmieniamy cenę z 100 na 250 i czas na 45 minut
        val updateRequest = TreatmentRequest(name = "Nowa Nazwa", durationMinutes = 45, price = 250)

        // WHEN & THEN
        mockMvc.put("/api/services/${initialService.id}") {
            header("Authorization", "Bearer $tokenA")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(updateRequest)
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Nowa Nazwa") }
            jsonPath("$.price") { value(250) }
            jsonPath("$.durationMinutes") { value(45) }
        }

        // Upewniamy się, że zmiany zapisały się w bazie
        val updatedServiceFromDb = serviceRepository.findById(initialService.id!!).get()
        assertEquals(250, updatedServiceFromDb.price)
    }
}