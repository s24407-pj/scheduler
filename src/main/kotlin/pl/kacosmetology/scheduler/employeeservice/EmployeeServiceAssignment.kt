package pl.kacosmetology.scheduler.employeeservice

import jakarta.persistence.*
import java.time.OffsetDateTime

/** Associates an employee with a service they are allowed to perform. */
@Entity
@Table(name = "employee_services")
class EmployeeServiceAssignment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "employee_id", nullable = false)
    val employeeId: Long,

    @Column(name = "service_id", nullable = false)
    val serviceId: Long,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null
)
