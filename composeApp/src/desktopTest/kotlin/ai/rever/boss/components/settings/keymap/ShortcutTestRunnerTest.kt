package ai.rever.boss.components.settings.keymap

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BossConsole#375: `validateKeyName` used to be its own hand-written, fourth copy of the key-name
 * vocabulary, and it had drifted from what the real matchers accept. Running it over every key
 * `AWTKeyboardInterceptor.getKeyName` names - every key the host can dispatch a shortcut for -
 * found it flagging `F1`, `F5`, `F7`, `F12`, `Home`, `End`, `PageUp`, `PageDown`, `Left Bracket`,
 * `Back Slash`, `Quote`, `Back Quote`, `Page Up`, `⇥` and `⏎` as "Unknown key name", all of which
 * fire correctly through both real matchers.
 */
class ShortcutTestRunnerTest {
    private fun assertValid(keyName: String) {
        val (isValid, message) = ShortcutTestRunner.validateKeyName(keyName)
        assertTrue(isValid, "'$keyName' should be a valid key name: $message")
    }

    @Test
    fun `function keys are valid, not just the ones the old list happened to include`() {
        assertValid("F1")
        assertValid("F5")
        assertValid("F7")
        assertValid("F12")
        assertValid("f1")
    }

    @Test
    fun `keys the old hand-written list never learned about are valid`() {
        assertValid("Home")
        assertValid("End")
        assertValid("PageUp")
        assertValid("PageDown")
        assertValid("Page Up")
        assertValid("Left Bracket")
        assertValid("Back Slash")
        assertValid("Quote")
        assertValid("Back Quote")
    }

    @Test
    fun `glyphs AWT reports once the Toolkit is warm are valid`() {
        assertValid("⇥")
        assertValid("⏎")
    }

    @Test
    fun `a character form is valid, not an error demanding a word form`() {
        // Both matchers canonicalize both sides through the same fold before comparing, so a
        // binding stored as "-" matches a real `-` keypress exactly as reliably as "Minus" does -
        // this is no longer something to flag.
        assertValid("-")
        assertValid("←")
    }

    @Test
    fun `single letters are valid`() {
        assertValid("A")
        assertValid("z")
    }

    @Test
    fun `a genuinely unknown name is still rejected`() {
        val (isValid, message) = ShortcutTestRunner.validateKeyName("NotARealKey")
        assertTrue(!isValid, "expected 'NotARealKey' to be rejected")
        assertTrue(message.contains("NotARealKey"), "message should name the offending key: $message")
    }
}
