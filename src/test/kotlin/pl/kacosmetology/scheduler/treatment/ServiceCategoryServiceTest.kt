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
import pl.kacosmetology.scheduler.treatment.dto.CategoryRequest
import java.util.*

@ExtendWith(MockKExtension::class)
class ServiceCategoryServiceTest {

    @MockK
    private lateinit var categoryRepository: ServiceCategoryRepository

    @MockK
    private lateinit var treatmentRepository: TreatmentRepository

    @InjectMockKs
    private lateinit var serviceCategoryService: ServiceCategoryService

    private val companyId = 1L
    private val categoryId = 50L
    private val serviceId = 100L

    @Test
    fun `createCategory should save and return category`() {
        val request = CategoryRequest(name = "Koloryzacja")
        val saved = ServiceCategory(id = categoryId, companyId = companyId, name = "Koloryzacja")

        every { categoryRepository.existsByCompanyIdAndName(companyId, "Koloryzacja") } returns false
        every { categoryRepository.save(any()) } returns saved

        val result = serviceCategoryService.createCategory(companyId, request)

        assertEquals(categoryId, result.id)
        assertEquals("Koloryzacja", result.name)
        verify(exactly = 1) { categoryRepository.save(any()) }
    }

    @Test
    fun `createCategory should throw when name already exists`() {
        val request = CategoryRequest(name = "Koloryzacja")
        every { categoryRepository.existsByCompanyIdAndName(companyId, "Koloryzacja") } returns true

        val ex = assertThrows<IllegalStateException> {
            serviceCategoryService.createCategory(companyId, request)
        }
        assertEquals("Kategoria o tej nazwie już istnieje", ex.message)
        verify(exactly = 0) { categoryRepository.save(any()) }
    }

    @Test
    fun `deleteCategory should delete when category belongs to company`() {
        val category = ServiceCategory(id = categoryId, companyId = companyId, name = "Strzyżenie")

        every { categoryRepository.findById(categoryId) } returns Optional.of(category)
        every { categoryRepository.delete(category) } returns Unit

        serviceCategoryService.deleteCategory(categoryId, companyId)

        verify(exactly = 1) { categoryRepository.delete(category) }
    }

    @Test
    fun `deleteCategory should throw when category does not exist`() {
        every { categoryRepository.findById(categoryId) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            serviceCategoryService.deleteCategory(categoryId, companyId)
        }
    }

    @Test
    fun `deleteCategory should throw when category belongs to different company`() {
        val otherCompanyId = 99L
        val category = ServiceCategory(id = categoryId, companyId = otherCompanyId, name = "Strzyżenie")

        every { categoryRepository.findById(categoryId) } returns Optional.of(category)

        assertThrows<IllegalStateException> {
            serviceCategoryService.deleteCategory(categoryId, companyId)
        }
        verify(exactly = 0) { categoryRepository.delete(any()) }
    }

    @Test
    fun `assignCategory should set categoryId on service`() {
        val svc = ProvidedService(id = serviceId, companyId = companyId, name = "Farbowanie", durationMinutes = 60, price = 150)
        val category = ServiceCategory(id = categoryId, companyId = companyId, name = "Koloryzacja")

        every { treatmentRepository.findById(serviceId) } returns Optional.of(svc)
        every { categoryRepository.findById(categoryId) } returns Optional.of(category)
        every { treatmentRepository.save(any()) } answers { firstArg() }

        serviceCategoryService.assignCategory(serviceId, companyId, categoryId)

        verify(exactly = 1) { treatmentRepository.save(match { it.categoryId == categoryId }) }
    }

    @Test
    fun `assignCategory with null categoryId should clear category`() {
        val svc = ProvidedService(id = serviceId, companyId = companyId, name = "Farbowanie", durationMinutes = 60, price = 150, categoryId = categoryId)

        every { treatmentRepository.findById(serviceId) } returns Optional.of(svc)
        every { treatmentRepository.save(any()) } answers { firstArg() }

        serviceCategoryService.assignCategory(serviceId, companyId, null)

        verify(exactly = 1) { treatmentRepository.save(match { it.categoryId == null }) }
    }

    @Test
    fun `assignCategory should throw when category belongs to different company`() {
        val otherCompanyId = 99L
        val svc = ProvidedService(id = serviceId, companyId = companyId, name = "Farbowanie", durationMinutes = 60, price = 150)
        val category = ServiceCategory(id = categoryId, companyId = otherCompanyId, name = "Obca kategoria")

        every { treatmentRepository.findById(serviceId) } returns Optional.of(svc)
        every { categoryRepository.findById(categoryId) } returns Optional.of(category)

        assertThrows<IllegalStateException> {
            serviceCategoryService.assignCategory(serviceId, companyId, categoryId)
        }
        verify(exactly = 0) { treatmentRepository.save(any()) }
    }
}
