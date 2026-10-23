package pl.kacosmetology.scheduler.workschedule

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.*
import org.hibernate.annotations.UuidGenerator

/** Represents a single day's working hours for an employee. One entry per [dayOfWeek] per employee. */
@Entity
@Table(name = "employee_work_schedules")
class EmployeeWorkSchedule(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "employee_id", nullable = false) val employeeId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "day_of_week", nullable = false, length = 10) val dayOfWeek: DayOfWeek,
    @Column(name = "start_time", nullable = false) val startTime: LocalTime,
    @Column(name = "end_time", nullable = false) val endTime: LocalTime,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
