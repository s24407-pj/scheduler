package pl.kacosmetology.scheduler.user

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*

/** Global phone-only customer authentication account. Salon data belongs to [CompanyCustomer]. */
@Entity
@Table(name = "customer_accounts")
class User(
    id: Long? = null,
    @Column(name = "phone_number", nullable = false, unique = true) val phoneNumber: String,
    @Transient var firstName: String = "",
    @Transient var lastName: String = "",
    @Transient var email: String? = null,
    @JsonIgnore @Transient val passwordHash: String? = null,
    @Transient var photoUrl: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
