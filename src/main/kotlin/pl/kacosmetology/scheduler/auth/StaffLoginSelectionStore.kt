package pl.kacosmetology.scheduler.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import tools.jackson.databind.ObjectMapper

/** Short-lived password verification grants. Atomic GETDEL consumes even invalid company selections. */
@Component
class StaffLoginSelectionStore(private val redis: StringRedisTemplate, private val mapper: ObjectMapper) {
    private val random = SecureRandom()

    /** Writes only scalar verification snapshots, never passwords or hashes of passwords. */
    fun issue(accounts: List<VerifiedStaffAccount>): String {
        while (true) {
            val bytes = ByteArray(32).also(random::nextBytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            if (
                redis
                    .opsForValue()
                    .setIfAbsent(key(token), mapper.writeValueAsString(accounts), Duration.ofMinutes(2)) == true
            )
                return token
        }
    }

    /** Redis GETDEL provides single-use semantics across threads, instances and transaction rollback. */
    fun consume(token: String): List<VerifiedStaffAccount>? {
        val value = redis.opsForValue().getAndDelete(key(token)) ?: return null
        return mapper.readValue(value, Array<VerifiedStaffAccount>::class.java).toList()
    }

    private fun key(token: String): String = "scheduler:staff-login-selection:${hash(token)}"

    companion object {
        /** Non-reversible lookup keys avoid storing plaintext tokens or login emails as Redis keys. */
        fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
    }
}

/** Exact account and revocation state captured while the credential row is locked. */
data class VerifiedStaffAccount(
    val accountId: UUID,
    val companyId: UUID,
    val userId: UUID,
    val role: String,
    val email: String,
    val authVersion: Long,
    val generation: Long,
) {
    companion object {
        /** Snapshots only identities that passed their own password check. */
        fun from(result: StaffAuthenticationResult): VerifiedStaffAccount =
            result.principal.let {
                VerifiedStaffAccount(
                    requireNotNull(it.employmentId),
                    it.requireCompanyId(),
                    it.id,
                    it.role.removePrefix("ROLE_"),
                    it.username,
                    it.authVersion,
                    result.sessionAttributes.getValue(AccountSessionRegistry.GENERATION_ATTRIBUTE) as Long,
                )
            }
    }
}
