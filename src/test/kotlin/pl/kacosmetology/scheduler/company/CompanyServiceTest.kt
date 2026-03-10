package pl.kacosmetology.scheduler.company

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.dto.UpdateCompanySettingsRequest
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalTime
import java.util.*

@ExtendWith(MockKExtension::class)
class CompanyServiceTest {

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK
    private lateinit var userRepository: UserRepository

    @InjectMockKs
    private lateinit var companyService: CompanyService

    private val companyId = 1L
    private val company = Company(id = companyId, name = "Test Salon")

    @Test
    fun `getCompany should return settings response`() {
        every { companyRepository.findById(companyId) } returns Optional.of(company)

        val result = companyService.getCompany(companyId)

        assertEquals(companyId, result.id)
        assertEquals("Test Salon", result.name)
        assertEquals(LocalTime.of(9, 0), result.openingTime)
        assertEquals(LocalTime.of(17, 0), result.closingTime)
        assertEquals(30, result.slotIntervalMinutes)
    }

    @Test
    fun `getCompany should throw when company does not exist`() {
        every { companyRepository.findById(companyId) } returns Optional.empty()

        assertThrows<NoSuchElementException> { companyService.getCompany(companyId) }
    }

    @Test
    fun `updateSettings should save updated company and return response`() {
        val request = UpdateCompanySettingsRequest(
            openingTime = LocalTime.of(8, 0),
            closingTime = LocalTime.of(18, 0),
            slotIntervalMinutes = 15
        )
        val updated = Company(id = companyId, name = "Test Salon",
            openingTime = LocalTime.of(8, 0), closingTime = LocalTime.of(18, 0), slotIntervalMinutes = 15)

        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { companyRepository.save(any()) } returns updated

        val result = companyService.updateSettings(companyId, request)

        assertEquals(LocalTime.of(8, 0), result.openingTime)
        assertEquals(LocalTime.of(18, 0), result.closingTime)
        assertEquals(15, result.slotIntervalMinutes)
        verify(exactly = 1) { companyRepository.save(any()) }
    }

    @Test
    fun `updateSettings should throw when closingTime equals openingTime`() {
        val request = UpdateCompanySettingsRequest(
            openingTime = LocalTime.of(9, 0),
            closingTime = LocalTime.of(9, 0),
            slotIntervalMinutes = 30
        )
        every { companyRepository.findById(companyId) } returns Optional.of(company)

        val ex = assertThrows<IllegalArgumentException> { companyService.updateSettings(companyId, request) }
        assertEquals("Godzina zamknięcia musi być późniejsza niż godzina otwarcia", ex.message)
        verify(exactly = 0) { companyRepository.save(any()) }
    }

    @Test
    fun `updateSettings should throw when closingTime is before openingTime`() {
        val request = UpdateCompanySettingsRequest(
            openingTime = LocalTime.of(17, 0),
            closingTime = LocalTime.of(9, 0),
            slotIntervalMinutes = 30
        )
        every { companyRepository.findById(companyId) } returns Optional.of(company)

        assertThrows<IllegalArgumentException> { companyService.updateSettings(companyId, request) }
    }

    @Test
    fun `getEmployees should return mapped employee responses`() {
        val user1 = User(id = 10L, phoneNumber = "+48100000001", firstName = "Anna", lastName = "Kowalska")
        val user2 = User(id = 11L, phoneNumber = "+48100000002", firstName = "Bartek", lastName = "Nowak")
        val ce1 = CompanyEmployee(id = 1L, companyId = companyId, userId = 10L, role = "OWNER")
        val ce2 = CompanyEmployee(id = 2L, companyId = companyId, userId = 11L, role = "EMPLOYEE")

        every { companyEmployeeRepository.findAllByCompanyId(companyId) } returns listOf(ce1, ce2)
        every { userRepository.findAllById(setOf(10L, 11L)) } returns listOf(user1, user2)

        val result = companyService.getEmployees(companyId)

        assertEquals(2, result.size)
        assertEquals("Anna", result.find { it.userId == 10L }?.firstName)
        assertEquals("OWNER", result.find { it.userId == 10L }?.role)
        assertEquals("Bartek", result.find { it.userId == 11L }?.firstName)
        assertEquals("EMPLOYEE", result.find { it.userId == 11L }?.role)
    }

    @Test
    fun `getEmployees should return empty list when no employees`() {
        every { companyEmployeeRepository.findAllByCompanyId(companyId) } returns emptyList()
        every { userRepository.findAllById(emptySet()) } returns emptyList()

        val result = companyService.getEmployees(companyId)

        assertEquals(0, result.size)
    }

    @Test
    fun `updateSettings should throw when company does not exist`() {
        val request = UpdateCompanySettingsRequest(
            openingTime = LocalTime.of(9, 0),
            closingTime = LocalTime.of(17, 0),
            slotIntervalMinutes = 30
        )
        every { companyRepository.findById(companyId) } returns Optional.empty()

        assertThrows<NoSuchElementException> { companyService.updateSettings(companyId, request) }
    }
}
