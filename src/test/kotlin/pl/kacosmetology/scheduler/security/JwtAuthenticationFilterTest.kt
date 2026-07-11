package pl.kacosmetology.scheduler.security

import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import pl.kacosmetology.scheduler.user.User

@ExtendWith(MockKExtension::class)
class JwtAuthenticationFilterTest {

    @MockK private lateinit var jwtService: JwtService
    @MockK private lateinit var userDetailsService: CustomUserDetailsService
    @MockK(relaxed = true) private lateinit var request: HttpServletRequest
    @MockK(relaxed = true) private lateinit var response: HttpServletResponse
    @MockK(relaxed = true) private lateinit var filterChain: FilterChain
    @InjectMockKs private lateinit var filter: JwtAuthenticationFilter

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        every { request.getAttribute(any()) } returns null
        every { request.setAttribute(any(), any()) } just Runs
        every { request.removeAttribute(any()) } just Runs
    }

    @AfterEach
    fun teardown() = SecurityContextHolder.clearContext()

    @Test
    fun `missing header should remain anonymous`() {
        every { request.getHeader("Authorization") } returns null

        filter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `customer token should load customer-only principal`() {
        val details = customerDetails()
        stubToken(role = "customer", companyId = null, employmentId = null)
        every { userDetailsService.loadUserByUsername(details.username) } returns details
        every { jwtService.isTokenValid("token", details) } returns true

        filter.doFilter(request, response, filterChain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(listOf("ROLE_CUSTOMER"), authentication!!.authorities.map { it.authority })
    }

    @Test
    fun `staff token should load only exact employment principal`() {
        val details = staffDetails()
        stubToken(role = "employee", companyId = 20L, employmentId = 10L)
        every { userDetailsService.loadStaffByEmployment(details.username, 10L) } returns details
        every { jwtService.isTokenValid("token", details) } returns true

        filter.doFilter(request, response, filterChain)

        val principal = SecurityContextHolder.getContext().authentication?.principal as CustomUserDetails
        assertEquals(10L, principal.employmentId)
        assertEquals(listOf("ROLE_EMPLOYEE"), principal.authorities.map { it.authority })
    }

    @Test
    fun `legacy staff token without employment should remain anonymous`() {
        stubToken(role = "owner", companyId = 20L, employmentId = null)

        filter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `changed employment role should remain anonymous`() {
        val details = staffDetails()
        stubToken(role = "owner", companyId = 20L, employmentId = 10L)
        every { userDetailsService.loadStaffByEmployment(details.username, 10L) } returns details

        filter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `mismatched employment company claim should remain anonymous`() {
        val details = staffDetails()
        stubToken(role = "employee", companyId = 99L, employmentId = 10L)
        every { userDetailsService.loadStaffByEmployment(details.username, 10L) } returns details

        filter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `deleted employment should remain anonymous`() {
        val details = staffDetails()
        stubToken(role = "employee", companyId = 20L, employmentId = 10L)
        every { userDetailsService.loadStaffByEmployment(details.username, 10L) } throws
            IllegalArgumentException("deleted")

        filter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    private fun stubToken(role: String, companyId: Long?, employmentId: Long?) {
        every { request.getHeader("Authorization") } returns "Bearer token"
        every { jwtService.extractUsername("token") } returns "+48111"
        every { jwtService.extractRole("token") } returns role
        every { jwtService.extractCompanyId("token") } returns companyId
        every { jwtService.extractEmploymentId("token") } returns employmentId
    }

    private fun customerDetails(): CustomUserDetails {
        val user = User(id = 1L, phoneNumber = "+48111", firstName = "A", lastName = "B")
        return CustomUserDetails(user, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
    }

    private fun staffDetails(): CustomUserDetails {
        val user = User(id = 1L, phoneNumber = "+48111", firstName = "A", lastName = "B")
        return CustomUserDetails(
            user,
            20L,
            listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE")),
            employmentId = 10L
        )
    }
}
