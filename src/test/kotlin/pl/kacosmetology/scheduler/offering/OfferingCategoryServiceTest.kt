package pl.kacosmetology.scheduler.offering

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.offering.dto.OfferingCategoryRequest
import java.util.*

@ExtendWith(MockKExtension::class)
class OfferingCategoryServiceTest {

    @MockK
    private lateinit var categoryRepository: OfferingCategoryRepository

    @MockK
    private lateinit var offeringRepository: OfferingRepository

    @InjectMockKs
    private lateinit var offeringCategoryService: OfferingCategoryService

    private val companyId = 1L
    private val categoryId = 50L
    private val offeringId = 100L

    @Test
    fun `createCategory should save and return category`() {
        val request = OfferingCategoryRequest(name = "Koloryzacja")
        val saved = OfferingCategory(id = categoryId, companyId = companyId, name = "Koloryzacja")

        every { categoryRepository.existsByCompanyIdAndName(companyId, "Koloryzacja") } returns false
        every { categoryRepository.save(any()) } returns saved

        val result = offeringCategoryService.createCategory(companyId, request)

        assertEquals(categoryId, result.id)
        assertEquals("Koloryzacja", result.name)
        verify(exactly = 1) { categoryRepository.save(any()) }
    }

    @Test
    fun `createCategory should throw when name already exists`() {
        val request = OfferingCategoryRequest(name = "Koloryzacja")
        every { categoryRepository.existsByCompanyIdAndName(companyId, "Koloryzacja") } returns true

        val ex = assertThrows<IllegalStateException> {
            offeringCategoryService.createCategory(companyId, request)
        }
        assertEquals("Kategoria o tej nazwie już istnieje", ex.message)
        verify(exactly = 0) { categoryRepository.save(any()) }
    }

    @Test
    fun `deleteCategory should delete when category belongs to company`() {
        val category = OfferingCategory(id = categoryId, companyId = companyId, name = "Strzyżenie")

        every { categoryRepository.findById(categoryId) } returns Optional.of(category)
        every { categoryRepository.delete(category) } returns Unit

        offeringCategoryService.deleteCategory(categoryId, companyId)

        verify(exactly = 1) { categoryRepository.delete(category) }
    }

    @Test
    fun `deleteCategory should throw when category does not exist`() {
        every { categoryRepository.findById(categoryId) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            offeringCategoryService.deleteCategory(categoryId, companyId)
        }
    }

    @Test
    fun `deleteCategory should throw when category belongs to different company`() {
        val otherCompanyId = 99L
        val category = OfferingCategory(id = categoryId, companyId = otherCompanyId, name = "Strzyżenie")

        every { categoryRepository.findById(categoryId) } returns Optional.of(category)

        assertThrows<IllegalStateException> {
            offeringCategoryService.deleteCategory(categoryId, companyId)
        }
        verify(exactly = 0) { categoryRepository.delete(any()) }
    }

    @Test
    fun `assignCategory should set categoryId on offering`() {
        val offering =
            Offering(id = offeringId, companyId = companyId, name = "Farbowanie", durationMinutes = 60, price = 150)
        val category = OfferingCategory(id = categoryId, companyId = companyId, name = "Koloryzacja")

        every { offeringRepository.findById(offeringId) } returns Optional.of(offering)
        every { categoryRepository.findById(categoryId) } returns Optional.of(category)
        every { offeringRepository.save(any()) } answers { firstArg() }

        offeringCategoryService.assignCategory(offeringId, companyId, categoryId)

        verify(exactly = 1) { offeringRepository.save(match { it.categoryId == categoryId }) }
    }

    @Test
    fun `assignCategory with null categoryId should clear category`() {
        val offering = Offering(
            id = offeringId,
            companyId = companyId,
            name = "Farbowanie",
            durationMinutes = 60,
            price = 150,
            categoryId = categoryId
        )

        every { offeringRepository.findById(offeringId) } returns Optional.of(offering)
        every { offeringRepository.save(any()) } answers { firstArg() }

        offeringCategoryService.assignCategory(offeringId, companyId, null)

        verify(exactly = 1) { offeringRepository.save(match { it.categoryId == null }) }
    }

    @Test
    fun `assignCategory should throw when category belongs to different company`() {
        val otherCompanyId = 99L
        val offering =
            Offering(id = offeringId, companyId = companyId, name = "Farbowanie", durationMinutes = 60, price = 150)
        val category = OfferingCategory(id = categoryId, companyId = otherCompanyId, name = "Obca kategoria")

        every { offeringRepository.findById(offeringId) } returns Optional.of(offering)
        every { categoryRepository.findById(categoryId) } returns Optional.of(category)

        assertThrows<IllegalStateException> {
            offeringCategoryService.assignCategory(offeringId, companyId, categoryId)
        }
        verify(exactly = 0) { offeringRepository.save(any()) }
    }
}
