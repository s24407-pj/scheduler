package pl.kacosmetology.scheduler.user

import io.mockk.every
import io.mockk.mockk
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.kacosmetology.scheduler.testId

class UserServiceTest {
    private val accounts = mockk<UserRepository>()
    private val service = UserService(accounts)

    @Test
    fun `customer account read exposes canonical identity`() {
        every { accounts.findById(testId(1)) } returns Optional.of(User(id = testId(1), phoneNumber = "+48500100200"))
        assertEquals("+48500100200", service.getProfile(testId(1)).phoneNumber)
    }
}
