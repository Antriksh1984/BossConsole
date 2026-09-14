package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BossConsole#506: [PluginStorageProviderImpl] is shared across every host window and plugin
 * coroutine that asks for it. Before this, `putString`/`remove`/`clear` each independently
 * snapshotted the cache and wrote it to disk with no serialization, so an older snapshot's write
 * could land after a newer one's and silently revert an already-reported-successful mutation, and
 * a failed write was logged but not surfaced to the caller.
 *
 * Each test uses a distinct, made-up `pluginId` (via [freshProvider]) rather than a real one, and
 * [cleanup] removes its directory afterward.
 */
class PluginStorageProviderImplTest {
    private val createdPluginIds = mutableListOf<String>()

    private fun freshProvider(): PluginStorageProviderImpl {
        val id = "pgtest-${System.nanoTime()}"
        createdPluginIds += id
        return PluginStorageProviderImpl(id)
    }

    @AfterTest
    fun cleanup() {
        createdPluginIds.forEach { id ->
            BossDirectories.resolve("plugin-data/$id").deleteRecursively()
        }
        createdPluginIds.clear()
    }

    @Test
    fun `many concurrent putString calls all persist, none lost`() =
        runBlocking {
            val provider = freshProvider()
            val keys = List(KEY_COUNT) { it + 1 }

            coroutineScope {
                val jobs = keys.map { i -> async { provider.putString("key$i", "value$i") } }
                jobs.forEach { it.await() }
            }

            for (i in keys) {
                assertEquals("value$i", provider.getString("key$i"), "key$i lost under concurrent writes")
            }

            // Reload from disk with a fresh instance - proves the writes actually landed, not
            // only that the in-memory cache looks right.
            val reloaded = PluginStorageProviderImpl(provider.getPluginId())
            for (i in keys) {
                assertEquals("value$i", reloaded.getString("key$i"), "key$i missing from disk after reload")
            }
        }

    @Test
    fun `concurrent writers to the same key never interleave bytes`() =
        runBlocking {
            val provider = freshProvider()
            // Each writer's value is long enough that a byte-interleaved write would produce
            // something that is neither writer's exact string.
            val values = List(WRITER_COUNT) { "writer-$it-" + "x".repeat(200) }

            coroutineScope {
                val jobs = values.map { v -> async { provider.putString("shared", v) } }
                jobs.forEach { it.await() }
            }

            assertTrue(provider.getString("shared") in values, "final value must be exactly one writer's, not a mix")

            val reloaded = PluginStorageProviderImpl(provider.getPluginId())
            assertTrue(reloaded.getString("shared") in values, "the on-disk value must also be exactly one writer's")
        }

    @Test
    fun `put then remove then clear behave in call order and persist`() =
        runBlocking {
            val provider = freshProvider()

            provider.putString("a", "1")
            provider.putString("b", "2")
            assertEquals("1", provider.getString("a"))
            assertEquals("2", provider.getString("b"))

            provider.remove("a")
            assertNull(provider.getString("a"))
            assertEquals("2", provider.getString("b"), "remove of one key must not touch another")

            provider.clear()
            assertNull(provider.getString("b"))
            assertTrue(provider.getAllKeys().isEmpty())

            val reloaded = PluginStorageProviderImpl(provider.getPluginId())
            assertTrue(reloaded.getAllKeys().isEmpty(), "clear must persist, not just clear the in-memory cache")
        }

    @Test
    fun `a failed write throws, leaves the cache unchanged, and emits no notification`() =
        runBlocking {
            val provider = freshProvider()
            provider.putString("existing", "value")

            // Force the next write to fail: the storage file's own path becomes a directory, so
            // atomicMoveFrom's replace has nothing valid to land on - the same technique
            // McpPolicyEngineTest uses to force a genuine disk error.
            val dir = BossDirectories.resolve("plugin-data/${provider.getPluginId()}")
            val storageFile = File(dir, "storage.properties")
            storageFile.delete()
            storageFile.mkdirs()

            val notifications = mutableListOf<String>()
            val collector =
                async {
                    provider.observeChanges().collect { notifications += it }
                }

            assertFailsWith<Exception> { provider.putString("existing", "corrupted") }

            // The failed mutation must not have reached the committed cache.
            assertEquals("value", provider.getString("existing"), "a failed write must not change the committed cache")
            collector.cancel()
            assertTrue(notifications.isEmpty(), "a failed write must not publish a change notification")
        }

    @Test
    fun `a value with non-Latin-1 characters round trips through a reload`() =
        runBlocking {
            val provider = freshProvider()
            // e-acute, two CJK characters, and an emoji outside the Basic Multilingual Plane.
            val value = "settings-é中文-😀"

            provider.putString("unicode", value)
            assertEquals(value, provider.getString("unicode"))

            val reloaded = PluginStorageProviderImpl(provider.getPluginId())
            assertEquals(value, reloaded.getString("unicode"), "non-Latin-1 content must survive a reload")
        }

    /**
     * Races a cancellation against a write started with `UNDISPATCHED` (so `putString` has
     * genuinely begun, past `writeLock.withLock`, by the time `cancel()` runs). Which side wins
     * is not deterministic - `withContext(Dispatchers.IO)` dispatches onto a real thread pool, so
     * the write can complete before the cancellation is even processed - and this test asserts
     * neither outcome specifically. What BossConsole#506 actually requires, and what this pins,
     * is that the two halves of one transaction never disagree: [PluginStorageProviderImpl]'s
     * `saveSnapshot` and its matching cache commit run back to back with no suspension point
     * between them (see the KDoc on `persistMutation`), so a caller can never observe the disk
     * updated without the cache, or the cache updated without the disk - only "both" or
     * "neither", whichever the race resolves to.
     */
    @Test
    fun `a write racing cancellation leaves cache and disk consistent with each other`() =
        runBlocking {
            val provider = freshProvider()

            val job =
                async(start = CoroutineStart.UNDISPATCHED) {
                    provider.putString("maybe", "written")
                }
            job.cancel()
            runCatching { job.await() }

            val cached = provider.getString("maybe")
            val reloaded = PluginStorageProviderImpl(provider.getPluginId()).getString("maybe")
            assertEquals(cached, reloaded, "cache and disk must never disagree, whichever way the race resolved")
        }

    private companion object {
        const val KEY_COUNT = 30
        const val WRITER_COUNT = 20
    }
}
