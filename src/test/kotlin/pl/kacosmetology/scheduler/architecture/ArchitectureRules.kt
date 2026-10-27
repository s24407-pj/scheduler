package pl.kacosmetology.scheduler.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import jakarta.persistence.EntityManager
import org.springframework.data.repository.Repository
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.stereotype.Controller

/** Bytecode boundaries intentionally allow cross-module repositories, policies and package cycles. */
internal object ArchitectureRules {
    const val ROOT_PACKAGE = "pl.kacosmetology.scheduler"

    private val sharedServices =
        setOf(
            "$ROOT_PACKAGE.security.SessionAuthenticationService",
            "$ROOT_PACKAGE.security.CustomUserDetailsService",
        )

    val foreignServices =
        classes()
            .should(
                object : ArchCondition<JavaClass>("not depend on services from other modules") {
                    override fun check(item: JavaClass, events: ConditionEvents) {
                        val module = moduleOf(item) ?: return
                        item.directDependenciesFromSelf.forEach { dependency ->
                            val service =
                                owners(dependency.targetClass).firstOrNull { it.simpleName.endsWith("Service") }
                            if (
                                service != null &&
                                    moduleOf(service) != null &&
                                    moduleOf(service) != module &&
                                    service.name !in sharedServices
                            ) {
                                events.add(SimpleConditionEvent.violated(dependency, dependency.description))
                            }
                        }
                    }
                },
            )

    val controllerPersistence =
        classes()
            .should(
                object : ArchCondition<JavaClass>("not access persistence directly from controllers") {
                    override fun check(item: JavaClass, events: ConditionEvents) {
                        if (owners(item).none(::isController)) return
                        item.directDependenciesFromSelf.forEach { dependency ->
                            if (owners(dependency.targetClass).any(::isPersistence)) {
                                events.add(SimpleConditionEvent.violated(dependency, dependency.description))
                            }
                        }
                    }
                },
            )

    private fun owners(type: JavaClass): Sequence<JavaClass> = generateSequence(type) { it.enclosingClass.orElse(null) }

    private fun moduleOf(type: JavaClass): String? =
        type.packageName.removePrefix("$ROOT_PACKAGE.").takeIf { it != type.packageName }?.substringBefore('.')

    private fun isController(type: JavaClass): Boolean =
        type.simpleName.endsWith("Controller") ||
            type.isAnnotatedWith(Controller::class.java) ||
            type.isMetaAnnotatedWith(Controller::class.java)

    private fun isPersistence(type: JavaClass): Boolean =
        (moduleOf(type) != null &&
            (type.simpleName.endsWith("Repository") ||
                type.isAnnotatedWith(org.springframework.stereotype.Repository::class.java))) ||
            type.isAssignableTo(Repository::class.java) ||
            type.isAssignableTo(JdbcOperations::class.java) ||
            type.isAssignableTo(NamedParameterJdbcOperations::class.java) ||
            type.isAssignableTo(EntityManager::class.java)
}
