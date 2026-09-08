package ai.rever.boss.plugin

import ai.rever.boss.services.supabase.SupabaseConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#370: `SystemPluginManifestService.startSync()` used to launch its startup fetch and
 * Realtime subscription before `SupabaseConfig` finished its own async `initialize()` call in the
 * Compose UI layer, so both routines' first attempt hit `SupabaseConfig.client`'s "not
 * initialized" throw. The fix waits on `SupabaseConfig.isInitialized` first; this pins the
 * precondition itself - that a coroutine awaiting it genuinely suspends until `initialize()` runs,
 * rather than racing it - since `awaitSupabaseInitialized` is private and this is the contract it
 * relies on.
 */
class SystemPluginManifestSyncOrderingTest {
    @AfterTest
    fun cleanup() {
        SupabaseConfig.clear()
    }

    @Test
    fun `a coroutine awaiting isInitialized does not complete before initialize is called`() =
        runTest {
            SupabaseConfig.clear()
            assertFalse(SupabaseConfig.isInitialized.value, "precondition: not yet initialized")

            val waiter = async { SupabaseConfig.isInitialized.first { it } }
            // Let the waiter actually start and register as a collector on the still-false flow,
            // rather than asserting on a coroutine that has merely been scheduled but never run -
            // that would pass unconditionally, proving nothing about suspension.
            runCurrent()

            assertFalse(waiter.isCompleted, "the waiter must still be suspended before initialize() runs")

            SupabaseConfig.initialize("example.supabase.co", "test-anon-key")
            runCurrent()

            assertTrue(waiter.isCompleted, "the waiter must resolve once initialize() runs")
            assertTrue(waiter.await())
        }
}
