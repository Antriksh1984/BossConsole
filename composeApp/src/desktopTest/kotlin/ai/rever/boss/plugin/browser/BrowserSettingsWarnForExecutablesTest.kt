package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > Browser > Downloads > "Warn before downloading executable files".
 *
 * Unlike the four toggles [BrowserSettingsMirrorTest] covers, this one has no system-property
 * mirror: FluckEngine's download handler lives in the host, not a separately classloaded plugin,
 * so a plain read of [BrowserSettings.warnForExecutables] is enough to reach it without a restart.
 *
 * The round-trip tests point [BrowserSettingsManager.settingsFile] at a temp file - the same
 * seam [DefaultAppsSettingsManager] uses - so they exercise the real saveSettings/load path
 * rather than a re-statement of the assignment. The two lines in [BrowserSettingsManager] that
 * copy the field into the persisted shape and back are the ones a mutation would slip past.
 */
class BrowserSettingsWarnForExecutablesTest {
    @TempDir
    lateinit var dir: File

    private lateinit var originalFile: File
    private val originalValue = BrowserSettings.warnForExecutables

    @BeforeEach
    fun pointAtTempFile() {
        originalFile = BrowserSettingsManager.settingsFile
        BrowserSettingsManager.settingsFile = File(dir, "browser-settings.json")
    }

    @AfterEach
    fun restore() {
        BrowserSettingsManager.settingsFile = originalFile
        BrowserSettings.warnForExecutables = originalValue
    }

    @Test
    fun `default is on, and the persisted shape agrees`() {
        assertTrue(BrowserSettings.warnForExecutables)
        assertTrue(BrowserSettingsData().warnForExecutables)
    }

    @Test
    fun `both values survive the real save and load`() {
        for (value in listOf(false, true)) {
            BrowserSettings.warnForExecutables = value
            runBlocking { BrowserSettingsManager.saveSettings() }
            BrowserSettingsManager.reloadForTest()
            assertEquals(
                value,
                BrowserSettings.warnForExecutables,
                "warnForExecutables=$value did not survive saveSettings + reload",
            )
        }
    }

    @Test
    fun `a settings file predating the key loads as on, so an upgrade keeps the warning`() {
        BrowserSettings.warnForExecutables = false
        // The shape an earlier build wrote: every field except warnForExecutables.
        BrowserSettingsManager.settingsFile.writeText(
            """
            {
                "currentProfile": "browser-profile",
                "availableProfiles": [
                    "browser-profile"
                ]
            }
            """.trimIndent(),
        )
        BrowserSettingsManager.reloadForTest()
        assertTrue(BrowserSettings.warnForExecutables, "a file without the key must load as on")
    }

    @Test
    fun `a corrupt settings file does not crash the load or clobber the in-memory value`() {
        BrowserSettings.warnForExecutables = false
        BrowserSettingsManager.settingsFile.writeText("{ not json")
        BrowserSettingsManager.reloadForTest()
        assertFalse(BrowserSettings.warnForExecutables, "a failed load must keep the in-memory value")
    }
}
