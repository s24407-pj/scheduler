package pl.kacosmetology.scheduler.user

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.dto.UserProfileResponse

/**
 * REST API for managing employee profile photos.
 * All operations require the OWNER role and operate within the owner's company.
 */
@RestController
@RequestMapping("/api/employees")
class EmployeeController(
    private val employeePhotoService: EmployeePhotoService
) {

    /**
     * Uploads or replaces the profile photo for [id].
     * Multipart field name: `photo`. Max 5 MB; allowed types: JPEG, PNG, WebP.
     * Requires OWNER role.
     */
    @PostMapping("/{id}/photo")
    @PreAuthorize("hasRole('OWNER')")
    fun uploadPhoto(
        @PathVariable id: Long,
        @RequestParam("photo") file: MultipartFile,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): UserProfileResponse {
        val companyId = requireCompanyId(userDetails)
        val user = try {
            employeePhotoService.upload(companyId, id, file)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        return user.toProfileResponse()
    }

    /**
     * Removes the profile photo for [id]. If no photo exists, this is a no-op.
     * Requires OWNER role.
     */
    @DeleteMapping("/{id}/photo")
    @PreAuthorize("hasRole('OWNER')")
    fun deletePhoto(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): UserProfileResponse {
        val companyId = requireCompanyId(userDetails)
        val user = try {
            employeePhotoService.delete(companyId, id)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
        return user.toProfileResponse()
    }

    // ---- helpers ----

    private fun requireCompanyId(userDetails: CustomUserDetails): Long =
        userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisania do firmy")

    private fun User.toProfileResponse() = UserProfileResponse(
        id = id,
        phoneNumber = phoneNumber,
        firstName = firstName,
        lastName = lastName,
        email = email,
        photoUrl = photoUrl
    )
}
