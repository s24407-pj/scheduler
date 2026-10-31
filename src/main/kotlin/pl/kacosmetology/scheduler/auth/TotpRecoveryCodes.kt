package pl.kacosmetology.scheduler.auth

import jakarta.persistence.*
import java.io.Serializable
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "staff_recovery_codes")
@IdClass(RecoveryCodeId::class)
class StaffRecoveryCode(
    @Id @Column(name = "account_id") val accountId: UUID,
    @Id @Column(name = "code_hash") val codeHash: String,
)

data class RecoveryCodeId(var accountId: UUID? = null, var codeHash: String? = null) : Serializable

interface StaffRecoveryCodeRepository : JpaRepository<StaffRecoveryCode, RecoveryCodeId> {
    fun deleteAllByAccountId(accountId: UUID)
}
