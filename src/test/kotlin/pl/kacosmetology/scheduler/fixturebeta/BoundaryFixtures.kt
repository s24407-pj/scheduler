package pl.kacosmetology.scheduler.fixturebeta

import java.util.UUID
import org.springframework.context.annotation.Profile
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.fixturealpha.AlphaRepository

// Inactive profiles keep annotated bytecode fixtures out of application integration contexts.

class ForeignService {
    fun execute() = Unit
}

class ForeignRepository {
    fun read() = Unit
}

@NoRepositoryBean interface DataStore : Repository<Any, UUID>

@org.springframework.stereotype.Repository @Profile("architecture-fixtures") class AnnotatedStore

@Service @Profile("architecture-fixtures") class SharedPolicy

class BetaRepository(val alpha: AlphaRepository)
