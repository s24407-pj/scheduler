package pl.kacosmetology.scheduler.auth

import java.util.*
import org.springframework.data.domain.PageRequest
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository

/** Separate proxied transactions keep expensive password verification outside employee allocation locks. */
@Component
class StaffCredentialTransactions(
    private val accounts: CompanyEmployeeRepository,
    private val sessions: pl.kacosmetology.scheduler.security.AccountSessionRegistry,
) {
    /** Copies bounded scalar state without managed credential entities or pessimistic locks. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun snapshots(email: String): List<StaffCredentialSnapshot> {
        val ids = accounts.findLoginCandidates(email, PageRequest.of(0, AuthService.MAX_EMAIL_CANDIDATES + 1))
        if (ids.size > AuthService.MAX_EMAIL_CANDIDATES) return emptyList()
        return ids.mapNotNull { id ->
            val generation = sessions.staffGeneration(id)
            accounts.findCredentialSnapshot(id)?.copy(generation = generation)
        }
    }

    /** Revalidates a consumed selection under its exact account lock, without network operations. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revalidateSelection(grant: VerifiedStaffAccount): pl.kacosmetology.scheduler.security.CustomUserDetails {
        val account =
            accounts.findAccountForUpdate(grant.accountId)
                ?: throw BadCredentialsException("Nieprawidłowy email lub hasło")
        if (
            !account.enabled ||
                account.passwordHash == null ||
                account.companyId != grant.companyId ||
                account.userId != grant.userId ||
                account.role != grant.role ||
                account.role !in setOf("OWNER", "EMPLOYEE") ||
                account.authVersion != grant.authVersion ||
                account.loginEmail != grant.email
        )
            throw BadCredentialsException("Nieprawidłowy email lub hasło")
        return pl.kacosmetology.scheduler.security.CustomUserDetails(
            account.userId,
            requireNotNull(account.loginEmail),
            "ROLE_${account.role}",
            account.companyId,
            requireNotNull(account.id),
            account.authVersion,
        )
    }

    /** Locks only password matches, in UUID order, and rejects any changed credential state as a whole. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revalidate(snapshots: List<StaffCredentialSnapshot>) {
        snapshots
            .sortedBy { it.id }
            .forEach { expected ->
                val current = accounts.findAccountForUpdate(expected.id)?.let(StaffCredentialSnapshot::from)
                if (current?.copy(generation = expected.generation) != expected)
                    throw BadCredentialsException("Nieprawidłowy email lub hasło")
            }
    }
}

/** Immutable credential state; password hashes stay in process and never enter selection grants or principals. */
data class StaffCredentialSnapshot(
    val id: UUID,
    val companyId: UUID,
    val userId: UUID,
    val email: String?,
    val passwordHash: String?,
    val enabled: Boolean,
    val role: String,
    val authVersion: Long,
    val generation: Long = 0,
) {
    companion object {
        /** Copies all fields that define the verified staff identity. */
        fun from(account: CompanyEmployee): StaffCredentialSnapshot =
            StaffCredentialSnapshot(
                requireNotNull(account.id),
                account.companyId,
                account.userId,
                account.loginEmail,
                account.passwordHash,
                account.enabled,
                account.role,
                account.authVersion,
            )
    }
}
