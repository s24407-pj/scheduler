package pl.kacosmetology.scheduler.reservation

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.treatment.ProvidedService
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime
import java.util.*

@ExtendWith(MockKExtension::class)
class ReservationServiceTest {

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @MockK
    private lateinit var serviceRepository: TreatmentRepository

    @MockK
    private lateinit var userRepository: UserRepository

    @InjectMockKs
    private lateinit var reservationService: ReservationService

    private val customerId = 100L
    private val employeeId = 200L
    private val serviceId = 300L
    private val companyId = 1L
    private val startTime = LocalDateTime.of(2024, 5, 20, 10, 0) // Jakaś losowa data

    @Test
    fun `should create reservation with price snapshot and calculated end time`() {
        // GIVEN
        val duration = 45
        val servicePrice = 150
        val mockService = ProvidedService(
            id = serviceId, companyId = companyId, name = "Strzyżenie",
            durationMinutes = duration, price = servicePrice
        )

        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        // Zakładamy, że termin jest wolny
        every {
            reservationRepository.existsOverlapping(
                employeeId,
                startTime,
                startTime.plusMinutes(duration.toLong())
            )
        } returns false

        // Mockujemy zapis (zwracamy to, co dostaliśmy)
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservation(customerId, employeeId, serviceId, startTime)

        // THEN
        assertNotNull(result)
        assertEquals(companyId, result.companyId)
        assertEquals(customerId, result.customerId)
        assertEquals(servicePrice, result.price, "Snapshot ceny powinien zgadzać się z ceną usługi!")
        assertEquals(
            startTime.plusMinutes(duration.toLong()),
            result.endTime,
            "Czas zakończenia powinien być poprawnie wyliczony"
        )
        assertEquals(ReservationStatus.PENDING, result.status)

        verify(exactly = 1) { reservationRepository.save(any()) }
    }

    @Test
    fun `should throw when time slot is already taken`() {
        // GIVEN
        val mockService = ProvidedService(
            id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50
        )

        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)

        // SYMULUJEMY ZAJĘTY TERMIN:
        every { reservationRepository.existsOverlapping(employeeId, any(), any()) } returns true

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Ten termin jest już zajęty", exception.message)

        // Upewniamy się, że repozytorium nigdy nie próbowało zapisać tej rezerwacji!
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `should throw when service does not exist`() {
        // GIVEN
        every { serviceRepository.findById(serviceId) } returns Optional.empty()

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Usługa nie istnieje", exception.message)
    }

    @Test
    fun `cancelReservation should change status to CANCELLED for reservation owner`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.cancelReservation(reservationId, customerId)

        // THEN
        assertEquals(ReservationStatus.CANCELLED, reservation.status)
        verify(exactly = 1) { reservationRepository.save(reservation) }
    }

    @Test
    fun `cancelReservation should throw when user tries to cancel someone else's reservation`() {
        // GIVEN
        val reservationId = 1L
        val hackerId = 999L // Inne ID klienta!
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.cancelReservation(reservationId, hackerId)
        }
        assertEquals("Nie możesz anulować nie swojej rezerwacji", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `completeReservation should change status to COMPLETED`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.completeReservation(reservationId)

        // THEN
        assertEquals(ReservationStatus.COMPLETED, reservation.status)
        verify(exactly = 1) { reservationRepository.save(reservation) }
    }

    @Test
    fun `completeReservation should throw for cancelled reservation`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.CANCELLED
        ) // Już anulowana!

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.completeReservation(reservationId)
        }
        assertEquals("Nie można zakończyć odwołanej wizyty", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    // ============================================================
    // Staff booking tests
    // ============================================================

    @Test
    fun `createReservationByStaff should create reservation for existing customer`() {
        // GIVEN
        val existingCustomer = User(id = customerId, phoneNumber = "+48111111111", firstName = "Ala", lastName = "Nowak")
        val duration = 30
        val mockService = ProvidedService(id = serviceId, companyId = companyId, name = "Paznokcie", durationMinutes = duration, price = 80)

        every { userRepository.findByPhoneNumber(existingCustomer.phoneNumber) } returns existingCustomer
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { reservationRepository.existsOverlapping(employeeId, startTime, startTime.plusMinutes(duration.toLong())) } returns false
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = startTime,
            customerPhone = existingCustomer.phoneNumber,
            customerFirstName = null,
            customerLastName = null
        )

        // THEN
        assertEquals(customerId, result.customerId)
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `createReservationByStaff should create new customer when phone is unknown`() {
        // GIVEN
        val newPhone = "+48999000111"
        val newCustomer = User(id = 500L, phoneNumber = newPhone, firstName = "Nowy", lastName = "Klient")
        val duration = 45
        val mockService = ProvidedService(id = serviceId, companyId = companyId, name = "Masaż", durationMinutes = duration, price = 120)

        every { userRepository.findByPhoneNumber(newPhone) } returns null
        every { userRepository.save(any()) } returns newCustomer
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { reservationRepository.existsOverlapping(employeeId, startTime, startTime.plusMinutes(duration.toLong())) } returns false
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = startTime,
            customerPhone = newPhone,
            customerFirstName = "Nowy",
            customerLastName = "Klient"
        )

        // THEN
        assertEquals(newCustomer.id, result.customerId)
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `createReservationByStaff should throw when new customer has no name provided`() {
        // GIVEN
        val unknownPhone = "+48000111222"
        every { userRepository.findByPhoneNumber(unknownPhone) } returns null

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservationByStaff(
                employeeId = employeeId,
                serviceId = serviceId,
                startTime = startTime,
                customerPhone = unknownPhone,
                customerFirstName = null,
                customerLastName = null
            )
        }
        assertEquals("Imię i nazwisko klienta są wymagane przy tworzeniu nowego konta", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }
}