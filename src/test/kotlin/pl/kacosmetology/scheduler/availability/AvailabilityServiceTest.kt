package pl.kacosmetology.scheduler.availability

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.treatment.ProvidedService
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class AvailabilityServiceTest {

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @MockK
    private lateinit var serviceRepository: TreatmentRepository

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @InjectMockKs
    private lateinit var availabilityService: AvailabilityService

    private val companyId = 1L
    private val employeeId = 1L
    private val serviceId = 99L
    private val testDate = LocalDate.now().plusDays(1)

    private val defaultCompany = Company(id = companyId, name = "Test Salon")

    @Test
    fun `should return all possible slots when day is completely free`() {
        // GIVEN - Usługa trwa 60 minut (od 9:00 do 17:00 zmieści się dużo takich slotów co 30 minut)
        val service =
            ProvidedService(
                id = serviceId,
                companyId = companyId,
                name = "Strzyżenie",
                durationMinutes = 60,
                price = 100
            )
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every {
            reservationRepository.findByEmployeeIdAndDate(
                employeeId,
                any(),
                any()
            )
        } returns emptyList() // Brak rezerwacji

        // WHEN
        val availableSlots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN
        assertTrue(availableSlots.isNotEmpty())
        assertEquals(LocalTime.of(9, 0), availableSlots.first(), "Pierwszy slot o 9:00")
        assertEquals(
            LocalTime.of(16, 0),
            availableSlots.last(),
            "Ostatni slot o 16:00 (bo usługa trwa godzinę, do 17:00)"
        )

        // Slotów od 9:00 do 16:00 z krokiem 30 minut jest dokładnie 15
        assertEquals(15, availableSlots.size)
    }

    @Test
    fun `should exclude slots that overlap with existing reservation`() {
        // GIVEN - Usługa trwa 60 minut
        val service =
            ProvidedService(
                id = serviceId,
                companyId = companyId,
                name = "Strzyżenie",
                durationMinutes = 60,
                price = 100
            )
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)

        // Symulujemy, że pracownik ma już jedną wizytę od 12:00 do 13:00
        val existingReservation = Reservation(
            companyId = companyId, customerId = 2, employeeId = employeeId, serviceId = serviceId, price = 100,
            startTime = testDate.atTime(12, 0),
            endTime = testDate.atTime(13, 0),
            status = ReservationStatus.PENDING
        )
        every { reservationRepository.findByEmployeeIdAndDate(employeeId, any(), any()) } returns listOf(
            existingReservation
        )

        // WHEN
        val availableSlots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN
        // Slot 11:00 (do 12:00) jest OK.
        assertTrue(availableSlots.contains(LocalTime.of(11, 0)))

        // Slot 11:30 (do 12:30) NACHODZI na 12:00-13:00 -> Odrzucony!
        assertFalse(availableSlots.contains(LocalTime.of(11, 30)))

        // Slot 12:00 (do 13:00) IDEALNIE POKRYWA SIĘ Z ZAJĘTYM -> Odrzucony!
        assertFalse(availableSlots.contains(LocalTime.of(12, 0)))

        // Slot 12:30 (do 13:30) NACHODZI na 12:00-13:00 -> Odrzucony!
        assertFalse(availableSlots.contains(LocalTime.of(12, 30)))

        // Slot 13:00 (do 14:00) jest już po starej rezerwacji -> OK!
        assertTrue(availableSlots.contains(LocalTime.of(13, 0)))
    }
}