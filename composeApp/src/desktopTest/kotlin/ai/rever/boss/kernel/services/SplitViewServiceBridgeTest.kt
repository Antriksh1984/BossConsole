package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.SplitViewApplyWorkspaceRequest
import ai.rever.boss.ipc.proto.services.SplitViewOpenFileAtPositionRequest
import ai.rever.boss.ipc.proto.services.SplitViewOpenFileRequest
import ai.rever.boss.ipc.proto.services.SplitViewOpenUrlRequest
import ai.rever.boss.ipc.proto.services.SplitViewPanelIdRequest
import ai.rever.boss.ipc.proto.services.SplitViewPreserveStateRequest
import ai.rever.boss.ipc.proto.services.SplitViewSelectTabInPanelRequest
import ai.rever.boss.ipc.proto.services.SplitViewServiceGrpcKt
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.api.TabsComponent
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The split-view bridge, exercised over a real gRPC server (BossConsole#53).
 *
 * Before this bridge checked identity, any process able to open a connection to the kernel IPC
 * server - not only the plugins the host itself loaded - could force an arbitrary window's active
 * panel to navigate to an attacker-chosen URL via `openUrlInActivePanel`, open an arbitrary file
 * path with no confinement via `openFileInActivePanel`/`openFileInEditor`/`openFileInBrowser`/
 * `openFileAtPosition`, or overwrite a workspace snapshot under an attacker-chosen name via
 * `preserveCurrentState`.
 */
class SplitViewServiceBridgeTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeSplitViewOperations
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticated: SplitViewServiceGrpcKt.SplitViewServiceCoroutineStub
    private lateinit var anonymous: SplitViewServiceGrpcKt.SplitViewServiceCoroutineStub

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        provider = FakeSplitViewOperations()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(SplitViewServiceBridge(provider))
                .build()
                .start()
        authenticatedChannel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        anonymousChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        authenticated = SplitViewServiceGrpcKt.SplitViewServiceCoroutineStub(authenticatedChannel)
        anonymous = SplitViewServiceGrpcKt.SplitViewServiceCoroutineStub(anonymousChannel)
    }

    @AfterTest
    fun tearDown() {
        authenticatedChannel.shutdownNow()
        authenticatedChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        anonymousChannel.shutdownNow()
        anonymousChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `every RPC is refused with no credential, and never reaches the provider`() =
        runBlocking {
            assertRefused {
                anonymous.openUrlInActivePanel(
                    SplitViewOpenUrlRequest.newBuilder().setUrl("https://evil.example").build(),
                )
            }
            assertRefused {
                anonymous.openFileInActivePanel(
                    SplitViewOpenFileRequest.newBuilder().setFilePath("/etc/passwd").build(),
                )
            }
            assertRefused {
                anonymous.openFileInBrowser(SplitViewOpenFileRequest.newBuilder().setFilePath("/etc/passwd").build())
            }
            assertRefused {
                anonymous.openFileInEditor(SplitViewOpenFileRequest.newBuilder().setFilePath("/etc/passwd").build())
            }
            assertRefused {
                anonymous.openFileAtPosition(
                    SplitViewOpenFileAtPositionRequest.newBuilder().setFilePath("/etc/passwd").build(),
                )
            }
            assertRefused { anonymous.setActivePanel(SplitViewPanelIdRequest.newBuilder().setPanelId("p1").build()) }
            assertRefused {
                anonymous.preserveCurrentState(
                    SplitViewPreserveStateRequest
                        .newBuilder()
                        .setWorkspaceId("w1")
                        .setWorkspaceName("evil")
                        .build(),
                )
            }
            assertRefused {
                anonymous.selectTabInPanel(
                    SplitViewSelectTabInPanelRequest
                        .newBuilder()
                        .setTabId("t1")
                        .setPanelId("p1")
                        .build(),
                )
            }
            assertRefused {
                anonymous.applyWorkspace(SplitViewApplyWorkspaceRequest.newBuilder().setWorkspaceJson("{}").build())
            }

            assertTrue(provider.calls.isEmpty(), "a refused call must never reach the provider")
        }

    @Test
    fun `an authenticated caller can open a URL, files and position`() =
        runBlocking {
            authenticated.openUrlInActivePanel(
                SplitViewOpenUrlRequest
                    .newBuilder()
                    .setUrl("https://example.com")
                    .setTitle("t")
                    .build(),
            )
            authenticated.openFileInActivePanel(
                SplitViewOpenFileRequest
                    .newBuilder()
                    .setFilePath("/tmp/a")
                    .setFileName("a")
                    .build(),
            )
            authenticated.openFileInBrowser(
                SplitViewOpenFileRequest
                    .newBuilder()
                    .setFilePath("/tmp/a")
                    .setFileName("a")
                    .build(),
            )
            authenticated.openFileInEditor(
                SplitViewOpenFileRequest
                    .newBuilder()
                    .setFilePath("/tmp/a")
                    .setFileName("a")
                    .build(),
            )
            authenticated.openFileAtPosition(
                SplitViewOpenFileAtPositionRequest
                    .newBuilder()
                    .setFilePath("/tmp/a")
                    .setFileName("a")
                    .setLine(1)
                    .setColumn(2)
                    .build(),
            )

            assertEquals(
                listOf(
                    "openUrlInActivePanel",
                    "openFileInActivePanel",
                    "openFileInBrowser",
                    "openFileInEditor",
                    "openFileAtPosition",
                ),
                provider.calls,
            )
        }

    @Test
    fun `an authenticated caller can drive panel and workspace state`() =
        runBlocking {
            authenticated.setActivePanel(SplitViewPanelIdRequest.newBuilder().setPanelId("p1").build())
            authenticated.preserveCurrentState(
                SplitViewPreserveStateRequest
                    .newBuilder()
                    .setWorkspaceId("w1")
                    .setWorkspaceName("n")
                    .build(),
            )
            authenticated.selectTabInPanel(
                SplitViewSelectTabInPanelRequest
                    .newBuilder()
                    .setTabId("t1")
                    .setPanelId("p1")
                    .build(),
            )
            authenticated.applyWorkspace(
                SplitViewApplyWorkspaceRequest.newBuilder().setWorkspaceJson("{}").build(),
            )

            assertEquals(
                listOf("setActivePanel", "preserveCurrentState", "selectTabInPanel"),
                provider.calls,
            )
        }

    @Test
    fun `revoked caller cannot invoke a unary RPC`() =
        runBlocking {
            tokenRegistry.revoke(CALLER)
            assertRefused {
                authenticated.setActivePanel(SplitViewPanelIdRequest.newBuilder().setPanelId("p1").build())
            }
            assertTrue(provider.calls.isEmpty())
        }

    private suspend fun assertRefused(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
    }

    /** Records every method it was actually asked to perform, so a refusal can be proven silent. */
    private class FakeSplitViewOperations : SplitViewOperations {
        val calls = mutableListOf<String>()

        override fun openUrlInActivePanel(
            url: String,
            title: String,
            forceNewTab: Boolean,
        ) {
            calls += "openUrlInActivePanel"
        }

        override fun openFileInActivePanel(
            filePath: String,
            fileName: String,
        ) {
            calls += "openFileInActivePanel"
        }

        override fun openFileInBrowser(
            filePath: String,
            fileName: String,
        ) {
            calls += "openFileInBrowser"
        }

        override fun openFileInEditor(
            filePath: String,
            fileName: String,
        ) {
            calls += "openFileInEditor"
        }

        override fun openFileAtPosition(
            filePath: String,
            fileName: String,
            line: Int,
            column: Int,
        ) {
            calls += "openFileAtPosition"
        }

        override fun setActivePanel(panelId: String) {
            calls += "setActivePanel"
        }

        override fun preserveCurrentState(
            workspaceId: String,
            workspaceName: String,
        ) {
            calls += "preserveCurrentState"
        }

        override fun getActiveTabsComponent(): TabsComponent? = null

        override fun applyWorkspace(workspace: LayoutWorkspace) = Unit

        override fun selectTabInPanel(
            tabId: String,
            panelId: String,
        ) {
            calls += "selectTabInPanel"
        }

        override fun openTab(tabInfo: TabInfo) = Unit

        override fun openTabInSplit(
            tabInfo: TabInfo,
            mode: TabSplitMode,
        ) = Unit

        override val supportsOpenPanelAsTab: Boolean = false

        override fun openPanelAsTab(panelId: ai.rever.boss.plugin.api.PanelId) = Unit

        override fun openUrlInSplit(
            url: String,
            title: String,
            mode: TabSplitMode,
        ) = Unit
    }

    private companion object {
        const val CALLER = "split-view-plugin"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
