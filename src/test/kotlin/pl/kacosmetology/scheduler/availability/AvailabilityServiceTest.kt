package pl.kacosmetology.scheduler.availability

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlock
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class AvailabilityServiceTest {

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @MockK
    private lateinit var serviceRepository: OfferingRepository

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @MockK
    private lateinit var scheduleBlockRepository: ScheduleBlockRepository

    @MockK
    private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockK
    private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @InjectMockKs
    private lateinit var availabilityService: AvailabilityService

    private val companyId = 1L
    private val employeeId = 1L
    private val serviceId = 99L
    private val testDate = LocalDate.now().plusDays(1)

    private val defaultCompany = Company(id = companyId, name = "Test Salon")

    private val defaultScheduleEntry = EmployeeWorkSchedule(
        companyId = companyId,
        employeeId = employeeId,
        dayOfWeek = testDate.dayOfWeek,
        startTime = LocalTime.of(9, 0),
        endTime = LocalTime.of(17, 0)
    )

    @Test
    fun `should return all possible slots when day is completely free`() {
        // GIVEN - Usługa trwa 60 minut (od 9:00 do 17:00 zmieści się dużo takich slotów co 30 minut)
        val service =
            Offering(
                id = serviceId,
                companyId = companyId,
                name = "Strzyżenie",
                durationMinutes = 60,
                price = 100
            )
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { workScheduleRepository.findByEmployeeIdAndDayOfWeek(employeeId, testDate.dayOfWeek) } returns defaultScheduleEntry
        every {
            reservationRepository.findByEmployeeIdAndDate(
                employeeId,
                any(),
                any()
            )
        } returns emptyList()
        every { scheduleBlockRepository.findByEmployeeIdAndStartTimeBetween(employeeId, any(), any()) } returns emptyList()

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
            Offering(
                id = serviceId,
                companyId = companyId,
                name = "Strzyżenie",
                durationMinutes = 60,
                price = 100
            )
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { workScheduleRepository.findByEmployeeIdAndDayOfWeek(employeeId, testDate.dayOfWeek) } returns defaultScheduleEntry

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
        every { scheduleBlockRepository.findByEmployeeIdAndStartTimeBetween(employeeId, any(), any()) } returns emptyList()

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

    @Test
    fun `should exclude slots that overlap with a schedule block`() {
        // GIVEN - Usługa trwa 60 minut
        val service = Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { workScheduleRepository.findByEmployeeIdAndDayOfWeek(employeeId, testDate.dayOfWeek) } returns defaultScheduleEntry
        every { reservationRepository.findByEmployeeIdAndDate(employeeId, any(), any()) } returns emptyList()

        // Blokada od 14:00 do 15:00
        val block = ScheduleBlock(
            id = 1L,
            companyId = companyId,
            employeeId = employeeId,
            startTime = testDate.atTime(14, 0),
            endTime = testDate.atTime(15, 0)
        )
        every { scheduleBlockRepository.findByEmployeeIdAndStartTimeBetween(employeeId, any(), any()) } returns listOf(block)

        // WHEN
        val availableSlots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN
        // Slot 13:00 do 14:00 jest OK (kończy się dokładnie kiedy zaczyna blokada)
        assertTrue(availableSlots.contains(LocalTime.of(13, 0)))

        // Slot 13:30 do 14:30 nachodzi na blokadę 14:00-15:00 -> Odrzucony!
        assertFalse(availableSlots.contains(LocalTime.of(13, 30)))

        // Slot 14:00 do 15:00 pokrywa się z blokadą -> Odrzucony!
        assertFalse(availableSlots.contains(LocalTime.of(14, 0)))

        // Slot 15:00 do 16:00 jest po blokadzie -> OK!
        assertTrue(availableSlots.contains(LocalTime.of(15, 0)))
    }

    @Test
    fun `should return empty list when employee has no schedule entry for that day`() {
        // GIVEN
        val service = Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { workScheduleRepository.findByEmployeeIdAndDayOfWeek(employeeId, testDate.dayOfWeek) } returns null

        // WHEN
        val result = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN
        assertTrue(result.isEmpty(), "Brak grafiku na ten dzień = brak slotów")
    }

    @Test
    fun `should use employee work schedule hours instead of company hours`() {
        // GIVEN - Pracownik pracuje tylko od 13:00 do 15:00
        val service = Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
        val shortSchedule = EmployeeWorkSchedule(
            companyId = companyId,
            employeeId = employeeId,
            dayOfWeek = testDate.dayOfWeek,
            startTime = LocalTime.of(13, 0),
            endTime = LocalTime.of(15, 0)
        )
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { workScheduleRepository.findByEmployeeIdAndDayOfWeek(employeeId, testDate.dayOfWeek) } returns shortSchedule
        every { reservationRepository.findByEmployeeIdAndDate(employeeId, any(), any()) } returns emptyList()
        every { scheduleBlockRepository.findByEmployeeIdAndStartTimeBetween(employeeId, any(), any()) } returns emptyList()

        // WHEN
        val slots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN - tylko 13:00 i 13:30 mieszczą się (usługa 60min, koniec do 15:00)
        assertFalse(slots.contains(LocalTime.of(9, 0)), "Firma otwarta od 9:00, ale pracownik od 13:00")
        assertTrue(slots.contains(LocalTime.of(13, 0)))
        assertTrue(slots.contains(LocalTime.of(13, 30)))
        // Slot 14:00 + 60min = 15:00 (ostatni możliwy, kończy się = end_time) - należy sprawdzić
        // Pętla: !currentSlotStart.plusMinutes(60).isAfter(15:00) -> 14:00 + 60 = 15:00 !isAfter(15:00) → true
        assertTrue(slots.contains(LocalTime.of(14, 0)))
        // 14:30 + 60 = 15:30 > 15:00 → false
        assertFalse(slots.contains(LocalTime.of(14, 30)), "Slot 14:30 przekraczałby koniec grafiku")
    }

    @Test
    fun `should throw when employee has assignments but service is not assigned`() {
        // GIVEN
        val service = Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns true
        every { assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, serviceId) } returns false

        // WHEN & THEN
        val ex = assertThrows<IllegalArgumentException> {
            availabilityService.getAvailableSlots(employeeId, serviceId, testDate)
        }
        assertEquals("Ten pracownik nie wykonuje wybranej usługi", ex.message)
    }
}
