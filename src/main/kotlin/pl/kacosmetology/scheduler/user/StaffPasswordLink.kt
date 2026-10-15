package pl.kacosmetology.scheduler.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository

/** Hash-only one-use staff setup/recovery credential; replacement overwrites the account's single link. */
@Entity
@Table(name = "staff_password_links")
class StaffPasswordLink(
    @Id @Column(name = "staff_account_id") val staffAccountId: Long,
    @Column(name = "token_hash", nullable = false) var tokenHash: String,
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant,
)

/** Persists one replacement-safe credential per staff account. */
interface StaffPasswordLinkRepository : JpaRepository<StaffPasswordLink, Long> {
    /** Locates a token without retaining its plaintext. */
    fun findByTokenHash(tokenHash: String): StaffPasswordLink?
}
