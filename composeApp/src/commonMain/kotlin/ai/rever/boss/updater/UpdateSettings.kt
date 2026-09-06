package ai.rever.boss.updater

/**
 * Platform-specific update settings
 *
 * Provides access to update configuration that persists across app restarts.
 * Actual implementations handle platform-specific storage (e.g., File I/O on desktop).
 */
expect object UpdateSettings {
    /**
     * Whether automatic update checks are enabled
     */
    var autoCheckEnabled: Boolean

    /**
     * Interval between automatic update checks in hours
     */
    var checkIntervalHours: Long

    /**
     * Whether to include pre-release versions (alpha, beta, RC) in update checks.
     * When false (default), only stable releases are shown.
     * When true, or when the current version is a pre-release, pre-releases are included.
     */
    var includePreReleases: Boolean

    /**
     * The version string the user last dismissed an update prompt for.
     * Automatic checks won't re-surface this exact version; any different
     * available version (normally a newer release, but also a server-side
     * rollback) or a manual/forced check will prompt again. Null when nothing
     * is dismissed.
     */
    var lastDismissedVersion: String?

    /**
     * The version string of the newest release the user has seen in the Dashboard's
     * "What's New" feed (BossConsole#149). Distinct from [lastDismissedVersion]: that one
     * suppresses the update *prompt* for a version the user chose to skip installing; this one
     * only tracks what has been shown, so a release the user has looked at loses its "NEW"
     * badge whether or not they ever install it. Null when nothing has been seen yet.
     */
    var lastSeenReleaseVersion: String?
}

/**
 * Platform-specific settings manager for persisting update preferences
 */
expect object UpdateSettingsManager {
    /**
     * Save current settings to persistent storage
     */
    suspend fun saveSettings()
}
