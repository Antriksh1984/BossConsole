package ai.rever.boss.components.home

import ai.rever.boss.updater.VersionInfo
import ai.rever.boss.utils.Version
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the "NEW" badge rule (BossConsole#149): whether a release counts as newer than what the
 * user has already seen in the feed.
 */
class WhatsNewSectionTest {
    private fun release(version: String) =
        VersionInfo(
            version = Version.parse(version)!!,
            releaseDate = "2026-01-01T00:00:00Z",
            downloadSize = 0L,
            releaseNotes = "",
            downloadUrl = "",
            isDraft = false,
            isPrerelease = false,
        )

    @Test
    fun `everything is new when nothing has been seen yet`() {
        assertTrue(release("9.5.8").isNewSince(null))
    }

    @Test
    fun `a release newer than the last seen one is new`() {
        assertTrue(release("9.5.9").isNewSince("9.5.8"))
    }

    @Test
    fun `the last seen release itself is not new`() {
        assertFalse(release("9.5.8").isNewSince("9.5.8"))
    }

    @Test
    fun `a release older than the last seen one is not new`() {
        assertFalse(release("9.5.7").isNewSince("9.5.8"))
    }

    @Test
    fun `an unparseable last-seen marker fails open rather than crashing`() {
        // Corrupted or hand-edited settings must not make every release look new forever, nor
        // throw and take the whole Dashboard down with it.
        assertTrue(release("9.5.8").isNewSince("not-a-version"))
    }
}
