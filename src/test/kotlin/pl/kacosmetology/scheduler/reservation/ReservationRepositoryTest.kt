package pl.kacosmetology.scheduler.reservation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime

@DataJpaTest
// Wyłączamy zastępowanie bazy na H2, żeby wymusić użycie PostgreSQL z Testcontainers!
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class ReservationRepositoryTest {

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var serviceRepository: OfferingRepository

    private var employeeId: Long = 0
    private val baseTime: LocalDateTime = LocalDateTime.of(2024, 5, 20, 12, 0) // 12:00

    @BeforeEach
    fun setup() {
        // Musimy stworzyć pełne środowisko dla bazy (ze względu na klucze obce)
        val company = companyRepository.save(Company(name = "Salon Testowy"))

        val employee = userRepository.save(User(phoneNumber = "+48111", firstName = "Pracownik", lastName = "Testowy"))
        employeeId = employee.id

        val customer = userRepository.save(User(phoneNumber = "+48222", firstName = "Klient", lastName = "Testowy"))

        val service = serviceRepository.save(
            Offering(
                companyId = company.id!!, name = "Strzyżenie", durationMinutes = 60, price = 100
            )
        )

        // Tworzymy bazową rezerwację: 12:00 - 13:00
        reservationRepository.save(
            Reservation(
                companyId = company.id!!,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 100,
                startTime = baseTime, // 12:00
                endTime = baseTime.plusHours(1), // 13:00
                status = ReservationStatus.PENDING
            )
        )
    }

    @Test
    fun `should return true when new slot exactly overlaps with existing one`() {
        // Próbujemy zarezerwować od 12:00 do 13:00
        val isOverlapping = reservationRepository.existsOverlapping(employeeId, baseTime, baseTime.plusHours(1))
        assertTrue(isOverlapping, "Terminy idealnie się pokrywają")
    }

    @Test
    fun `should return true when new slot starts during existing one`() {
        // Próbujemy zarezerwować od 12:30 do 13:30
        val isOverlapping =
            reservationRepository.existsOverlapping(employeeId, baseTime.plusMinutes(30), baseTime.plusMinutes(90))
        assertTrue(isOverlapping, "Nowy termin nachodzi na koniec starego")
    }

    @Test
    fun `should return true when new slot ends during existing one`() {
        // Próbujemy zarezerwować od 11:30 do 12:30
        val isOverlapping =
            reservationRepository.existsOverlapping(employeeId, baseTime.minusMinutes(30), baseTime.plusMinutes(30))
        assertTrue(isOverlapping, "Nowy termin nachodzi na początek starego")
    }

    @Test
    fun `should return false when slot is right after existing one`() {
        // Próbujemy zarezerwować od 13:00 do 14:00
        val isOverlapping =
            reservationRepository.existsOverlapping(employeeId, baseTime.plusHours(1), baseTime.plusHours(2))
        assertFalse(isOverlapping, "Terminy stykają się, ale nie nakładają (dozwolone)")
    }

    @Test
    fun `should return false for cancelled reservation`() {
        // Najpierw zmieniamy status rezerwacji na CANCELLED
        val reservation = reservationRepository.findAll().first()
        reservation.status = ReservationStatus.CANCELLED
        reservationRepository.save(reservation)

        // Próbujemy zarezerwować ten sam termin: 12:00 - 13:00
        val isOverlapping = reservationRepository.existsOverlapping(employeeId, baseTime, baseTime.plusHours(1))

        assertFalse(isOverlapping, "Termin powinien być wolny, ponieważ poprzednia rezerwacja została anulowana")
    }

    @Test
    fun `findByEmployeeIdAndDate should return only active reservations for given day`() {
        // GIVEN
        val today = baseTime.toLocalDate()
        val startOfDay = today.atStartOfDay()
        val endOfDay = today.plusDays(1).atStartOfDay()

        // Tworzymy drugą rezerwację, ale ze statusem CANCELLED
        val cancelledReservation = reservationRepository.save(
            Reservation(
                companyId = reservationRepository.findAll().first().companyId,
                customerId = reservationRepository.findAll().first().customerId,
                employeeId = employeeId,
                serviceId = reservationRepository.findAll().first().serviceId,
                price = 100,
                startTime = baseTime.plusHours(2),
                endTime = baseTime.plusHours(3),
                status = ReservationStatus.CANCELLED // <--- Odwołana!
            )
        )

        // Tworzymy trzecią rezerwację na JUTRO
        val tomorrowReservation = reservationRepository.save(
            Reservation(
                companyId = reservationRepository.findAll().first().companyId,
                customerId = reservationRepository.findAll().first().customerId,
                employeeId = employeeId,
                serviceId = reservationRepository.findAll().first().serviceId,
                price = 100,
                startTime = baseTime.plusDays(1), // <--- Jutro!
                endTime = baseTime.plusDays(1).plusHours(1),
                status = ReservationStatus.PENDING
            )
        )

        // WHEN
        val reservations = reservationRepository.findByEmployeeIdAndDate(employeeId, startOfDay, endOfDay)

        // THEN
        assertEquals(1, reservations.size, "Powinna wrocic tylko jedna, aktywna, dzisiejsza rezerwacja")

        // Sprawdzamy czy to ta bazowa (utworzona w setup() z godz 12:00)
        assertEquals(baseTime, reservations.first().startTime)

        // Upewniamy się, że w liście NIE MA odwołanej
        assertFalse(
            reservations.any { it.id == cancelledReservation.id },
            "Odwołana rezerwacja nie powinna byc w wynikach"
        )

        // Upewniamy się, że w liście NIE MA jutrzejszej
        assertFalse(
            reservations.any { it.id == tomorrowReservation.id },
            "Jutrzejsza rezerwacja nie powinna byc w wynikach"
        )
    }

    @Test
    fun `findByEmployeeIdAndDate should sort results ascending by date`() {
        // GIVEN
        val today = baseTime.toLocalDate()
        val startOfDay = today.atStartOfDay()
        val endOfDay = today.plusDays(1).atStartOfDay()

        // Dodajemy rezerwację, która jest WCZEŚNIEJ niż bazowa (bazowa jest o 12:00, dajemy na 10:00)
        reservationRepository.save(
            Reservation(
                companyId = reservationRepository.findAll().first().companyId,
                customerId = reservationRepository.findAll().first().customerId,
                employeeId = employeeId,
                serviceId = reservationRepository.findAll().first().serviceId,
                price = 100,
                startTime = baseTime.minusHours(2), // 10:00
                endTime = baseTime.minusHours(1), // 11:00
                status = ReservationStatus.PENDING
            )
        )

        // WHEN
        val reservations = reservationRepository.findByEmployeeIdAndDate(employeeId, startOfDay, endOfDay)

        // THEN
        assertEquals(2, reservations.size)
        // Pierwsza na liście powinna być ta o 10:00
        assertEquals(baseTime.minusHours(2), reservations[0].startTime)
        // Druga na liście powinna być bazowa o 12:00
        assertEquals(baseTime, reservations[1].startTime)
    }
}