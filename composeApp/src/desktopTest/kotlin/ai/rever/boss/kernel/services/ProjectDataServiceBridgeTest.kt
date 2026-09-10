package ai.rever.boss.kernel.services

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * BossConsole#520: `watchRecentProjects` must read [ProjectState] - the process-wide singleton -
 * rather than a per-window [ProjectDataProvider]'s own mirror, because that mirror stops updating
 * the moment its owning window disposes it (`ProjectDataProviderImpl.dispose`). Reading it instead
 * would freeze a KERNEL client at whatever the window last saw.
 *
 * Read-only against the real [ProjectState] singleton (whatever the running process's actual
 * recent-projects list happens to be) rather than mutating it - a test writing through
 * `ProjectState.updateRecentProjects` would persist to this machine's real `recent-projects.json`.
 * The fake provider's own list is deliberately different from whatever that is, so a match against
 * [ProjectState] rather than the fake is what proves the routing.
 */
class ProjectDataServiceBridgeTest {
    @Test
    fun `watchRecentProjects reflects the process-wide ProjectState, not the per-window provider`() =
        runBlocking {
            val decoy = ProjectData(name = "decoy-window-only-project", path = "/nowhere/decoy", lastOpened = 0L)
            val bridge = ProjectDataServiceBridge(FakeProjectDataProvider(MutableStateFlow(listOf(decoy))))

            val emitted = bridge.watchRecentProjects(Empty.getDefaultInstance()).first()
            val emittedPaths = emitted.projectsList.map { it.path }
            val actualPaths = ProjectState.recentProjects.value.map { it.path }

            assertEquals(actualPaths, emittedPaths)
            assertNotEquals(listOf(decoy.path), emittedPaths)
        }
}

/** A provider whose [recentProjects] is fixed and deliberately unlike [ProjectState]'s real value. */
private class FakeProjectDataProvider(
    override val recentProjects: StateFlow<List<ProjectData>>,
) : ProjectDataProvider {
    override fun updateRecentProjects(project: ProjectData) = error("not used by this test")

    override fun removeRecentProject(projectPath: String) = error("not used by this test")

    override fun selectProject(project: ProjectData) = error("not used by this test")
}
