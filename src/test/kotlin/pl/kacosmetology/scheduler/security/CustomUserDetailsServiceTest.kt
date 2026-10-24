package pl.kacosmetology.scheduler.security

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import java.util.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.core.userdetails.UsernameNotFoundException
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.CompanyCustomer
import pl.kacosmetology.scheduler.user.CompanyCustomerRepository

@ExtendWith(MockKExtension::class)
class CustomUserDetailsServiceTest {

    @MockK private lateinit var userRepository: CompanyCustomerRepository

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @InjectMockKs private lateinit var customUserDetailsService: CustomUserDetailsService

    private val testPhone = "+48111222333"

    @Test
    fun `should throw exception when user not found`() {
        every { userRepository.findByCompanyIdAndId(testId(9), testId(1)) } returns null

        val exception =
            assertThrows<UsernameNotFoundException> {
                customUserDetailsService.loadCustomer(testPhone, testId(9), testId(1))
            }
        Assertions.assertEquals("Customer account unavailable", exception.message)
    }

    @Test
    fun `should assign only ROLE_CUSTOMER for regular customer`() {
        val user =
            CompanyCustomer(
                companyId = testId(9),
                id = testId(1),
                phoneNumber = testPhone,
                firstName = "Jan",
                lastName = "Kowalski",
            )
        every { userRepository.findByCompanyIdAndId(testId(9), testId(1)) } returns user
        val userDetails = customUserDetailsService.loadCustomer(testPhone, testId(9), testId(1))

        Assertions.assertEquals(1, userDetails.authorities.size)
        Assertions.assertTrue(userDetails.authorities.any { it.authority == "ROLE_CUSTOMER" })
    }

    @Test
    fun `customer loading should not aggregate employment roles`() {
        val user =
            CompanyCustomer(
                companyId = testId(9),
                id = testId(1),
                phoneNumber = testPhone,
                firstName = "Anna",
                lastName = "Nowak",
            )
        every { userRepository.findByCompanyIdAndId(testId(9), testId(1)) } returns user

        val userDetails = customUserDetailsService.loadCustomer(testPhone, testId(9), testId(1))

        Assertions.assertEquals(listOf("ROLE_CUSTOMER"), userDetails.authorities.map { it.authority })
    }

    @Test
    fun `staff loading should use only the exact employment role and scope`() {
        val user =
            CompanyCustomer(
                companyId = testId(9),
                id = testId(1),
                phoneNumber = testPhone,
                firstName = "Anna",
                lastName = "Nowak",
            )
        val employment =
            CompanyEmployee(
                id = testId(8),
                companyId = testId(99),
                userId = testId(1),
                role = "OWNER",
                loginEmail = "staff@example.test",
            )
        every { userRepository.findByCompanyIdAndId(testId(9), testId(1)) } returns user
        every { companyEmployeeRepository.findById(testId(8)) } returns Optional.of(employment)

        val details = customUserDetailsService.loadStaffByEmployment("staff@example.test", testId(8))

        Assertions.assertEquals(testId(8), details.employmentId)
        Assertions.assertEquals(testId(99), details.companyId)
        Assertions.assertEquals(listOf("ROLE_OWNER"), details.authorities.map { it.authority })
    }

    @Test
    fun `staff loading should reject employment owned by another user`() {
        val user =
            CompanyCustomer(
                companyId = testId(9),
                id = testId(1),
                phoneNumber = testPhone,
                firstName = "Anna",
                lastName = "Nowak",
            )
        every { userRepository.findByCompanyIdAndId(testId(9), testId(1)) } returns user
        every { companyEmployeeRepository.findById(testId(8)) } returns
            Optional.of(
                CompanyEmployee(id = testId(8), companyId = testId(99), userId = testId(2), role = "OWNER"),
            )

        assertThrows<UsernameNotFoundException> {
            customUserDetailsService.loadStaffByEmployment("staff@example.test", testId(8))
        }
    }
}
