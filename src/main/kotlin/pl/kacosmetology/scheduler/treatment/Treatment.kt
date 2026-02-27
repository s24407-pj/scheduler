package pl.kacosmetology.scheduler.treatment

import jakarta.persistence.*
import jakarta.validation.constraints.*
import java.time.LocalDateTime

@Entity
@Table(name = "services")
class ProvidedService(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @field:NotBlank(message = "Nazwa usługi nie może być pusta")
    @field:Size(min = 2, max = 100, message = "Nazwa musi mieć od 2 do 100 znaków")
    @Column(nullable = false)
    val name: String,

    @field:Min(value = 1, message = "Usługa musi trwać minimum 1 minutę")
    @field:Max(value = 480, message = "Usługa nie może trwać dłużej niż 8 godzin")
    @Column(name = "duration_minutes", nullable = false)
    val durationMinutes: Int,

    @field:PositiveOrZero(message = "Cena nie może być ujemna")
    @Column(nullable = false)
    val price: Int,

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
)