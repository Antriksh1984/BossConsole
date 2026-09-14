package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop implementation of PluginStorageFactory factory.
 */
actual fun createPluginStorageFactory(): PluginStorageFactory = PluginStorageFactoryImpl.getInstance()

/**
 * Desktop implementation of PluginStorageFactory.
 * Creates plugin-scoped storage providers that persist data to disk.
 */
class PluginStorageFactoryImpl private constructor() : PluginStorageFactory {
    companion object {
        private val logger = BossLogger.forComponent("PluginStorageFactory")

        @Volatile
        private var instance: PluginStorageFactoryImpl? = null

        fun getInstance(): PluginStorageFactoryImpl =
            instance ?: synchronized(this) {
                instance ?: PluginStorageFactoryImpl().also { instance = it }
            }
    }

    // Cache of storage providers per plugin
    private val storageCache = ConcurrentHashMap<String, PluginStorageProviderImpl>()

    override fun createStorage(pluginId: String): PluginStorageProvider =
        storageCache.getOrPut(pluginId) {
            PluginStorageProviderImpl(pluginId)
        }
}

/**
 * Desktop implementation of PluginStorageProvider.
 * Stores data in ~/.boss/plugin-data/{pluginId}/storage.properties
 *
 * **Read-modify-persist-publish is one transaction per instance (BossConsole#506).** This
 * provider is shared across every host window and every plugin coroutine that asks for it
 * ([PluginStorageFactoryImpl.createStorage] caches one per plugin id), so [putString], [remove]
 * and [clear] all serialize through [writeLock] rather than each independently reading [cache],
 * writing a snapshot to disk, and publishing - which let an older snapshot's disk write land
 * *after* a newer one's, silently reverting an already-reported-successful mutation. Every typed
 * setter ([putInt], [putBoolean], …) and [putJson] go through [putString], so they inherit the
 * same guarantee without their own lock.
 *
 * **A failed write no longer looks like a success.** [saveSnapshot] now throws instead of only
 * logging, [cache] is updated only after that write actually lands on disk, and the change
 * notification only fires after [cache] is updated - so a caller that gets an exception back can
 * trust that neither the in-memory state nor the file changed, and a caller that gets a normal
 * return can trust both did.
 */
class PluginStorageProviderImpl(
    private val pluginId: String,
) : PluginStorageProvider {
    companion object {
        private val logger = BossLogger.forComponent("PluginStorage")
    }

    private val storageDir: File by lazy {
        val dir = BossDirectories.resolve("plugin-data/$pluginId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val storageFile: File by lazy {
        File(storageDir, "storage.properties")
    }

    // The last COMMITTED state - only ever changed by a mutation whose disk write already
    // succeeded (see persistMutation). ConcurrentHashMap so a concurrent getString/contains/
    // getAllKeys reads a consistent value without itself needing writeLock; a reader racing an
    // in-flight (not yet committed) write simply sees the pre-transaction state, which is correct.
    private val cache = ConcurrentHashMap<String, String>()

    // Serializes the whole snapshot-write-commit transaction per instance - see the class KDoc.
    private val writeLock = Mutex()

    // Change notification
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    init {
        // Load existing data on initialization
        loadFromDisk()
    }

    override fun getPluginId(): String = pluginId

    // ============ String Storage ============

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        persistMutation { it[key] = value }
        _changes.tryEmit(key)
    }

    override suspend fun getString(
        key: String,
        defaultValue: String?,
    ): String? = cache[key] ?: defaultValue

    // ============ Int Storage ============

    override suspend fun putInt(
        key: String,
        value: Int,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = getString(key)?.toIntOrNull() ?: defaultValue

    // ============ Long Storage ============

    override suspend fun putLong(
        key: String,
        value: Long,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = getString(key)?.toLongOrNull() ?: defaultValue

    // ============ Boolean Storage ============

    override suspend fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = getString(key)?.toBooleanStrictOrNull() ?: defaultValue

    // ============ Float Storage ============

    override suspend fun putFloat(
        key: String,
        value: Float,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = getString(key)?.toFloatOrNull() ?: defaultValue

    // ============ JSON Storage ============

    override suspend fun putJson(
        key: String,
        jsonValue: String,
    ) {
        putString("json:$key", jsonValue)
    }

    override suspend fun getJson(key: String): String? = getString("json:$key")

    // ============ Utility Methods ============

    override suspend fun contains(key: String): Boolean = cache.containsKey(key)

    override suspend fun remove(key: String) {
        persistMutation { it.remove(key) }
        _changes.tryEmit(key)
    }

    override suspend fun getAllKeys(): Set<String> = cache.keys.toSet()

    override suspend fun clear() {
        persistMutation { it.clear() }
        _changes.tryEmit("*")
    }

    override fun observeString(key: String): Flow<String?> =
        _changes
            .asSharedFlow()
            .map { changedKey ->
                if (changedKey == key || changedKey == "*") {
                    cache[key]
                } else {
                    null
                }
            }

    override fun observeChanges(): Flow<String> = _changes.asSharedFlow()

    // ============ Disk Operations ============

    private fun loadFromDisk() {
        try {
            if (storageFile.exists()) {
                val properties = Properties()
                storageFile.inputStream().use { properties.load(it) }
                properties.forEach { key, value ->
                    cache[key.toString()] = value.toString()
                }
                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded plugin storage",
                    mapOf(
                        "pluginId" to pluginId,
                        "keyCount" to cache.size,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to load plugin storage",
                mapOf(
                    "pluginId" to pluginId,
                ),
                e,
            )
        }
    }

    /**
     * Applies [mutate] to a snapshot of the last committed [cache], persists that snapshot, and
     * only once the write has actually landed applies the SAME [mutate] to the live [cache] -
     * on the same dispatched [Dispatchers.IO] block as the write, with no suspension point
     * between them, so a caller cancelled at just the wrong moment cannot see the disk commit
     * without the matching cache commit (BossConsole#506's cancellation-consistency
     * requirement). [writeLock] means nothing else can be mid-transaction while this runs, so the
     * snapshot copied from [cache] is exactly the last committed state.
     *
     * Propagates whatever [saveSnapshot] throws - callers must not read a normal return as
     * success unless persistence actually happened.
     */
    private suspend fun persistMutation(mutate: (MutableMap<String, String>) -> Unit) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val candidate = HashMap(cache)
                mutate(candidate)
                saveSnapshot(candidate)
                mutate(cache)
            }
        }
    }

    /**
     * Writes [snapshot] to [storageFile] via a unique sibling temp file and [File.atomicMoveFrom]
     * - not [ai.rever.boss.utils.atomicWriteText], which writes UTF-8 text: [Properties.store] on
     * an [java.io.OutputStream] uses ISO-8859-1 with `\uXXXX` escapes for anything outside it, and
     * [loadFromDisk] reads that same byte format back via [Properties.load] on an
     * [java.io.InputStream]. Swapping in a UTF-8 text writer would silently corrupt any stored
     * value containing a non-Latin-1 character the moment it round-tripped.
     *
     * Throws on failure rather than swallowing it - a caller must be able to tell "your data is
     * not on disk" from "it saved fine", which logging alone can't do.
     */
    private fun saveSnapshot(snapshot: Map<String, String>) {
        val properties = Properties()
        snapshot.forEach { (key, value) -> properties[key] = value }
        val tmp = File.createTempFile("${storageFile.name}.", ".tmp", storageDir)
        try {
            tmp.outputStream().use { properties.store(it, "Plugin storage for $pluginId") }
            storageFile.atomicMoveFrom(tmp)
        } catch (e: Exception) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to save plugin storage",
                mapOf("pluginId" to pluginId),
                e,
            )
            throw e
        } finally {
            // No-op when the move already took it; cleans up on a failure path.
            tmp.delete()
        }
    }
}
