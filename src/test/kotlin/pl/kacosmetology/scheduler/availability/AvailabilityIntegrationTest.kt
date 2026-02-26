package pl.kacosmetology.scheduler.availability

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
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.treatment.ProvidedService
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AvailabilityIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired
    private lateinit var serviceRepository: TreatmentRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    private var employeeId: Long = 0
    private var serviceId: Long = 0
    private val testDate = LocalDate.now().plusDays(2) // Za dwa dni, by uniknąć filtrów przeszłości

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Włosów"))
        val employee =
            userRepository.save(User(phoneNumber = "+48111222333", firstName = "Fryzjer", lastName = "Testowy"))
        val customer =
            userRepository.save(User(phoneNumber = "+48999888777", firstName = "Klient", lastName = "Testowy"))
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = company.id!!,
                userId = employee.id,
                role = "EMPLOYEE"
            )
        )

        val service = serviceRepository.save(
            ProvidedService(
                companyId = company.id!!,
                name = "Farbowanie",
                durationMinutes = 120,
                price = 250 // Usługa długa, 2 godziny!
            )
        )

        employeeId = employee.id
        serviceId = service.id!!

        // Wstawiamy rezerwację od 10:00 do 12:00
        reservationRepository.save(
            Reservation(
                companyId = company.id!!,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 250,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(12, 0),
                status = ReservationStatus.CONFIRMED
            )
        )
    }

    @Test
    fun `GET api-availability should return calendar gaps without authorization (200)`() {
        // Zauważ, że nie dodajemy nagłówka Authorization!
        mockMvc.get("/api/availability") {
            param("employeeId", employeeId.toString())
            param("serviceId", serviceId.toString())
            param("date", testDate.toString()) // format YYYY-MM-DD
        }.andExpect {
            status { isOk() }
            // Oczekujemy tablicy JSON z godzinami
            jsonPath("$") { isArray() }

            // Usługa trwa 2 godziny.
            // Przed rezerwacją z 10:00 zmieści się tylko o 9:00 (zakończy o 11:00 -> NACHODZI!)
            // Więc przed 12:00 nie będzie żadnego wolnego terminu dla usługi 2-godzinnej (bo salon otwiera o 9:00).
            jsonPath("$[?(@ == '09:00:00')]") { doesNotExist() }
            jsonPath("$[?(@ == '10:00:00')]") { doesNotExist() }

            // Od 12:00 zaczyna się pasmo wolnego czasu (12:00-14:00 to 2h, OK)
            jsonPath("$[?(@ == '12:00:00')]") { exists() }
            jsonPath("$[?(@ == '12:30:00')]") { exists() }
            jsonPath("$[?(@ == '15:00:00')]") { exists() } // 15:00-17:00, ostatni możliwy!

            // Usługa wzięta o 15:30 skończyłaby się o 17:30 (poza godzinami pracy), więc nie ma jej na liście
            jsonPath("$[?(@ == '15:30:00')]") { doesNotExist() }
        }
    }
}