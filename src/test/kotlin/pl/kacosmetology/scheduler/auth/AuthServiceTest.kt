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
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.CompanyCustomer
import pl.kacosmetology.scheduler.user.CompanyCustomerRepository

private const val DUMMY_HASH = "dummy-hash"

@ExtendWith(MockKExtension::class)
class AuthServiceTest {

    @MockK private lateinit var userRepository: CompanyCustomerRepository

    @MockK private lateinit var otpStore: OtpStore

    @MockK(relaxUnitFun = true) private lateinit var eventPublisher: ApplicationEventPublisher

    @MockK private lateinit var passwordEncoder: PasswordEncoder

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var accountSessions: pl.kacosmetology.scheduler.security.AccountSessionRegistry

    @MockK private lateinit var loginRateLimiter: LoginRateLimiter

    @MockK private lateinit var otpVerificationRateLimiter: OtpVerificationRateLimiter

    @MockK private lateinit var otpRequestRateLimiter: OtpRequestRateLimiter

    @MockK private lateinit var selections: StaffLoginSelectionStore

    @MockK private lateinit var companyRepository: pl.kacosmetology.scheduler.company.CompanyRepository

    @MockK private lateinit var credentials: StaffCredentialTransactions

    @InjectMockKs private lateinit var authService: AuthService

    @org.junit.jupiter.api.BeforeEach
    fun companyExists() {
        every { companyRepository.existsById(testId(9)) } returns true
    }

    private val testIp = "127.0.0.1"

    private val testPhone = "+48111222333"

    @Test
    fun `requestCode uses explicit international phone and publishes durable issuance intent`() {
        every { otpRequestRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        every { otpStore.issueCode(testId(9), testPhone) } returns "123456"
        every { eventPublisher.publishEvent(any<OtpIssuedEvent>()) } just Runs

        authService.requestCode(RequestCodeRequest(testId(9), testPhone), testIp)

        verify(exactly = 1) { otpStore.issueCode(testId(9), testPhone) }
        verify {
            eventPublisher.publishEvent(match<OtpIssuedEvent> { it.phoneNumber == testPhone && it.code == "123456" })
        }
    }

    @Test
    fun `requestCode does not issue when IP limit exceeded`() {
        every { otpRequestRateLimiter.check(testIp) } returns RateLimitDecision(false, 900)
        val failure =
            assertThrows<RateLimitExceededException> {
                authService.requestCode(RequestCodeRequest(testId(9), testPhone), testIp)
            }
        assertEquals(900L, failure.retryAfterSeconds)
        verify(exactly = 0) { otpStore.issueCode(testId(9), any()) }
    }

    @Test
    fun `requestCode does not dispatch when phone cooldown exceeded`() {
        every { otpRequestRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        every { otpStore.issueCode(testId(9), testPhone) } throws RateLimitExceededException(retryAfterSeconds = 60)
        assertThrows<RateLimitExceededException> {
            authService.requestCode(RequestCodeRequest(testId(9), testPhone), testIp)
        }
        verify(exactly = 0) { eventPublisher.publishEvent(any<OtpIssuedEvent>()) }
    }

    @Test
    fun `verifyCode does not lookup identity when OTP is invalid missing or exhausted`() {
        every { otpVerificationRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        listOf(OtpVerificationResult.INVALID, OtpVerificationResult.EXPIRED_OR_MISSING).forEach { result ->
            every { otpStore.verifyAndConsumeCode(testId(9), testPhone, "123456") } returns result
            assertThrows<ApiException> {
                authService.verifyCode(VerifyCodeRequest(testId(9), testPhone, "123456"), testIp)
            }
        }
        every { otpStore.verifyAndConsumeCode(testId(9), testPhone, "123456") } returns
            OtpVerificationResult.ATTEMPTS_EXCEEDED
        assertThrows<RateLimitExceededException> {
            authService.verifyCode(VerifyCodeRequest(testId(9), testPhone, "123456"), testIp)
        }
        verify(exactly = 0) { userRepository.findByCompanyIdAndPhoneNumber(testId(9), any()) }
    }

    @Test
    fun `verifyCode rejects IP limit before consuming code`() {
        every { otpVerificationRateLimiter.check(testIp) } returns RateLimitDecision(false, 300)
        assertThrows<RateLimitExceededException> {
            authService.verifyCode(VerifyCodeRequest(testId(9), testPhone, "123456"), testIp)
        }
        verify(exactly = 0) { otpStore.verifyAndConsumeCode(testId(9), any(), any()) }
    }

    @Test
    fun `verifyCode creates name-free account using canonical phone`() {
        every { otpVerificationRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        every { otpStore.verifyAndConsumeCode(testId(9), testPhone, "123456") } returns OtpVerificationResult.VERIFIED
        every { userRepository.insertIfPhoneAbsent(testId(9), testPhone) } returns 1
        every { userRepository.findByCompanyIdAndPhoneNumber(testId(9), testPhone) } returns
            CompanyCustomer(
                companyId = testId(9),
                id = testId(1),
                phoneNumber = testPhone,
                firstName = "",
                lastName = "",
            )

        val response = authService.verifyCode(VerifyCodeRequest(testId(9), testPhone, "123456"), testIp)

        assertEquals(testId(1), response.id)
        assertEquals("ROLE_CUSTOMER", response.role)
        verify(exactly = 1) {
            userRepository.insertIfPhoneAbsent(testId(9), testPhone)
        }
        verify(exactly = 1) { otpStore.verifyAndConsumeCode(testId(9), testPhone, "123456") }
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
        val request = StaffLoginRequest("  STAFF@Salon.PL ", "password123", testId(9))
        val account =
            CompanyEmployee(
                id = testId(31),
                companyId = testId(9),
                userId = testId(15),
                role = "EMPLOYEE",
                loginEmail = "staff@salon.pl",
                passwordHash = "hash",
            )
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { companyEmployeeRepository.findLoginForUpdate(testId(9), "staff@salon.pl") } returns account
        every { accountSessions.sessionAttributes(any()) } returns mapOf("generation" to 0L)
        every { passwordEncoder.matches("password123", "hash") } returns true
        val result = authService.loginStaff(request, testIp)
        assertEquals(testId(31), result.principal.employmentId)
        assertEquals("staff@salon.pl", result.principal.username)
        verify(exactly = 0) { userRepository.findByCompanyIdAndPhoneNumber(testId(9), any()) }
        io.mockk.verifyOrder {
            accountSessions.sessionAttributes(any())
            passwordEncoder.matches("password123", "hash")
        }
    }

    @Test
    fun `email first has a fixed bounded bcrypt budget even for missing and overflowing candidates`() {
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { loginRateLimiter.checkEmail("staff@salon.pl") } returns true
        every { passwordEncoder.encode(any()) } returns DUMMY_HASH
        every { passwordEncoder.matches("password123", DUMMY_HASH) } returns false
        repeat(2) {
            every { credentials.snapshots("staff@salon.pl") } returns emptyList()
            every { credentials.revalidate(emptyList()) } just Runs
            assertThrows<org.springframework.security.authentication.BadCredentialsException> {
                authService.loginStaffEmail(
                    pl.kacosmetology.scheduler.auth.dto.StaffEmailLoginRequest(" STAFF@SALON.PL ", "password123"),
                    testIp,
                    { "browser" },
                )
            }
        }
        verify(exactly = 2 * AuthService.MAX_EMAIL_CANDIDATES) { passwordEncoder.matches("password123", DUMMY_HASH) }
        verify(exactly = 0) { companyEmployeeRepository.findAccountForUpdate(any()) }
        verify(exactly = 2) { loginRateLimiter.checkAndIncrement(testIp) }
        verify(exactly = 2) { loginRateLimiter.checkEmail("staff@salon.pl") }
    }

    @Test
    fun `one matching account uses five comparisons and locks only the password match`() {
        val valid =
            StaffCredentialSnapshot(testId(1), testId(2), testId(3), "staff@salon.pl", "hash", true, "EMPLOYEE", 0)
        val disabled = valid.copy(id = testId(4), enabled = false)
        val wrong = valid.copy(id = testId(5), passwordHash = "wrong-hash")
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { loginRateLimiter.checkEmail("staff@salon.pl") } returns true
        every { credentials.snapshots("staff@salon.pl") } returns listOf(valid, disabled, wrong)
        every { credentials.revalidate(listOf(valid)) } just Runs
        every { passwordEncoder.encode(any()) } returns DUMMY_HASH
        every { passwordEncoder.matches("password123", any()) } answers { secondArg<String>() == "hash" }
        every { accountSessions.sessionAttributes(any()) } returns
            mapOf(pl.kacosmetology.scheduler.security.AccountSessionRegistry.GENERATION_ATTRIBUTE to 0L)
        every { accountSessions.isCurrent(any(), any()) } returns true
        val result =
            authService.loginStaffEmail(
                pl.kacosmetology.scheduler.auth.dto.StaffEmailLoginRequest("staff@salon.pl", "password123"),
                testIp,
                { error("No selection needed") },
            )
        assertEquals(valid.id, result.authentication!!.principal.employmentId)
        verify(exactly = 5) { passwordEncoder.matches("password123", any()) }
        verify(exactly = 3) { passwordEncoder.matches("password123", DUMMY_HASH) }
        verify(exactly = 1) { credentials.revalidate(listOf(valid)) }
        verify(exactly = 0) { selections.issue(any(), any()) }
    }

    @Test
    fun `missing disabled and passwordless staff use same failed authentication`() {
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { passwordEncoder.encode(any()) } returns DUMMY_HASH
        every { passwordEncoder.matches("password123", DUMMY_HASH) } returns false
        for (account in
            listOf(
                null,
                CompanyEmployee(
                    id = testId(1),
                    companyId = testId(9),
                    userId = testId(1),
                    role = "EMPLOYEE",
                    enabled = false,
                ),
                CompanyEmployee(id = testId(1), companyId = testId(9), userId = testId(1), role = "EMPLOYEE"),
            )) {
            every { companyEmployeeRepository.findLoginForUpdate(testId(9), "staff@salon.pl") } returns account
            assertThrows<org.springframework.security.authentication.BadCredentialsException> {
                authService.loginStaff(StaffLoginRequest("staff@salon.pl", "password123", testId(9)), testIp)
            }
        }
    }
}
