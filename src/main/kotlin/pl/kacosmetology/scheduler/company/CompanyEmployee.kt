package pl.kacosmetology.scheduler.company

import jakarta.persistence.*
import java.time.LocalDateTime

/** Company-owned staff authentication account and presentation; [userId] is its public employee identifier. */
@Entity
@Table(name = "company_employees")
class CompanyEmployee(
    id: Long? = null,
    @Column(name = "company_id", nullable = false) val companyId: Long,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(nullable = false) val role: String,
    @Column(name = "login_email") var loginEmail: String? = null,
    @Column(name = "password_hash") var passwordHash: String? = null,
    @Column(name = "first_name", nullable = false) var firstName: String = "",
    @Column(name = "last_name", nullable = false) var lastName: String = "",
    @Column(name = "contact_phone") var contactPhone: String? = null,
    @Column(name = "photo_url") var photoUrl: String? = null,
    @Column(nullable = false) var enabled: Boolean = true,
    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_mode", nullable = false)
    var eligibilityMode: EmployeeEligibilityMode = EmployeeEligibilityMode.ALL_SERVICES,
    @Column(name = "auth_version", nullable = false) var authVersion: Long = 0,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}

/** Explicit service qualification: an empty SPECIFIC_SERVICES set qualifies for no service. */
enum class EmployeeEligibilityMode {
    ALL_SERVICES,
    SPECIFIC_SERVICES,
}
