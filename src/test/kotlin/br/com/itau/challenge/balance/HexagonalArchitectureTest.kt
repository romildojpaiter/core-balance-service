package br.com.itau.challenge.balance

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue as kotlinAssertTrue

/**
 * Enforces the hexagonal boundary over the packages that are actually shipped
 * (Constitution II and III). The starter kit scoped this test at
 * `br.com.itau.challenge.hello..`, a package that never existed in the shipped code — so the
 * assertions ran over an empty set and passed without protecting anything. The scope below is the
 * real production package, and [scope contains the shipped classes] guards against that failure
 * mode returning if a package is ever renamed.
 *
 * Every scope is pinned to the `main` source set. Without it Konsist also scans `test`, and rules
 * such as [application services implement an input port] would then be asserted against the test
 * classes that exercise those services — failing for a reason that has nothing to do with the
 * architecture.
 */
class HexagonalArchitectureTest {

    private val scope = productionScope("br.com.itau.challenge.balance..")

    private val domain = Layer("Domain", "..balance.domain..")
    private val port = Layer("Port", "..balance.port..")
    private val application = Layer("Application", "..balance.application..")
    private val adapter = Layer("Adapter", "..balance.adapter..")

    @Test
    fun `scope contains the shipped classes`() {
        kotlinAssertTrue(
            scope.classes().isNotEmpty(),
            "Konsist scope is empty: the architecture assertions would pass vacuously. Check the package name.",
        )
    }

    @Test
    fun `hexagonal layers respect dependency direction`() {
        scope.assertArchitecture {
            domain.dependsOnNothing()
            port.doesNotDependOn(application, adapter)
            application.doesNotDependOn(adapter)
        }
    }

    @Test
    fun `domain does not depend on any infrastructure library`() {
        productionScope("br.com.itau.challenge.balance.domain..")
            .files
            .assertFalse { file ->
                file.hasImport { import -> FORBIDDEN_DOMAIN_IMPORT_PREFIXES.any { import.name.startsWith(it) } }
            }
    }

    @Test
    fun `application does not depend on infrastructure clients`() {
        productionScope("br.com.itau.challenge.balance.application..")
            .files
            .assertFalse { file ->
                file.hasImport { import ->
                    import.name.startsWith("software.amazon") ||
                        import.name.startsWith("org.apache.kafka") ||
                        import.name.startsWith("io.micrometer")
                }
            }
    }

    @Test
    fun `input adapters do not depend on the aws sdk`() {
        productionScope("br.com.itau.challenge.balance.adapter.input..")
            .files
            .assertFalse { file -> file.hasImport { import -> import.name.startsWith("software.amazon") } }
    }

    @Test
    fun `application services implement an input port`() {
        productionScope("br.com.itau.challenge.balance.application..")
            .classes()
            .assertTrue { klass -> klass.hasParent { parent -> parent.name.endsWith("UseCase") } }
    }

    private fun productionScope(packageName: String) = Konsist.scopeFromPackage(packageName, sourceSetName = "main")

    private companion object {
        val FORBIDDEN_DOMAIN_IMPORT_PREFIXES =
            listOf(
                "org.springframework",
                "software.amazon",
                "org.apache.kafka",
                "tools.jackson",
                "com.fasterxml.jackson",
                "io.micrometer",
                "jakarta",
            )
    }
}
