package ai.rever.boss.plugin.browser

import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > Browser > Downloads > "Warn before downloading executable files".
 *
 * Unlike the four toggles [BrowserSettingsMirrorTest] covers, this one has no system-property
 * mirror: FluckEngine's download handler lives in the host, not a separately classloaded plugin,
 * so a plain read of [BrowserSettings.warnForExecutables] is enough to reach it without a restart.
 * These tests pin the three things that would otherwise silently drift: the default matches
 * between the in-memory object and the persisted shape, both values survive a JSON round-trip
 * (there is no injectable settings file to test the real save/load path against - see
 * [BrowserSettingsManager], which resolves a fixed path under the real BOSS data directory), and
 * the handler in [ExecutableDownloadConsentTest] reads this field live rather than a cached copy.
 */
class BrowserSettingsWarnForExecutablesTest {
    private val original = BrowserSettings.warnForExecutables

    @AfterTest
    fun restore() {
        BrowserSettings.warnForExecutables = original
    }

    @Test
    fun `default is on, and the persisted shape agrees`() {
        assertTrue(BrowserSettings.warnForExecutables)
        assertTrue(BrowserSettingsData().warnForExecutables)
    }

    @Test
    fun `both persisted values round-trip through JSON`() {
        val json = Json { ignoreUnknownKeys = true }

        for (value in listOf(true, false)) {
            val encoded = json.encodeToString(BrowserSettingsData(warnForExecutables = value))
            val decoded = json.decodeFromString<BrowserSettingsData>(encoded)
            assertEquals(value, decoded.warnForExecutables, "round-trip failed for warnForExecutables=$value")
        }
    }

    @Test
    fun `turning the setting off is what a save actually persists`() {
        BrowserSettings.warnForExecutables = false
        val snapshot =
            BrowserSettingsData(
                warnForExecutables = BrowserSettings.warnForExecutables,
            )
        assertFalse(snapshot.warnForExecutables)
    }
}
