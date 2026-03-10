package pl.kacosmetology.scheduler.scheduleblock

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import java.time.LocalDateTime

/** Business logic for employee schedule blocks (breaks, personal unavailability). */
@Service
class ScheduleBlockService(
    private val scheduleBlockRepository: ScheduleBlockRepository,
    private val reservationRepository: ReservationRepository
) {

    /**
     * Creates a new schedule block for the given employee.
     * Validates that [endTime] is after [startTime] and that the range does not overlap
     * any existing reservation or another schedule block for the same employee.
     */
    @Transactional
    fun createBlock(
        employeeId: Long,
        companyId: Long,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        reason: String?
    ): ScheduleBlock {
        if (!endTime.isAfter(startTime)) {
            throw IllegalArgumentException("Czas zakończenia musi być późniejszy niż czas rozpoczęcia")
        }

        if (reservationRepository.existsOverlapping(employeeId, startTime, endTime)) {
            throw IllegalStateException("W tym czasie istnieje już rezerwacja")
        }

        if (scheduleBlockRepository.existsOverlapping(employeeId, startTime, endTime)) {
            throw IllegalStateException("W tym czasie istnieje już blokada")
        }

        return scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employeeId,
                startTime = startTime,
                endTime = endTime,
                reason = reason
            )
        )
    }

    /**
     * Deletes a schedule block.
     * OWNER may delete any block within their company (verified by [companyId]).
     * EMPLOYEE may only delete their own block (verified by [requesterId]).
     */
    @Transactional
    fun deleteBlock(blockId: Long, requesterId: Long, isOwner: Boolean, companyId: Long?) {
        val block = scheduleBlockRepository.findById(blockId)
            .orElseThrow { NoSuchElementException("Blokada nie istnieje") }

        if (isOwner) {
            if (block.companyId != companyId) throw IllegalStateException("Brak dostępu do tej blokady")
        } else {
            if (block.employeeId != requesterId) throw IllegalStateException("Nie możesz usunąć cudzej blokady")
        }

        scheduleBlockRepository.delete(block)
    }

    /** Returns all schedule blocks for an employee whose start time falls within [start, end). */
    @Transactional(readOnly = true)
    fun getEmployeeBlocks(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<ScheduleBlock> {
        return scheduleBlockRepository.findByEmployeeIdAndStartTimeBetween(employeeId, start, end)
    }
}
