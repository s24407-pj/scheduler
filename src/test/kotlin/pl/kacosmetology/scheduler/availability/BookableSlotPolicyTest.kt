package pl.kacosmetology.scheduler.availability

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.workschedule.EmployeeScheduleOverride
import pl.kacosmetology.scheduler.workschedule.EmployeeScheduleOverrideRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository

@ExtendWith(MockKExtension::class)
class BookableSlotPolicyTest {

    @MockK private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockK private lateinit var overrideRepository: EmployeeScheduleOverrideRepository

    @InjectMockKs private lateinit var policy: BookableSlotPolicy

    private val companyId = testId(1)
    private val employeeId = testId(10)
    private val date = LocalDate.of(2030, 1, 7)

    @BeforeEach
    fun setup() {
        every { overrideRepository.findByCompanyIdAndEmployeeIdAndScheduleDate(any(), any(), any()) } returns null
        every {
            workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(companyId, employeeId, date.dayOfWeek)
        } returns
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = date.dayOfWeek,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(11, 0),
            )
        every {
            workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(testId(2), employeeId, any())
        } returns null
    }

    @Test
    fun `slotStarts should step by the interval and keep slots that end by the schedule end`() {
        val starts = policy.slotStarts(companyId, employeeId, date, durationMinutes = 60, intervalMinutes = 30)

        assertEquals(listOf(date.atTime(9, 0), date.atTime(9, 30), date.atTime(10, 0)), starts)
    }

    @Test
    fun `slotStarts should be empty without a schedule entry at that company`() {
        assertTrue(policy.slotStarts(testId(2), employeeId, date, durationMinutes = 60, intervalMinutes = 30).isEmpty())
    }

    @Test
    fun `isOnGrid should accept exactly the generated slot starts`() {
        assertTrue(policy.isOnGrid(companyId, employeeId, date.atTime(9, 30), 60, 30))
        assertTrue(policy.isOnGrid(companyId, employeeId, date.atTime(10, 0), 60, 30), "ends exactly at 11:00")
    }

    @Test
    fun `isOnGrid should reject off-grid, out-of-hours, overrunning and other-company times`() {
        assertFalse(policy.isOnGrid(companyId, employeeId, date.atTime(9, 7), 60, 30), "off grid")
        assertFalse(policy.isOnGrid(companyId, employeeId, date.atTime(8, 30), 60, 30), "before start")
        assertFalse(policy.isOnGrid(companyId, employeeId, date.atTime(10, 30), 60, 30), "ends after 11:00")
        assertFalse(policy.isOnGrid(companyId, employeeId, date.atTime(9, 0, 30), 60, 30), "seconds off grid")
        assertFalse(policy.isOnGrid(testId(2), employeeId, date.atTime(9, 0), 60, 30), "no schedule at company 2")
    }

    @Test
    fun `replacement starts a new grid while day off removes weekly slots`() {
        every { overrideRepository.findByCompanyIdAndEmployeeIdAndScheduleDate(companyId, employeeId, date) } returns
            EmployeeScheduleOverride(
                companyId = companyId,
                employeeId = employeeId,
                scheduleDate = date,
                dayOff = false,
                startTime = LocalTime.of(12, 15),
                endTime = LocalTime.of(14, 15),
            )
        assertEquals(
            listOf(date.atTime(12, 15), date.atTime(12, 45), date.atTime(13, 15)),
            policy.slotStarts(companyId, employeeId, date, 60, 30),
        )
        assertEquals(120, policy.effectiveShift(companyId, employeeId, date)!!.durationMinutes)
        every { overrideRepository.findByCompanyIdAndEmployeeIdAndScheduleDate(companyId, employeeId, date) } returns
            EmployeeScheduleOverride(companyId = companyId, employeeId = employeeId, scheduleDate = date, dayOff = true)
        assertTrue(policy.slotStarts(companyId, employeeId, date, 60, 30).isEmpty())
    }

    @Test
    fun `DST gaps overlaps and transition spanning appointments are excluded from grids`() {
        listOf(LocalDate.of(2030, 3, 31), LocalDate.of(2030, 10, 27)).forEach { transitionDate ->
            every {
                workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(
                    companyId,
                    employeeId,
                    transitionDate.dayOfWeek,
                )
            } returns
                EmployeeWorkSchedule(
                    companyId = companyId,
                    employeeId = employeeId,
                    dayOfWeek = transitionDate.dayOfWeek,
                    startTime = LocalTime.MIDNIGHT,
                    endTime = LocalTime.of(5, 0),
                )
            val slots = policy.slotStarts(companyId, employeeId, transitionDate, 30, 30)
            assertFalse(slots.any { it.hour == 2 })
            assertFalse(policy.isOnGrid(companyId, employeeId, transitionDate.atTime(1, 30), 120, 30))
            assertTrue(slots.any { it.hour == 4 })
        }
        assertEquals(240, policy.effectiveShift(companyId, employeeId, LocalDate.of(2030, 3, 31))!!.durationMinutes)
        assertEquals(360, policy.effectiveShift(companyId, employeeId, LocalDate.of(2030, 10, 27))!!.durationMinutes)
    }
}
