package pl.kacosmetology.scheduler.treatment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.treatment.dto.CategoryRequest

/** Business logic for managing service categories. */
@Service
class ServiceCategoryService(
    private val categoryRepository: ServiceCategoryRepository,
    private val treatmentRepository: TreatmentRepository
) {

    /** Returns all categories for the given company. */
    @Transactional(readOnly = true)
    fun getCategories(companyId: Long): List<ServiceCategory> =
        categoryRepository.findAllByCompanyId(companyId)

    /**
     * Creates a new category for the company.
     * Throws [IllegalStateException] if a category with the same name already exists.
     */
    @Transactional
    fun createCategory(companyId: Long, request: CategoryRequest): ServiceCategory {
        if (categoryRepository.existsByCompanyIdAndName(companyId, request.name)) {
            throw IllegalStateException("Kategoria o tej nazwie już istnieje")
        }
        return categoryRepository.save(ServiceCategory(companyId = companyId, name = request.name))
    }

    /**
     * Deletes a category. Services using it will have their [ProvidedService.categoryId] set to null automatically.
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
     * Assigns or removes a category from a service.
     * Pass [categoryId] = null to remove the category assignment.
     * Validates that both the service and category belong to [companyId].
     */
    @Transactional
    fun assignCategory(serviceId: Long, companyId: Long, categoryId: Long?) {
        val service = treatmentRepository.findById(serviceId)
            .orElseThrow { NoSuchElementException("Usługa nie istnieje") }
        if (service.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }
        if (categoryId != null) {
            val category = categoryRepository.findById(categoryId)
                .orElseThrow { NoSuchElementException("Kategoria nie istnieje") }
            if (category.companyId != companyId) {
                throw IllegalStateException("Brak dostępu do tej kategorii")
            }
        }
        treatmentRepository.save(
            ProvidedService(
                id = service.id,
                companyId = service.companyId,
                name = service.name,
                durationMinutes = service.durationMinutes,
                price = service.price,
                active = service.active,
                categoryId = categoryId
            )
        )
    }
}
