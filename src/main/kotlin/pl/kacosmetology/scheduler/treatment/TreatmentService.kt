package pl.kacosmetology.scheduler.treatment

import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import pl.kacosmetology.scheduler.treatment.dto.TreatmentRequest

/** Business logic for managing salon services (CRUD). Results are cached per company. */
@Service
class TreatmentService(
    private val treatmentRepository: TreatmentRepository,
    private val imageService: ImageService
) {

    /** Returns only active services for a company. Used by public endpoints and booking. Cached in Redis. */
    @Transactional(readOnly = true)
    @Cacheable("companyServices", key = "#companyId")
    fun getCompanyServices(companyId: Long): List<ProvidedService> {
        return treatmentRepository.findAllByCompanyIdAndActiveTrue(companyId)
    }

    /** Returns all services (including inactive) for a company. Used by owner/staff management panel. */
    @Transactional(readOnly = true)
    fun getAllCompanyServices(companyId: Long): List<ProvidedService> {
        return treatmentRepository.findAllByCompanyId(companyId)
    }

    /** Returns a single service by ID or throws [IllegalArgumentException]. */
    @Transactional(readOnly = true)
    fun getServiceById(id: Long): ProvidedService {
        return treatmentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("Usługa o ID $id nie istnieje") }
    }

    /** Creates a new service and evicts the company services cache. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun createService(companyId: Long, request: TreatmentRequest): ProvidedService {
        return treatmentRepository.save(
            ProvidedService(
                companyId = companyId,
                name = request.name,
                durationMinutes = request.durationMinutes,
                price = request.price
            )
        )
    }

    /** Updates an existing service. Throws if the service belongs to a different company. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun updateService(id: Long, companyId: Long, request: TreatmentRequest): ProvidedService {
        val existing = getServiceById(id)

        if (existing.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }

        return treatmentRepository.save(
            ProvidedService(
                id = existing.id,
                companyId = existing.companyId,
                name = request.name,
                durationMinutes = request.durationMinutes,
                price = request.price,
                active = existing.active,
                categoryId = existing.categoryId
            )
        )
    }

    /** Reactivates a previously deactivated service. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun activateService(id: Long, companyId: Long) {
        val existing = getServiceById(id)

        if (existing.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }

        treatmentRepository.save(
            ProvidedService(
                id = existing.id,
                companyId = existing.companyId,
                name = existing.name,
                durationMinutes = existing.durationMinutes,
                price = existing.price,
                active = true,
                categoryId = existing.categoryId
            )
        )
    }

    /**
     * Uploads an image for the service and persists it in R2 + DB.
     * Validates that [companyId] owns the service and that the per-service image limit is not exceeded.
     */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun uploadImage(serviceId: Long, companyId: Long, file: MultipartFile): ProvidedService {
        val service = getServiceById(serviceId)
        if (service.companyId != companyId) throw IllegalStateException("Brak dostępu do tej usługi")
        imageService.upload(companyId, serviceId, file)
        return service
    }

    /**
     * Deletes the specified image from R2 and DB.
     * Validates that [companyId] owns the service.
     */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun deleteImage(serviceId: Long, imageId: Long, companyId: Long): ProvidedService {
        val service = getServiceById(serviceId)
        if (service.companyId != companyId) throw IllegalStateException("Brak dostępu do tej usługi")
        imageService.delete(imageId, serviceId)
        return service
    }

    /** Soft-deletes a service by marking it as inactive. Existing reservations are preserved. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun deleteService(id: Long, companyId: Long) {
        val existing = getServiceById(id)

        if (existing.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }

        treatmentRepository.save(
            ProvidedService(
                id = existing.id,
                companyId = existing.companyId,
                name = existing.name,
                durationMinutes = existing.durationMinutes,
                price = existing.price,
                active = false,
                categoryId = existing.categoryId
            )
        )
    }
}
