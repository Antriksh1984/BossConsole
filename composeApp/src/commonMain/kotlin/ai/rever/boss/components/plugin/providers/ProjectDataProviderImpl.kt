package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.plugin.api.ProjectChangeEvent
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.selectProjectInWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementation of ProjectDataProvider that wraps ProjectState.
 * Converts between composeApp's Project type and plugin's ProjectData type.
 *
 * Built per window ([DefaultPlugin]'s `projectDataProviderDelegate`), but [scope]'s collector
 * subscribes to [ProjectState.recentProjects] - a process-wide singleton, not this window's own
 * state - so it outlives the window unless [dispose] cancels it (BossConsole#520). Because the
 * collector target is process-wide, [ai.rever.boss.kernel.services.ProjectDataServiceBridge]
 * deliberately does NOT read [recentProjects] here: a KERNEL client watching it would freeze at
 * whatever this instance last saw the moment its window's [dispose] runs. That bridge reads
 * [ProjectState.recentProjects] directly instead, which is what makes disposing this safe.
 */
class ProjectDataProviderImpl(
    private val windowProjectState: WindowProjectState?,
) : ProjectDataProvider,
    DisposableProvider {
    private val scope = CoroutineScope(Dispatchers.Main)

    // Map ProjectState's recentProjects to plugin's ProjectData type
    private val _recentProjects = MutableStateFlow<List<ProjectData>>(emptyList())
    override val recentProjects: StateFlow<List<ProjectData>> = _recentProjects.asStateFlow()

    init {
        // Sync with ProjectState
        scope.launch {
            ProjectState.recentProjects.collect { projects ->
                _recentProjects.value = projects.map { it.toProjectData() }
            }
        }
    }

    override fun updateRecentProjects(project: ProjectData) {
        ProjectState.updateRecentProjects(project.toProject())
    }

    override fun removeRecentProject(projectPath: String) {
        ProjectState.removeRecentProject(projectPath)
    }

    override fun selectProject(project: ProjectData) {
        val previousPath = windowProjectState?.selectedProject?.value?.path
        selectProjectInWindow(windowProjectState, project.toProject())
        publishSystemEvent(
            ProjectChangeEvent(
                projectPath = project.path,
                previousProjectPath = previousPath,
                windowId = windowProjectState?.windowId ?: "",
            ),
        )
    }

    /** Stops mirroring [ProjectState.recentProjects] into [recentProjects]. See the class KDoc. */
    override fun dispose() {
        scope.cancel()
    }

    // Extension functions for type conversion
    private fun Project.toProjectData(): ProjectData =
        ProjectData(
            name = name,
            path = path,
            lastOpened = lastOpened,
        )

    private fun ProjectData.toProject(): Project =
        Project(
            name = name,
            path = path,
            lastOpened = lastOpened,
        )
}
