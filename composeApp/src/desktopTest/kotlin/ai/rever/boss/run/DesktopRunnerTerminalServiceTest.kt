package ai.rever.boss.run

import ai.rever.boss.components.events.RunnerTerminalEventBus
import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * BossConsole#486 review, round 3: `rerunRunner`'s `withContext(NonCancellable)` guarantees the
 * interrupt/close teardown of the terminal being replaced always finishes, but guarantees nothing
 * about what runs after it returns to a caller that was cancelled *during* that teardown - neither
 * `stateLock.withLock` nor the `RunnerTerminalEventBus` emit are suspension points that check
 * cancellation on their own. The maintainer's own diagnostic (an [IpcEventBridge] that cancels the
 * caller on the close event's forward) reproduced exactly this: the event sequence came out
 * `[Open, Close, Open]`, i.e. a cancelled caller still requested a replacement terminal.
 *
 * This reuses that same diagnostic, but asserts on [RunnerTerminalService]'s own state rather than
 * the event sequence's timing - `configToTerminal`/`isConfigRunning` are what Stop and a later
 * re-run actually read, and are unaffected by how quickly a `MutableSharedFlow` collector drains.
 *
 * `TerminalAPIAccess.sendInterrupt` needs no test double: with nothing registered it returns
 * `false`, so `rerunRunner` skips its `delay()` and goes straight to `closeRunnerTerminal` - which
 * is where this test's [IpcEventBridge] cancels the caller. That proves cancellation landing at
 * teardown/return; cancellation during the delay itself (a real, successful interrupt) needs a
 * `TerminalAPIAccess` provider double this repo does not have and is not covered here, matching
 * the review's own note that the two need separate coverage.
 */
class DesktopRunnerTerminalServiceTest {
    private val windowId = "cancel-test-window"
    private val config =
        RunConfiguration(
            id = "cancel-test-config",
            name = "cancel test",
            type = RunConfigurationType.CUSTOM,
            filePath = "",
            lineNumber = 0,
            language = Language.UNKNOWN,
            command = "echo hi",
            workingDirectory = "",
        )
    private val originalSettings = RunnerSettingsManager.currentSettings.value

    @AfterTest
    fun tearDown() =
        runBlocking {
            RunnerTerminalEventBus.ipcBridge = null
            RunnerTerminalService.cleanupWindow(windowId)
            RunnerSettingsManager.updateSettings(originalSettings)
        }

    @Test
    fun `a caller cancelled during rerun teardown does not leave a stale running config`() =
        runBlocking {
            // MAIN_PANEL is required for the interrupt/close teardown block to run at all -
            // rerunRunner skips it entirely under SIDEBAR_PANEL (usesSidebar).
            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.MAIN_PANEL)

            lateinit var rerunJob: Job
            RunnerTerminalEventBus.ipcBridge =
                object : IpcEventBridge {
                    override suspend fun forward(
                        eventType: String,
                        payload: Any,
                        sourceWindowId: String,
                    ) {
                        if (eventType == "RunnerTerminalCloseEvent") rerunJob.cancel()
                    }
                }

            rerunJob =
                launch {
                    RunnerTerminalService.openRunnerTerminal(config, windowId) {}
                    RunnerTerminalService.rerunRunner(config, windowId) {}
                }
            rerunJob.join()

            assertFalse(
                RunnerTerminalService.isConfigRunning(config.id),
                "a rerun cancelled during teardown must not leave the config marked as running",
            )
            assertNull(
                RunnerTerminalService.configToTerminal.value[config.id],
                "a rerun cancelled during teardown must not leave the config pointing at a never-opened terminal",
            )
        }
}
