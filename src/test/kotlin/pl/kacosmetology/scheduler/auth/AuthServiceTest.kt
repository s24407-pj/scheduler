package pl.kacosmetology.scheduler.auth

import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

private const val DUMMY_HASH = "dummy-hash"

@ExtendWith(MockKExtension::class)
class AuthServiceTest {

    @MockK private lateinit var userRepository: UserRepository

    @MockK private lateinit var otpStore: OtpStore

    @MockK(relaxUnitFun = true) private lateinit var eventPublisher: ApplicationEventPublisher

    @MockK private lateinit var passwordEncoder: PasswordEncoder

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var accountSessions: pl.kacosmetology.scheduler.security.AccountSessionRegistry

    @MockK private lateinit var loginRateLimiter: LoginRateLimiter

    @MockK private lateinit var otpVerificationRateLimiter: OtpVerificationRateLimiter

    @MockK private lateinit var otpRequestRateLimiter: OtpRequestRateLimiter

    @InjectMockKs private lateinit var authService: AuthService

    private val testIp = "127.0.0.1"

    private val testPhone = "+48111222333"

    @Test
    fun `requestCode normalizes phone and publishes durable issuance intent`() {
        every { otpRequestRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        every { otpStore.issueCode(testPhone) } returns "123456"
        every { eventPublisher.publishEvent(any<OtpIssuedEvent>()) } just Runs

        authService.requestCode(RequestCodeRequest(" 48111222333 "), testIp)

        verify(exactly = 1) { otpStore.issueCode(testPhone) }
        verify {
            eventPublisher.publishEvent(match<OtpIssuedEvent> { it.phoneNumber == testPhone && it.code == "123456" })
        }
    }

    @Test
    fun `requestCode does not issue when IP limit exceeded`() {
        every { otpRequestRateLimiter.check(testIp) } returns RateLimitDecision(false, 900)
        val failure =
            assertThrows<RateLimitExceededException> { authService.requestCode(RequestCodeRequest(testPhone), testIp) }
        assertEquals(900L, failure.retryAfterSeconds)
        verify(exactly = 0) { otpStore.issueCode(any()) }
    }

    @Test
    fun `requestCode does not dispatch when phone cooldown exceeded`() {
        every { otpRequestRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        every { otpStore.issueCode(testPhone) } throws RateLimitExceededException(retryAfterSeconds = 60)
        assertThrows<RateLimitExceededException> { authService.requestCode(RequestCodeRequest(testPhone), testIp) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<OtpIssuedEvent>()) }
    }

    @Test
    fun `verifyCode does not lookup identity when OTP is invalid missing or exhausted`() {
        every { otpVerificationRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        listOf(OtpVerificationResult.INVALID, OtpVerificationResult.EXPIRED_OR_MISSING).forEach { result ->
            every { otpStore.verifyAndConsumeCode(testPhone, "123456") } returns result
            assertThrows<ApiException> { authService.verifyCode(VerifyCodeRequest(testPhone, "123456"), testIp) }
        }
        every { otpStore.verifyAndConsumeCode(testPhone, "123456") } returns OtpVerificationResult.ATTEMPTS_EXCEEDED
        assertThrows<RateLimitExceededException> {
            authService.verifyCode(VerifyCodeRequest(testPhone, "123456"), testIp)
        }
        verify(exactly = 0) { userRepository.findByPhoneNumber(any()) }
    }

    @Test
    fun `verifyCode rejects IP limit before consuming code`() {
        every { otpVerificationRateLimiter.check(testIp) } returns RateLimitDecision(false, 300)
        assertThrows<RateLimitExceededException> {
            authService.verifyCode(VerifyCodeRequest(testPhone, "123456"), testIp)
        }
        verify(exactly = 0) { otpStore.verifyAndConsumeCode(any(), any()) }
    }

    @Test
    fun `verifyCode creates name-free account using canonical phone`() {
        every { otpVerificationRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        every { otpStore.verifyAndConsumeCode(testPhone, "123456") } returns OtpVerificationResult.VERIFIED
        every { userRepository.findByPhoneNumber(testPhone) } returns null
        every { userRepository.save(any()) } returns
            User(id = 1L, phoneNumber = testPhone, firstName = "", lastName = "")

        val response = authService.verifyCode(VerifyCodeRequest(" 48111222333 ", "123456"), testIp)

        assertEquals(1L, response.id)
        assertEquals("ROLE_CUSTOMER", response.role)
        verify(exactly = 1) {
            userRepository.save(match { it.phoneNumber == testPhone && it.firstName == "" && it.lastName == "" })
        }
        verify(exactly = 1) { otpStore.verifyAndConsumeCode(testPhone, "123456") }
        verify(exactly = 1) { eventPublisher.publishEvent(match<OtpConsumedEvent> { it.phoneNumber == testPhone }) }
    }

    @Test
    fun `loginStaff should throw RateLimitExceededException when limit exceeded`() {
        // GIVEN
        val request = StaffLoginRequest("pracownik@mail.com", "haslo123")
        every { loginRateLimiter.checkAndIncrement(testIp) } returns false

        // WHEN & THEN
        assertThrows<RateLimitExceededException> {
            authService.loginStaff(request, testIp)
        }
    }

    @Test
    fun `staff lookup uses company and canonical email without global customer lookup`() {
        val request = StaffLoginRequest("  STAFF@Salon.PL ", "password123", 9L)
        val account =
            CompanyEmployee(
                id = 31L,
                companyId = 9L,
                userId = 15L,
                role = "EMPLOYEE",
                loginEmail = "staff@salon.pl",
                passwordHash = "hash",
            )
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { companyEmployeeRepository.findLoginForUpdate(9L, "staff@salon.pl") } returns account
        every { accountSessions.sessionAttributes(any()) } returns mapOf("generation" to 0L)
        every { passwordEncoder.matches("password123", "hash") } returns true
        val result = authService.loginStaff(request, testIp)
        assertEquals(31L, result.principal.employmentId)
        assertEquals("staff@salon.pl", result.principal.username)
        verify(exactly = 0) { userRepository.findByPhoneNumber(any()) }
        io.mockk.verifyOrder {
            accountSessions.sessionAttributes(any())
            passwordEncoder.matches("password123", "hash")
        }
    }

    @Test
    fun `missing disabled and passwordless staff use same failed authentication`() {
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { passwordEncoder.encode(any()) } returns DUMMY_HASH
        every { passwordEncoder.matches("password123", DUMMY_HASH) } returns false
        for (account in
            listOf(
                null,
                CompanyEmployee(id = 1L, companyId = 9L, userId = 1L, role = "EMPLOYEE", enabled = false),
                CompanyEmployee(id = 1L, companyId = 9L, userId = 1L, role = "EMPLOYEE"),
            )) {
            every { companyEmployeeRepository.findLoginForUpdate(9L, "staff@salon.pl") } returns account
            assertThrows<org.springframework.security.authentication.BadCredentialsException> {
                authService.loginStaff(StaffLoginRequest("staff@salon.pl", "password123", 9L), testIp)
            }
        }
    }
}
