package ai.rever.boss.components.plugin

/**
 * Bridges the commonMain host UI to the desktopMain plugin update machinery
 * (PluginUpdateManager in PluginStoreSetup). The actual implementation publishes results into
 * [PluginUpdateRegistry] and performs downloads/installs.
 */
expect object PluginUpdateBridge {
    /** Check all [installed] plugins and publish compatible updates to [PluginUpdateRegistry]. */
    suspend fun refreshAll(installed: List<InstalledPluginRef>)

    /** Check a single plugin on demand; also refreshes its [PluginUpdateRegistry] entry. */
    suspend fun checkOne(ref: InstalledPluginRef): UpdateCheckOutcome

    /** Download + install the latest compatible version, reusing [manager] to unload/load. */
    suspend fun performUpdate(
        pluginId: String,
        manager: DynamicPluginManager,
    ): Result<String>
}

/**
 * Another [PluginUpdateBridge.performUpdate] call for [pluginId] is already in flight, process-
 * wide - typically a second window confirming the same "Update Available" prompt before the
 * first finishes (BossConsole#600). A distinct type so the caller can word this as "try again in
 * a moment" rather than "Update failed": nothing was downloaded, unloaded, or removed, and the
 * in-flight caller's own progress/cancel controls are untouched.
 */
class PluginUpdateBusyException(
    val pluginId: String,
) : Exception("An update for $pluginId is already in progress in another window")
