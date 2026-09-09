package ai.rever.boss.updater

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#119's review, point 2: the artifact an unsupported-OS refusal removes must be
 * exactly the one that was refused - never a newer version already staged, and never an
 * unrelated file that happens to share the staging directory.
 */
class DeleteRefusedArtifactTest {
    private val tempDir = createTempDirectory("delete-refused-artifact-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `removes only the exact refused file`() {
        val refused = File(tempDir, "BOSS-9.5.3-Universal.dmg").apply { writeText("refused") }
        val newerStaged =
            File(tempDir, "BOSS-9.5.4-Universal.dmg").apply { writeText("newer, unrelated to this refusal") }
        val unrelated = File(tempDir, "notes.txt").apply { writeText("some other file in the same directory") }

        assertTrue(deleteRefusedArtifact(refused))

        assertFalse(refused.exists(), "the refused artifact must actually be gone")
        assertTrue(newerStaged.exists(), "a newer staged download must survive")
        assertTrue(unrelated.exists(), "an unrelated file in the same directory must survive")
    }

    @Test
    fun `a missing file is reported as not deleted, without throwing`() {
        val alreadyGone = File(tempDir, "already-gone.dmg")
        assertFalse(deleteRefusedArtifact(alreadyGone))
    }
}
