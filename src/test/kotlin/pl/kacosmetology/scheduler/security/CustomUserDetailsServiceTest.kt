package pl.kacosmetology.scheduler.security

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.core.userdetails.UsernameNotFoundException
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

@ExtendWith(MockKExtension::class)
class CustomUserDetailsServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @InjectMockKs
    private lateinit var customUserDetailsService: CustomUserDetailsService

    private val testPhone = "+48111222333"

    @Test
    fun `should throw exception when user not found`() {
        every { userRepository.findByPhoneNumber(testPhone) } returns null

        val exception = assertThrows<UsernameNotFoundException> {
            customUserDetailsService.loadUserByUsername(testPhone)
        }
        Assertions.assertEquals("User not found with phone number: $testPhone", exception.message)
    }

    @Test
    fun `should assign only ROLE_CUSTOMER for regular customer`() {
        val user = User(id = 1, phoneNumber = testPhone, firstName = "Jan", lastName = "Kowalski")
        every { userRepository.findByPhoneNumber(testPhone) } returns user
        every { companyEmployeeRepository.findAllByUserId(1L) } returns emptyList() // Klient nie pracuje w zadnej firmie

        val userDetails = customUserDetailsService.loadUserByUsername(testPhone)

        Assertions.assertEquals(1, userDetails.authorities.size)
        Assertions.assertTrue(userDetails.authorities.any { it.authority == "ROLE_CUSTOMER" })
    }

    @Test
    fun `should assign correct roles for salon employee`() {
        val user = User(id = 1, phoneNumber = testPhone, firstName = "Anna", lastName = "Nowak")
        every { userRepository.findByPhoneNumber(testPhone) } returns user

        // Zwracamy informacje, ze Anna jest wlascicielem (OWNER)
        val employeeData = CompanyEmployee(companyId = 99L, userId = 1L, role = "OWNER")
        every { companyEmployeeRepository.findAllByUserId(1L) } returns listOf(employeeData)

        val userDetails = customUserDetailsService.loadUserByUsername(testPhone)

        // Anna ma miec role CUSTOMER oraz ROLE_OWNER
        Assertions.assertEquals(2, userDetails.authorities.size)
        Assertions.assertTrue(userDetails.authorities.any { it.authority == "ROLE_CUSTOMER" })
        Assertions.assertTrue(userDetails.authorities.any { it.authority == "ROLE_OWNER" })
    }
}