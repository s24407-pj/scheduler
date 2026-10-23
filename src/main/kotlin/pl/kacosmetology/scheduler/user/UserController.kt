package pl.kacosmetology.scheduler.user

import java.util.*
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** Exposes only the global customer's authentication account; salon profiles are staff-managed. */
@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {
    /** Returns the authenticated customer's canonical phone without salon profile data. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun getMyProfile(@AuthenticationPrincipal principal: CustomUserDetails): CustomerAccountResponse {
        val account = userService.getProfile(principal.id)
        return CustomerAccountResponse(requireNotNull(account.id), account.phoneNumber)
    }
}

/** Global customer authentication identity without company-owned presentation. */
data class CustomerAccountResponse(val id: UUID, val phoneNumber: String)
