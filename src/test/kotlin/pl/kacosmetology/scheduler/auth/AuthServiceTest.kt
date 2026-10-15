package pl.kacosmetology.scheduler.auth

import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

private const val DUMMY_HASH = "dummy-hash"

@ExtendWith(MockKExtension::class)
class AuthServiceTest {

    @MockK private lateinit var userRepository: UserRepository

    @MockK private lateinit var otpStore: OtpStore

    @MockK private lateinit var eventPublisher: ApplicationEventPublisher

    @MockK private lateinit var passwordEncoder: PasswordEncoder

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var companyRepository: CompanyRepository

    @MockK private lateinit var loginRateLimiter: LoginRateLimiter

    @MockK private lateinit var otpVerificationRateLimiter: OtpVerificationRateLimiter

    @MockK private lateinit var otpRequestRateLimiter: OtpRequestRateLimiter

    @InjectMockKs private lateinit var authService: AuthService

    private val testIp = "127.0.0.1"

    private val testPhone = "+48111222333"

    @Test
    fun `requestCode normalizes phone and publishes after commit dispatch event`() {
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
    fun `loginStaff should not reveal that a customer account without password exists`() {
        // GIVEN
        val request = StaffLoginRequest("klient@mail.com", "haslo123")
        val customer =
            User(
                phoneNumber = testPhone,
                firstName = "A",
                lastName = "B",
                email = "klient@mail.com",
                passwordHash = null,
            )

        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns customer
        stubDummyPasswordCheck()

        // WHEN & THEN
        val exception =
            assertThrows<IllegalArgumentException> {
                authService.loginStaff(request, testIp)
            }
        assertEquals("Nieprawidłowy email lub hasło", exception.message)
        verify(exactly = 1) { passwordEncoder.matches("haslo123", DUMMY_HASH) }
    }

    @Test
    fun `loginStaff should throw when email does not exist`() {
        // GIVEN
        val request = StaffLoginRequest("nieistnieje@mail.com", "haslo123")
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns null
        stubDummyPasswordCheck()

        // WHEN & THEN
        val exception =
            assertThrows<IllegalArgumentException> {
                authService.loginStaff(request, testIp)
            }
        assertEquals("Nieprawidłowy email lub hasło", exception.message)
        verify(exactly = 1) { passwordEncoder.matches("haslo123", DUMMY_HASH) }
    }

    @Test
    fun `loginStaff should throw when password is incorrect`() {
        // GIVEN
        val request = StaffLoginRequest("pracownik@mail.com", "zlehaslo")
        val employee =
            User(
                phoneNumber = testPhone,
                firstName = "A",
                lastName = "B",
                email = "pracownik@mail.com",
                passwordHash = "hashed_password",
            )

        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns employee
        every { passwordEncoder.matches("zlehaslo", "hashed_password") } returns false

        // WHEN & THEN
        val exception =
            assertThrows<IllegalArgumentException> {
                authService.loginStaff(request, testIp)
            }
        assertEquals("Nieprawidłowy email lub hasło", exception.message)
    }

    @Test
    fun `loginStaff should return same error message for non-existent email and wrong password`() {
        // GIVEN - Oba scenariusze powinny zwracać identyczny komunikat (bezpieczeństwo — nie ujawniamy czy email
        // istnieje)
        val requestBadEmail = StaffLoginRequest("nieistnieje@mail.com", "haslo123")
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail("nieistnieje@mail.com") } returns null
        stubDummyPasswordCheck()

        val requestBadPassword = StaffLoginRequest("istnieje@mail.com", "zlehaslo")
        val existingUser =
            User(
                phoneNumber = testPhone,
                firstName = "A",
                lastName = "B",
                email = "istnieje@mail.com",
                passwordHash = "hashed",
            )
        every { userRepository.findByEmail("istnieje@mail.com") } returns existingUser
        every { passwordEncoder.matches("zlehaslo", "hashed") } returns false

        // WHEN
        val exBadEmail = assertThrows<IllegalArgumentException> { authService.loginStaff(requestBadEmail, testIp) }
        val exBadPassword =
            assertThrows<IllegalArgumentException> { authService.loginStaff(requestBadPassword, testIp) }

        // THEN - Komunikaty MUSZĄ być identyczne
        assertEquals(
            exBadEmail.message,
            exBadPassword.message,
            "Komunikat dla złego emaila i hasła musi być identyczny aby nie ujawniać czy email istnieje",
        )
    }

    @Test
    fun `verifyCode should login existing user without creating duplicate`() {
        val request = VerifyCodeRequest(testPhone, "123456")
        val existingUser = User(id = 1, phoneNumber = testPhone, firstName = "Jan", lastName = "Kowalski")
        every { otpVerificationRateLimiter.check(testIp) } returns RateLimitDecision(true, 0)
        every { otpStore.verifyAndConsumeCode(testPhone, request.code) } returns OtpVerificationResult.VERIFIED
        every { userRepository.findByPhoneNumber(testPhone) } returns existingUser
        val response = authService.verifyCode(request, testIp)
        assertEquals(1L, response.id)
        assertEquals("ROLE_CUSTOMER", response.role)
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 1) { otpStore.verifyAndConsumeCode(testPhone, request.code) }
    }

    @Test
    fun `loginStaff should return identity for the only employment and exact role`() {
        val request = StaffLoginRequest("owner@mail.com", "password123")
        val user = staffUser()
        val employment = CompanyEmployee(11L, 22L, user.id!!, "OWNER")
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns user
        every { passwordEncoder.matches(request.password, user.passwordHash) } returns true
        every { companyEmployeeRepository.findAllByUserId(user.id!!) } returns listOf(employment)

        val response = authService.loginStaff(request, testIp)

        assertEquals("AUTHENTICATED", response.response.status.name)
        assertEquals(11L, response.principal?.employmentId)
        assertEquals("ROLE_OWNER", response.principal?.role)
        assertEquals(emptyList<Any>(), response.response.employments)
    }

    @Test
    fun `loginStaff should return sorted options for multiple employments without selection`() {
        val request = StaffLoginRequest("owner@mail.com", "password123")
        val user = staffUser()
        val employmentB = CompanyEmployee(12L, 32L, user.id!!, "OWNER")
        val employmentA = CompanyEmployee(11L, 31L, user.id!!, "EMPLOYEE")
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns user
        every { passwordEncoder.matches(request.password, user.passwordHash) } returns true
        every { companyEmployeeRepository.findAllByUserId(user.id!!) } returns listOf(employmentB, employmentA)
        every { companyRepository.findAllById(listOf(32L, 31L)) } returns
            listOf(Company(id = 32L, name = "Zulu"), Company(id = 31L, name = "Alpha"))

        val response = authService.loginStaff(request, testIp)

        assertEquals("EMPLOYMENT_SELECTION_REQUIRED", response.response.status.name)
        assertNull(response.principal)
        assertEquals(listOf(11L, 12L), response.response.employments.map { it.employmentId })
    }

    @Test
    fun `loginStaff should return identity only for selected employment`() {
        val user = staffUser()
        val employee = CompanyEmployee(11L, 31L, user.id!!, "EMPLOYEE")
        val owner = CompanyEmployee(12L, 32L, user.id!!, "OWNER")
        val request = StaffLoginRequest("owner@mail.com", "password123", employmentId = 11L)
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns user
        every { passwordEncoder.matches(request.password, user.passwordHash) } returns true
        every { companyEmployeeRepository.findAllByUserId(user.id!!) } returns listOf(employee, owner)

        val response = authService.loginStaff(request, testIp)

        assertEquals(11L, response.principal?.employmentId)
        assertEquals("ROLE_EMPLOYEE", response.principal?.role)
    }

    @Test
    fun `loginStaff should reject unknown or foreign employment selection`() {
        val user = staffUser()
        val request = StaffLoginRequest("owner@mail.com", "password123", employmentId = 99L)
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns user
        every { passwordEncoder.matches(request.password, user.passwordHash) } returns true
        every { companyEmployeeRepository.findAllByUserId(user.id!!) } returns
            listOf(CompanyEmployee(11L, 31L, user.id!!, "EMPLOYEE"))

        assertThrows<IllegalArgumentException> { authService.loginStaff(request, testIp) }
    }

    @Test
    fun `loginStaff should reject user without employment`() {
        val user = staffUser()
        val request = StaffLoginRequest("owner@mail.com", "password123")
        every { loginRateLimiter.checkAndIncrement(testIp) } returns true
        every { userRepository.findByEmail(request.email) } returns user
        every { passwordEncoder.matches(request.password, user.passwordHash) } returns true
        every { companyEmployeeRepository.findAllByUserId(user.id!!) } returns emptyList()

        assertThrows<IllegalArgumentException> { authService.loginStaff(request, testIp) }
    }

    private fun stubDummyPasswordCheck() {
        every { passwordEncoder.encode(any()) } returns DUMMY_HASH
        every { passwordEncoder.matches(any(), DUMMY_HASH) } returns false
    }

    private fun staffUser() =
        User(
            id = 7L,
            phoneNumber = testPhone,
            firstName = "A",
            lastName = "B",
            email = "owner@mail.com",
            passwordHash = "hash",
        )
}
