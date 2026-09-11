package ai.rever.boss.run

import ai.rever.boss.components.events.RunnerTerminalEventBus
import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import ai.rever.boss.plugin.run.RunnerTerminalCloseEvent
import ai.rever.boss.plugin.run.RunnerTerminalOpenEvent
import ai.rever.boss.plugin.run.RunnerTerminalStopEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private val windowA = "cancel-test-window-a"
    private val windowB = "cancel-test-window-b"
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
            RunnerTerminalService.cleanupWindow(windowA)
            RunnerTerminalService.cleanupWindow(windowB)
            RunnerSettingsManager.updateSettings(originalSettings)
        }

    @Test
    fun `a caller cancelled during rerun teardown does not leave a stale running config`() =
        runBlocking {
            // MAIN_PANEL is required for the interrupt/close teardown block to run at all -
            // rerunRunner skips it entirely under SIDEBAR_PANEL (usesSidebar).
            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.MAIN_PANEL)

            val events = mutableListOf<String>()
            lateinit var rerunJob: Job
            RunnerTerminalEventBus.ipcBridge =
                object : IpcEventBridge {
                    override suspend fun forward(
                        eventType: String,
                        payload: Any,
                        sourceWindowId: String,
                    ) {
                        events += eventType
                        if (eventType == "RunnerTerminalCloseEvent") rerunJob.cancel()
                    }
                }

            rerunJob =
                launch(start = CoroutineStart.LAZY) {
                    RunnerTerminalService.openRunnerTerminal(config, windowId) {}
                    RunnerTerminalService.rerunRunner(config, windowId) {}
                }
            rerunJob.start()
            rerunJob.join()

            assertTrue(rerunJob.isCancelled)
            assertEquals(listOf("RunnerTerminalOpenEvent", "RunnerTerminalCloseEvent"), events)

            assertFalse(
                RunnerTerminalService.isConfigRunning(config.id),
                "a rerun cancelled during teardown must not leave the config marked as running",
            )
            assertNull(
                RunnerTerminalService.configToTerminal.value[config.id],
                "a rerun cancelled during teardown must not leave the config pointing at a never-opened terminal",
            )
        }

    @Test
    fun `cancelled rerun preserves the other window terminal and routes Stop to it`() =
        runBlocking {
            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.MAIN_PANEL)
            // Model the host's window-scoped open/close routing without creating a live PTY.
            val liveTabs = mutableSetOf<Pair<String, String>>()
            val events = mutableListOf<String>()
            var rerunJob: Job? = null
            RunnerTerminalEventBus.ipcBridge =
                object : IpcEventBridge {
                    override suspend fun forward(
                        eventType: String,
                        payload: Any,
                        sourceWindowId: String,
                    ) {
                        events += eventType
                        when (payload) {
                            is RunnerTerminalOpenEvent -> {
                                liveTabs += sourceWindowId to payload.terminalId
                            }

                            is RunnerTerminalCloseEvent -> {
                                liveTabs -= sourceWindowId to payload.terminalId
                                rerunJob?.cancel()
                            }

                            is RunnerTerminalStopEvent -> {
                                assertTrue(liveTabs.remove(sourceWindowId to payload.terminalId))
                            }
                        }
                    }
                }
            val originalId = RunnerTerminalService.openRunnerTerminal(config, windowA) {}
            RunnerTerminalService.openRunnerTerminal(config, windowB) {}
            val job = launch(start = CoroutineStart.LAZY) { RunnerTerminalService.rerunRunner(config, windowB) {} }
            rerunJob = job
            job.start()
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(
                listOf("RunnerTerminalOpenEvent", "RunnerTerminalOpenEvent", "RunnerTerminalCloseEvent"),
                events,
            )
            // Existing rerun routing tears down the first registered window (A), not B.
            assertEquals(setOf(windowB to originalId), liveTabs)
            RunnerTerminalService.removeTerminal(windowA, originalId)
            assertEquals(originalId, RunnerTerminalService.configToTerminal.value[config.id])
            assertFalse(RunnerTerminalService.isConfigRunningInWindow(windowA, config.id))
            assertTrue(RunnerTerminalService.isConfigRunningInWindow(windowB, config.id))
            assertTrue(RunnerTerminalService.isConfigRunning(config.id))
            assertEquals(config.id, RunnerTerminalService.getConfigForTerminal(originalId))

            // No provider is registered, so closeActiveTab returns false; verify Stop's actual
            // window/terminal event and tracking cleanup rather than claiming a live PTY closed.
            RunnerTerminalService.stopRunner(windowB, config.id)
            assertEquals("RunnerTerminalStopEvent", events.last())
            assertTrue(liveTabs.isEmpty())
            assertFalse(RunnerTerminalService.isConfigRunning(config.id))
            assertNull(RunnerTerminalService.configToTerminal.value[config.id])
        }
}
