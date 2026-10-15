package pl.kacosmetology.scheduler.user

import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import pl.kacosmetology.scheduler.user.dto.UpdateUserProfileRequest

class UserServiceTest {
    private val accounts = mockk<UserRepository>()
    private val service = UserService(accounts)

    @Test
    fun `customer account read exposes canonical identity`() {
        every { accounts.findById(1L) } returns Optional.of(User(id = 1L, phoneNumber = "+48500100200"))
        assertEquals("+48500100200", service.getProfile(1L).phoneNumber)
    }

    @Test
    fun `global customer presentation cannot be edited`() {
        assertThrows<AccessDeniedException> {
            service.updateProfile(1L, UpdateUserProfileRequest("Jan", "Nowak", null))
        }
    }
}
