package pl.kacosmetology.scheduler.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.auth.dto.AuthResponse
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.StaffEmploymentOption
import pl.kacosmetology.scheduler.auth.dto.StaffLoginResponse
import pl.kacosmetology.scheduler.auth.dto.StaffLoginStatus
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.security.SessionStore
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.security.SecureRandom

/** Handles authentication flows: SMS OTP for customers and email/password for staff. */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val otpStore: OtpStore,
    private val smsSender: SmsSender,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val companyRepository: CompanyRepository,
    private val loginRateLimiter: LoginRateLimiter,
    private val otpVerificationRateLimiter: OtpVerificationRateLimiter,
    private val otpRequestRateLimiter: OtpRequestRateLimiter,
    private val sessionStore: SessionStore
) {

    companion object {
        private const val INVALID_CREDENTIALS_MESSAGE = "Nieprawidłowy email lub hasło"
    }

    private val secureRandom = SecureRandom()

    /** Compared against when the account is unknown or has no password, so every failed login costs one bcrypt check. */
    private val dummyPasswordHash: String by lazy {
        requireNotNull(passwordEncoder.encode("dummy-password-for-timing-equalization"))
    }

    /**
     * Generates a 6-digit OTP code, stores it in Redis with TTL and sends it via SMS.
     * Throws [RateLimitExceededException] if [clientIp] or the phone number exceeded its request limit.
     */
    fun requestCode(request: RequestCodeRequest, clientIp: String) {
        if (!otpRequestRateLimiter.checkAndIncrement(clientIp)) {
            throw RateLimitExceededException()
        }
        if (!otpStore.checkAndIncrementRateLimit(request.phoneNumber)) {
            throw RateLimitExceededException()
        }

        val code = (100000 + secureRandom.nextInt(900000)).toString()
        otpStore.saveCode(request.phoneNumber, code)
        smsSender.sendOtp(request.phoneNumber, code)
    }

    /**
     * Verifies the OTP code and returns a JWT token.
     * Creates a new user on first verification (requires firstName and lastName).
     */
    @Transactional
    fun verifyCode(request: VerifyCodeRequest, clientIp: String): AuthResponse {
        if (!otpVerificationRateLimiter.checkAndIncrement(clientIp)) {
            throw RateLimitExceededException()
        }

        requireVerified(otpStore.verifyCode(request.phoneNumber, request.code))

        val existingUser = userRepository.findByPhoneNumber(request.phoneNumber)

        if (existingUser == null) {
            requireNotNull(request.firstName) { "Imię jest wymagane przy pierwszej rejestracji" }
            requireNotNull(request.lastName) { "Nazwisko jest wymagane przy pierwszej rejestracji" }
        }

        requireVerified(otpStore.verifyAndConsumeCode(request.phoneNumber, request.code))

        val user = existingUser ?: userRepository.save(
            User(
                phoneNumber = request.phoneNumber,
                firstName = request.firstName!!,
                lastName = request.lastName!!
            )
        )

        return AuthResponse(jwtService.generateCustomerToken(user))
    }

    private fun requireVerified(result: OtpVerificationResult) {
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
     * Authenticates a staff member using email and password.
     * Returns a JWT scoped to one employment, or available employments when a selection is required.
     * Throws [RateLimitExceededException] if [clientIp] has exceeded the allowed login attempt rate.
     */
    @Transactional(readOnly = true)
    fun loginStaff(request: StaffLoginRequest, clientIp: String): StaffLoginResponse {
        if (!loginRateLimiter.checkAndIncrement(clientIp)) {
            throw RateLimitExceededException()
        }
        val user = userRepository.findByEmail(request.email)
        val passwordHash = user?.passwordHash
        if (user == null || passwordHash == null) {
            passwordEncoder.matches(request.password, dummyPasswordHash)
            throw IllegalArgumentException(INVALID_CREDENTIALS_MESSAGE)
        }

        if (!passwordEncoder.matches(request.password, passwordHash)) {
            throw IllegalArgumentException(INVALID_CREDENTIALS_MESSAGE)
        }

        val userId = requireNotNull(user.id) { "Persisted staff user must have an ID" }
        val employments = companyEmployeeRepository.findAllByUserId(userId)
        if (employments.isEmpty()) {
            throw IllegalArgumentException("Użytkownik nie ma przypisania do firmy")
        }

        if (employments.size > 1 && request.employmentId == null) {
            val companies = companyRepository.findAllById(employments.map { it.companyId })
                .associateBy { requireNotNull(it.id) }
            val options = employments.map { employment ->
                val company = companies[employment.companyId]
                    ?: throw IllegalStateException("Firma przypisana do zatrudnienia nie istnieje")
                StaffEmploymentOption(
                    employmentId = requireNotNull(employment.id),
                    companyId = employment.companyId,
                    companyName = company.name,
                    role = employment.role
                )
            }.sortedWith(compareBy<StaffEmploymentOption> { it.companyName }.thenBy { it.companyId })
            return StaffLoginResponse(StaffLoginStatus.EMPLOYMENT_SELECTION_REQUIRED, null, options)
        }

        val selected = if (request.employmentId == null) {
            employments.single()
        } else {
            employments.find { it.id == request.employmentId }
                ?: throw IllegalArgumentException("Nieprawidłowy wybór zatrudnienia")
        }
        return StaffLoginResponse(
            status = StaffLoginStatus.AUTHENTICATED,
            token = jwtService.generateStaffToken(user, selected),
            employments = emptyList()
        )
    }

    /** Revokes the session of the token that authenticated [userDetails]. */
    fun logout(userDetails: CustomUserDetails) {
        val sessionId = checkNotNull(userDetails.sessionId) { "Authenticated principal has no session" }
        sessionStore.revoke(sessionId, userDetails.id)
    }

    /** Revokes every session of the user behind [userDetails], on all devices and employments. */
    fun logoutAll(userDetails: CustomUserDetails) {
        sessionStore.revokeAll(userDetails.id)
    }
}
