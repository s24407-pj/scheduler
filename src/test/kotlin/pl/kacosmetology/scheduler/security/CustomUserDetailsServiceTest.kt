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
import java.util.Optional

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
        val userDetails = customUserDetailsService.loadUserByUsername(testPhone)

        Assertions.assertEquals(1, userDetails.authorities.size)
        Assertions.assertTrue(userDetails.authorities.any { it.authority == "ROLE_CUSTOMER" })
    }

    @Test
    fun `customer loading should not aggregate employment roles`() {
        val user = User(id = 1, phoneNumber = testPhone, firstName = "Anna", lastName = "Nowak")
        every { userRepository.findByPhoneNumber(testPhone) } returns user

        // Zwracamy informacje, ze Anna jest wlascicielem (OWNER)
        val employeeData = CompanyEmployee(companyId = 99L, userId = 1L, role = "OWNER")
        val userDetails = customUserDetailsService.loadUserByUsername(testPhone)

        Assertions.assertEquals(listOf("ROLE_CUSTOMER"), userDetails.authorities.map { it.authority })
    }

    @Test
    fun `staff loading should use only the exact employment role and scope`() {
        val user = User(id = 1, phoneNumber = testPhone, firstName = "Anna", lastName = "Nowak")
        val employment = CompanyEmployee(id = 8L, companyId = 99L, userId = 1L, role = "OWNER")
        every { userRepository.findByPhoneNumber(testPhone) } returns user
        every { companyEmployeeRepository.findById(8L) } returns Optional.of(employment)

        val details = customUserDetailsService.loadStaffByEmployment(testPhone, 8L)

        Assertions.assertEquals(8L, details.employmentId)
        Assertions.assertEquals(99L, details.companyId)
        Assertions.assertEquals(listOf("ROLE_OWNER"), details.authorities.map { it.authority })
    }

    @Test
    fun `staff loading should reject employment owned by another user`() {
        val user = User(id = 1, phoneNumber = testPhone, firstName = "Anna", lastName = "Nowak")
        every { userRepository.findByPhoneNumber(testPhone) } returns user
        every { companyEmployeeRepository.findById(8L) } returns Optional.of(
            CompanyEmployee(id = 8L, companyId = 99L, userId = 2L, role = "OWNER")
        )

        assertThrows<UsernameNotFoundException> {
            customUserDetailsService.loadStaffByEmployment(testPhone, 8L)
        }
    }
}
