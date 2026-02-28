package pl.kacosmetology.scheduler.treatment

import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.treatment.dto.TreatmentRequest

/** Business logic for managing salon services (CRUD). Results are cached per company. */
@Service
class TreatmentService(
    private val treatmentRepository: TreatmentRepository
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
                active = existing.active
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
                active = true
            )
        )
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
                active = false
            )
        )
    }
}
