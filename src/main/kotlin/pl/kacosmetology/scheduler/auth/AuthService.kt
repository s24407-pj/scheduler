package pl.kacosmetology.scheduler.auth

import java.util.*
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.auth.dto.*
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.CompanyCustomerRepository

/** Handles authentication flows: SMS OTP for customers and email/password for staff. */
@Service
class AuthService(
    private val userRepository: CompanyCustomerRepository,
    private val otpStore: OtpStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val passwordEncoder: PasswordEncoder,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val accountSessions: AccountSessionRegistry,
    private val loginRateLimiter: LoginRateLimiter,
    private val otpVerificationRateLimiter: OtpVerificationRateLimiter,
    private val otpRequestRateLimiter: OtpRequestRateLimiter,
    private val selections: StaffLoginSelectionStore,
    private val companyRepository: CompanyRepository,
    private val credentials: StaffCredentialTransactions,
) {

    companion object {
        /** Maximum BCrypt checks per email-first attempt; overflow uses company-scoped login. */
        const val MAX_EMAIL_CANDIDATES = 5
        private const val INVALID_CREDENTIALS_MESSAGE = "Nieprawidłowy email lub hasło"
    }

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * Compared against when the account is unknown or has no password, so every failed login costs one bcrypt check.
     */
    private val dummyPasswordHash: String by lazy {
        requireNotNull(passwordEncoder.encode("dummy-password-for-timing-equalization"))
    }

    /** Issues a six-digit OTP atomically and sends it only after the surrounding transaction commits. */
    @Transactional
    fun requestCode(request: RequestCodeRequest, clientIp: String) {
        val limit = otpRequestRateLimiter.check(clientIp)
        if (!limit.allowed) throw RateLimitExceededException(retryAfterSeconds = limit.retryAfterSeconds)
        val phoneNumber = canonicalPhone(request.phoneNumber)
        requireCompany(request.companyId)
        val code = otpStore.issueCode(request.companyId, phoneNumber)
        eventPublisher.publishEvent(OtpIssuedEvent(request.companyId, phoneNumber, code))
    }

    /** Consumes an OTP once and creates a company-local phone identity, without establishing staff visibility. */
    @Transactional
    fun verifyCode(request: VerifyCodeRequest, clientIp: String): CustomUserDetails {
        val limit = otpVerificationRateLimiter.check(clientIp)
        if (!limit.allowed) throw RateLimitExceededException(retryAfterSeconds = limit.retryAfterSeconds)
        val phoneNumber = canonicalPhone(request.phoneNumber)
        requireCompany(request.companyId)
        requireVerified(otpStore.verifyAndConsumeCode(request.companyId, phoneNumber, request.code), clientIp)
        eventPublisher.publishEvent(OtpConsumedEvent(request.companyId, phoneNumber, request.code))
        userRepository.insertIfPhoneAbsent(request.companyId, phoneNumber)
        val user = checkNotNull(userRepository.findByCompanyIdAndPhoneNumber(request.companyId, phoneNumber))
        return CustomUserDetails(requireNotNull(user.id), user.phoneNumber, "ROLE_CUSTOMER", request.companyId)
    }

    private fun requireCompany(companyId: UUID) {
        if (!companyRepository.existsById(companyId)) throw ApiException(ErrorCode.NOT_FOUND, "Nie znaleziono firmy")
    }

    private fun canonicalPhone(value: String): String {
        val digits = value.trim().removePrefix("+")
        require(digits.matches(Regex("[0-9]{9,15}"))) { "Nieprawidłowy format numeru telefonu" }
        return "+$digits"
    }

    private fun rateLimited(limiter: String, clientIp: String): Nothing {
        log.warn("Rate limit exceeded: limiter={}, ip={}", limiter, clientIp)
        throw RateLimitExceededException()
    }

    private fun requireVerified(result: OtpVerificationResult, clientIp: String) {
        if (result != OtpVerificationResult.VERIFIED) {
            log.info("OTP verification failed: result={}, ip={}", result, clientIp)
        }
        when (result) {
            OtpVerificationResult.EXPIRED_OR_MISSING ->
                throw ApiException(ErrorCode.INVALID_CREDENTIALS, "Brak kodu dla tego numeru lub kod wygasł")
            OtpVerificationResult.INVALID -> throw ApiException(ErrorCode.INVALID_CREDENTIALS, "Nieprawidłowy kod")
            OtpVerificationResult.ATTEMPTS_EXCEEDED ->
                throw RateLimitExceededException("Zbyt wiele nieudanych prób kodu. Poproś o nowy kod.")
            OtpVerificationResult.VERIFIED -> Unit
        }
    }

    private fun staffPrincipal(account: CompanyEmployee): CustomUserDetails {
        return CustomUserDetails(
            account.userId,
            requireNotNull(account.loginEmail),
            "ROLE_${account.role}",
            account.companyId,
            requireNotNull(account.id),
            account.authVersion,
        )
    }

    /** Checks a fixed work budget outside transactions, then revalidates only matches under short row locks. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun loginStaffEmail(
        request: StaffEmailLoginRequest,
        clientIp: String,
        browserSession: () -> String,
    ): EmailStaffAuthenticationResult {
        if (!loginRateLimiter.checkAndIncrement(clientIp)) rateLimited("login", clientIp)
        val email = request.email.trim().lowercase(Locale.ROOT)
        if (!loginRateLimiter.checkEmail(email)) throw RateLimitExceededException()
        val snapshots = credentials.snapshots(email)
        // Capture revocation state before BCrypt, without holding any database locks.
        val candidates = snapshots.map { snapshot ->
            val eligible =
                snapshot.email == email &&
                    snapshot.enabled &&
                    snapshot.passwordHash != null &&
                    snapshot.role in setOf("OWNER", "EMPLOYEE")
            val principal =
                if (eligible)
                    CustomUserDetails(
                        snapshot.userId,
                        requireNotNull(snapshot.email),
                        "ROLE_${snapshot.role}",
                        snapshot.companyId,
                        snapshot.id,
                        snapshot.authVersion,
                    )
                else null
            Triple(snapshot, principal, principal?.let(accountSessions::sessionAttributes))
        }
        val matches = candidates.filter { candidate ->
            val (_, principal, _) = candidate
            passwordEncoder.matches(
                request.password,
                if (principal != null) requireNotNull(candidate.first.passwordHash) else dummyPasswordHash,
            ) && principal != null
        }
        repeat(MAX_EMAIL_CANDIDATES - candidates.size) {
            passwordEncoder.matches(request.password, dummyPasswordHash)
        }
        val verified = matches.map { (_, principal, attributes) ->
            StaffAuthenticationResult(
                StaffLoginResponse(StaffLoginStatus.AUTHENTICATED),
                requireNotNull(principal),
                requireNotNull(attributes),
            )
        }
        credentials.revalidate(matches.map { it.first })
        if (matches.any { it.third?.get(AccountSessionRegistry.GENERATION_ATTRIBUTE) != it.first.generation })
            throw BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        verified.forEach {
            if (!accountSessions.isAuthorized(it.principal, it.sessionAttributes::get))
                throw BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        }
        if (verified.isEmpty()) throw BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        if (verified.size == 1) return EmailStaffAuthenticationResult(verified.single().response, verified.single())
        val companies = verified.map {
            StaffLoginCompany(
                it.principal.requireCompanyId(),
                companyRepository.findById(it.principal.requireCompanyId()).orElseThrow().name,
            )
        }
        val token = selections.issue(verified.map { VerifiedStaffAccount.from(it) }, browserSession())
        return EmailStaffAuthenticationResult(
            StaffLoginResponse(StaffLoginStatus.COMPANY_SELECTION_REQUIRED, token, companies),
        )
    }

    /** Consumes the browser-bound grant before short locked validation; Redis calls run outside row locks. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun completeStaffLogin(
        request: CompleteStaffLoginRequest,
        clientIp: String,
        browserSession: String?,
    ): StaffAuthenticationResult {
        if (!loginRateLimiter.checkCompletion(clientIp)) rateLimited("login-completion", clientIp)
        val grant =
            selections.consume(request.selectionToken, browserSession)?.singleOrNull {
                it.companyId == request.companyId
            } ?: throw BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        val principal = credentials.revalidateSelection(grant)
        val attributes = accountSessions.sessionAttributes(principal)
        if (attributes[AccountSessionRegistry.GENERATION_ATTRIBUTE] != grant.generation)
            throw BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        return StaffAuthenticationResult(StaffLoginResponse(StaffLoginStatus.AUTHENTICATED), principal, attributes)
    }

    /** Authenticates one company-owned account; the row lock serializes validation with password reset/disable. */
    @Transactional
    fun loginStaff(request: StaffLoginRequest, clientIp: String): StaffAuthenticationResult {
        if (!loginRateLimiter.checkAndIncrement(clientIp)) rateLimited("login", clientIp)
        val email = request.email.trim().lowercase(Locale.ROOT)
        val companyId = request.companyId
        val account = companyId?.let { companyEmployeeRepository.findLoginForUpdate(it, email) }
        if (
            account == null ||
                !account.enabled ||
                account.passwordHash == null ||
                account.role !in setOf("OWNER", "EMPLOYEE")
        ) {
            passwordEncoder.matches(request.password, dummyPasswordHash)
            throw BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        }
        val principal = staffPrincipal(account)
        // Capture before password verification under the credential row lock. A subsequent reset cannot validate this
        // response with a new revocation generation, even when the browser's session is saved after reset commits.
        val attributes = accountSessions.sessionAttributes(principal)
        if (!passwordEncoder.matches(request.password, account.passwordHash))
            throw BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
        return StaffAuthenticationResult(StaffLoginResponse(StaffLoginStatus.AUTHENTICATED), principal, attributes)
    }
}

/** Internal login outcome; generation attributes and identity never become response credentials. */
data class StaffAuthenticationResult(
    val response: StaffLoginResponse,
    val principal: CustomUserDetails,
    val sessionAttributes: Map<String, Any>,
)

/** Internal outcome; only the response is serialized. */
data class EmailStaffAuthenticationResult(
    val response: StaffLoginResponse,
    val authentication: StaffAuthenticationResult? = null,
)
