package ai.rever.boss.app

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.window.MenuActionsHandler

/**
 * BossConsole#700: [KeymapActions.getAllActionIds] advertises 48 command ids to Spotlight's
 * search catalog, but [ai.rever.boss.app.BossAppDialogs]'s `onCommandSelect` `when` only ever
 * handled 22 of them - the other 26 fell through a bare `else -> {}` and silently did nothing
 * when selected.
 *
 * Eighteen of those 26 already have a `MenuActionsHandler` route (every tab-navigation and
 * browser-history command); nothing was wrong with the host wiring, `onCommandSelect` simply
 * never called it. [dispatchSpotlightTabBrowserCommand] is that missing call, extracted into a
 * pure function (rather than inlined into the `when`) so the id-to-trigger mapping is
 * unit-testable without mounting the dialog.
 *
 * The remaining eight - the editor verbs and the debug external-link entry - have no safe host
 * route: the editor lives in a plugin the host cannot reach synchronously from here without
 * either synthesizing keyboard events or duplicating the plugin's own save/find implementation,
 * both of which are worse than reporting "not available yet". Those are named in
 * [SPOTLIGHT_UNSUPPORTED_COMMAND_IDS], which `onCommandSelect` uses to show an explicit status
 * message instead of discarding the selection. `SpotlightCommandCoverageTest` pins that every id
 * [KeymapActions.getAllActionIds] returns is classified as exactly one of: already handled by
 * `onCommandSelect`'s own pre-existing branches, dispatched here, or named as unsupported - so a
 * future catalog addition nobody wires up fails a test instead of silently doing nothing.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun dispatchSpotlightTabBrowserCommand(
    actionId: String,
    windowId: String,
): Boolean {
    when (actionId) {
        // Spotlight's selection is one discrete action with no modifier-release gesture to
        // commit an MRU cycle on - unlike Ctrl+Tab, which commits when the held modifier is
        // released - so the step and its commit fire back to back. triggerCommitTabCycle is a
        // documented no-op outside an in-progress cycle, so this is safe even if the window was
        // not mid-cycle.
        KeymapActions.TAB_NEXT -> {
            MenuActionsHandler.triggerNextTab(windowId)
            MenuActionsHandler.triggerCommitTabCycle(windowId)
        }

        KeymapActions.TAB_PREVIOUS -> {
            MenuActionsHandler.triggerPreviousTab(windowId)
            MenuActionsHandler.triggerCommitTabCycle(windowId)
        }

        // Positional stepping starts no MRU cycle (see TabSwitchAction.NEXT_POSITIONAL's own
        // KDoc), so it needs no matching commit.
        KeymapActions.TAB_NEXT_POSITIONAL -> {
            MenuActionsHandler.triggerNextTabPositional(windowId)
        }

        KeymapActions.TAB_PREVIOUS_POSITIONAL -> {
            MenuActionsHandler.triggerPreviousTabPositional(windowId)
        }

        KeymapActions.TAB_REOPEN_CLOSED -> {
            MenuActionsHandler.triggerReopenClosedTab(windowId)
        }

        // triggerSelectTabByIndex is zero-based; TAB_SELECT_1 is Cmd+1, index 0.
        KeymapActions.TAB_SELECT_1 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 0)
        }

        KeymapActions.TAB_SELECT_2 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 1)
        }

        KeymapActions.TAB_SELECT_3 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 2)
        }

        KeymapActions.TAB_SELECT_4 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 3)
        }

        KeymapActions.TAB_SELECT_5 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 4)
        }

        KeymapActions.TAB_SELECT_6 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 5)
        }

        KeymapActions.TAB_SELECT_7 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 6)
        }

        KeymapActions.TAB_SELECT_8 -> {
            MenuActionsHandler.triggerSelectTabByIndex(windowId, 7)
        }

        KeymapActions.TAB_SELECT_LAST -> {
            MenuActionsHandler.triggerSelectLastTab(windowId)
        }

        KeymapActions.BROWSER_FIND -> {
            MenuActionsHandler.triggerBrowserFind(windowId)
        }

        KeymapActions.BROWSER_BACK -> {
            MenuActionsHandler.triggerBrowserBack(windowId)
        }

        KeymapActions.BROWSER_FORWARD -> {
            MenuActionsHandler.triggerBrowserForward(windowId)
        }

        KeymapActions.BROWSER_DEVTOOLS -> {
            MenuActionsHandler.triggerBrowserDevTools(windowId)
        }

        else -> {
            return false
        }
    }
    return true
}

/**
 * Catalog ids with no safe host-side dispatch route. See the KDoc on
 * [dispatchSpotlightTabBrowserCommand] for why: these belong to the editor-tab plugin or are a
 * debug-only entry, and there is no route to them from here that does not either synthesize
 * keyboard events or duplicate the plugin's own implementation.
 */
internal val SPOTLIGHT_UNSUPPORTED_COMMAND_IDS: Set<String> =
    setOf(
        KeymapActions.EDITOR_SAVE,
        KeymapActions.EDITOR_SAVE_ALL,
        KeymapActions.EDITOR_FIND,
        KeymapActions.EDITOR_REPLACE,
        KeymapActions.EDITOR_FIND_NEXT,
        KeymapActions.EDITOR_FIND_PREVIOUS,
        KeymapActions.EDITOR_GO_TO_LINE,
        KeymapActions.TEST_EXTERNAL_LINK,
    )
