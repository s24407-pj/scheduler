package pl.kacosmetology.scheduler.offering

import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import pl.kacosmetology.scheduler.offering.dto.OfferingRequest

/** Business logic for managing salon offerings (CRUD). Results are cached per company. */
@Service
class OfferingService(
    private val offeringRepository: OfferingRepository,
    private val offeringImageService: OfferingImageService
) {

    /** Returns only active offerings for a company. Used by public endpoints and booking. Cached in Redis. */
    @Transactional(readOnly = true)
    @Cacheable("companyServices", key = "#companyId")
    fun getCompanyOfferings(companyId: Long): List<Offering> {
        return offeringRepository.findAllByCompanyIdAndActiveTrue(companyId)
    }

    /** Returns all offerings (including inactive) for a company. Used by owner/staff management panel. */
    @Transactional(readOnly = true)
    fun getAllCompanyOfferings(companyId: Long): List<Offering> {
        return offeringRepository.findAllByCompanyId(companyId)
    }

    /** Returns a single offering by ID or throws [IllegalArgumentException]. */
    @Transactional(readOnly = true)
    fun getOfferingById(id: Long): Offering {
        return offeringRepository.findById(id)
            .orElseThrow { IllegalArgumentException("Usługa o ID $id nie istnieje") }
    }

    /** Creates a new offering and evicts the company offerings cache. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun createOffering(companyId: Long, request: OfferingRequest): Offering {
        return offeringRepository.save(
            Offering(
                companyId = companyId,
                name = request.name,
                durationMinutes = request.durationMinutes,
                price = request.price
            )
        )
    }

    /** Updates an existing offering. Throws if the offering belongs to a different company. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun updateOffering(id: Long, companyId: Long, request: OfferingRequest): Offering {
        val existing = getOfferingById(id)

        if (existing.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }

        existing.name = request.name
        existing.durationMinutes = request.durationMinutes
        existing.price = request.price
        return existing
    }

    /** Reactivates a previously deactivated offering. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun activateOffering(id: Long, companyId: Long) {
        val existing = getOfferingById(id)

        if (existing.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }

        existing.active = true
    }

    /**
     * Uploads an image for the offering and persists it in R2 + DB.
     * Validates that [companyId] owns the offering and that the per-offering image limit is not exceeded.
     */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun uploadImage(offeringId: Long, companyId: Long, file: MultipartFile): Offering {
        val offering = getOfferingById(offeringId)
        if (offering.companyId != companyId) throw IllegalStateException("Brak dostępu do tej usługi")
        offeringImageService.upload(companyId, offeringId, file)
        return offering
    }

    /**
     * Deletes the specified image from R2 and DB.
     * Validates that [companyId] owns the offering.
     */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun deleteImage(offeringId: Long, imageId: Long, companyId: Long): Offering {
        val offering = getOfferingById(offeringId)
        if (offering.companyId != companyId) throw IllegalStateException("Brak dostępu do tej usługi")
        offeringImageService.delete(imageId, offeringId)
        return offering
    }

    /** Soft-deletes an offering by marking it as inactive. Existing reservations are preserved. */
    @Transactional
    @CacheEvict("companyServices", key = "#companyId")
    fun deleteOffering(id: Long, companyId: Long) {
        val existing = getOfferingById(id)

        if (existing.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej usługi")
        }

        existing.active = false
    }
}
