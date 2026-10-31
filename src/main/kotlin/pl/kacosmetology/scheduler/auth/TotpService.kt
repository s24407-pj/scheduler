package pl.kacosmetology.scheduler.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.auth.dto.*
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** Password verification grants never authenticate: only a committed TOTP proof can create a staff session. */
@Service
class TotpService(
    private val grants: TotpGrantStore,
    private val transactions: TotpTransactions,
    private val sessions: AccountSessionRegistry,
    private val codes: TotpCodes,
    private val companies: CompanyRepository,
    private val clock: Clock,
) {
    fun challenge(result: StaffAuthenticationResult, browser: String): StaffLoginResponse {
        val account = VerifiedStaffAccount.from(result)
        checkGeneration(account)
        val kind = if (transactions.configured(account)) TotpGrantKind.VERIFY else TotpGrantKind.SETUP
        return response(
            TotpGrant(
                account,
                StaffLoginSelectionStore.hash(browser),
                kind,
                clock.instant().plus(Duration.ofMinutes(10)),
            ),
        )
    }

    fun setup(token: String, browser: String?, ip: String): TotpSetupResponse {
        val grant = read(token, browser, ip, TotpGrantKind.SETUP, TotpGrantKind.RECOVER, TotpGrantKind.VERIFY)
        if (grant.kind == TotpGrantKind.VERIFY) transactions.requireRecovery(grant, token)
        transactions.configured(
            grant.account,
        ) // Revalidate the exact password-verified account before disclosing a secret.
        val secret = codes.newSecret()
        val recovery = if (grant.account.role == "OWNER") codes.recoveryCodes() else emptyList()
        // Retain recovery authorization so a failed write or lost setup response can be retried until expiry.
        if (grant.kind != TotpGrantKind.VERIFY) grants.consume(token)
        val setup = grants.issue(grant.copy(kind = TotpGrantKind.CONFIRM, secret = secret, recoveryCodes = recovery))
        val companyName = companies.findById(grant.account.companyId).orElseThrow().name
        val label =
            "Scheduler:" +
                companyName.replace(":", " ") +
                " (" +
                grant.account.companyId +
                ") / " +
                grant.account.email.replace(":", " ")
        val uri = "otpauth://totp/${encode(label)}?secret=$secret&issuer=Scheduler&algorithm=SHA1&digits=6&period=30"
        return TotpSetupResponse(setup, secret, uri, grant.expiresAt, recovery.takeIf { it.isNotEmpty() })
    }

    fun verify(token: String, code: String, browser: String?, ip: String): StaffAuthenticationResult {
        val grant = read(token, browser, ip, TotpGrantKind.VERIFY)
        grants.verificationAttempt(token, grant.account.accountId)
        val principal = prove(token, grant) { transactions.verify(grant, code) }
        return authenticated(principal, grant)
    }

    fun confirm(token: String, code: String, browser: String?, ip: String): StaffAuthenticationResult {
        val grant = read(token, browser, ip, TotpGrantKind.CONFIRM)
        grants.verificationAttempt(token, grant.account.accountId)
        val principal = prove(token, grant) { transactions.confirm(grant, code) }
        return authenticated(principal, grant)
    }

    fun recover(token: String, code: String, browser: String?, ip: String): StaffLoginResponse {
        val grant = read(token, browser, ip, TotpGrantKind.VERIFY)
        grants.verificationAttempt(token, grant.account.accountId)
        transactions.recover(grant, token, code)
        grants.verificationSucceeded(token, grant.account.accountId)
        return StaffLoginResponse(
            StaffLoginStatus.TOTP_SETUP_REQUIRED,
            challengeToken = token,
            expiresAt = grant.expiresAt,
            recoveryAvailable = false,
            companyId = grant.account.companyId,
        )
    }

    fun reset(owner: CustomUserDetails, employee: UUID, request: TotpResetRequest, ip: String) {
        grants.admitIp(ip)
        // Account-level admission also covers repeated reauthentication attempts from a valid session.
        grants.verificationAttempt("reset:${owner.employmentId}", requireNotNull(owner.employmentId))
        transactions.reset(owner, employee, request.currentPassword, request.code)
        grants.verificationSucceeded("reset:${owner.employmentId}", requireNotNull(owner.employmentId))
    }

    /** Claim before row-lock waits: concurrent completions cannot both rotate the anonymous session. */
    private fun <T> prove(token: String, grant: TotpGrant, action: () -> T): T {
        grants.consume(token)
        try {
            val result = action()
            grants.verificationSucceeded(token, grant.account.accountId)
            return result
        } catch (error: ApiException) {
            if (error.code == ErrorCode.INVALID_CREDENTIALS) grants.restore(token, grant)
            throw error
        }
    }

    private fun read(token: String, browser: String?, ip: String, vararg kinds: TotpGrantKind): TotpGrant {
        val grant = grants.read(token, browser, ip)
        if (grant.kind !in kinds) TotpGrantStore.expired()
        checkGeneration(grant.account)
        return grant
    }

    private fun checkGeneration(account: VerifiedStaffAccount) {
        if (sessions.staffGeneration(account.accountId) != account.generation) TotpGrantStore.expired()
    }

    private fun response(grant: TotpGrant) =
        StaffLoginResponse(
            if (grant.kind == TotpGrantKind.VERIFY) StaffLoginStatus.TOTP_REQUIRED
            else StaffLoginStatus.TOTP_SETUP_REQUIRED,
            challengeToken = grants.issue(grant),
            expiresAt = grant.expiresAt,
            recoveryAvailable = grant.account.role == "OWNER" && grant.kind == TotpGrantKind.VERIFY,
            companyId = grant.account.companyId,
        )

    private fun authenticated(principal: CustomUserDetails, grant: TotpGrant): StaffAuthenticationResult {
        checkGeneration(grant.account)
        val attributes = sessions.sessionAttributes(principal)
        if (attributes[AccountSessionRegistry.GENERATION_ATTRIBUTE] != grant.account.generation)
            TotpGrantStore.expired()
        return StaffAuthenticationResult(
            StaffLoginResponse(StaffLoginStatus.AUTHENTICATED),
            principal,
            attributes + (AccountSessionRegistry.MFA_ATTRIBUTE to principal.authVersion),
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
