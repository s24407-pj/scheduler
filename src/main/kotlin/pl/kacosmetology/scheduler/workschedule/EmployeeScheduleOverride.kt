package pl.kacosmetology.scheduler.workschedule

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalTime
import java.util.*
import org.hibernate.annotations.UuidGenerator

/** A company employee's date-specific replacement shift or explicit day off. */
@Entity
@Table(name = "employee_schedule_overrides")
class EmployeeScheduleOverride(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "employee_id", nullable = false) val employeeId: UUID,
    @Column(name = "schedule_date", nullable = false) val scheduleDate: LocalDate,
    @Column(name = "day_off", nullable = false) var dayOff: Boolean,
    @Column(name = "start_time") var startTime: LocalTime? = null,
    @Column(name = "end_time") var endTime: LocalTime? = null,
)
