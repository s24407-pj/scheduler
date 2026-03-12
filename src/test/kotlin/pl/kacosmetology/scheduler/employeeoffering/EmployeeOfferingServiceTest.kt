package pl.kacosmetology.scheduler.employeeoffering

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import java.util.*

@ExtendWith(MockKExtension::class)
class EmployeeOfferingServiceTest {

    @MockK
    private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK
    private lateinit var offeringRepository: OfferingRepository

    @InjectMockKs
    private lateinit var service: EmployeeOfferingService

    private val companyId = 1L
    private val employeeId = 10L
    private val offeringId = 100L

    private val mockOffering =
        Offering(id = offeringId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 60)

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
        every { assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId) } returns true
        every { assignmentRepository.deleteByEmployeeIdAndOfferingId(employeeId, offeringId) } returns Unit

        service.removeAssignment(companyId, employeeId, offeringId)

        verify(exactly = 1) { assignmentRepository.deleteByEmployeeIdAndOfferingId(employeeId, offeringId) }
    }

    @Test
    fun `removeAssignment should throw when assignment does not exist`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId) } returns false

        assertThrows<NoSuchElementException> {
            service.removeAssignment(companyId, employeeId, offeringId)
        }
    }

    @Test
    fun `getOfferingsForEmployee should return empty list when no assignments`() {
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false

        val result = service.getOfferingsForEmployee(employeeId)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getOfferingsForEmployee should return active assigned offerings`() {
        val assignment =
            EmployeeOfferingAssignment(companyId = companyId, employeeId = employeeId, offeringId = offeringId)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns true
        every { assignmentRepository.findAllByEmployeeId(employeeId) } returns listOf(assignment)
        every { offeringRepository.findAllById(listOf(offeringId)) } returns listOf(mockOffering)

        val result = service.getOfferingsForEmployee(employeeId)

        assertEquals(1, result.size)
        assertEquals(offeringId, result.first().id)
    }
}
