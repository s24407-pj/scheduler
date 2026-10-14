package pl.kacosmetology.scheduler.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Enforces the layering rules from AGENTS.md on production sources: Controller → Service → Repository, and no
 * cross-module service calls.
 */
class ArchitectureTest {

    private val scope = Konsist.scopeFromProduction()

    @Test
    fun `modules should not depend on services from other modules`() {
        scope.files.assertTrue { file ->
            val module = moduleOf(file.packagee?.name ?: return@assertTrue true)
            file.imports.none { import ->
                val importedModule = moduleOf(import.name.substringBeforeLast('.'))
                importedModule != null &&
                    importedModule != module &&
                    importedModule !in SHARED_MODULES &&
                    import.name.substringAfterLast('.').endsWith("Service")
            }
        }
    }

    @Test
    fun `controllers should not inject repositories`() {
        scope.classes().withNameEndingWith("Controller").assertFalse { controller ->
            controller.primaryConstructor?.parameters.orEmpty().any { it.type.name.endsWith("Repository") }
        }
    }

    /** Returns the top-level module of [packageName] (e.g. `company` for `...scheduler.company.dto`). */
    private fun moduleOf(packageName: String): String? =
        packageName.removePrefix("$ROOT_PACKAGE.").takeIf { it != packageName }?.substringBefore('.')

    private companion object {
        const val ROOT_PACKAGE = "pl.kacosmetology.scheduler"

        /** Infrastructure modules whose services (e.g. `SessionAuthenticationService`) any module may use. */
        val SHARED_MODULES = setOf("security", "config")
    }
}
