package ai.rever.boss.plugin.browser

import ai.rever.boss.platform.FileSystemUtils
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExecutableDownloadConsentTest {
    @Test
    fun `renaming a text file to an executable still asks for consent`() {
        var shownName: String? = null
        var prompts = 0
        assertFalse(
            executableDownloadAllowed(true, "report.txt", "report.EXE") {
                shownName = it
                prompts++
                false
            },
        )
        assertEquals("report.EXE", shownName)
        assertEquals(1, prompts)
    }

    @Test
    fun `renaming an executable does not bypass the original warning`() {
        assertFalse(executableDownloadAllowed(true, "setup.sh", "setup.txt") { false })
        assertTrue(executableDownloadAllowed(true, "setup.sh", "setup.txt") { true })
    }

    @Test
    fun `ordinary files and disabled warnings never prompt`() {
        val unexpectedPrompt: (String) -> Boolean = { error("Unexpected prompt") }
        assertTrue(executableDownloadAllowed(true, "report.pdf", "report (1).pdf", unexpectedPrompt))
        assertTrue(executableDownloadAllowed(false, "setup.exe", "setup.exe", unexpectedPrompt))
    }

    @Test
    fun `the download handler reads the live BrowserSettings toggle, not a cached copy`() {
        // FluckEngine passes BrowserSettings.warnForExecutables at the call site rather than a
        // value captured earlier, so a Settings > Browser > Downloads change applies to the very
        // next download with no restart. This pins that call-site shape rather than a snapshot.
        val original = BrowserSettings.warnForExecutables
        try {
            BrowserSettings.warnForExecutables = false
            assertTrue(
                executableDownloadAllowed(BrowserSettings.warnForExecutables, "setup.exe", "setup.exe") {
                    error("disabled warning must not prompt")
                },
            )

            BrowserSettings.warnForExecutables = true
            assertFalse(
                executableDownloadAllowed(BrowserSettings.warnForExecutables, "setup.exe", "setup.exe") { false },
            )
        } finally {
            BrowserSettings.warnForExecutables = original
        }
    }

    @Test
    fun `declining releases the owned path and URL before cancelling exactly once`() {
        val directory = Files.createTempDirectory("download-consent").toFile()
        val owner = "declined-download"
        val path = FileSystemUtils.generateUniqueFilePath(directory.path, "setup.exe", owner)
        val urls = mutableSetOf("https://example.test/setup.exe")
        var cancels = 0
        try {
            cancelPendingDownload(path, owner, urls.single(), urls) {
                assertTrue(urls.isEmpty())
                val next = FileSystemUtils.generateUniqueFilePath(directory.path, "setup.exe", "retry")
                try {
                    assertEquals(path, next, "Cancel must leave the original filename available")
                } finally {
                    FileSystemUtils.releaseFilePath(next, "retry")
                }
                cancels++
            }
            assertEquals(1, cancels)
            assertFalse(java.io.File(path).exists())
        } finally {
            FileSystemUtils.releaseFilePath(path, owner)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancelling a save-dialog path never releases another downloads claim`() {
        val directory = Files.createTempDirectory("download-consent-owner").toFile()
        val path = FileSystemUtils.generateUniqueFilePath(directory.path, "setup.exe", "other")
        try {
            cancelPendingDownload(path, "dialog", "url", mutableSetOf("url")) { }
            val next = FileSystemUtils.generateUniqueFilePath(directory.path, "setup.exe", "next")
            try {
                assertNotEquals(path, next)
            } finally {
                FileSystemUtils.releaseFilePath(next, "next")
            }
        } finally {
            FileSystemUtils.releaseFilePath(path, "other")
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancelling the save dialog clears its URL without a claimed path`() {
        val urls = mutableSetOf("url")
        var cancels = 0
        cancelPendingDownload(null, "dialog", "url", urls) { cancels++ }
        assertTrue(urls.isEmpty())
        assertEquals(1, cancels)
    }
}
