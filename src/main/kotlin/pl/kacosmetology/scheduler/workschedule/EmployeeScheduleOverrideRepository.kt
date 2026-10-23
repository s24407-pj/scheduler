package pl.kacosmetology.scheduler.workschedule

import java.time.LocalDate
import java.util.*
import org.springframework.data.jpa.repository.JpaRepository

/** Tenant-scoped date-specific schedule storage. */
interface EmployeeScheduleOverrideRepository : JpaRepository<EmployeeScheduleOverride, UUID> {
    /** Returns a replacement or day off for the requested employee and date. */
    fun findByCompanyIdAndEmployeeIdAndScheduleDate(
        companyId: UUID,
        employeeId: UUID,
        scheduleDate: LocalDate,
    ): EmployeeScheduleOverride?

    /** Lists explicit overrides in an inclusive date range. */
    fun findAllByCompanyIdAndEmployeeIdAndScheduleDateBetweenOrderByScheduleDate(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDate,
        end: LocalDate,
    ): List<EmployeeScheduleOverride>
}
