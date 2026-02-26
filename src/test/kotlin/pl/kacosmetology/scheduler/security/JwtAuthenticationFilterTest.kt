package pl.kacosmetology.scheduler.security

import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import pl.kacosmetology.scheduler.user.User

@ExtendWith(MockKExtension::class)
class JwtAuthenticationFilterTest {

    @MockK
    private lateinit var jwtService: JwtService

    @MockK
    private lateinit var userDetailsService: UserDetailsService

    @MockK(relaxed = true)
    private lateinit var request: HttpServletRequest

    @MockK(relaxed = true)
    private lateinit var response: HttpServletResponse

    @MockK(relaxed = true)
    private lateinit var filterChain: FilterChain

    @InjectMockKs
    private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @BeforeEach
    fun setup() {
        // Czyścimy kontekst bezpieczeństwa przed każdym testem
        SecurityContextHolder.clearContext()

        // Mówimy Springowi: "To jest świeże zapytanie, filtr jeszcze nie działał"
        every { request.getAttribute(any()) } returns null

        // Zezwalamy Springowi na ustawienie flagi "filtr w trakcie działania" bez rzucania błędów
        every { request.setAttribute(any(), any()) } just Runs
        every { request.removeAttribute(any()) } just Runs
    }

    @AfterEach
    fun teardown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `should pass request without verification when Authorization header is missing`() {
        every { request.getHeader("Authorization") } returns null

        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication, "Kontekst powinien być pusty")
        verify(exactly = 1) { filterChain.doFilter(request, response) } // Udowadniamy, że zapytanie poszło dalej
        verify(exactly = 0) { jwtService.extractUsername(any()) }
    }

    @Test
    fun `should pass request without verification when header does not start with Bearer`() {
        every { request.getHeader("Authorization") } returns "ZlyNaglowek 12345"

        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly = 1) { filterChain.doFilter(request, response) }
    }

    @Test
    fun `should ignore broken token and pass request as anonymous`() {
        val badToken = "zepsuty_token"
        every { request.getHeader("Authorization") } returns "Bearer $badToken"
        // Symulujemy wyjatek z biblioteki JWT podczas proby odczytu
        every { jwtService.extractUsername(badToken) } throws IllegalArgumentException("Invalid token")

        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        assertNull(SecurityContextHolder.getContext().authentication, "Zepsuty token nie loguje uzytkownika")
        verify(exactly = 1) { filterChain.doFilter(request, response) }
    }

    @Test
    fun `should set SecurityContext for valid token`() {
        val goodToken = "dobry_token"
        val phone = "+48111"
        val user = User(id = 1, phoneNumber = phone, firstName = "A", lastName = "B")

        // Mockujemy zachowania
        every { request.getHeader("Authorization") } returns "Bearer $goodToken"
        every { jwtService.extractUsername(goodToken) } returns phone
        val userDetails = CustomUserDetails(user, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
        every { userDetailsService.loadUserByUsername(phone) } returns userDetails
        every { jwtService.isTokenValid(goodToken, userDetails) } returns true

        jwtAuthenticationFilter.doFilter(request, response, filterChain)

        // SPRAWDZAMY CZY UŻYTKOWNIK JEST ZALOGOWANY
        assertNotNull(SecurityContextHolder.getContext().authentication, "Kontekst musi zawierac Authentication")
        verify(exactly = 1) { filterChain.doFilter(request, response) }
    }
}