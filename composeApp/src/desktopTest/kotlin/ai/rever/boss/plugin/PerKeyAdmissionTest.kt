package ai.rever.boss.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [PerKeyAdmission] — the try-acquire guard behind
 * [ai.rever.boss.components.plugin.PluginUpdateBridge.performUpdate] (BossConsole#600). The
 * invariant that matters: a second caller for a key already held must be told immediately and
 * must never run its own work, not join the first caller's eventual result the way
 * [KeyedDetachedJobs] would.
 */
class PerKeyAdmissionTest {
    @Test
    fun `a second acquire for the same key fails while the first is held`() {
        val admission = PerKeyAdmission<String>()

        assertTrue(admission.tryAcquire("plugin"), "the first caller must be admitted")
        assertFalse(admission.tryAcquire("plugin"), "a second caller must be refused while the first still holds it")
    }

    @Test
    fun `a refused caller never runs its own work`() =
        runBlocking {
            val admission = PerKeyAdmission<String>()
            val executions = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()

            val first =
                async {
                    admission.tryAcquire("plugin")
                    executions.incrementAndGet()
                    entered.complete(Unit)
                    gate.await()
                    admission.release("plugin")
                    "first"
                }
            entered.await()

            // UNDISPATCHED so the attempt happens before this async returns — guaranteed to
            // observe "plugin" still held by the first caller.
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    if (!admission.tryAcquire("plugin")) return@async "busy"
                    executions.incrementAndGet()
                    "second"
                }
            assertEquals("busy", second.await())
            assertEquals(1, executions.get(), "the refused caller must not have run its work")

            gate.complete(Unit)
            assertEquals("first", first.await())
        }

    @Test
    fun `release lets a later acquire for the same key succeed`() {
        val admission = PerKeyAdmission<String>()

        assertTrue(admission.tryAcquire("plugin"))
        admission.release("plugin")

        assertTrue(admission.tryAcquire("plugin"), "a retry after release must be admitted, not permanently locked out")
    }

    @Test
    fun `release is idempotent`() {
        val admission = PerKeyAdmission<String>()

        admission.release("never-held")
        assertTrue(admission.tryAcquire("never-held"), "releasing a key nobody held must not corrupt later admission")
    }

    @Test
    fun `distinct keys are admitted independently`() {
        val admission = PerKeyAdmission<String>()

        assertTrue(admission.tryAcquire("a"))
        assertTrue(admission.tryAcquire("b"), "an unrelated key must not queue behind another key's holder")
    }

    @Test
    fun `under real concurrency exactly one of many racing acquires wins`() =
        runBlocking {
            val admission = PerKeyAdmission<String>()
            val winners = AtomicInteger()

            val racers =
                (1..50).map {
                    async {
                        if (admission.tryAcquire("plugin")) winners.incrementAndGet()
                    }
                }
            withTimeout(5_000) { racers.forEach { it.await() } }

            assertEquals(1, winners.get(), "exactly one of many simultaneous acquires must win")
        }
}
