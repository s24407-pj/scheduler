package pl.kacosmetology.scheduler.auth

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.auth.dto.AuthResponse
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
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
    private val loginRateLimiter: LoginRateLimiter
) {

    private val secureRandom = SecureRandom()

    /** Generates a 6-digit OTP code, stores it in Redis with TTL and sends it via SMS. */
    fun requestCode(request: RequestCodeRequest) {
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
    fun verifyCode(request: VerifyCodeRequest): AuthResponse {
        val storedCode = otpStore.getCode(request.phoneNumber)
            ?: throw IllegalArgumentException("Brak kodu dla tego numeru lub kod wygasł")

        if (storedCode != request.code) {
            throw IllegalArgumentException("Nieprawidłowy kod")
        }

        var user = userRepository.findByPhoneNumber(request.phoneNumber)

        if (user == null) {
            requireNotNull(request.firstName) { "Imię jest wymagane przy pierwszej rejestracji" }
            requireNotNull(request.lastName) { "Nazwisko jest wymagane przy pierwszej rejestracji" }

            user = userRepository.save(
                User(
                    phoneNumber = request.phoneNumber,
                    firstName = request.firstName,
                    lastName = request.lastName
                )
            )
        }

        otpStore.deleteCode(request.phoneNumber)

        val userDetails = CustomUserDetails(
            user = user,
            companyId = null,
            authorities = listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))
        )

        return AuthResponse(jwtService.generateToken(userDetails, null))
    }

    /**
     * Authenticates a staff member using email and password.
     * Returns a JWT token containing the company ID and employee roles.
     * Throws [RateLimitExceededException] if [clientIp] has exceeded the allowed login attempt rate.
     */
    @Transactional(readOnly = true)
    fun loginStaff(request: StaffLoginRequest, clientIp: String): AuthResponse {
        if (!loginRateLimiter.checkAndIncrement(clientIp)) {
            throw RateLimitExceededException()
        }
        val user = userRepository.findByEmail(request.email)
            ?: throw IllegalArgumentException("Nieprawidłowy email lub hasło")

        if (user.passwordHash == null) {
            throw IllegalArgumentException("To konto obsługuje tylko logowanie SMS")
        }

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw IllegalArgumentException("Nieprawidłowy email lub hasło")
        }

        val employments = companyEmployeeRepository.findAllByUserId(user.id)
        val companyId = employments.firstOrNull()?.companyId

        val authorities = mutableListOf<GrantedAuthority>(SimpleGrantedAuthority("ROLE_CUSTOMER"))
        employments.forEach { employment ->
            authorities.add(SimpleGrantedAuthority("ROLE_${employment.role.uppercase()}"))
        }

        val userDetails = CustomUserDetails(user = user, companyId = companyId, authorities = authorities)
        return AuthResponse(jwtService.generateToken(userDetails, companyId))
    }
}