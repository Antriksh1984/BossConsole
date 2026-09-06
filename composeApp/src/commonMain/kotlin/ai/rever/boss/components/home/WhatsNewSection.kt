package ai.rever.boss.components.home

import ai.rever.boss.components.dashboard.cards.ReleaseCard
import ai.rever.boss.components.dashboard.sections.DashboardSection
import ai.rever.boss.components.dialogs.dialogScrollFence
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.updater.NotesBlockView
import ai.rever.boss.updater.UpdateCoordinator
import ai.rever.boss.updater.UpdateSettings
import ai.rever.boss.updater.UpdateSettingsManager
import ai.rever.boss.updater.VersionInfo
import ai.rever.boss.updater.VersionSelectionDialog
import ai.rever.boss.updater.parseReleaseNotes
import ai.rever.boss.utils.Version
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** How many releases the feed shows. BossConsole#149 asks for 3-5; the high end reads as a feed. */
private const val WHATS_NEW_LIMIT = 5

/**
 * The Dashboard's "What's New" feed (BossConsole#149): the same [VersionInfo] list the Settings
 * screen's version dialog already fetches, surfaced on the home screen so a release is
 * discoverable without opening Settings.
 *
 * Reads [UpdateCoordinator.versionListManager] directly rather than taking it as a parameter -
 * matching how [RecentFilesSection] reads `RecentFilesManager` - because [HomeScreen] takes no
 * action callbacks by design (see its own doc comment) and this section needs no action a caller
 * would supply anyway: expanding a card's notes and browsing all versions are both handled with
 * local dialog state, exactly as the Settings screen already handles them.
 *
 * Hides itself entirely on an empty or failed fetch (BossConsole#149's own acceptance criterion:
 * "no error UI when offline"), the same shape every other section here uses via its own
 * `if (list.isEmpty()) return`.
 */
@Composable
internal fun WhatsNewSection() {
    val versionListManager = UpdateCoordinator.instance.versionListManager
    val versions by versionListManager.versions.collectAsState()
    val isLoading by versionListManager.isLoading.collectAsState()
    val error by versionListManager.error.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { versionListManager.fetchVersions() }

    // Deliberately not gated on `error`: a cache-hit fetchVersions() call returns early without
    // clearing a PREVIOUS failed attempt's error flag, so `error` can be stale and non-null even
    // though `versions` still holds perfectly good cached data. Hiding on empty data (below) is
    // what "no error UI when offline" actually needs - this section never renders `error` text
    // anywhere, so there is nothing for a lingering flag to leak into the UI.
    val recent = remember(versions) { versions.take(WHATS_NEW_LIMIT) }
    if (recent.isEmpty()) return

    // Frozen for this composition's whole lifetime, not re-read after the effect below updates
    // it: badges must reflect what was seen BEFORE this visit, not disappear the instant this
    // section (which is itself "seeing" the feed) renders.
    val lastSeenAtOpen = remember { UpdateSettings.lastSeenReleaseVersion }
    var expandedRelease by remember { mutableStateOf<VersionInfo?>(null) }
    var showAllVersions by remember { mutableStateOf(false) }

    // Being on the Dashboard with the feed visible IS "seeing" it. Guarded so it only ever
    // advances: a later `versions` update cannot move the watermark backwards, and re-fetching
    // the same newest release (e.g. the 1-hour cache simply expiring) is a no-op write.
    LaunchedEffect(recent.first().version) {
        val newest = recent.first().version
        val current = UpdateSettings.lastSeenReleaseVersion?.let { Version.parse(it) }
        if (current == null || newest > current) {
            UpdateSettings.lastSeenReleaseVersion = newest.toString()
            scope.launch { UpdateSettingsManager.saveSettings() }
        }
    }

    DashboardSection(
        title = "What's New",
        actionText = "View all",
        onAction = { showAllVersions = true },
    ) {
        CardStrip {
            recent.forEach { release ->
                ReleaseCard(
                    release = release,
                    isNew = release.isNewSince(lastSeenAtOpen),
                    onClick = { expandedRelease = release },
                )
            }
        }
    }

    expandedRelease?.let { release ->
        ReleaseNotesDialog(release = release, onDismiss = { expandedRelease = null })
    }

    if (showAllVersions) {
        VersionSelectionDialog(
            currentVersion = UpdateCoordinator.instance.currentVersion(),
            versions = versions,
            isLoading = isLoading,
            error = error,
            // Browsing here is for reading, not installing - the Dashboard is not the place to
            // start a downgrade/reinstall flow the user did not ask for. Selecting a version
            // just reads its own notes, same as clicking its card would.
            onVersionSelected = { versionInfo ->
                showAllVersions = false
                expandedRelease = versionInfo
            },
            onDismiss = { showAllVersions = false },
        )
    }
}

/** True when this release is newer than the last one the user has seen in the feed. */
internal fun VersionInfo.isNewSince(lastSeenReleaseVersion: String?): Boolean {
    val lastSeen = lastSeenReleaseVersion?.let { Version.parse(it) } ?: return true
    return version > lastSeen
}

/** Full release notes for one version, reusing the same parse-or-fall-back-to-plain-text rule. */
@Composable
private fun ReleaseNotesDialog(
    release: VersionInfo,
    onDismiss: () -> Unit,
) {
    val notesBlocks =
        remember(release.releaseNotes) {
            runCatching { parseReleaseNotes(release.releaseNotes) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.width(480.dp),
            backgroundColor = BossTheme.colors.panel,
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "v${release.version}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossTheme.colors.textPrimary,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = BossTheme.colors.textSecondary)
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .dialogScrollFence(NOTES_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (notesBlocks != null) {
                        notesBlocks.forEach { block -> NotesBlockView(block) }
                    } else {
                        release.releaseNotes.lines().forEach { line ->
                            Text(line, color = BossTheme.colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private val NOTES_MAX_HEIGHT = 420.dp
