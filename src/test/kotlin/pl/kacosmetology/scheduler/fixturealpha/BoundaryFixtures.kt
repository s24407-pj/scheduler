package pl.kacosmetology.scheduler.fixturealpha

import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RestController
import pl.kacosmetology.scheduler.fixturebeta.*
import pl.kacosmetology.scheduler.security.CustomUserDetailsService
import pl.kacosmetology.scheduler.security.SessionAuthenticationService

// Inactive profiles keep annotated bytecode fixtures out of application integration contexts.

class WildcardClient(val service: ForeignService)

class FullyQualifiedClient {
    fun execute() = pl.kacosmetology.scheduler.fixturebeta.ForeignService().execute()
}

class UnexpectedSecurityClient(val service: pl.kacosmetology.scheduler.security.UnexpectedService)

class UnexpectedConfigClient(val service: pl.kacosmetology.scheduler.config.UnexpectedService)

class ConstructorController(val repository: ForeignRepository)

class PropertyController {
    lateinit var repository: ForeignRepository
}

class MethodController {
    fun read() = ForeignRepository().read()
}

class JdbcController(val jdbc: JdbcTemplate)

class JdbcInterfaceController(val jdbc: JdbcOperations)

@RestController class NamedJdbcEndpoint(val jdbc: NamedParameterJdbcTemplate)

class NamedJdbcInterfaceController(val jdbc: NamedParameterJdbcOperations)

@Controller
@Profile("architecture-fixtures")
class JpaEndpoint {
    lateinit var manager: EntityManager
}

class SpringRepositoryController(val store: DataStore)

class AnnotatedRepositoryController(val store: AnnotatedStore)

class LocalService

class LocalController(val service: LocalService)

class AllowedClient(
    val local: LocalService,
    val repository: ForeignRepository,
    val policy: SharedPolicy,
    val authentication: SessionAuthenticationService,
    val userDetails: CustomUserDetailsService,
)

class AlphaRepository(val beta: BetaRepository)
