package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.keyName
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * BossConsole#329: [KeyCaptureDialog][ai.rever.boss.components.settings.keymap.KeyCaptureDialog]
 * used to persist `capturedKey!!.keyCode.toString()` - Compose's packed `Long`, e.g.
 * `"281474976710721"` - which neither matcher has an alias for, so a shortcut rebound through the
 * Shortcuts screen appeared to save (the list reformats whatever is stored) and then silently
 * fired on neither path. [keyName] is the fix: the same `"Key: X"` extraction
 * `KeymapMatcher.keyMatches` derives from a live event, exposed so the dialog can persist the same
 * thing rather than a number.
 */
class KeyNameTest {
    @Test
    fun `a named key resolves to its name, not its packed keyCode`() {
        assertEquals("A", keyName(Key.A))
        assertNotEquals(Key.A.keyCode.toString(), keyName(Key.A))
    }

    @Test
    fun `distinct keys stay distinct`() {
        assertNotEquals(keyName(Key.DirectionLeft), keyName(Key.DirectionRight))
        assertNotEquals(keyName(Key.Tab), keyName(Key.Enter))
    }

    @Test
    fun `the same key always resolves to the same name`() {
        assertEquals(keyName(Key.Escape), keyName(Key.Escape))
    }
}
