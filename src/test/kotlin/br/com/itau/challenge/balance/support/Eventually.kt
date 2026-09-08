package br.com.itau.challenge.balance.support

import java.time.Duration

/**
 * Retries an assertion until it holds or the deadline passes.
 *
 * Written here rather than pulled in as a dependency: it is twelve lines, and the project's rule is
 * that a new dependency needs a justification stronger than "it saves twelve lines".
 *
 * A fixed `Thread.sleep` was the alternative and is worse in both directions — too short and the
 * test is flaky, too long and every run pays for the worst case. Polling finishes as soon as the
 * condition holds and fails with the assertion's own message, not a timeout.
 */
fun eventually(
    timeout: Duration = Duration.ofSeconds(30),
    interval: Duration = Duration.ofMillis(200),
    assertion: () -> Unit,
) {
    val deadline = System.nanoTime() + timeout.toNanos()
    var lastFailure: Throwable

    while (true) {
        try {
            assertion()
            return
        } catch (e: AssertionError) {
            lastFailure = e
        } catch (e: IllegalStateException) {
            // The datastore or broker may not have the row yet; that is what we are waiting for.
            lastFailure = e
        }

        if (System.nanoTime() >= deadline) {
            throw AssertionError("condition did not hold within $timeout", lastFailure)
        }
        Thread.sleep(interval.toMillis())
    }
}
