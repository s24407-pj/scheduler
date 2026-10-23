package pl.kacosmetology.scheduler.user

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.util.*
import org.hibernate.annotations.UuidGenerator

/**
 * Global phone-only customer authentication account. Salon data belongs to [CompanyCustomer]. Legacy transient
 * constructor fields support older test fixtures only; runtime authentication/presentation must never use them.
 */
@Entity
@Table(name = "customer_accounts")
class User(
    id: UUID? = null,
    @Column(name = "phone_number", nullable = false, unique = true) val phoneNumber: String,
    @JsonIgnore @Transient var firstName: String = "",
    @JsonIgnore @Transient var lastName: String = "",
    @JsonIgnore @Transient var email: String? = null,
    @JsonIgnore @Transient val passwordHash: String? = null,
    @JsonIgnore @Transient var photoUrl: String? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
