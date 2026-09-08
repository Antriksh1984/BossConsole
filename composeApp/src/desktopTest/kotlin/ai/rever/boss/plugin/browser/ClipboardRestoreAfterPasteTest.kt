package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#205: `FluckEngine`'s Cmd+Shift+V ("paste without formatting") restores the
 * pre-paste clipboard after a fixed 200ms delay. That restore used to be unconditional, so
 * anything the user copied during the window was silently replaced with no signal - a Cmd+C
 * landing there was simply lost. [FluckEngine.shouldRestoreClipboardAfterPaste] is the fix:
 * restore only when the clipboard still holds exactly the plain text this code wrote, so a copy
 * that happened in the meantime wins instead of being clobbered.
 */
class ClipboardRestoreAfterPasteTest {
    @Test
    fun `restores when nothing else was copied in the meantime`() {
        assertTrue(FluckEngine.shouldRestoreClipboardAfterPaste("hello world", "hello world"))
    }

    @Test
    fun `does not restore when the user copied something else during the window`() {
        // The exact race #205 reports: a Cmd+C landing in the 200ms window used to be silently
        // overwritten by the restore.
        assertFalse(FluckEngine.shouldRestoreClipboardAfterPaste("something the user just copied", "hello world"))
    }

    @Test
    fun `does not restore when the clipboard was cleared in the meantime`() {
        assertFalse(FluckEngine.shouldRestoreClipboardAfterPaste(null, "hello world"))
    }
}
