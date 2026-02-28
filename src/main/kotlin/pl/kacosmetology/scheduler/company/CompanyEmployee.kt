package pl.kacosmetology.scheduler.company

import jakarta.persistence.*
import java.time.LocalDateTime

/** Associates a [pl.kacosmetology.scheduler.user.User] with a [Company] and assigns them a role (`OWNER` or `EMPLOYEE`). */
@Entity
@Table(name = "company_employees")
class CompanyEmployee(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false)
    val role: String,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
)

