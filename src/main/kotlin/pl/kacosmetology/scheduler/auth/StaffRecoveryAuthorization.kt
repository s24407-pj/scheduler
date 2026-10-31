package pl.kacosmetology.scheduler.auth

import jakarta.persistence.*
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/** Durable receipt committed together with code consumption; usable only with the original live browser grant. */
@Entity
@Table(name = "staff_recovery_authorizations")
class StaffRecoveryAuthorization(
    @Id @Column(name = "grant_hash") val grantHash: String,
    @Column(name = "account_id", nullable = false) val accountId: UUID,
)

interface StaffRecoveryAuthorizationRepository : JpaRepository<StaffRecoveryAuthorization, String> {
    fun existsByGrantHashAndAccountId(grantHash: String, accountId: UUID): Boolean

    fun deleteAllByAccountId(accountId: UUID)
}
