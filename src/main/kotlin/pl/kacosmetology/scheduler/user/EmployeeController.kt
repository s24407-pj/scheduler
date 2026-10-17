package pl.kacosmetology.scheduler.user

import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.dto.StaffProfileResponse
import pl.kacosmetology.scheduler.user.dto.toStaffProfile

/** Owner management of company-local employee photos. */
@RestController
@RequestMapping("/api/employees")
@PreAuthorize("hasRole('OWNER')")
class EmployeeController(private val employeePhotoService: EmployeePhotoService) {
    /** Uploads validated bytes to one company employee's profile. */
    @PostMapping("/{id}/photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadPhoto(
        @PathVariable id: UUID,
        @RequestParam("photo") file: MultipartFile,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): StaffProfileResponse = employeePhotoService.upload(userDetails.requireCompanyId(), id, file).toStaffProfile()

    /** Removes only this company's photo reference. */
    @DeleteMapping("/{id}/photo")
    fun deletePhoto(
        @PathVariable id: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): StaffProfileResponse = employeePhotoService.delete(userDetails.requireCompanyId(), id).toStaffProfile()
}
