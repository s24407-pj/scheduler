package pl.kacosmetology.scheduler.user

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*

/** Unified user entity for both customers and staff members. Role is determined by [pl.kacosmetology.scheduler.company.CompanyEmployee]. */
@Entity
@Table(name = "users")
class User(
    id: Long? = null,

    @Column(name = "phone_number", nullable = false, unique = true)
    val phoneNumber: String,

    @Column(name = "first_name", nullable = false)
    var firstName: String,

    @Column(name = "last_name", nullable = false)
    var lastName: String,

    @Column(unique = true)
    var email: String? = null,

    @JsonIgnore
    @Column(name = "password_hash")
    val passwordHash: String? = null,

    @Column(name = "photo_url")
    var photoUrl: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
