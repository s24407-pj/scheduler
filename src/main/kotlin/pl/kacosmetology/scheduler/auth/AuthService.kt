package pl.kacosmetology.scheduler.auth

import java.util.Locale
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginResponse
import pl.kacosmetology.scheduler.auth.dto.StaffLoginStatus
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

/** Handles authentication flows: SMS OTP for customers and email/password for staff. */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val otpStore: OtpStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val passwordEncoder: PasswordEncoder,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val accountSessions: AccountSessionRegistry,
    private val loginRateLimiter: LoginRateLimiter,
    private val otpVerificationRateLimiter: OtpVerificationRateLimiter,
    private val otpRequestRateLimiter: OtpRequestRateLimiter,
) {

    companion object {
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
        val code = otpStore.issueCode(phoneNumber)
        eventPublisher.publishEvent(OtpIssuedEvent(phoneNumber, code))
    }

    /** Consumes an OTP once and creates only a global phone authentication account, without salon profile data. */
    @Transactional
    fun verifyCode(request: VerifyCodeRequest, clientIp: String): CustomUserDetails {
        val limit = otpVerificationRateLimiter.check(clientIp)
        if (!limit.allowed) throw RateLimitExceededException(retryAfterSeconds = limit.retryAfterSeconds)
        val phoneNumber = canonicalPhone(request.phoneNumber)
        requireVerified(otpStore.verifyAndConsumeCode(phoneNumber, request.code), clientIp)
        eventPublisher.publishEvent(OtpConsumedEvent(phoneNumber, request.code))
        val user = userRepository.findByPhoneNumber(phoneNumber) ?: userRepository.save(User(phoneNumber = phoneNumber))
        return CustomUserDetails(requireNotNull(user.id), user.phoneNumber, "ROLE_CUSTOMER")
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
        val principal =
            CustomUserDetails(
                account.userId,
                requireNotNull(account.loginEmail),
                "ROLE_${account.role}",
                account.companyId,
                requireNotNull(account.id),
                account.authVersion,
            )
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
