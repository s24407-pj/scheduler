package pl.kacosmetology.scheduler.offering

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.offering.dto.OfferingCategoryRequest

/** Business logic for managing offering categories. */
@Service
class OfferingCategoryService(
    private val categoryRepository: OfferingCategoryRepository,
    private val offeringRepository: OfferingRepository
) {

    /** Returns all categories for the given company. */
    @Transactional(readOnly = true)
    fun getCategories(companyId: Long): List<OfferingCategory> =
        categoryRepository.findAllByCompanyId(companyId)

    /**
     * Creates a new category for the company.
     * Throws [IllegalStateException] if a category with the same name already exists.
     */
    @Transactional
    fun createCategory(companyId: Long, request: OfferingCategoryRequest): OfferingCategory {
        if (categoryRepository.existsByCompanyIdAndName(companyId, request.name)) {
            throw IllegalStateException("Kategoria o tej nazwie już istnieje")
        }
        return categoryRepository.save(OfferingCategory(companyId = companyId, name = request.name))
    }

    /**
     * Deletes a category. Offerings using it will have their [Offering.categoryId] set to null automatically.
     * Throws [NoSuchElementException] if the category does not exist.
     * Throws [IllegalStateException] if the category belongs to a different company.
     */
    @Transactional
    fun deleteCategory(categoryId: Long, companyId: Long) {
        val category = categoryRepository.findById(categoryId)
            .orElseThrow { NoSuchElementException("Kategoria nie istnieje") }
        if (category.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej kategorii")
        }
        categoryRepository.delete(category)
    }

    /**
     * Assigns or removes a category from an offering.
     * Pass [categoryId] = null to remove the category assignment.
     * Validates that both the offering and category belong to [companyId].
     */
    @Transactional
    fun assignCategory(offeringId: Long, companyId: Long, categoryId: Long?) {
        val offering = offeringRepository.findById(offeringId)
            .orElseThrow { NoSuchElementException("Usługa nie istnieje") }
        if (offering.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }
        if (categoryId != null) {
            val category = categoryRepository.findById(categoryId)
                .orElseThrow { NoSuchElementException("Kategoria nie istnieje") }
            if (category.companyId != companyId) {
                throw IllegalStateException("Brak dostępu do tej kategorii")
            }
        }
        offeringRepository.save(
            Offering(
                id = offering.id,
                companyId = offering.companyId,
                name = offering.name,
                durationMinutes = offering.durationMinutes,
                price = offering.price,
                active = offering.active,
                categoryId = categoryId
            )
        )
    }
}
