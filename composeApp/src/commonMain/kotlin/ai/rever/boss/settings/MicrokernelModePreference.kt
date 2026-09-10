package ai.rever.boss.settings

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The confirmation dialog's body text, shared by both entry points so Settings and the
 * application menu offer to enable the exact same thing in the exact same words. Wording is
 * BossConsole#472's own suggested copy: it names what the mode is for, states plainly that it is
 * experimental, and stops short of promising isolation or recovery guarantees this mode does not
 * actually make.
 */
const val MICROKERNEL_MODE_CONFIRMATION_MESSAGE =
    "Microkernel Mode is designed to run supported services and plugin workloads in separate " +
        "processes instead of keeping everything inside the main BOSS process. This architecture " +
        "is intended to improve fault isolation and allow those components to be managed " +
        "independently.\n\n" +
        "This mode is experimental. Some features or plugins may not work correctly, startup or " +
        "communication between processes may fail, and additional processes may use more memory. " +
        "It does not guarantee that every plugin runs separately or that all crashes are " +
        "isolated. Save your work before trying it.\n\n" +
        "The change takes effect after restarting BOSS. You can turn Microkernel Mode off and " +
        "restart again to return to normal mode."

/** Shared persistence and save outcome for Settings and every application menu. */
object MicrokernelModePreference {
    private val mutex = Mutex()
    private val _saveState = MutableStateFlow(MicrokernelModeSaveState())
    internal val saveState = _saveState.asStateFlow()

    // Latched from the FIRST refresh() this process makes, never updated after - this is "what
    // env_vars said when BOSS started", which is the comparand "restart required" actually needs.
    // ConfigLoader.getConfig("BOSS_MODE") looks like the right answer to that question and isn't:
    // it resolves from an env var / system property / local.properties / the embedded build
    // config, and nothing in this repo loads env_vars into any of those, so on an ordinary
    // install it is permanently false and the banner it used to drive could never clear after an
    // actual restart (or could never appear for someone who does export BOSS_MODE). See #472's
    // review. Whole-hog fix (making env_vars actually reach the running process) is #391's.
    private var startupEnabledLatched: Boolean? = null

    /**
     * [envFile] defaults to the real `env_vars` file; exists as a parameter only so a test can
     * simulate "the process starting" against an isolated file - see [isEnabled].
     */
    suspend fun refresh(envFile: File = BossDirectories.resolve("env_vars")) =
        mutex.withLock {
            val current = isEnabled(envFile)
            if (startupEnabledLatched == null) {
                startupEnabledLatched = current
            }
            _saveState.value =
                _saveState.value.copy(
                    enabled = current,
                    startupEnabled = startupEnabledLatched,
                    // A fresh read is also the point at which a stale save error stops being
                    // useful - most concretely, reopening Settings after seeing one.
                    saveFailed = false,
                )
        }

    /**
     * Forgets the latched startup snapshot and resets published state to its construction-time
     * default. Test-only: [startupEnabledLatched] is deliberately latched once per real process
     * and has no other reset seam, which would otherwise make every test after the first
     * [refresh] call in a JVM run see a stale snapshot from whichever test happened to run first.
     */
    internal fun forgetStartupSnapshotForTest() {
        startupEnabledLatched = null
        _saveState.value = MicrokernelModeSaveState()
    }

    /** Finish publication even if the window closes while the file write is in progress. */
    internal suspend fun saveAndPublish(
        enabled: Boolean,
        write: suspend () -> Result<Unit>,
    ): Result<Unit> =
        withContext(NonCancellable) {
            mutex.withLock {
                val result = write()
                _saveState.value =
                    if (result.isSuccess) {
                        MicrokernelModeSaveState(enabled = enabled, startupEnabled = startupEnabledLatched)
                    } else {
                        _saveState.value.copy(saveFailed = true)
                    }
                result
            }
        }

    suspend fun save(enabled: Boolean): Result<Unit> = saveAndPublish(enabled) { setEnabled(enabled) }

    /**
     * Whether the persisted preference currently requests Microkernel Mode.
     *
     * [envFile] defaults to the real `env_vars` file and exists as a parameter only so a test
     * can point this at an isolated temp file - [BossDirectories.resolve] always resolves
     * against the real user home directory with no override seam of its own.
     */
    suspend fun isEnabled(envFile: File = BossDirectories.resolve("env_vars")): Boolean =
        withContext(Dispatchers.IO) {
            if (!envFile.exists()) return@withContext false
            try {
                envFile
                    .readLines(Charsets.UTF_8)
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2 && parts[0].trim() == "BOSS_MODE") parts[1].trim() else null
                    }.lastOrNull() == "KERNEL"
            } catch (_: IOException) {
                false
            }
        }

    /**
     * Persists [enabled] to `env_vars`, returning whether the write actually succeeded.
     *
     * A caller must not update its own "saved" state, or show the restart-required notice, on a
     * `false` result - BossConsole#472 asks explicitly that only a successful save do either,
     * with the failure staying visible instead.
     *
     * [envFile] defaults to the real `env_vars` file; see [isEnabled] for why a test overrides it.
     */
    suspend fun setEnabled(
        enabled: Boolean,
        envFile: File = BossDirectories.resolve("env_vars"),
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                envFile.parentFile?.mkdirs()

                if (!envFile.exists()) {
                    envFile.atomicWriteText(
                        if (enabled) "BOSS_MODE=KERNEL\n" else "# BOSS_MODE=KERNEL\n",
                    )
                    return@withContext Result.success(Unit)
                }

                val lines = envFile.readLines(Charsets.UTF_8).toMutableList()
                val modeLineIndices =
                    lines.indices.filter { index ->
                        val key =
                            lines[index]
                                .trimStart()
                                .removePrefix("#")
                                .trimStart()
                                .substringBefore("=")
                                .trim()
                        key == "BOSS_MODE"
                    }
                val newLine = if (enabled) "BOSS_MODE=KERNEL" else "# BOSS_MODE=KERNEL"
                if (modeLineIndices.isEmpty()) {
                    lines.add(newLine)
                } else {
                    lines[modeLineIndices.first()] = newLine
                    modeLineIndices.drop(1).reversed().forEach { lines.removeAt(it) }
                }

                // env_vars is also where the secret-manager plugin resolves API keys from - a
                // truncate-on-open write left mid-flight (disk full, killed process, a Windows
                // lock) would destroy every unrelated key in it, not just this one. Write a
                // sibling temp file and move it into place instead, the same pattern this repo
                // already uses for exactly this hazard (StoreMissingDependencyInstaller's
                // `.jar.part`).
                envFile.atomicWriteText(lines.joinToString("\n") + "\n")
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
}

/**
 * Whether requesting [nextEnabled] for the current preference state [currentlyEnabled] needs the
 * operator to confirm first.
 *
 * BossConsole#472's acceptance criteria, restated as one predicate so both entry points ask the
 * same question the same way: confirmation is for an explicit off-to-on request only. Turning it
 * off stays a plain, un-confirmed toggle ("Disabling remains straightforward"), and a
 * no-op request (already in the requested state - the menu item's stale, restart-pinned display
 * can send one) confirms nothing because nothing would actually change.
 */
fun needsMicrokernelModeConfirmation(
    currentlyEnabled: Boolean,
    nextEnabled: Boolean,
): Boolean = nextEnabled && !currentlyEnabled

internal data class MicrokernelModeSaveState(
    val enabled: Boolean? = null,
    val saveFailed: Boolean = false,
    // What env_vars said the FIRST time this process read it - see the KDoc on
    // MicrokernelModePreference.startupEnabledLatched for why this, and not a live
    // ConfigLoader read, is the correct "what is actually running" comparand.
    val startupEnabled: Boolean? = null,
) {
    val needsRestart: Boolean
        get() = enabled != null && startupEnabled != null && enabled != startupEnabled
}

internal fun microkernelModeMenuLabel(state: MicrokernelModeSaveState): String =
    when {
        state.saveFailed -> "Microkernel Mode (save failed - retry)"
        state.needsRestart -> "Microkernel Mode (restart required)"
        else -> "Microkernel Mode"
    }

/** Local consent belongs to the initiating surface; a dismissal never calls persistence. */
internal class MicrokernelModeConfirmation {
    var pending by mutableStateOf(false)
        private set

    fun request() {
        pending = true
    }

    fun cancel() {
        pending = false
    }

    fun confirm(save: () -> Unit) {
        if (!pending) return
        pending = false
        save()
    }
}
