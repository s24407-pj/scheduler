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
import org.springframework.security.crypto.password.PasswordEncoder
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

@ExtendWith(MockKExtension::class)
class AuthServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var otpStore: OtpStore

    @MockK
    private lateinit var smsSender: SmsSender

    @MockK
    private lateinit var jwtService: JwtService

    @MockK
    private lateinit var passwordEncoder: PasswordEncoder

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @InjectMockKs
    private lateinit var authService: AuthService

    private val testPhone = "+48111222333"

    @Test
    fun `requestCode should generate code, save in Redis and send SMS`() {
        // GIVEN
        val request = RequestCodeRequest(testPhone)
        every { otpStore.checkAndIncrementRateLimit(testPhone) } returns true
        every { otpStore.saveCode(eq(testPhone), any()) } just Runs
        every { smsSender.sendOtp(any(), any()) } just Runs

        // WHEN
        authService.requestCode(request)

        // THEN
        verify(exactly = 1) { otpStore.saveCode(eq(testPhone), any()) }
        verify(exactly = 1) { smsSender.sendOtp(testPhone, any()) }
    }

    @Test
    fun `requestCode should throw RateLimitExceededException when limit exceeded`() {
        // GIVEN
        val request = RequestCodeRequest(testPhone)
        every { otpStore.checkAndIncrementRateLimit(testPhone) } returns false

        // WHEN & THEN
        assertThrows<RateLimitExceededException> {
            authService.requestCode(request)
        }
        verify(exactly = 0) { smsSender.sendOtp(any(), any()) }
    }

    @Test
    fun `verifyCode should throw when code does not exist or expired`() {
        // GIVEN
        val request = VerifyCodeRequest(testPhone, "123456")
        every { otpStore.getCode(testPhone) } returns null

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            authService.verifyCode(request)
        }
        assertEquals("Brak kodu dla tego numeru lub kod wygasł", exception.message)
    }

    @Test
    fun `verifyCode should throw when code is invalid`() {
        // GIVEN
        val request = VerifyCodeRequest(testPhone, "111111") // Zły kod
        every { otpStore.getCode(testPhone) } returns "999999"

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            authService.verifyCode(request)
        }
        assertEquals("Nieprawidłowy kod", exception.message)
    }

    @Test
    fun `verifyCode should create new user and issue token when code is valid`() {
        // GIVEN
        val request = VerifyCodeRequest(testPhone, "123456", "Jan", "Kowalski")
        every { otpStore.getCode(testPhone) } returns "123456"
        every { userRepository.findByPhoneNumber(testPhone) } returns null // Użytkownika jeszcze nie ma

        val savedUser = User(id = 1, phoneNumber = testPhone, firstName = "Jan", lastName = "Kowalski")
        every { userRepository.save(any()) } returns savedUser

        every { otpStore.deleteCode(testPhone) } just Runs
        every { jwtService.generateToken(any(), null) } returns "DUMMY_JWT_TOKEN"

        // WHEN
        val response = authService.verifyCode(request)

        // THEN
        assertEquals("DUMMY_JWT_TOKEN", response.token)
        verify(exactly = 1) { userRepository.save(any()) }
        verify(exactly = 1) { otpStore.deleteCode(testPhone) }
    }

    @Test
    fun `loginStaff should throw for customer without password`() {
        // GIVEN
        val request = StaffLoginRequest("klient@mail.com", "haslo123")
        val customer = User(
            phoneNumber = testPhone,
            firstName = "A",
            lastName = "B",
            email = "klient@mail.com",
            passwordHash = null
        )

        every { userRepository.findByEmail(request.email) } returns customer

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            authService.loginStaff(request)
        }
        assertEquals("To konto obsługuje tylko logowanie SMS", exception.message)
    }

    @Test
    fun `loginStaff should throw when email does not exist`() {
        // GIVEN
        val request = StaffLoginRequest("nieistnieje@mail.com", "haslo123")
        every { userRepository.findByEmail(request.email) } returns null

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            authService.loginStaff(request)
        }
        assertEquals("Nieprawidłowy email lub hasło", exception.message)
    }

    @Test
    fun `loginStaff should throw when password is incorrect`() {
        // GIVEN
        val request = StaffLoginRequest("pracownik@mail.com", "zlehaslo")
        val employee = User(
            phoneNumber = testPhone,
            firstName = "A",
            lastName = "B",
            email = "pracownik@mail.com",
            passwordHash = "hashed_password"
        )

        every { userRepository.findByEmail(request.email) } returns employee
        every { passwordEncoder.matches("zlehaslo", "hashed_password") } returns false

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            authService.loginStaff(request)
        }
        assertEquals("Nieprawidłowy email lub hasło", exception.message)
    }

    @Test
    fun `loginStaff should return same error message for non-existent email and wrong password`() {
        // GIVEN - Oba scenariusze powinny zwracać identyczny komunikat (bezpieczeństwo — nie ujawniamy czy email istnieje)
        val requestBadEmail = StaffLoginRequest("nieistnieje@mail.com", "haslo123")
        every { userRepository.findByEmail("nieistnieje@mail.com") } returns null

        val requestBadPassword = StaffLoginRequest("istnieje@mail.com", "zlehaslo")
        val existingUser = User(
            phoneNumber = testPhone, firstName = "A", lastName = "B",
            email = "istnieje@mail.com", passwordHash = "hashed"
        )
        every { userRepository.findByEmail("istnieje@mail.com") } returns existingUser
        every { passwordEncoder.matches("zlehaslo", "hashed") } returns false

        // WHEN
        val exBadEmail = assertThrows<IllegalArgumentException> { authService.loginStaff(requestBadEmail) }
        val exBadPassword = assertThrows<IllegalArgumentException> { authService.loginStaff(requestBadPassword) }

        // THEN - Komunikaty MUSZĄ być identyczne
        assertEquals(
            exBadEmail.message, exBadPassword.message,
            "Komunikat dla złego emaila i hasła musi być identyczny aby nie ujawniać czy email istnieje"
        )
    }

    @Test
    fun `verifyCode should throw when firstName is missing on first registration`() {
        // GIVEN - nowy użytkownik (nie ma w bazie), ale nie podał firstName
        val request = VerifyCodeRequest(testPhone, "123456", firstName = null, lastName = "Kowalski")
        every { otpStore.getCode(testPhone) } returns "123456"
        every { userRepository.findByPhoneNumber(testPhone) } returns null

        // WHEN & THEN
        assertThrows<IllegalArgumentException> {
            authService.verifyCode(request)
        }
    }

    @Test
    fun `verifyCode should login existing user without creating duplicate`() {
        // GIVEN - użytkownik już istnieje w bazie
        val request = VerifyCodeRequest(testPhone, "123456") // Bez firstName/lastName
        val existingUser = User(id = 1, phoneNumber = testPhone, firstName = "Jan", lastName = "Kowalski")

        every { otpStore.getCode(testPhone) } returns "123456"
        every { userRepository.findByPhoneNumber(testPhone) } returns existingUser // Użytkownik JUŻ istnieje!
        every { otpStore.deleteCode(testPhone) } just Runs
        every { jwtService.generateToken(any(), null) } returns "EXISTING_USER_TOKEN"

        // WHEN
        val response = authService.verifyCode(request)

        // THEN
        assertEquals("EXISTING_USER_TOKEN", response.token)
        verify(exactly = 0) { userRepository.save(any()) } // NIE tworzymy duplikatu!
        verify(exactly = 1) { otpStore.deleteCode(testPhone) }
    }
}