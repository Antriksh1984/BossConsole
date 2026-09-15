package ai.rever.boss.app

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.window.MenuActionsHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#700: [KeymapActions.getAllActionIds] and [BossAppDialogs]'s `onCommandSelect`
 * `when` used to disagree silently - 26 of the 48 advertised commands fell through a bare
 * `else -> {}` with no signal to the user or a test. This pins that every catalog id is
 * classified as exactly one of: already handled by `onCommandSelect`'s own pre-existing
 * branches (mirrored below, unchanged by this fix), dispatched by
 * [dispatchSpotlightTabBrowserCommand], or named in [SPOTLIGHT_UNSUPPORTED_COMMAND_IDS]. A
 * future catalog addition nobody classifies fails this test instead of silently doing nothing
 * when a user selects it.
 */
class SpotlightCommandCoverageTest {
    /** Mirrors the ids `onCommandSelect`'s own `when` branches already handled before this fix. */
    private val preExistingHandledIds =
        setOf(
            KeymapActions.WINDOW_NEW,
            KeymapActions.WINDOW_CLOSE,
            KeymapActions.TAB_NEW,
            KeymapActions.TAB_CLOSE,
            KeymapActions.BROWSER_RELOAD,
            KeymapActions.BROWSER_ZOOM_RESET,
            KeymapActions.BROWSER_ZOOM_IN,
            KeymapActions.BROWSER_ZOOM_OUT,
            KeymapActions.PANEL_NAVIGATE_LEFT,
            KeymapActions.PANEL_NAVIGATE_RIGHT,
            KeymapActions.PANEL_NAVIGATE_UP,
            KeymapActions.PANEL_NAVIGATE_DOWN,
            KeymapActions.PANEL_SPLIT_VERTICAL,
            KeymapActions.PANEL_SPLIT_HORIZONTAL,
            KeymapActions.QUICK_SWITCHER_OPEN,
            KeymapActions.WORKSPACE_SAVE,
            KeymapActions.CODEBASE_OPEN,
            KeymapActions.GLOBAL_SEARCH_OPEN,
            KeymapActions.FOCUS_MODE_TOGGLE,
            KeymapActions.CHROME_DENSITY_CYCLE,
            KeymapActions.SETTINGS_OPEN,
            KeymapActions.HELP_SHORTCUTS,
        )

    /** The 18 ids [dispatchSpotlightTabBrowserCommand] recognizes. */
    private val tabBrowserCommandIds =
        setOf(
            KeymapActions.TAB_NEXT,
            KeymapActions.TAB_PREVIOUS,
            KeymapActions.TAB_REOPEN_CLOSED,
            KeymapActions.TAB_NEXT_POSITIONAL,
            KeymapActions.TAB_PREVIOUS_POSITIONAL,
            KeymapActions.TAB_SELECT_1,
            KeymapActions.TAB_SELECT_2,
            KeymapActions.TAB_SELECT_3,
            KeymapActions.TAB_SELECT_4,
            KeymapActions.TAB_SELECT_5,
            KeymapActions.TAB_SELECT_6,
            KeymapActions.TAB_SELECT_7,
            KeymapActions.TAB_SELECT_8,
            KeymapActions.TAB_SELECT_LAST,
            KeymapActions.BROWSER_FIND,
            KeymapActions.BROWSER_BACK,
            KeymapActions.BROWSER_FORWARD,
            KeymapActions.BROWSER_DEVTOOLS,
        )

    private var collectorJob: Job? = null

    @AfterTest
    fun cleanup() {
        collectorJob?.cancel()
        collectorJob = null
    }

    @Test
    fun `every catalog id is classified as handled, dispatched, or explicitly unsupported`() {
        val catalog = KeymapActions.getAllActionIds().toSet()
        val classified = preExistingHandledIds + tabBrowserCommandIds + SPOTLIGHT_UNSUPPORTED_COMMAND_IDS

        assertEquals(
            catalog,
            classified,
            "catalog and dispatch classification disagree - " +
                "missing from classification: ${catalog - classified}, " +
                "classified but not in the catalog: ${classified - catalog}",
        )
    }

    @Test
    fun `the three classifications do not overlap`() {
        assertTrue(
            (preExistingHandledIds intersect tabBrowserCommandIds).isEmpty(),
            "an id is claimed by both the pre-existing handlers and the new tab-browser dispatch",
        )
        assertTrue(
            (preExistingHandledIds intersect SPOTLIGHT_UNSUPPORTED_COMMAND_IDS).isEmpty(),
            "an id is claimed by both the pre-existing handlers and the unsupported set",
        )
        assertTrue(
            (tabBrowserCommandIds intersect SPOTLIGHT_UNSUPPORTED_COMMAND_IDS).isEmpty(),
            "an id is claimed by both the new tab-browser dispatch and the unsupported set",
        )
    }

    @Test
    fun `dispatchSpotlightTabBrowserCommand recognizes exactly the tab and browser command ids`() {
        for (id in tabBrowserCommandIds) {
            assertTrue(dispatchSpotlightTabBrowserCommand(id, "coverage-test-window"), "$id should be dispatched")
        }
        for (id in KeymapActions.getAllActionIds().toSet() - tabBrowserCommandIds) {
            assertFalse(
                dispatchSpotlightTabBrowserCommand(id, "coverage-test-window"),
                "$id should NOT be dispatched here",
            )
        }
    }

    @Test
    fun `TAB_NEXT and TAB_PREVIOUS commit their MRU cycle immediately`() {
        // Spotlight has no modifier-release gesture to commit an MRU cycle on (unlike Ctrl+Tab),
        // so each step must be followed by an explicit commit in the same call. Dispatchers.
        // Unconfined runs the collector synchronously up to its first suspension point before
        // `launch` returns, so it is guaranteed attached before the dispatch below emits -
        // the same pattern ShortcutKeyUpSemanticsTest uses for this exact flow.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val events = mutableListOf<Pair<String, MenuActionsHandler.TabSwitchAction>>()
        collectorJob = scope.launch { MenuActionsHandler.tabSwitchEvents.collect { events.add(it) } }

        dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_NEXT, "commit-test-window")
        dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_PREVIOUS, "commit-test-window")

        assertEquals(
            listOf(
                "commit-test-window" to MenuActionsHandler.TabSwitchAction.NEXT,
                "commit-test-window" to MenuActionsHandler.TabSwitchAction.COMMIT,
                "commit-test-window" to MenuActionsHandler.TabSwitchAction.PREVIOUS,
                "commit-test-window" to MenuActionsHandler.TabSwitchAction.COMMIT,
            ),
            events,
        )
        scope.cancel()
    }

    @Test
    fun `positional tab stepping emits no commit`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val events = mutableListOf<Pair<String, MenuActionsHandler.TabSwitchAction>>()
        collectorJob = scope.launch { MenuActionsHandler.tabSwitchEvents.collect { events.add(it) } }

        dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_NEXT_POSITIONAL, "positional-test-window")
        dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_PREVIOUS_POSITIONAL, "positional-test-window")

        assertEquals(
            listOf(
                "positional-test-window" to MenuActionsHandler.TabSwitchAction.NEXT_POSITIONAL,
                "positional-test-window" to MenuActionsHandler.TabSwitchAction.PREVIOUS_POSITIONAL,
            ),
            events,
        )
        scope.cancel()
    }

    @Test
    fun `select-tab commands use zero-based indices`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val events = mutableListOf<Pair<String, Int>>()
        collectorJob = scope.launch { MenuActionsHandler.selectTabIndexEvents.collect { events.add(it) } }

        dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_SELECT_1, "select-test-window")
        dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_SELECT_8, "select-test-window")
        dispatchSpotlightTabBrowserCommand(KeymapActions.TAB_SELECT_LAST, "select-test-window")

        assertEquals(
            listOf(
                "select-test-window" to 0,
                "select-test-window" to 7,
                "select-test-window" to MenuActionsHandler.LAST_TAB_INDEX,
            ),
            events,
        )
        scope.cancel()
    }
}
