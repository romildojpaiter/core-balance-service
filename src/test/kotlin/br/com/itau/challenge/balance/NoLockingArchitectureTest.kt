package br.com.itau.challenge.balance

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * FR-027 forbids locking, and until now that was only text.
 *
 * The prohibition is not stylistic. A JVM lock protects one process, so it cannot order writes
 * across the three consumer threads of one instance *and* the several instances the deployment
 * runs — it would create the appearance of safety while the real race stayed open. A distributed
 * lock would work and is worse: it adds infrastructure, latency, and the problem of the holder that
 * dies mid-hold, all to buy a guarantee the DynamoDB conditional expression already gives for free.
 *
 * If this test ever fails, the fix is almost never to add the lock back — it is to move the decision
 * into the conditional write.
 */
class NoLockingArchitectureTest {

    private val production = Konsist.scopeFromPackage("br.com.itau.challenge.balance..", sourceSetName = "main")

    @Test
    fun `the scope is not empty`() {
        assertTrue(production.classes().isNotEmpty(), "Konsist scope is empty; check the package name")
    }

    @Test
    fun `no production file imports a locking primitive`() {
        production.files.assertFalse { file ->
            file.hasImport { import -> FORBIDDEN_IMPORT_PREFIXES.any { import.name.startsWith(it) } }
        }
    }

    @Test
    fun `no production file uses synchronized or a lock keyword`() {
        val offenders =
            production.files
                .map { File(it.path) }
                .filter { it.exists() }
                .filter { file ->
                    val source = file.readText().lineSequence().filterNot { it.trimStart().startsWith("*") }.joinToString("\n")
                    FORBIDDEN_TOKENS.any { source.contains(it) }
                }.map { it.name }

        assertTrue(offenders.isEmpty(), "locking primitives found in: $offenders")
    }

    private companion object {
        val FORBIDDEN_IMPORT_PREFIXES =
            listOf(
                "java.util.concurrent.locks",
                "kotlinx.coroutines.sync",
                "java.util.concurrent.Semaphore",
            )

        val FORBIDDEN_TOKENS = listOf("synchronized(", "@Synchronized", "ReentrantLock", "StampedLock")
    }
}
