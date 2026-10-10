package pl.kacosmetology.scheduler.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import pl.kacosmetology.scheduler.TestcontainersConfiguration

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class UserRepositoryTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `insertIfPhoneAbsent should create a user when the phone is new`() {
        val inserted = userRepository.insertIfPhoneAbsent("+48600100200", "Anna", "Nowak")

        assertEquals(1, inserted)
        assertEquals("Anna", userRepository.findByPhoneNumber("+48600100200")?.firstName)
    }

    @Test
    fun `insertIfPhoneAbsent should keep the existing user when the phone is taken`() {
        userRepository.save(User(phoneNumber = "+48600100300", firstName = "Pierwsza", lastName = "Osoba"))

        val inserted = userRepository.insertIfPhoneAbsent("+48600100300", "Druga", "Osoba")

        assertEquals(0, inserted)
        assertEquals("Pierwsza", userRepository.findByPhoneNumber("+48600100300")?.firstName)
    }
}
