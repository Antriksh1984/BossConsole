package ai.rever.boss.settings

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the shared preference-save path BossConsole#472 asks both entry points to use, and the
 * pure off-to-on decision that gates the confirmation dialog.
 *
 * Every test points [MicrokernelModePreference] at an isolated temp file rather than the real
 * `env_vars` - [ai.rever.boss.plugin.pathutils.BossDirectories.resolve] always resolves against
 * the real user home directory with no override seam, so calling the real defaults from a test
 * would read and write this machine's actual preference file.
 */
class MicrokernelModePreferenceTest {
    private val tempFiles = mutableListOf<File>()

    private fun tempEnvFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("microkernel-mode-test")
                .toFile()
        return File(dir, "env_vars").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `a missing env_vars file reads as disabled`() =
        runTest {
            val file = tempEnvFile()
            assertFalse(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `enabling then reading back from the same file reflects the write`() =
        runTest {
            val file = tempEnvFile()

            assertTrue(MicrokernelModePreference.setEnabled(true, file).isSuccess)
            assertTrue(MicrokernelModePreference.isEnabled(file))

            // "Survives a reload" means a fresh read of the file, not a cached in-memory value -
            // there is no engine instance here to hold one, so the read has to hit disk to pass.
            assertTrue(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `disabling comments the line out rather than removing it`() =
        runTest {
            val file = tempEnvFile()
            MicrokernelModePreference.setEnabled(true, file)

            assertTrue(MicrokernelModePreference.setEnabled(false, file).isSuccess)
            assertFalse(MicrokernelModePreference.isEnabled(file))
            assertTrue(file.readText().contains("# BOSS_MODE=KERNEL"), "the key should stay, just commented")
        }

    @Test
    fun `writing the preference does not disturb other lines already in the file`() =
        runTest {
            val file = tempEnvFile()
            file.parentFile.mkdirs()
            file.writeText("OTHER_KEY=value\nBOSS_LOG_LEVEL=DEBUG\n")

            assertTrue(MicrokernelModePreference.setEnabled(true, file).isSuccess)

            val lines = file.readLines()
            assertTrue(lines.contains("OTHER_KEY=value"))
            assertTrue(lines.contains("BOSS_LOG_LEVEL=DEBUG"))
            assertTrue(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `a failed write is reported as failure, not thrown or silently ignored`() =
        runTest {
            // A directory sitting at the target path: the write inside has nothing it can
            // atomically become, so it fails the same way an unwritable real config dir would.
            val file = tempEnvFile().also { it.mkdirs() }

            val result = MicrokernelModePreference.setEnabled(true, file)

            assertTrue(result.isFailure)
            // The on-disk state (still a directory, still reading as disabled) must not have
            // silently drifted despite the failure.
            assertFalse(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `confirmation is needed only for an explicit off-to-on request`() {
        assertTrue(needsMicrokernelModeConfirmation(currentlyEnabled = false, nextEnabled = true))
        assertFalse(needsMicrokernelModeConfirmation(currentlyEnabled = true, nextEnabled = false))
        assertFalse(needsMicrokernelModeConfirmation(currentlyEnabled = false, nextEnabled = false))
        assertFalse(needsMicrokernelModeConfirmation(currentlyEnabled = true, nextEnabled = true))
    }

    @Test
    fun `the confirmation message states the mode is experimental without overclaiming`() {
        // Loose content pins rather than a full-string match: wording may be polished later,
        // but the two load-bearing claims - "experimental" and no promise of guaranteed isolation
        // - must survive that, since they are what #472 is actually asking this dialog to say.
        assertTrue(MICROKERNEL_MODE_CONFIRMATION_MESSAGE.contains("experimental", ignoreCase = true))
        assertTrue(MICROKERNEL_MODE_CONFIRMATION_MESSAGE.contains("does not guarantee"))
    }

    @Test
    fun `similarly named keys survive and duplicate mode assignments cannot defeat disable`() =
        runTest {
            val file = tempEnvFile()
            file.writeText("BOSS_MODE_EXTRA=keep\n\t# BOSS_MODE=KERNEL\nBOSS_MODE=KERNEL\nOTHER=keep\n")
            assertTrue(MicrokernelModePreference.setEnabled(false, file).isSuccess)
            assertFalse(MicrokernelModePreference.isEnabled(file))
            assertTrue(file.readLines().contains("BOSS_MODE_EXTRA=keep"))
            assertTrue(file.readLines().contains("OTHER=keep"))
        }

    @Test
    fun `last active assignment determines saved mode`() =
        runTest {
            val file = tempEnvFile()
            file.writeText("BOSS_MODE=KERNEL\nBOSS_MODE=MONOLITH\n")
            assertFalse(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `menu and settings observe only successful saves and a later success clears failure`() =
        runTest {
            MicrokernelModePreference.saveAndPublish(false) { Result.success(Unit) }
            val settings = MicrokernelModePreference.saveState
            val menu = MicrokernelModePreference.saveState
            MicrokernelModePreference.saveAndPublish(true) { Result.failure(java.io.IOException("blocked")) }
            assertEquals(false, settings.value.enabled)
            assertTrue(menu.value.saveFailed)
            assertEquals("Microkernel Mode (save failed - retry)", microkernelModeMenuLabel(menu.value, false))
            MicrokernelModePreference.saveAndPublish(true) { Result.success(Unit) }
            assertEquals(true, settings.value.enabled)
            assertFalse(menu.value.saveFailed)
            assertEquals("Microkernel Mode (restart required)", microkernelModeMenuLabel(menu.value, false))
            MicrokernelModePreference.saveAndPublish(false) { Result.success(Unit) }
            assertEquals("Microkernel Mode", microkernelModeMenuLabel(menu.value, false))
        }

    @Test
    fun `shared consent cancels without writing and confirms once`() {
        val consent = MicrokernelModeConfirmation()
        var writes = 0
        consent.request()
        assertTrue(consent.pending)
        assertEquals(0, writes)
        consent.cancel()
        consent.confirm { writes++ }
        assertFalse(consent.pending)
        assertEquals(0, writes)
        consent.request()
        consent.confirm { writes++ }
        consent.confirm { writes++ }
        assertFalse(consent.pending)
        assertEquals(1, writes)
    }
}
