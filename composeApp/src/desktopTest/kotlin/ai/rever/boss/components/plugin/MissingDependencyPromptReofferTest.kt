package ai.rever.boss.components.plugin

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class MissingDependencyPromptReofferTest {
    @Test
    fun `closing a rejecting collector during throttle leaves the prompt available`() =
        runTest {
            val installer =
                object : MissingDependencyInstaller {
                    override fun isInstalled(pluginId: String): Boolean = false

                    override suspend fun displayNameFor(pluginId: String): String? = null

                    override suspend fun install(pluginId: String): Result<Unit> = Result.success(Unit)
                }
            val prompt =
                MissingDependencyPrompt(
                    MissingPluginDependency("dependent", "Dependent", "missing", optional = false),
                    installer,
                    windowId = "target",
                )
            val bus = PluginDependencyBus()
            bus.report(prompt)
            val rejectingCollector =
                launch {
                    reofferMissingDependencyPrompt(bus, bus.missingDependencies.first())
                }
            runCurrent()
            rejectingCollector.cancel()
            rejectingCollector.join()

            assertEquals(prompt, withTimeoutOrNull(1) { bus.missingDependencies.first() })
        }
}
