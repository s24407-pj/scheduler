package pl.kacosmetology.scheduler.user

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*

/**
 * Global phone-only customer authentication account. Salon data belongs to [CompanyCustomer]. Legacy transient
 * constructor fields support older test fixtures only; runtime authentication/presentation must never use them.
 */
@Entity
@Table(name = "customer_accounts")
class User(
    id: Long? = null,
    @Column(name = "phone_number", nullable = false, unique = true) val phoneNumber: String,
    @JsonIgnore @Transient var firstName: String = "",
    @JsonIgnore @Transient var lastName: String = "",
    @JsonIgnore @Transient var email: String? = null,
    @JsonIgnore @Transient val passwordHash: String? = null,
    @JsonIgnore @Transient var photoUrl: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
