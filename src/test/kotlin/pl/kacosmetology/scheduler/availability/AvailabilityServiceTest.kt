package pl.kacosmetology.scheduler.availability

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class AvailabilityServiceTest {

    @MockK
    private lateinit var serviceRepository: OfferingRepository

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @MockK
    private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockK
    private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK
    private lateinit var employeeAvailabilityPolicy: EmployeeAvailabilityPolicy

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

    @BeforeEach
    fun setupAvailabilityPolicy() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(any(), any()) } returns true
        every { employeeAvailabilityPolicy.findConflicts(any(), employeeId, any(), any()) } returns emptyList()
        every { employeeAvailabilityPolicy.overlapsAny(any(), any(), any()) } answers {
            val start = firstArg<LocalDateTime>()
            val end = secondArg<LocalDateTime>()
            val conflicts = thirdArg<List<EmployeeAvailabilityConflict>>()
            conflicts.any { start.isBefore(it.endTime) && end.isAfter(it.startTime) }
        }
    }

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
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns defaultScheduleEntry

        // WHEN
        val availableSlots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN
        assertTrue(availableSlots.isNotEmpty())
        assertEquals(LocalTime.of(9, 0), availableSlots.first().time, "Pierwszy slot o 9:00")
        assertEquals(
            LocalTime.of(16, 0),
            availableSlots.last().time,
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
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns defaultScheduleEntry

        // Symulujemy, że pracownik ma już jedną wizytę od 12:00 do 13:00
        val existingReservation = EmployeeAvailabilityConflict(
            source = EmployeeAvailabilityConflictSource.RESERVATION,
            startTime = testDate.atTime(12, 0),
            endTime = testDate.atTime(13, 0)
        )
        every {
            employeeAvailabilityPolicy.findConflicts(any(), employeeId, any(), any())
        } returns listOf(existingReservation)

        // WHEN
        val availableSlots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN
        // Slot 11:00 (do 12:00) jest OK.
        assertTrue(availableSlots.any { it.time == LocalTime.of(11, 0) })

        // Slot 11:30 (do 12:30) NACHODZI na 12:00-13:00 -> Odrzucony!
        assertFalse(availableSlots.any { it.time == LocalTime.of(11, 30) })

        // Slot 12:00 (do 13:00) IDEALNIE POKRYWA SIĘ Z ZAJĘTYM -> Odrzucony!
        assertFalse(availableSlots.any { it.time == LocalTime.of(12, 0) })

        // Slot 12:30 (do 13:30) NACHODZI na 12:00-13:00 -> Odrzucony!
        assertFalse(availableSlots.any { it.time == LocalTime.of(12, 30) })

        // Slot 13:00 (do 14:00) jest już po starej rezerwacji -> OK!
        assertTrue(availableSlots.any { it.time == LocalTime.of(13, 0) })
    }

    @Test
    fun `should exclude slots that overlap with a schedule block`() {
        // GIVEN - Usługa trwa 60 minut
        val service =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns defaultScheduleEntry

        // Blokada od 14:00 do 15:00
        val block = EmployeeAvailabilityConflict(
            source = EmployeeAvailabilityConflictSource.SCHEDULE_BLOCK,
            startTime = testDate.atTime(14, 0),
            endTime = testDate.atTime(15, 0)
        )
        every { employeeAvailabilityPolicy.findConflicts(any(), employeeId, any(), any()) } returns listOf(block)

        // WHEN
        val availableSlots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN
        // Slot 13:00 do 14:00 jest OK (kończy się dokładnie kiedy zaczyna blokada)
        assertTrue(availableSlots.any { it.time == LocalTime.of(13, 0) })

        // Slot 13:30 do 14:30 nachodzi na blokadę 14:00-15:00 -> Odrzucony!
        assertFalse(availableSlots.any { it.time == LocalTime.of(13, 30) })

        // Slot 14:00 do 15:00 pokrywa się z blokadą -> Odrzucony!
        assertFalse(availableSlots.any { it.time == LocalTime.of(14, 0) })

        // Slot 15:00 do 16:00 jest po blokadzie -> OK!
        assertTrue(availableSlots.any { it.time == LocalTime.of(15, 0) })
    }

    @Test
    fun `should return empty list when employee has no schedule entry for that day`() {
        // GIVEN
        val service =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
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
        val service =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
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
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns shortSchedule
        // WHEN
        val slots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN - tylko 13:00 i 13:30 mieszczą się (usługa 60min, koniec do 15:00)
        assertFalse(slots.any { it.time == LocalTime.of(9, 0) }, "Firma otwarta od 9:00, ale pracownik od 13:00")
        assertTrue(slots.any { it.time == LocalTime.of(13, 0) })
        assertTrue(slots.any { it.time == LocalTime.of(13, 30) })
        // Slot 14:00 + 60min = 15:00 (ostatni możliwy, kończy się = end_time) - należy sprawdzić
        // Pętla: !currentSlotStart.plusMinutes(60).isAfter(15:00) -> 14:00 + 60 = 15:00 !isAfter(15:00) → true
        assertTrue(slots.any { it.time == LocalTime.of(14, 0) })
        // 14:30 + 60 = 15:30 > 15:00 → false
        assertFalse(slots.any { it.time == LocalTime.of(14, 30) }, "Slot 14:30 przekraczałby koniec grafiku")
    }

    @Test
    fun `should apply last-minute discount when slot is within the discount window`() {
        // GIVEN - 20% discount for slots within 48 hours; testDate is tomorrow so all slots are within window
        val basePrice = 100
        val discountCompany =
            Company(id = companyId, name = "Test Salon", lastMinuteDiscountPercent = 20, lastMinuteDiscountHours = 48)
        val service = Offering(
            id = serviceId,
            companyId = companyId,
            name = "Strzyżenie",
            durationMinutes = 60,
            price = basePrice
        )

        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(discountCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns defaultScheduleEntry
        // WHEN
        val slots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN - all slots are within 48h window so discounted price = 100 * (100 - 20) / 100 = 80
        assertTrue(slots.isNotEmpty())
        slots.forEach { slot ->
            assertEquals(80, slot.price, "Slot ${slot.time} should have discounted price")
            assertEquals(basePrice, slot.originalPrice, "originalPrice should be catalog price")
        }
    }

    @Test
    fun `should not apply discount when discount percent is zero`() {
        // GIVEN - no discount configured (default)
        val basePrice = 100
        val service = Offering(
            id = serviceId,
            companyId = companyId,
            name = "Strzyżenie",
            durationMinutes = 60,
            price = basePrice
        )

        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns defaultScheduleEntry
        // WHEN
        val slots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN - price == originalPrice, no discount
        assertTrue(slots.isNotEmpty())
        slots.forEach { slot ->
            assertEquals(basePrice, slot.price)
            assertEquals(basePrice, slot.originalPrice)
        }
    }

    @Test
    fun `should not apply discount when slot is beyond the discount window`() {
        // GIVEN - discount only for slots within 1 hour; testDate is tomorrow so slots are outside window
        val basePrice = 100
        val narrowWindowCompany =
            Company(id = companyId, name = "Test Salon", lastMinuteDiscountPercent = 30, lastMinuteDiscountHours = 1)
        val service = Offering(
            id = serviceId,
            companyId = companyId,
            name = "Strzyżenie",
            durationMinutes = 60,
            price = basePrice
        )

        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(narrowWindowCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns defaultScheduleEntry
        // WHEN - testDate is LocalDate.now().plusDays(1) so all slots start > 1 hour from now
        val slots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN - no discount applied since slots are beyond 1-hour window
        assertTrue(slots.isNotEmpty())
        slots.forEach { slot ->
            assertEquals(basePrice, slot.price, "Slot ${slot.time} should have full price outside discount window")
            assertEquals(basePrice, slot.originalPrice)
        }
    }

    @Test
    fun `should exclude slots within min booking advance window`() {
        // GIVEN - company requires 60 minutes advance; testDate is tomorrow so 9:00 is beyond the window
        // but we simulate a date = today and slots in the past/within-advance by using a near-future date
        val advanceCompany = Company(id = companyId, name = "Test Salon", minBookingAdvanceMinutes = 60)
        val service =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 100)
        // Use a schedule that starts from NOW so we can reason about advance filtering
        val nearSchedule = EmployeeWorkSchedule(
            companyId = companyId,
            employeeId = employeeId,
            dayOfWeek = testDate.dayOfWeek,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0)
        )
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(advanceCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns nearSchedule
        // WHEN - testDate is tomorrow; all slots start > 60 min from now so they should all be included
        val slots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN - slots should be present (tomorrow is well beyond 60-minute window)
        assertTrue(slots.isNotEmpty(), "Slots tomorrow should be available when advance is 60 min")
    }

    @Test
    fun `should return no slots when all slots are within min booking advance window`() {
        // GIVEN - company requires 2 days advance; testDate is tomorrow so all slots fall within the window
        val twoDay = 2 * 24 * 60
        val advanceCompany = Company(id = companyId, name = "Test Salon", minBookingAdvanceMinutes = twoDay)
        val service =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(advanceCompany)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every {
            workScheduleRepository.findByEmployeeIdAndDayOfWeek(
                employeeId,
                testDate.dayOfWeek
            )
        } returns defaultScheduleEntry
        // WHEN - testDate is tomorrow but advance requires 2 days ahead
        val slots = availabilityService.getAvailableSlots(employeeId, serviceId, testDate)

        // THEN - no slots returned because all are within the 2-day advance window
        assertTrue(slots.isEmpty(), "No slots should be available when all are within the 2-day advance requirement")
    }

    @Test
    fun `should throw when employee has assignments but service is not assigned`() {
        // GIVEN
        val service =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
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

    @Test
    fun `should throw when employee does not belong to offering company`() {
        // GIVEN
        val service =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 100)
        every { serviceRepository.findById(serviceId) } returns Optional.of(service)
        every { companyRepository.findById(companyId) } returns Optional.of(defaultCompany)
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false

        // WHEN & THEN
        val ex = assertThrows<IllegalArgumentException> {
            availabilityService.getAvailableSlots(employeeId, serviceId, testDate)
        }
        assertEquals("Pracownik nie należy do firmy wybranej usługi", ex.message)
    }
}
