package pl.kacosmetology.scheduler.availability

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository

@ExtendWith(MockKExtension::class)
class BookableSlotPolicyTest {

    @MockK private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @InjectMockKs private lateinit var policy: BookableSlotPolicy

    private val companyId = 1L
    private val employeeId = 10L
    private val date = LocalDate.of(2030, 1, 7)

    @BeforeEach
    fun setup() {
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
            workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(2L, employeeId, any())
        } returns null
    }

    @Test
    fun `slotStarts should step by the interval and keep slots that end by the schedule end`() {
        val starts = policy.slotStarts(companyId, employeeId, date, durationMinutes = 60, intervalMinutes = 30)

        assertEquals(listOf(date.atTime(9, 0), date.atTime(9, 30), date.atTime(10, 0)), starts)
    }

    @Test
    fun `slotStarts should be empty without a schedule entry at that company`() {
        assertTrue(policy.slotStarts(2L, employeeId, date, durationMinutes = 60, intervalMinutes = 30).isEmpty())
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
        assertFalse(policy.isOnGrid(2L, employeeId, date.atTime(9, 0), 60, 30), "no schedule at company 2")
    }
}
