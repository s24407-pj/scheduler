package pl.kacosmetology.scheduler.company

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import java.time.LocalTime
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.dto.UpdateCompanySettingsRequest
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.testId

@ExtendWith(MockKExtension::class)
class CompanyServiceTest {

    @MockK private lateinit var companyRepository: CompanyRepository

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var offeringRepository: OfferingRepository

    @MockK private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @InjectMockKs private lateinit var companyService: CompanyService

    private val companyId = testId(1)
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
    fun `updateSettings should mutate managed company and return response`() {
        val request =
            UpdateCompanySettingsRequest(
                openingTime = LocalTime.of(8, 0),
                closingTime = LocalTime.of(18, 0),
                slotIntervalMinutes = 15,
            )
        every { companyRepository.findByIdForUpdate(companyId) } returns company

        val result = companyService.updateSettings(companyId, request)

        assertEquals(LocalTime.of(8, 0), result.openingTime)
        assertEquals(LocalTime.of(18, 0), result.closingTime)
        assertEquals(15, result.slotIntervalMinutes)
        assertEquals(LocalTime.of(8, 0), company.openingTime)
        assertEquals(LocalTime.of(18, 0), company.closingTime)
        assertEquals(15, company.slotIntervalMinutes)
        verify(exactly = 0) { companyRepository.save(any()) }
    }

    @Test
    fun `updateSettings should throw when closingTime equals openingTime`() {
        val request =
            UpdateCompanySettingsRequest(
                openingTime = LocalTime.of(9, 0),
                closingTime = LocalTime.of(9, 0),
                slotIntervalMinutes = 30,
            )
        every { companyRepository.findByIdForUpdate(companyId) } returns company

        val ex = assertThrows<IllegalArgumentException> { companyService.updateSettings(companyId, request) }
        assertEquals("Godzina zamknięcia musi być późniejsza niż godzina otwarcia", ex.message)
        verify(exactly = 0) { companyRepository.save(any()) }
    }

    @Test
    fun `updateSettings should throw when closingTime is before openingTime`() {
        val request =
            UpdateCompanySettingsRequest(
                openingTime = LocalTime.of(17, 0),
                closingTime = LocalTime.of(9, 0),
                slotIntervalMinutes = 30,
            )
        every { companyRepository.findByIdForUpdate(companyId) } returns company

        assertThrows<IllegalArgumentException> { companyService.updateSettings(companyId, request) }
    }

    @Test
    fun `getEmployees should return mapped employee responses`() {
        val ce1 =
            CompanyEmployee(
                id = testId(1),
                companyId = companyId,
                userId = testId(10),
                role = "OWNER",
                firstName = "Anna",
                lastName = "Kowalska",
            )
        val ce2 =
            CompanyEmployee(
                id = testId(2),
                companyId = companyId,
                userId = testId(11),
                role = "EMPLOYEE",
                firstName = "Bartek",
                lastName = "Nowak",
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES,
            )

        every { companyEmployeeRepository.findAllByCompanyId(companyId) } returns listOf(ce1, ce2)

        val result = companyService.getEmployees(companyId)

        assertEquals(2, result.size)
        assertEquals("Anna", result.find { it.userId == testId(10) }?.firstName)
        assertEquals("OWNER", result.find { it.userId == testId(10) }?.role)
        assertEquals("Bartek", result.find { it.userId == testId(11) }?.firstName)
        assertEquals("EMPLOYEE", result.find { it.userId == testId(11) }?.role)
        assertEquals(
            EmployeeEligibilityMode.SPECIFIC_SERVICES,
            result.find { it.userId == testId(11) }?.eligibilityMode,
        )
    }

    @Test
    fun `getEmployees should return empty list when no employees`() {
        every { companyEmployeeRepository.findAllByCompanyId(companyId) } returns emptyList()

        val result = companyService.getEmployees(companyId)

        assertEquals(0, result.size)
    }

    @Test
    fun `getPublicProfile should return public fields only`() {
        company.lastMinuteDiscountPercent = 15
        company.lastMinuteDiscountHours = 12
        every { companyRepository.findById(companyId) } returns Optional.of(company)

        val result = companyService.getPublicProfile(companyId)

        assertEquals(companyId, result.id)
        assertEquals("Test Salon", result.name)
        assertEquals(15, result.lastMinuteDiscountPercent)
        assertEquals(12, result.lastMinuteDiscountHours)
    }

    @Test
    fun `getPublicBookableEmployees should filter by offering assignment`() {
        val offeringId = testId(5)
        val offering =
            Offering(
                id = offeringId,
                companyId = companyId,
                name = "Manicure",
                durationMinutes = 60,
                price = 100,
                active = true,
            )
        val ce =
            CompanyEmployee(
                id = testId(1),
                companyId = companyId,
                userId = testId(10),
                role = "EMPLOYEE",
                firstName = "Anna",
                lastName = "Kowalska",
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES,
            )

        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { offeringRepository.findById(offeringId) } returns Optional.of(offering)
        every { companyEmployeeRepository.findAllByCompanyId(companyId) } returns listOf(ce)
        every { assignmentRepository.existsByCompanyIdAndEmployeeId(companyId, testId(10)) } returns true
        every {
            assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(companyId, testId(10), offeringId)
        } returns true

        val result = companyService.getPublicBookableEmployees(companyId, offeringId)

        assertEquals(1, result.size)
        assertEquals(testId(10), result.first().id)
        assertEquals("Anna", result.first().firstName)
    }

    @Test
    fun `updateSettings should throw when company does not exist`() {
        val request =
            UpdateCompanySettingsRequest(
                openingTime = LocalTime.of(9, 0),
                closingTime = LocalTime.of(17, 0),
                slotIntervalMinutes = 30,
            )
        every { companyRepository.findByIdForUpdate(companyId) } returns null

        assertThrows<NoSuchElementException> { companyService.updateSettings(companyId, request) }
    }
}
