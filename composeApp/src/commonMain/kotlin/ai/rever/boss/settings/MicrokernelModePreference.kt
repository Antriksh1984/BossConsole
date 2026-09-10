package ai.rever.boss.settings

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
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

/**
 * The one place that reads and writes the persisted Microkernel Mode preference
 * (`BOSS_MODE=KERNEL` in `env_vars`).
 *
 * Before this existed, [ai.rever.boss.components.settings.sections.AdvancedSettings] and the
 * "Microkernel Mode" application-menu item each carried their own copy of this file-editing
 * logic, and had already drifted: the Settings copy created a missing `env_vars` file with a
 * comment explaining the key, the menu copy did not. BossConsole#472 asks for a confirmation
 * dialog "before any preference is written" reachable from both entry points using "the existing
 * shared preference-save path" (singular) - which this object *is*, not a description of
 * something that already existed.
 *
 * Deliberately outside the `kernel` package: that package is excluded entirely from Windows
 * ARM64 builds (no `boss-ipc` there), but this preference is plain file I/O with no such
 * dependency, and both its callers - Settings and the app menu - are not excluded. Living inside
 * `kernel` would have made this object vanish out from under them on exactly one platform.
 *
 * Read and write deliberately stay separate operations with separate meanings, not merged into
 * one: [isEnabled] answers "what does the saved preference currently say", used by both entry
 * points to decide whether a requested change is actually an off-to-on transition worth
 * confirming. Neither entry point's *displayed* checked state reads through here - Settings
 * reads the file live on every open (its checkbox can lag a menu-triggered write until reopened,
 * unchanged behavior), the menu item reads the environment/config the running process actually
 * started under (its checkbox cannot move until a restart makes that value stale by definition
 * either way) - changing which source each display trusts is out of scope for #472, which asks
 * for a confirmation dialog, not a display-consistency fix.
 */
object MicrokernelModePreference {
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
                    .any { line ->
                        val parts = line.split("=", limit = 2)
                        parts.size == 2 && parts[0].trim() == "BOSS_MODE" && parts[1].trim() == "KERNEL"
                    }
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
                    envFile.writeText(
                        if (enabled) "BOSS_MODE=KERNEL\n" else "# BOSS_MODE=KERNEL\n",
                        Charsets.UTF_8,
                    )
                    return@withContext Result.success(Unit)
                }

                val lines = envFile.readLines(Charsets.UTF_8).toMutableList()
                val modeLineIndex =
                    lines.indexOfFirst { line ->
                        line.trimStart('#', ' ').startsWith("BOSS_MODE")
                    }
                val newLine = if (enabled) "BOSS_MODE=KERNEL" else "# BOSS_MODE=KERNEL"

                if (modeLineIndex >= 0) {
                    lines[modeLineIndex] = newLine
                } else {
                    lines.add("")
                    lines.add("# Microkernel mode - enables out-of-process plugins, gRPC IPC, and AI self-healing")
                    lines.add(newLine)
                }

                envFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
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
