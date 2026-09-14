package ai.rever.boss.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Try-acquire admission per key: at most one holder at a time, process-wide. A second
 * [tryAcquire] for a key already held fails immediately - it never waits for the first holder
 * to finish, and never runs any of the caller's own work.
 *
 * The opposite shape from [KeyedDetachedJobs], which COALESCES a second request onto the
 * in-flight job's eventual result. That is right for a reload the caller is happy to just
 * observe the outcome of; it is wrong here (BossConsole#600) - a second `performUpdate` for a
 * plugin already mid-update must not download, unload, or touch the first operation's artifact
 * at all, so the two operations must never even overlap, not merely never interleave their
 * writes.
 *
 * [ConcurrentHashMap.newKeySet] because [MutableSet.add] is exactly the atomic test-and-set this
 * needs: two threads racing [tryAcquire] on the same key have exactly one see `true`.
 */
internal class PerKeyAdmission<K : Any> {
    private val held: MutableSet<K> = ConcurrentHashMap.newKeySet()

    /** True if [key] was free and is now held by the caller; false if another holder has it. */
    fun tryAcquire(key: K): Boolean = held.add(key)

    /** Releases [key]. Idempotent - releasing a key not currently held is a no-op. */
    fun release(key: K) {
        held.remove(key)
    }
}
