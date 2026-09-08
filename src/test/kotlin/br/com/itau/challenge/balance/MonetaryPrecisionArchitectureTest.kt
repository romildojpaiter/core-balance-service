package br.com.itau.challenge.balance

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Turns Constitution X from prose into something that breaks the build.
 *
 * Binary floating point cannot represent most decimal fractions: `0.1 + 0.2` is `0.30000000000000004`
 * in every IEEE-754 implementation. Applied to money, the error is invisible in a single value and
 * compounds silently across a system. The rule is therefore absolute — no `Double` or `Float`
 * anywhere in production code, not merely "not for money", because a value that starts as a
 * throughput measurement has a way of becoming an amount.
 *
 * `Money` already makes the violation hard to write by not offering a `Double` constructor. This
 * test covers the rest of the codebase, where no such type stands guard.
 */
class MonetaryPrecisionArchitectureTest {

    private val production = Konsist.scopeFromPackage("br.com.itau.challenge.balance..", sourceSetName = "main")

    @Test
    fun `the scope is not empty`() {
        // Without this, every assertion below would pass vacuously if the package were renamed.
        assertTrue(production.classes().isNotEmpty(), "Konsist scope is empty; check the package name")
    }

    @Test
    fun `no production property is declared as a floating point number`() {
        production.properties().assertFalse { property ->
            FORBIDDEN_TYPES.any { property.type?.name?.removeSuffix("?") == it }
        }
    }

    @Test
    fun `no production function returns or accepts a floating point number`() {
        production.functions().assertFalse { function ->
            FORBIDDEN_TYPES.any { forbidden ->
                function.returnType?.name?.removeSuffix("?") == forbidden ||
                    function.parameters.any { it.type.name.removeSuffix("?") == forbidden }
            }
        }
    }

    @Test
    fun `no production constructor accepts a floating point number`() {
        production.classes().assertFalse { klass ->
            klass.constructors.any { constructor ->
                constructor.parameters.any { parameter ->
                    FORBIDDEN_TYPES.any { parameter.type.name.removeSuffix("?") == it }
                }
            }
        }
    }

    private companion object {
        val FORBIDDEN_TYPES = listOf("Double", "Float", "double", "float")
    }
}
