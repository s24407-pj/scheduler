package pl.kacosmetology.scheduler.employeeoffering

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository

@ExtendWith(MockKExtension::class)
class EmployeeOfferingServiceTest {

    @MockK private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var offeringRepository: OfferingRepository

    @MockK private lateinit var companyRepository: CompanyRepository

    @InjectMockKs private lateinit var service: EmployeeOfferingService

    private val companyId = 1L
    private val employeeId = 10L
    private val offeringId = 100L

    private val mockOffering =
        Offering(id = offeringId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 60)

    @BeforeEach
    fun mockPolicyLocks() {
        every { companyRepository.findByIdForUpdate(companyId) } returns Company(id = companyId, name = "Firma")
        every { companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) } returns
            CompanyEmployee(companyId = companyId, userId = employeeId, role = "EMPLOYEE")
    }

    @Test
    fun `assignOffering should save new assignment`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { offeringRepository.findById(offeringId) } returns Optional.of(mockOffering)
        every { assignmentRepository.findByEmployeeIdAndOfferingId(employeeId, offeringId) } returns null
        every { assignmentRepository.save(any()) } answers { firstArg() }

        val result = service.assignOffering(companyId, employeeId, offeringId)

        assertEquals(offeringId, result.offeringId)
        assertEquals(employeeId, result.employeeId)
        verify(exactly = 1) { assignmentRepository.save(any()) }
    }

    @Test
    fun `assignOffering should be idempotent when already assigned`() {
        val existing =
            EmployeeOfferingAssignment(id = 1L, companyId = companyId, employeeId = employeeId, offeringId = offeringId)
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { offeringRepository.findById(offeringId) } returns Optional.of(mockOffering)
        every { assignmentRepository.findByEmployeeIdAndOfferingId(employeeId, offeringId) } returns existing

        val result = service.assignOffering(companyId, employeeId, offeringId)

        assertEquals(offeringId, result.offeringId)
        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    @Test
    fun `assignOffering should throw when employee is not in company`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false

        assertThrows<NoSuchElementException> {
            service.assignOffering(companyId, employeeId, offeringId)
        }
    }

    @Test
    fun `assignOffering should throw when offering does not exist`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { offeringRepository.findById(offeringId) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            service.assignOffering(companyId, employeeId, offeringId)
        }
    }

    @Test
    fun `removeAssignment should delete assignment`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { offeringRepository.findByIdAndCompanyId(offeringId, companyId) } returns mockOffering
        every {
            assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(companyId, employeeId, offeringId)
        } returns true
        every {
            assignmentRepository.deleteByCompanyIdAndEmployeeIdAndOfferingId(companyId, employeeId, offeringId)
        } returns Unit

        service.removeAssignment(companyId, employeeId, offeringId)

        verify(exactly = 1) {
            assignmentRepository.deleteByCompanyIdAndEmployeeIdAndOfferingId(companyId, employeeId, offeringId)
        }
    }

    @Test
    fun `removeAssignment should throw when assignment does not exist`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { offeringRepository.findByIdAndCompanyId(offeringId, companyId) } returns mockOffering
        every {
            assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(companyId, employeeId, offeringId)
        } returns false

        assertThrows<NoSuchElementException> {
            service.removeAssignment(companyId, employeeId, offeringId)
        }
    }

    @Test
    fun `foreign offering deletion is opaque and never deletes another company assignment`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { offeringRepository.findByIdAndCompanyId(offeringId, companyId) } returns null
        assertThrows<NoSuchElementException> { service.removeAssignment(companyId, employeeId, offeringId) }
        verify(exactly = 0) { assignmentRepository.deleteByCompanyIdAndEmployeeIdAndOfferingId(any(), any(), any()) }
    }
}
