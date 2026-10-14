package pl.kacosmetology.scheduler.auth

import java.security.SecureRandom
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffEmploymentOption
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginResponse
import pl.kacosmetology.scheduler.auth.dto.StaffLoginStatus
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

/** Handles authentication flows: SMS OTP for customers and email/password for staff. */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val otpStore: OtpStore,
    private val smsSender: SmsSender,
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

    private val secureRandom = SecureRandom()

    /**
     * Compared against when the account is unknown or has no password, so every failed login costs one bcrypt check.
     */
    private val dummyPasswordHash: String by lazy {
        requireNotNull(passwordEncoder.encode("dummy-password-for-timing-equalization"))
    }

    /**
     * Generates a 6-digit OTP code, stores it in Redis with TTL and sends it via SMS. Throws
     * [RateLimitExceededException] if [clientIp] or the phone number exceeded its request limit.
     */
    fun requestCode(request: RequestCodeRequest, clientIp: String) {
        if (!otpRequestRateLimiter.checkAndIncrement(clientIp)) {
            rateLimited("otp-request", clientIp)
        }
        if (!otpStore.checkAndIncrementRateLimit(request.phoneNumber)) {
            rateLimited("otp-phone", clientIp)
        }

        val code = (100000 + secureRandom.nextInt(900000)).toString()
        otpStore.saveCode(request.phoneNumber, code)
        smsSender.sendOtp(request.phoneNumber, code)
    }

    /**
     * Verifies the OTP code and returns a safe authenticated identity. Creates a new user on first verification
     * (requires firstName and lastName).
     */
    @Transactional
    fun verifyCode(request: VerifyCodeRequest, clientIp: String): CustomUserDetails {
        if (!otpVerificationRateLimiter.checkAndIncrement(clientIp)) {
            rateLimited("otp-verify", clientIp)
        }

        requireVerified(otpStore.verifyCode(request.phoneNumber, request.code), clientIp)

        val existingUser = userRepository.findByPhoneNumber(request.phoneNumber)

        if (existingUser == null) {
            requireNotNull(request.firstName) { "Imię jest wymagane przy pierwszej rejestracji" }
            requireNotNull(request.lastName) { "Nazwisko jest wymagane przy pierwszej rejestracji" }
        }

        requireVerified(otpStore.verifyAndConsumeCode(request.phoneNumber, request.code), clientIp)

        val user =
            existingUser
                ?: userRepository.save(
                    User(
                        phoneNumber = request.phoneNumber,
                        firstName = request.firstName!!,
                        lastName = request.lastName!!,
                    ),
                )

        return CustomUserDetails(requireNotNull(user.id), user.phoneNumber, "ROLE_CUSTOMER")
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
                throw IllegalArgumentException("Brak kodu dla tego numeru lub kod wygasł")
            OtpVerificationResult.INVALID -> throw IllegalArgumentException("Nieprawidłowy kod")
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
