package pl.kacosmetology.scheduler.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.user.User

class JwtServiceTest {

    private val secret = "BardzoTajnyKluczKtoryMusiMiecPrzynajmniej256BitowZebyZadzialac!!!"
    private val sessionStore = mockk<SessionStore>()
    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setUp() {
        every { sessionStore.create(any(), any()) } returns "session-1"
        jwtService = JwtService(secret, 86_400_000L, sessionStore)
    }

    @Test
    fun `customer token should contain only customer scope`() {
        val user = user(1L, "+48123456789")

        val token = jwtService.generateCustomerToken(user)

        assertEquals(user.phoneNumber, jwtService.extractUsername(token))
        assertEquals("customer", jwtService.extractRole(token))
        assertNull(jwtService.extractCompanyId(token))
        assertNull(jwtService.extractEmploymentId(token))
    }

    @Test
    fun `staff token should contain claims from the same employment`() {
        val user = user(7L, "+48999888777")
        val employment = CompanyEmployee(id = 12L, companyId = 34L, userId = user.id!!, role = "EMPLOYEE")

        val token = jwtService.generateStaffToken(user, employment)

        assertEquals(user.phoneNumber, jwtService.extractUsername(token))
        assertEquals(12L, jwtService.extractEmploymentId(token))
        assertEquals(34L, jwtService.extractCompanyId(token))
        assertEquals("employee", jwtService.extractRole(token))
    }

    @Test
    fun `staff token should reject an employment belonging to another user`() {
        val user = user(7L, "+48999888777")
        val foreignEmployment = CompanyEmployee(id = 12L, companyId = 34L, userId = 8L, role = "OWNER")

        assertThrows<IllegalArgumentException> {
            jwtService.generateStaffToken(user, foreignEmployment)
        }
    }

    @Test
    fun `every token should register a session for its user with the token lifetime`() {
        val user = user(5L, "+48111222333")

        val token = jwtService.generateCustomerToken(user)

        assertEquals("session-1", jwtService.extractSessionId(token))
        verify(exactly = 1) { sessionStore.create(5L, Duration.ofMillis(86_400_000L)) }
    }

    @Test
    fun `parse should return all claims in one pass`() {
        val user = user(7L, "+48999888777")
        val employment = CompanyEmployee(id = 12L, companyId = 34L, userId = user.id!!, role = "OWNER")

        val claims = jwtService.parse(jwtService.generateStaffToken(user, employment))

        assertEquals(TokenClaims("+48999888777", "owner", 34L, 12L, "session-1"), claims)
    }

    @Test
    fun `expired token should fail parsing`() {
        val shortLived = JwtService(secret, 1L, sessionStore)
        val token = shortLived.generateCustomerToken(user(1L, "+48111222333"))
        Thread.sleep(50)

        assertThrows<Exception> { shortLived.parse(token) }
    }

    @Test
    fun `token signed with another secret should fail parsing`() {
        val other = JwtService("InnyBardzoTajnyKluczKtoryMaPrzynajmniej256BitowDlugosci!!!", 86_400_000L, sessionStore)
        val token = other.generateCustomerToken(user(1L, "+48111222333"))

        assertThrows<Exception> { jwtService.parse(token) }
    }

    private fun user(id: Long, phone: String) =
        User(
            id = id,
            phoneNumber = phone,
            firstName = "A",
            lastName = "B",
        )
}
