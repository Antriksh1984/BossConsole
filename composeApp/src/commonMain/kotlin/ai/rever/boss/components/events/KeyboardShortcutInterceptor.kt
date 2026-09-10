package ai.rever.boss.components.events

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.ShortcutContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/**
 * Set of modifier-only keys that should not trigger shortcut matching.
 * These keys don't have a standalone action and are only used in combination.
 */
internal val MODIFIER_ONLY_KEYS =
    setOf(
        Key.CapsLock,
        Key.ShiftLeft,
        Key.ShiftRight,
        Key.CtrlLeft,
        Key.CtrlRight,
        Key.AltLeft,
        Key.AltRight,
        Key.MetaLeft,
        Key.MetaRight,
        Key.NumLock,
        Key.ScrollLock,
    )

/**
 * Creates a modifier that intercepts keyboard events and routes matched shortcuts
 * to KeyboardEventBus, preventing the wrapped component from receiving them.
 *
 * Use this to wrap components that consume all keyboard input (like terminals, browsers)
 * to ensure global/workspace shortcuts still work.
 *
 * @param windowId The current window ID for event routing
 * @param source The event source identifier (e.g., COMPONENT_TERMINAL, COMPONENT_BROWSER)
 * @param context The shortcut context for matching (e.g., TERMINAL, BROWSER)
 * @return Modifier that intercepts matched shortcuts
 */
@Composable
fun Modifier.interceptKeyboardShortcuts(
    windowId: String,
    source: KeyEventSource,
    context: ShortcutContext,
): Modifier {
    val settings by KeymapSettingsManager.currentSettings.collectAsState()
    val matcher = remember(settings) { KeymapMatcher(settings) }
    val coroutineScope = rememberCoroutineScope()

    // The key currently armed by a matched KeyDown, or null - BossConsole#490: emitting (which
    // is what eventually invokes the bound action) must wait for the matching KeyUp, not fire on
    // KeyDown itself, and must not re-fire on repeat KeyDown events while the key is held. Mirrors
    // AWTKeyboardInterceptor.handleKeyPressed/handleKeyReleased, the other entry point this
    // matches against the same KeymapMatcher.
    var armedKey by remember { mutableStateOf<Key?>(null) }

    // Same stale-arm hazard AWTKeyboardInterceptor's focus-loss listener closes: a lost KeyUp
    // (this composable moving out of focus, or being disposed with a key still held) would
    // otherwise leave armedKey set until the SAME key is pressed again - at which point the
    // repeat branch above would swallow that later, unrelated press and its KeyUp would emit
    // the shortcut. This is unwired to any production caller today, but it is the file the next
    // wiring reaches for, so it should not carry this gap forward.
    return this
        .onFocusChanged { if (!it.hasFocus) armedKey = null }
        .onPreviewKeyEvent { keyEvent ->
            when (keyEvent.type) {
                KeyEventType.KeyUp -> {
                    val armed = armedKey
                    if (armed != null && keyEvent.key == armed) {
                        armedKey = null
                        coroutineScope.launch {
                            KeyboardEventBus.emit(
                                KeyboardEvent(
                                    keyEvent = keyEvent,
                                    source = source,
                                    context = context,
                                    sourceWindowId = windowId,
                                ),
                            )
                        }
                        true // Consume the event - don't let wrapped component handle it
                    } else {
                        false
                    }
                }

                KeyEventType.KeyDown -> {
                    // A repeat KeyDown for the key already armed: keep claiming it (so it doesn't
                    // leak to the wrapped component while held) without re-matching or re-arming -
                    // this is what stops OS auto-repeat from emitting more than once.
                    if (armedKey == keyEvent.key) return@onPreviewKeyEvent true

                    // Skip modifier-only keys
                    if (keyEvent.key in MODIFIER_ONLY_KEYS) return@onPreviewKeyEvent false

                    // Check if this key combo matches any shortcut - recognized now, but not
                    // emitted until the matching KeyUp above.
                    if (matcher.match(keyEvent, context) != null) {
                        armedKey = keyEvent.key
                        true // Consume the event - don't let wrapped component handle it
                    } else {
                        false // Let wrapped component handle regular input
                    }
                }

                else -> {
                    false
                }
            }
        }
}

/**
 * Composable wrapper that intercepts keyboard shortcuts before they reach child content.
 * Convenience wrapper around [interceptKeyboardShortcuts] modifier.
 *
 * @param windowId The current window ID for event routing
 * @param source The event source identifier
 * @param context The shortcut context for matching
 * @param content The content to wrap
 */
@Composable
fun KeyboardShortcutInterceptor(
    windowId: String,
    source: KeyEventSource,
    context: ShortcutContext,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .interceptKeyboardShortcuts(windowId, source, context),
    ) {
        content()
    }
}
