package pl.kacosmetology.scheduler.reservation

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.notification.NotificationService
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime
import java.util.*

@ExtendWith(MockKExtension::class)
class ReservationServiceTest {

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @MockK
    private lateinit var serviceRepository: OfferingRepository

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @MockK(relaxed = true)
    private lateinit var notificationService: NotificationService

    @InjectMockKs
    private lateinit var reservationService: ReservationService

    private val customerId = 100L
    private val employeeId = 200L
    private val serviceId = 300L
    private val companyId = 1L
    private val startTime = LocalDateTime.of(2024, 5, 20, 10, 0) // Jakaś losowa data

    private val mockCustomer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski")

    @Test
    fun `should create reservation with price snapshot and calculated end time`() {
        // GIVEN
        val duration = 45
        val servicePrice = 150
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie",
            durationMinutes = duration, price = servicePrice
        )

        every { userRepository.findById(customerId) } returns Optional.of(mockCustomer)
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
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
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50
        )

        every { userRepository.findById(customerId) } returns Optional.of(mockCustomer)
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false

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
        every { userRepository.findById(customerId) } returns Optional.of(mockCustomer)
        every { serviceRepository.findById(serviceId) } returns Optional.empty()

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Usługa nie istnieje", exception.message)
    }

    @Test
    fun `should throw when employee has service assignments but not for this service`() {
        // GIVEN
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50
        )
        every { userRepository.findById(customerId) } returns Optional.of(mockCustomer)
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns true
        every { assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, serviceId) } returns false

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Ten pracownik nie wykonuje wybranej usługi", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
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
        val mockService = Offering(id = serviceId, companyId = companyId, name = "Paznokcie", durationMinutes = duration, price = 80)

        every { userRepository.findByPhoneNumber(existingCustomer.phoneNumber) } returns existingCustomer
        every { userRepository.findById(customerId) } returns Optional.of(existingCustomer)
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
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
        val mockService = Offering(id = serviceId, companyId = companyId, name = "Masaż", durationMinutes = duration, price = 120)

        every { userRepository.findByPhoneNumber(newPhone) } returns null
        every { userRepository.save(any()) } returns newCustomer
        every { userRepository.findById(500L) } returns Optional.of(newCustomer)
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
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

    // ============================================================
    // markNoShow tests
    // ============================================================

    @Test
    fun `markNoShow should set status to NO_SHOW and increment customer no-show count`() {
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
        val customer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski", noShowCount = 0)
        val company = Company(id = companyId, name = "Salon", maxNoShows = 3)

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { userRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.markNoShow(reservationId)

        // THEN
        assertEquals(ReservationStatus.NO_SHOW, reservation.status)
        assertEquals(1, customer.noShowCount)
        assertTrue(!customer.blocked)
        verify(exactly = 1) { reservationRepository.save(reservation) }
        verify(exactly = 1) { userRepository.save(customer) }
    }

    @Test
    fun `markNoShow should auto-block customer when threshold is reached`() {
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
            status = ReservationStatus.CONFIRMED
        )
        val customer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski", noShowCount = 2)
        val company = Company(id = companyId, name = "Salon", maxNoShows = 3)

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { userRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.markNoShow(reservationId)

        // THEN
        assertEquals(3, customer.noShowCount)
        assertTrue(customer.blocked)
    }

    @Test
    fun `markNoShow should not auto-block when threshold is zero`() {
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
        val customer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski", noShowCount = 99)
        val company = Company(id = companyId, name = "Salon", maxNoShows = 0)

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { userRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.markNoShow(reservationId)

        // THEN
        assertTrue(!customer.blocked, "threshold=0 powinno wyłączyć automatyczne blokowanie")
    }

    @Test
    fun `markNoShow should throw when reservation status is not PENDING or CONFIRMED`() {
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
            status = ReservationStatus.COMPLETED
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.markNoShow(reservationId)
        }
        assertEquals("Tylko aktywna rezerwacja może być oznaczona jako nieobecność", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `createReservation should throw when customer is blocked`() {
        // GIVEN
        val blockedCustomer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski", blocked = true)

        every { userRepository.findById(customerId) } returns Optional.of(blockedCustomer)

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Klient jest zablokowany i nie może rezerwować online", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }
}