package pl.kacosmetology.scheduler.auth

import java.util.Locale
import java.util.UUID
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** Only short database work runs under account locks; Redis grants and session creation live outside transactions. */
@Component
class TotpTransactions(
    private val accounts: CompanyEmployeeRepository,
    private val recovery: StaffRecoveryCodeRepository,
    private val recoveryAuthorizations: StaffRecoveryAuthorizationRepository,
    private val codes: TotpCodes,
    private val cipher: TotpCipher,
    private val passwords: PasswordEncoder,
) {
    @Transactional fun configured(grant: VerifiedStaffAccount): Boolean = account(grant).totpSecret != null

    @Transactional
    fun verify(grant: TotpGrant, code: String): CustomUserDetails {
        val account = account(grant.account)
        val encrypted = account.totpSecret ?: TotpGrantStore.expired()
        val step =
            codes.match(cipher.decrypt(encrypted, account.id.toString()), code, account.totpLastStep) ?: invalidCode()
        account.totpLastStep = step
        return principal(account)
    }

    @Transactional
    fun confirm(grant: TotpGrant, code: String): CustomUserDetails {
        val account = account(grant.account)
        val secret = requireNotNull(grant.secret)
        val step = codes.match(secret, code) ?: invalidCode()
        account.totpSecret = cipher.encrypt(secret, account.id.toString())
        account.totpLastStep = step
        account.authVersion++
        recoveryAuthorizations.deleteAllByAccountId(requireNotNull(account.id))
        recovery.deleteAllByAccountId(requireNotNull(account.id))
        recovery.flush()
        if (account.role == "OWNER") {
            require(grant.recoveryCodes.size == 10)
            recovery.saveAll(
                grant.recoveryCodes.map { StaffRecoveryCode(requireNotNull(account.id), recoveryHash(it)) },
            )
        } else require(grant.recoveryCodes.isEmpty())
        // The new durable version rejects old sessions and grants, including delayed Redis saves.
        // Do not advance the generation here: completion must retain its original logout-all fence.
        return principal(account)
    }

    @Transactional
    fun recover(grant: TotpGrant, token: String, code: String) {
        val account = account(grant.account)
        if (account.role != "OWNER" || account.totpSecret == null) invalidCode()
        val grantHash = StaffLoginSelectionStore.hash(token)
        if (recoveryAuthorizations.existsByGrantHashAndAccountId(grantHash, requireNotNull(account.id))) return
        val stored =
            recovery.findById(RecoveryCodeId(requireNotNull(account.id), recoveryHash(code))).orElse(null)
                ?: invalidCode()
        recovery.delete(stored)
        recoveryAuthorizations.save(StaffRecoveryAuthorization(grantHash, requireNotNull(account.id)))
    }

    @Transactional
    fun requireRecovery(grant: TotpGrant, token: String) {
        val account = account(grant.account)
        if (
            !recoveryAuthorizations.existsByGrantHashAndAccountId(
                StaffLoginSelectionStore.hash(token),
                requireNotNull(account.id),
            )
        )
            TotpGrantStore.expired()
    }

    /** Reauthenticates the owner and resets only an employee in the same company. Locks are taken in UUID order. */
    @Transactional
    fun reset(owner: CustomUserDetails, employeeId: UUID, password: String, code: String) {
        val targetId =
            accounts.findAccountIdByCompanyIdAndUserId(owner.requireCompanyId(), employeeId)
                ?: throw ApiException(ErrorCode.NOT_FOUND, "Pracownik nie istnieje")
        if (targetId == owner.employmentId)
            throw ApiException(ErrorCode.ACCESS_DENIED, "Nie można zresetować 2FA właściciela")
        val ids = listOf(requireNotNull(owner.employmentId), targetId).sorted()
        val locked = ids.associateWith { accounts.findAccountForUpdate(it) ?: TotpGrantStore.expired() }
        val actor = requireNotNull(locked[owner.employmentId])
        val employee = requireNotNull(locked[targetId])
        if (
            principal(actor) != owner ||
                !actor.enabled ||
                actor.role != "OWNER" ||
                !passwords.matches(password, actor.passwordHash ?: "")
        )
            invalidCode()
        if (employee.companyId != owner.companyId || employee.userId != employeeId || employee.role != "EMPLOYEE")
            throw ApiException(ErrorCode.NOT_FOUND, "Pracownik nie istnieje")
        val secret = actor.totpSecret ?: TotpGrantStore.expired()
        actor.totpLastStep =
            codes.match(cipher.decrypt(secret, actor.id.toString()), code, actor.totpLastStep) ?: invalidCode()
        employee.totpSecret = null
        employee.totpLastStep = null
        employee.authVersion++
        recoveryAuthorizations.deleteAllByAccountId(requireNotNull(employee.id))
        recovery.deleteAllByAccountId(requireNotNull(employee.id))
    }

    private fun account(grant: VerifiedStaffAccount): CompanyEmployee {
        val account = accounts.findAccountForUpdate(grant.accountId) ?: TotpGrantStore.expired()
        if (
            !account.enabled ||
                account.passwordHash == null ||
                account.companyId != grant.companyId ||
                account.userId != grant.userId ||
                account.role != grant.role ||
                account.role !in setOf("OWNER", "EMPLOYEE") ||
                account.loginEmail != grant.email ||
                account.authVersion != grant.authVersion
        )
            TotpGrantStore.expired()
        return account
    }

    private fun principal(account: CompanyEmployee) =
        CustomUserDetails(
            account.userId,
            requireNotNull(account.loginEmail),
            "ROLE_${account.role}",
            account.companyId,
            requireNotNull(account.id),
            account.authVersion,
        )

    companion object {
        fun recoveryHash(value: String): String =
            StaffLoginSelectionStore.hash(value.replace("-", "").replace(" ", "").uppercase(Locale.ROOT))

        fun invalidCode(): Nothing =
            throw ApiException(ErrorCode.INVALID_CREDENTIALS, "Nieprawidłowy lub wykorzystany kod weryfikacyjny")
    }
}
