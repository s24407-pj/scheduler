package pl.kacosmetology.scheduler.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.authority.SimpleGrantedAuthority
import pl.kacosmetology.scheduler.user.User

class JwtServiceTest {

    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setUp() {
        // Ręcznie tworzymy instancję serwisu.
        // Używamy długiego, wymyślonego klucza dla algorytmu HS256 i czasu życia 1 dzień.
        val dummySecret = "BardzoTajnyKluczKtoryMusiMiecPrzynajmniej256BitowZebyZadzialac!!!"
        val dummyExpirationMs = 86400000L // 24 godziny

        jwtService = JwtService(dummySecret, dummyExpirationMs)
    }

    @Test
    fun `should generate valid token with phone number as subject`() {
        // GIVEN
        val user = User(
            phoneNumber = "+48123456789",
            firstName = "Jan",
            lastName = "Kowalski"
        )
        val companyId = 1L

        // OPAKOWUJEMY USERA:
        val userDetails = CustomUserDetails(
            user = user,
            companyId = companyId,
            authorities = listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))
        )

        // WHEN (przekazujemy userDetails zamiast user)
        val token = jwtService.generateToken(userDetails, companyId)

        // THEN
        assertNotNull(token)
        assertTrue(token.isNotEmpty())

        val extractedPhoneNumber = jwtService.extractUsername(token)
        assertEquals("+48123456789", extractedPhoneNumber, "Subject w tokenie powinien byc numerem telefonu")

        val extractedCompanyId = jwtService.extractCompanyId(token)
        assertEquals(1L, extractedCompanyId, "Token powinien zawierac poprawne companyId")
    }

    @Test
    fun `isTokenValid should return true for correct user and valid token`() {
        // GIVEN
        val user = User(
            phoneNumber = "+48999888777",
            firstName = "Anna",
            lastName = "Nowak"
        )
        val userDetails = CustomUserDetails(user, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))

        val token = jwtService.generateToken(userDetails, null)

        // WHEN
        val isValid = jwtService.isTokenValid(token, userDetails)

        // THEN
        assertTrue(isValid, "Token powinien byc uznany za wazny")
    }

    @Test
    fun `isTokenValid should return false for token of different user`() {
        // GIVEN
        val realUser = User(phoneNumber = "+48111222333", firstName = "X", lastName = "Y")
        val fakeUser = User(phoneNumber = "+48999999999", firstName = "Z", lastName = "W")

        val realUserDetails = CustomUserDetails(realUser, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
        val fakeUserDetails = CustomUserDetails(fakeUser, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))

        val tokenForRealUser = jwtService.generateToken(realUserDetails, null)

        // WHEN
        val isValid = jwtService.isTokenValid(tokenForRealUser, fakeUserDetails)

        // THEN
        assertFalse(isValid, "Token wystawiony dla innego numeru nie moze byc wazny")
    }

    @Test
    fun `isTokenValid should return false for expired token`() {
        // GIVEN - serwis z tokenem ważnym 1ms
        val shortLivedJwtService = JwtService(
            "BardzoTajnyKluczKtoryMusiMiecPrzynajmniej256BitowZebyZadzialac!!!",
            1L // 1 milisekunda!
        )

        val user = User(phoneNumber = "+48111222333", firstName = "X", lastName = "Y")
        val userDetails = CustomUserDetails(user, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
        val token = shortLivedJwtService.generateToken(userDetails, null)

        // Czekamy aż token wygaśnie
        Thread.sleep(50)

        // WHEN & THEN - parsowanie wygasłego tokena powinno rzucić wyjątek
        assertThrows<Exception> {
            shortLivedJwtService.isTokenValid(token, userDetails)
        }
    }

    @Test
    fun `generateToken should contain null companyId when not provided`() {
        // GIVEN
        val user = User(phoneNumber = "+48111222333", firstName = "A", lastName = "B")
        val userDetails = CustomUserDetails(user, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))

        // WHEN
        val token = jwtService.generateToken(userDetails, null)

        // THEN
        val extractedCompanyId = jwtService.extractCompanyId(token)
        assertNull(extractedCompanyId, "CompanyId powinien byc null dla zwyklego klienta")
    }
}