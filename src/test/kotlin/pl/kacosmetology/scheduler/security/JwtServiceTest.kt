package pl.kacosmetology.scheduler.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.authority.SimpleGrantedAuthority
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.user.User

class JwtServiceTest {

    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setUp() {
        jwtService = JwtService(
            "BardzoTajnyKluczKtoryMusiMiecPrzynajmniej256BitowZebyZadzialac!!!",
            86_400_000L
        )
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
        val employment = CompanyEmployee(id = 12L, companyId = 34L, userId = user.id, role = "EMPLOYEE")

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
    fun `isTokenValid should reject token of another user`() {
        val realUser = user(1L, "+48111222333")
        val fakeUser = user(2L, "+48999999999")
        val token = jwtService.generateCustomerToken(realUser)
        val fakeDetails = CustomUserDetails(
            fakeUser,
            null,
            listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))
        )

        assertFalse(jwtService.isTokenValid(token, fakeDetails))
    }

    @Test
    fun `expired token should fail validation`() {
        val shortLived = JwtService(
            "BardzoTajnyKluczKtoryMusiMiecPrzynajmniej256BitowZebyZadzialac!!!",
            1L
        )
        val user = user(1L, "+48111222333")
        val token = shortLived.generateCustomerToken(user)
        val details = CustomUserDetails(user, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
        Thread.sleep(50)

        assertThrows<Exception> { shortLived.isTokenValid(token, details) }
    }

    @Test
    fun `valid customer token should validate for its user`() {
        val user = user(1L, "+48111222333")
        val token = jwtService.generateCustomerToken(user)
        val details = CustomUserDetails(user, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))

        assertTrue(jwtService.isTokenValid(token, details))
    }

    private fun user(id: Long, phone: String) = User(
        id = id,
        phoneNumber = phone,
        firstName = "A",
        lastName = "B"
    )
}
