package pl.kacosmetology.scheduler.user

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.user.dto.UpdateUserProfileRequest
import java.util.*

@ExtendWith(MockKExtension::class)
class UserServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @InjectMockKs
    private lateinit var userService: UserService

    @Test
    fun `updateProfile should update user data`() {
        // GIVEN
        val userId = 1L
        val existingUser = User(id = userId, phoneNumber = "123", firstName = "Stare", lastName = "Dane", email = null)
        val request = UpdateUserProfileRequest(firstName = "Nowe", lastName = "Nazwisko", email = "nowy@mail.com")

        every { userRepository.findById(userId) } returns Optional.of(existingUser)
        every { userRepository.findByEmail("nowy@mail.com") } returns null // Email jest wolny
        every { userRepository.save(any()) } answers { firstArg() }

        // WHEN
        val updatedUser = userService.updateProfile(userId, request)

        // THEN
        assertEquals("Nowe", updatedUser.firstName)
        assertEquals("Nazwisko", updatedUser.lastName)
        assertEquals("nowy@mail.com", updatedUser.email)
    }

    @Test
    fun `updateProfile should throw when email is already taken`() {
        // GIVEN
        val userId = 1L
        val existingUser =
            User(id = userId, phoneNumber = "123", firstName = "Jan", lastName = "Kowalski", email = "jan@mail.com")
        val request = UpdateUserProfileRequest(firstName = "Jan", lastName = "Kowalski", email = "zajety@mail.com")

        every { userRepository.findById(userId) } returns Optional.of(existingUser)
        // Symulujemy, że w bazie jest już INNY użytkownik z tym emailem
        every { userRepository.findByEmail("zajety@mail.com") } returns User(
            id = 2L,
            phoneNumber = "999",
            firstName = "X",
            lastName = "Y"
        )

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            userService.updateProfile(userId, request)
        }
        assertEquals("Ten adres e-mail jest już zajęty", exception.message)
    }
}