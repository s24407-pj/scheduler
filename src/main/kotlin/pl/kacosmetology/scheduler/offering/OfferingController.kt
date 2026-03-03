package pl.kacosmetology.scheduler.offering

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.offering.dto.OfferingRequest
import pl.kacosmetology.scheduler.offering.dto.OfferingResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails

/**
 * REST API for managing salon offerings.
 * Public read endpoints are available without authentication; write operations require the OWNER role.
 */
@RestController
@RequestMapping("/api/offerings")
class OfferingController(
    private val offeringService: OfferingService,
    private val offeringImageService: OfferingImageService
) {

    /** Returns all active offerings for a given company. Public — no authentication required. */
    @GetMapping("/public/company/{companyId}")
    fun getPublicCompanyOfferings(@PathVariable companyId: Long): List<OfferingResponse> {
        val offerings = offeringService.getCompanyOfferings(companyId)
        return enrichWithImages(offerings)
    }

    /** Returns all offerings (including inactive) for a company. Requires staff membership in the same company. */
    @GetMapping("/company/{companyId}")
    fun getCompanyOfferings(
        @PathVariable companyId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<OfferingResponse> {
        if (userDetails.companyId != companyId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tej firmy")
        }
        val offerings = offeringService.getAllCompanyOfferings(companyId)
        return enrichWithImages(offerings)
    }

    /** Returns a single offering by ID. Requires staff membership in the same company as the offering. */
    @GetMapping("/{id}")
    fun getOfferingById(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): OfferingResponse {
        val offering = findOfferingOrThrow(id)
        if (offering.companyId != userDetails.companyId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tej usługi")
        }
        return enrichWithImages(offering)
    }

    /** Creates a new offering for the authenticated user's company. Requires OWNER role. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER')")
    fun createOffering(
        @Valid @RequestBody request: OfferingRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): OfferingResponse {
        val offering = offeringService.createOffering(requireCompanyId(userDetails), request)
        return enrichWithImages(offering)
    }

    /** Updates an existing offering. Requires OWNER role. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER')")
    fun updateOffering(
        @PathVariable id: Long,
        @Valid @RequestBody request: OfferingRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): OfferingResponse {
        return try {
            val offering = offeringService.updateOffering(id, requireCompanyId(userDetails), request)
            enrichWithImages(offering)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    /** Reactivates a previously deactivated offering. Requires OWNER role. */
    @PatchMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER')")
    fun activateOffering(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        try {
            offeringService.activateOffering(id, requireCompanyId(userDetails))
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    /** Soft-deletes an offering by marking it as inactive. Requires OWNER role. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER')")
    fun deleteOffering(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        try {
            offeringService.deleteOffering(id, requireCompanyId(userDetails))
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    /**
     * Uploads an image for the offering (multipart field `image`).
     * Max 5 images per offering; max 5 MB per file; allowed types: JPEG, PNG, WebP.
     * Requires OWNER role.
     */
    @PostMapping("/{id}/image")
    @PreAuthorize("hasAnyRole('OWNER')")
    fun uploadImage(
        @PathVariable id: Long,
        @RequestParam("image") file: MultipartFile,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): OfferingResponse {
        findOfferingOrThrow(id) // throws 404 before file validation runs
        return try {
            val offering = offeringService.uploadImage(id, requireCompanyId(userDetails), file)
            enrichWithImages(offering)
        } catch (e: IllegalArgumentException) {
            // Only file-validation errors reach here (type / size)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
    }

    /**
     * Deletes one image from the offering by [imageId].
     * Requires OWNER role.
     */
    @DeleteMapping("/{id}/image/{imageId}")
    @PreAuthorize("hasAnyRole('OWNER')")
    fun deleteImage(
        @PathVariable id: Long,
        @PathVariable imageId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): OfferingResponse {
        return try {
            val offering = offeringService.deleteImage(id, imageId, requireCompanyId(userDetails))
            enrichWithImages(offering)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    // ---- helpers ----

    private fun requireCompanyId(userDetails: CustomUserDetails): Long =
        userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisania do firmy")

    private fun findOfferingOrThrow(id: Long): Offering =
        try {
            offeringService.getOfferingById(id)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }

    private fun enrichWithImages(offering: Offering): OfferingResponse {
        val images = offeringImageService.findByOfferingIds(listOfNotNull(offering.id))
        return OfferingResponse.from(offering, images)
    }

    private fun enrichWithImages(offerings: List<Offering>): List<OfferingResponse> {
        val ids = offerings.mapNotNull { it.id }
        val imagesByOfferingId = offeringImageService.findByOfferingIds(ids).groupBy { it.offeringId }
        return offerings.map { o -> OfferingResponse.from(o, imagesByOfferingId[o.id] ?: emptyList()) }
    }
}
