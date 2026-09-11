package ai.rever.boss.kernel.services

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ProjectProto
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Two routing guarantees on the kernel bridge:
 *
 * 1. `selectProject` confines provider mutation to the UI dispatcher, which lets
 *    project-change announcement stay lock-free.
 *
 * 2. BossConsole#520: `watchRecentProjects` must read [ProjectState] - the process-wide
 *    singleton - rather than a per-window [ProjectDataProvider]'s own mirror, because that
 *    mirror stops updating the moment its owning window disposes it
 *    (`ProjectDataProviderImpl.dispose`). Reading it instead would freeze a KERNEL client at
 *    whatever the window last saw.
 */
class ProjectDataServiceBridgeTest {
    @Test
    fun `selectProject switches from the grpc caller to the UI dispatcher`() {
        val provider = RecordingProjectDataProvider()
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, UI_THREAD_NAME)
            }

        executor.asCoroutineDispatcher().use { uiDispatcher ->
            val bridge = ProjectDataServiceBridge(provider, uiDispatcher)
            val response =
                runBlocking {
                    bridge.selectProject(
                        ProjectProto
                            .newBuilder()
                            .setName("Selected")
                            .setPath("/tmp/boss-bridge-selected")
                            .setLastOpened(42L)
                            .build(),
                    )
                }

            assertEquals(Empty.getDefaultInstance(), response)
        }

        assertTrue(
            provider.selectionThread?.startsWith(UI_THREAD_NAME) == true,
            "selection ran on ${provider.selectionThread}",
        )
        assertEquals(
            ProjectData(name = "Selected", path = "/tmp/boss-bridge-selected", lastOpened = 42L),
            provider.selectedProject,
        )
    }

    @Test
    fun `watchRecentProjects reflects the process-wide ProjectState, not the per-window provider`() =
        runBlocking {
            // Read-only against the real [ProjectState] singleton (whatever the running
            // process's actual recent-projects list happens to be) rather than mutating it -
            // a test writing through `ProjectState.updateRecentProjects` would persist to
            // this machine's real `recent-projects.json`. The fake provider's own list is
            // deliberately different from whatever that is, so a match against [ProjectState]
            // rather than the fake is what proves the routing.
            //
            // [ProjectState] loads its list from disk asynchronously once per JVM, so the
            // snapshot taken around the emission may straddle that load: the first emission
            // must equal whichever side of the load it landed on, and both sides are read
            // here. On a machine with an empty (or absent) file both sides agree.
            val decoy = ProjectData(name = "decoy-window-only-project", path = "/nowhere/decoy", lastOpened = 0L)
            val bridge = ProjectDataServiceBridge(FakeProjectDataProvider(MutableStateFlow(listOf(decoy))))

            val before = ProjectState.recentProjects.value.map { it.path }
            val emitted = bridge.watchRecentProjects(Empty.getDefaultInstance()).first()
            val after = ProjectState.recentProjects.value.map { it.path }
            val emittedPaths = emitted.projectsList.map { it.path }

            assertTrue(
                emittedPaths == before || emittedPaths == after,
                "emitted $emittedPaths matched neither the pre-emission snapshot $before " +
                    "nor the post-emission snapshot $after",
            )
            assertNotEquals(listOf(decoy.path), emittedPaths)
        }

    private class RecordingProjectDataProvider : ProjectDataProvider {
        override val recentProjects: StateFlow<List<ProjectData>> = MutableStateFlow(emptyList())
        var selectedProject: ProjectData? = null
        var selectionThread: String? = null

        override fun updateRecentProjects(project: ProjectData) = Unit

        override fun removeRecentProject(projectPath: String) = Unit

        override fun selectProject(project: ProjectData) {
            selectedProject = project
            selectionThread = Thread.currentThread().name
        }
    }

    private companion object {
        const val UI_THREAD_NAME = "project-data-ui-test"
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
