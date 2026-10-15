package pl.kacosmetology.scheduler.auth

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffEmploymentOption
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginResponse
import pl.kacosmetology.scheduler.auth.dto.StaffLoginStatus
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
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
    private val companyRepository: CompanyRepository,
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
        val user =
            userRepository.findByPhoneNumber(phoneNumber)
                ?: userRepository.save(User(phoneNumber = phoneNumber, firstName = "", lastName = ""))
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

    /**
     * Authenticates a staff member using email and password. Returns an identity scoped to one employment, or available
     * employments when a selection is required. Throws [RateLimitExceededException] if [clientIp] has exceeded the
     * allowed login attempt rate.
     */
    @Transactional(readOnly = true)
    fun loginStaff(request: StaffLoginRequest, clientIp: String): StaffAuthenticationResult {
        if (!loginRateLimiter.checkAndIncrement(clientIp)) {
            rateLimited("login", clientIp)
        }
        val user = userRepository.findByEmail(request.email)
        val passwordHash = user?.passwordHash
        if (user == null || passwordHash == null) {
            passwordEncoder.matches(request.password, dummyPasswordHash)
            log.info("Staff login failed: unknown account or no password, ip={}", clientIp)
            throw IllegalArgumentException(INVALID_CREDENTIALS_MESSAGE)
        }

        if (!passwordEncoder.matches(request.password, passwordHash)) {
            log.info("Staff login failed: wrong password, userId={}, ip={}", user.id, clientIp)
            throw IllegalArgumentException(INVALID_CREDENTIALS_MESSAGE)
        }

        val userId = requireNotNull(user.id) { "Persisted staff user must have an ID" }
        val employments = companyEmployeeRepository.findAllByUserId(userId)
        if (employments.isEmpty()) {
            throw IllegalArgumentException("Użytkownik nie ma przypisania do firmy")
        }

        if (employments.size > 1 && request.employmentId == null) {
            val companies =
                companyRepository.findAllById(employments.map { it.companyId }).associateBy { requireNotNull(it.id) }
            val options =
                employments
                    .map { employment ->
                        val company =
                            companies[employment.companyId]
                                ?: throw IllegalStateException("Firma przypisana do zatrudnienia nie istnieje")
                        StaffEmploymentOption(
                            employmentId = requireNotNull(employment.id),
                            companyId = employment.companyId,
                            companyName = company.name,
                            role = employment.role,
                        )
                    }
                    .sortedWith(compareBy<StaffEmploymentOption> { it.companyName }.thenBy { it.companyId })
            return StaffAuthenticationResult(
                StaffLoginResponse(StaffLoginStatus.EMPLOYMENT_SELECTION_REQUIRED, options),
                null,
            )
        }

        val selected =
            if (request.employmentId == null) {
                employments.single()
            } else {
                employments.find { it.id == request.employmentId }
                    ?: throw IllegalArgumentException("Nieprawidłowy wybór zatrudnienia")
            }
        log.info("Staff login succeeded: userId={}, employmentId={}, ip={}", userId, selected.id, clientIp)
        require(selected.role in setOf("OWNER", "EMPLOYEE")) { "Nieprawidłowa rola zatrudnienia" }
        return StaffAuthenticationResult(
            StaffLoginResponse(StaffLoginStatus.AUTHENTICATED, emptyList()),
            CustomUserDetails(
                userId,
                user.phoneNumber,
                "ROLE_${selected.role}",
                selected.companyId,
                requireNotNull(selected.id),
            ),
        )
    }
}

/** Internal login outcome; the identity is never serialized as an authentication response. */
data class StaffAuthenticationResult(val response: StaffLoginResponse, val principal: CustomUserDetails?)
