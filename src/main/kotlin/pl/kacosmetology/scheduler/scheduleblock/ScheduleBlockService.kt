package pl.kacosmetology.scheduler.scheduleblock

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityConflictSource
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import java.time.LocalDateTime

/** Business logic for employee schedule blocks (breaks, personal unavailability). */
@Service
class ScheduleBlockService(
    private val scheduleBlockRepository: ScheduleBlockRepository,
    private val employeeAvailabilityPolicy: EmployeeAvailabilityPolicy,
    private val companyEmployeeRepository: CompanyEmployeeRepository
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
        lockCompanyMembership(companyId, employeeId)

        if (!endTime.isAfter(startTime)) {
            throw IllegalArgumentException("Czas zakończenia musi być późniejszy niż czas rozpoczęcia")
        }

        val conflict = employeeAvailabilityPolicy.findFirstConflict(companyId, employeeId, startTime, endTime)
        if (conflict != null) {
            val message = when (conflict.source) {
                EmployeeAvailabilityConflictSource.RESERVATION -> "W tym czasie istnieje już rezerwacja"
                EmployeeAvailabilityConflictSource.SCHEDULE_BLOCK -> "W tym czasie istnieje już blokada"
            }
            throw IllegalStateException(message)
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
     * Both roles are restricted to [companyId]. OWNER may delete any block within that company,
     * while EMPLOYEE may only delete their own block (verified by [requesterId]).
     */
    @Transactional
    fun deleteBlock(blockId: Long, requesterId: Long, isOwner: Boolean, companyId: Long) {
        val block = scheduleBlockRepository.findById(blockId)
            .orElseThrow { NoSuchElementException("Blokada nie istnieje") }

        if (block.companyId != companyId) throw IllegalStateException("Brak dostępu do tej blokady")
        if (!isOwner) {
            if (block.employeeId != requesterId) throw IllegalStateException("Nie możesz usunąć cudzej blokady")
        }

        scheduleBlockRepository.delete(block)
    }

    /** Returns all schedule blocks for an employee in [companyId] that overlap [start, end). */
    @Transactional(readOnly = true)
    fun getEmployeeBlocks(
        companyId: Long,
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<ScheduleBlock> {
        requireCompanyMembership(companyId, employeeId)
        return scheduleBlockRepository.findOverlapping(companyId, employeeId, start, end)
    }

    private fun requireCompanyMembership(companyId: Long, employeeId: Long) {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
    }

    private fun lockCompanyMembership(companyId: Long, employeeId: Long) {
        if (companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) == null) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
    }
}
