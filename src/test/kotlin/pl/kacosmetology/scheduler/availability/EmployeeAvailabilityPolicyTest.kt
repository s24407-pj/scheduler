package pl.kacosmetology.scheduler.availability

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlock
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import java.time.LocalDateTime

@ExtendWith(MockKExtension::class)
class EmployeeAvailabilityPolicyTest {

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @MockK
    private lateinit var scheduleBlockRepository: ScheduleBlockRepository

    @InjectMockKs
    private lateinit var policy: EmployeeAvailabilityPolicy

    private val companyId = 1L
    private val employeeId = 10L
    private val start = LocalDateTime.of(2030, 1, 1, 10, 0)
    private val end = LocalDateTime.of(2030, 1, 1, 11, 0)

    @Test
    fun `overlapsAny should return false for touching ranges`() {
        val conflicts = listOf(
            EmployeeAvailabilityConflict(EmployeeAvailabilityConflictSource.RESERVATION, start.minusHours(1), start),
            EmployeeAvailabilityConflict(EmployeeAvailabilityConflictSource.SCHEDULE_BLOCK, end, end.plusHours(1))
        )

        assertFalse(policy.overlapsAny(start, end, conflicts))
    }

    @Test
    fun `findConflicts should include overlapping active reservation`() {
        every { reservationRepository.findOverlapping(employeeId, start, end) } returns listOf(
            Reservation(
                companyId = 1L,
                customerId = 2L,
                employeeId = employeeId,
                serviceId = 3L,
                price = 100,
                startTime = start.plusMinutes(15),
                endTime = end.plusMinutes(15),
                status = ReservationStatus.PENDING
            )
        )
        every { scheduleBlockRepository.findOverlapping(companyId, employeeId, start, end) } returns emptyList()

        val conflicts = policy.findConflicts(companyId, employeeId, start, end)

        assertEquals(1, conflicts.size)
        assertEquals(EmployeeAvailabilityConflictSource.RESERVATION, conflicts.first().source)
    }

    @Test
    fun `findFirstConflict should return null when cancelled and no-show reservations are ignored by repository`() {
        every { reservationRepository.findOverlapping(employeeId, start, end) } returns emptyList()
        every { scheduleBlockRepository.findOverlapping(companyId, employeeId, start, end) } returns emptyList()

        assertNull(policy.findFirstConflict(companyId, employeeId, start, end))
    }

    @Test
    fun `findConflicts should include overlapping schedule block`() {
        every { reservationRepository.findOverlapping(employeeId, start, end) } returns emptyList()
        every { scheduleBlockRepository.findOverlapping(companyId, employeeId, start, end) } returns listOf(
            ScheduleBlock(
                companyId = 1L,
                employeeId = employeeId,
                startTime = start.plusMinutes(30),
                endTime = end.plusMinutes(30)
            )
        )

        val conflicts = policy.findConflicts(companyId, employeeId, start, end)

        assertEquals(1, conflicts.size)
        assertEquals(EmployeeAvailabilityConflictSource.SCHEDULE_BLOCK, conflicts.first().source)
    }

    @Test
    fun `findFirstConflict should prefer reservation over schedule block`() {
        every { reservationRepository.findOverlapping(employeeId, start, end) } returns listOf(
            Reservation(
                companyId = 1L,
                customerId = 2L,
                employeeId = employeeId,
                serviceId = 3L,
                price = 100,
                startTime = start,
                endTime = end,
                status = ReservationStatus.CONFIRMED
            )
        )
        every { scheduleBlockRepository.findOverlapping(companyId, employeeId, start, end) } returns listOf(
            ScheduleBlock(companyId = 1L, employeeId = employeeId, startTime = start, endTime = end)
        )

        val conflict = policy.findFirstConflict(companyId, employeeId, start, end)

        assertEquals(EmployeeAvailabilityConflictSource.RESERVATION, conflict?.source)
    }

    @Test
    fun `assertAvailable should throw when any conflict exists`() {
        every { reservationRepository.findOverlapping(employeeId, start, end) } returns emptyList()
        every { scheduleBlockRepository.findOverlapping(companyId, employeeId, start, end) } returns listOf(
            ScheduleBlock(companyId = 1L, employeeId = employeeId, startTime = start, endTime = end)
        )

        assertThrows(IllegalStateException::class.java) {
            policy.assertAvailable(companyId, employeeId, start, end)
        }
    }
}
