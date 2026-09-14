package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException

/**
 * Kernel-side bridge for `SplitViewService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505 and since applied to ActiveTabs,
 * Download, Git, Log, PluginUI, ProjectData, RoleManagement, RunConfig and Supabase. Before this
 * bridge checked identity at all, any process able to open a connection to the kernel IPC server -
 * not only the plugins the host itself loaded - could [openUrlInActivePanel] to force the browser
 * to navigate an arbitrary window's active panel to an attacker-chosen URL, with no confirmation
 * of any kind for an ordinary http(s) URL. A `boss://` URL routes through
 * [ai.rever.boss.components.plugin.providers.SplitViewOperationsImpl]'s deep-link dispatch as
 * [ai.rever.boss.utils.DeepLinkOrigin.EXTERNAL] - `boss://terminal` gets that tier's confirmation
 * prompt, but per this repo's own AGENTS.md every other `boss://` host, `boss://plugin?id=…`
 * included, is unchanged by origin and has no confirmation gate at all. The same unauthenticated
 * caller could also
 * [openFileInActivePanel]/[openFileInEditor]/[openFileInBrowser]/[openFileAtPosition] to open an
 * arbitrary file path in the user's editor or browser with no path confinement, or
 * [preserveCurrentState] to overwrite a workspace snapshot under an attacker-chosen name.
 *
 * This closes the last bridge in this PR's own scope. ProjectData's identical fix is in flight
 * separately; once both land, every bridge on the kernel IPC server BossConsole#53 named requires
 * a verified caller identity.
 */
// One method per RPC the generated service base class declares, plus small identity and audit helpers.
@Suppress("TooManyFunctions")
class SplitViewServiceBridge(
    private val provider: SplitViewOperations,
) : SplitViewServiceGrpcKt.SplitViewServiceCoroutineImplBase() {
    override suspend fun openUrlInActivePanel(request: SplitViewOpenUrlRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openUrlInActivePanel")
        logMutation("openUrlInActivePanel", caller)
        provider.openUrlInActivePanel(request.url, request.title, request.forceNewTab)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInActivePanel(request: SplitViewOpenFileRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileInActivePanel")
        logMutation("openFileInActivePanel", caller)
        provider.openFileInActivePanel(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInBrowser(request: SplitViewOpenFileRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileInBrowser")
        logMutation("openFileInBrowser", caller)
        provider.openFileInBrowser(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInEditor(request: SplitViewOpenFileRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileInEditor")
        logMutation("openFileInEditor", caller)
        provider.openFileInEditor(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileAtPosition(request: SplitViewOpenFileAtPositionRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileAtPosition")
        logMutation("openFileAtPosition", caller)
        provider.openFileAtPosition(request.filePath, request.fileName, request.line, request.column)
        return Empty.getDefaultInstance()
    }

    override suspend fun setActivePanel(request: SplitViewPanelIdRequest): Empty {
        val caller = authenticatedCallerOrRefuse("setActivePanel")
        logMutation("setActivePanel", caller)
        provider.setActivePanel(request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun preserveCurrentState(request: SplitViewPreserveStateRequest): Empty {
        val caller = authenticatedCallerOrRefuse("preserveCurrentState")
        logMutation("preserveCurrentState", caller)
        provider.preserveCurrentState(request.workspaceId, request.workspaceName)
        return Empty.getDefaultInstance()
    }

    override suspend fun selectTabInPanel(request: SplitViewSelectTabInPanelRequest): Empty {
        val caller = authenticatedCallerOrRefuse("selectTabInPanel")
        logMutation("selectTabInPanel", caller)
        provider.selectTabInPanel(request.tabId, request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun applyWorkspace(request: SplitViewApplyWorkspaceRequest): Empty {
        authenticatedCallerOrRefuse("applyWorkspace")
        // Workspace JSON needs to be deserialized on the host side
        // For now, log the request — full implementation depends on LayoutWorkspace serialization
        return Empty.getDefaultInstance()
    }

    /**
     * The verified identity, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun logMutation(
        rpc: String,
        caller: String,
    ) {
        logger.info(
            LogCategory.AUTH,
            "Authenticated split-view mutation requested",
            mapOf("rpc" to rpc, "caller" to caller),
        )
    }

    private fun refuseIdentity(rpc: String): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private companion object {
        val logger = BossLogger.forComponent("SplitViewServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
