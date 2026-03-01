package pl.kacosmetology.scheduler.employeeservice

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
import pl.kacosmetology.scheduler.treatment.ProvidedService
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import java.util.*

@ExtendWith(MockKExtension::class)
class EmployeeServiceManagementServiceTest {

    @MockK
    private lateinit var assignmentRepository: EmployeeServiceAssignmentRepository

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK
    private lateinit var treatmentRepository: TreatmentRepository

    @InjectMockKs
    private lateinit var service: EmployeeServiceManagementService

    private val companyId = 1L
    private val employeeId = 10L
    private val serviceId = 100L

    private val mockService = ProvidedService(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 60)

    @Test
    fun `assignService should save new assignment`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { treatmentRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeIdAndServiceId(employeeId, serviceId) } returns false
        every { assignmentRepository.save(any()) } answers { firstArg() }

        val result = service.assignService(companyId, employeeId, serviceId)

        assertEquals(serviceId, result.serviceId)
        assertEquals(employeeId, result.employeeId)
        verify(exactly = 1) { assignmentRepository.save(any()) }
    }

    @Test
    fun `assignService should be idempotent when already assigned`() {
        val existing = EmployeeServiceAssignment(id = 1L, companyId = companyId, employeeId = employeeId, serviceId = serviceId)
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { treatmentRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeIdAndServiceId(employeeId, serviceId) } returns true
        every { assignmentRepository.findAllByEmployeeId(employeeId) } returns listOf(existing)

        val result = service.assignService(companyId, employeeId, serviceId)

        assertEquals(serviceId, result.serviceId)
        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    @Test
    fun `assignService should throw when employee is not in company`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false

        assertThrows<NoSuchElementException> {
            service.assignService(companyId, employeeId, serviceId)
        }
    }

    @Test
    fun `assignService should throw when service does not exist`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { treatmentRepository.findById(serviceId) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            service.assignService(companyId, employeeId, serviceId)
        }
    }

    @Test
    fun `removeAssignment should delete assignment`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { assignmentRepository.existsByEmployeeIdAndServiceId(employeeId, serviceId) } returns true
        every { assignmentRepository.deleteByEmployeeIdAndServiceId(employeeId, serviceId) } returns Unit

        service.removeAssignment(companyId, employeeId, serviceId)

        verify(exactly = 1) { assignmentRepository.deleteByEmployeeIdAndServiceId(employeeId, serviceId) }
    }

    @Test
    fun `removeAssignment should throw when assignment does not exist`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { assignmentRepository.existsByEmployeeIdAndServiceId(employeeId, serviceId) } returns false

        assertThrows<NoSuchElementException> {
            service.removeAssignment(companyId, employeeId, serviceId)
        }
    }

    @Test
    fun `getServicesForEmployee should return empty list when no assignments`() {
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false

        val result = service.getServicesForEmployee(employeeId)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getServicesForEmployee should return active assigned services`() {
        val assignment = EmployeeServiceAssignment(companyId = companyId, employeeId = employeeId, serviceId = serviceId)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns true
        every { assignmentRepository.findAllByEmployeeId(employeeId) } returns listOf(assignment)
        every { treatmentRepository.findById(serviceId) } returns Optional.of(mockService)

        val result = service.getServicesForEmployee(employeeId)

        assertEquals(1, result.size)
        assertEquals(serviceId, result.first().id)
    }
}
