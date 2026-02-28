package pl.kacosmetology.scheduler.scheduleblock

import jakarta.persistence.*
import java.time.LocalDateTime

/** Represents a time range blocked by an employee (e.g. a break or personal unavailability). */
@Entity
@Table(name = "schedule_blocks")
class ScheduleBlock(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "employee_id", nullable = false)
    val employeeId: Long,

    @Column(name = "start_time", nullable = false)
    val startTime: LocalDateTime,

    @Column(name = "end_time", nullable = false)
    val endTime: LocalDateTime,

    @Column(length = 255)
    val reason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
)
