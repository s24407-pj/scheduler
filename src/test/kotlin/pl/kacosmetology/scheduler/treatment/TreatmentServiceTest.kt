package pl.kacosmetology.scheduler.treatment

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.treatment.dto.TreatmentRequest
import java.util.*

@ExtendWith(MockKExtension::class)
class TreatmentServiceTest {

    @MockK
    private lateinit var treatmentRepository: TreatmentRepository

    @InjectMockKs
    private lateinit var treatmentService: TreatmentService

    private val companyId = 1L

    @Test
    fun `getCompanyServices should return list of services for given company`() {
        // GIVEN
        val services = listOf(
            ProvidedService(id = 1, companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200),
            ProvidedService(id = 2, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
        )
        every { treatmentRepository.findAllByCompanyIdAndActiveTrue(companyId) } returns services

        // WHEN
        val result = treatmentService.getCompanyServices(companyId)

        // THEN
        assertEquals(2, result.size)
        assertEquals("Masaż", result[0].name)
    }

    @Test
    fun `getServiceById should return service when it exists`() {
        // GIVEN
        val service = ProvidedService(id = 1, companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200)
        every { treatmentRepository.findById(1L) } returns Optional.of(service)

        // WHEN
        val result = treatmentService.getServiceById(1L)

        // THEN
        assertEquals("Masaż", result.name)
    }

    @Test
    fun `getServiceById should throw when service does not exist`() {
        // GIVEN
        every { treatmentRepository.findById(999L) } returns Optional.empty()

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            treatmentService.getServiceById(999L)
        }
        assertEquals("Usługa o ID 999 nie istnieje", exception.message)
    }

    @Test
    fun `createService should save service with correct data`() {
        // GIVEN
        val request = TreatmentRequest(name = "Masaż", durationMinutes = 60, price = 200)
        every { treatmentRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = treatmentService.createService(companyId, request)

        // THEN
        assertEquals(companyId, result.companyId)
        assertEquals("Masaż", result.name)
        assertEquals(60, result.durationMinutes)
        assertEquals(200, result.price)
        verify(exactly = 1) { treatmentRepository.save(any()) }
    }

    @Test
    fun `updateService should update service belonging to company`() {
        // GIVEN
        val existing = ProvidedService(id = 1, companyId = companyId, name = "Stara", durationMinutes = 30, price = 100)
        val request = TreatmentRequest(name = "Nowa", durationMinutes = 45, price = 150)

        every { treatmentRepository.findById(1L) } returns Optional.of(existing)
        every { treatmentRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = treatmentService.updateService(1L, companyId, request)

        // THEN
        assertEquals("Nowa", result.name)
        assertEquals(45, result.durationMinutes)
        assertEquals(150, result.price)
        assertEquals(companyId, result.companyId) // companyId nie zmienione
    }

    @Test
    fun `updateService should throw when service belongs to different company`() {
        // GIVEN
        val existing = ProvidedService(id = 1, companyId = 999L, name = "Obca", durationMinutes = 30, price = 100)
        val request = TreatmentRequest(name = "Hack", durationMinutes = 10, price = 0)

        every { treatmentRepository.findById(1L) } returns Optional.of(existing)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            treatmentService.updateService(1L, companyId, request)
        }
        assertEquals("Brak dostępu do tej usługi", exception.message)
        verify(exactly = 0) { treatmentRepository.save(any()) }
    }

    @Test
    fun `deleteService should soft-delete service belonging to company`() {
        // GIVEN
        val existing =
            ProvidedService(id = 1, companyId = companyId, name = "Do usunięcia", durationMinutes = 30, price = 50)
        every { treatmentRepository.findById(1L) } returns Optional.of(existing)
        every { treatmentRepository.save(any()) } answers { firstArg() }

        // WHEN
        treatmentService.deleteService(1L, companyId)

        // THEN
        verify(exactly = 1) {
            treatmentRepository.save(match { it.id == 1L && !it.active })
        }
        verify(exactly = 0) { treatmentRepository.deleteById(any()) }
    }

    @Test
    fun `deleteService should throw when service belongs to different company`() {
        // GIVEN
        val existing = ProvidedService(id = 1, companyId = 999L, name = "Obca", durationMinutes = 30, price = 100)
        every { treatmentRepository.findById(1L) } returns Optional.of(existing)

        // WHEN & THEN
        assertThrows<IllegalStateException> {
            treatmentService.deleteService(1L, companyId)
        }
        verify(exactly = 0) { treatmentRepository.save(any()) }
    }

    @Test
    fun `deleteService should throw when service does not exist`() {
        // GIVEN
        every { treatmentRepository.findById(999L) } returns Optional.empty()

        // WHEN & THEN
        assertThrows<IllegalArgumentException> {
            treatmentService.deleteService(999L, companyId)
        }
    }
}


