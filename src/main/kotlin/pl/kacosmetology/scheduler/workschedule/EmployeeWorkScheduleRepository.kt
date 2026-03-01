package pl.kacosmetology.scheduler.workschedule

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.DayOfWeek

/** JPA repository for [EmployeeWorkSchedule] entities. */
@Repository
interface EmployeeWorkScheduleRepository : JpaRepository<EmployeeWorkSchedule, Long> {
    /** Returns all schedule entries for the given employee. */
    fun findAllByEmployeeId(employeeId: Long): List<EmployeeWorkSchedule>

    /** Returns the schedule entry for a specific day, or null if the employee doesn't work that day. */
    fun findByEmployeeIdAndDayOfWeek(employeeId: Long, dayOfWeek: DayOfWeek): EmployeeWorkSchedule?

    /** Deletes all schedule entries for the given employee. Used in atomic replace during [WorkScheduleService.setSchedule]. */
    fun deleteAllByEmployeeId(employeeId: Long)

    /** Returns true if the employee has any schedule entries configured. */
    fun existsByEmployeeId(employeeId: Long): Boolean
}
