package ai.rever.boss.components.plugin.remote

import ai.rever.boss.kernel.ui.RemoteUiSurfaceHost
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.ui.sdk.UIEventMapper
import ai.rever.boss.ui.sdk.WidgetEvent
import ai.rever.boss.ui.sdk.WidgetTree
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf

/** Shared host attachment, tree state and ordered event delivery for panels and tabs. */
internal class RemoteSurfaceComponent(
    val surfaceId: String,
    val displayName: String,
    private val processId: String,
    private val registry: RemoteUiSurfaceRegistry = RemoteUiSurfaceRegistry.shared,
) {
    private val logger = BossLogger.forComponent("RemoteSurfaceComponent")

    private val _widgetTree = mutableStateOf<WidgetTree?>(null)
    private val _connected = mutableStateOf(false)

    /**
     * Whether the plugin behind this surface declared [RemoteUiSurfaceDescriptor.wantsKeys].
     * Read opportunistically off the registry rather than carried through a new
     * [RemoteUiSurfaceHost] callback: the descriptor is immutable for a surface's whole lifetime, so
     * there is nothing to keep in sync once read, and by the time either existing callback fires at
     * least once the registration [registry].[RemoteUiSurfaceRegistry.surfaceOf] would read is
     * already there - [RemoteUiSurfaceRegistry.attach]'s replay always calls
     * [RemoteUiSurfaceHost.onConnectionChanged] (even with `false`) once the surface is registered,
     * and [RemoteUiSurfaceHost.onTreeUpdated] never fires before it either. Stays at its safe
     * `false` default for the entire window before either callback, matching every other "nothing
     * has told us yet" state this component starts in.
     */
    private val _wantsKeys = mutableStateOf(false)

    /**
     * The transport's view of this panel.
     *
     * Kept private rather than implemented by the class: these are calls the registry makes *into* the
     * panel from an IPC thread, not API for the rest of the host.
     */
    private val surfaceHost =
        object : RemoteUiSurfaceHost {
            override fun onTreeUpdated(tree: WidgetTree) {
                refreshWantsKeys()
                updateTree(tree)
            }

            override fun onConnectionChanged(connected: Boolean) {
                refreshWantsKeys()
                _connected.value = connected
            }
        }

    /** Re-read [RemoteUiSurfaceDescriptor.wantsKeys] off the registry; a no-op before registration. */
    private fun refreshWantsKeys() {
        _wantsKeys.value = registry.surfaceOf(surfaceId)?.descriptor?.wantsKeys == true
    }

    /** Whether a plugin process is currently streaming this panel's surface. */
    val connected: State<Boolean> get() = _connected

    /**
     * Compose content for this remote surface.
     */
    @Composable
    fun Content() {
        val tree by _widgetTree
        RemoteSurfaceContent(
            tree = tree,
            connected = _connected.value,
            wantsKeys = _wantsKeys.value,
            onEvent = { nodeId, event -> sendUIEvent(nodeId, event) },
        )
    }

    /**
     * Bind this panel to its surface. Call this when the panel is first displayed.
     *
     * Safe before the plugin exists: the registry replays the surface's retained tree and connection
     * state if there already is one, and delivers the first update if there is not yet.
     */
    fun attach() {
        logger.info(
            LogCategory.UI,
            "Attaching remote surface to its surface",
            mapOf("surfaceId" to surfaceId, "process" to processId),
        )
        registry.attach(surfaceId, surfaceHost)
    }

    /**
     * Update the displayed widget tree (called from the transport, or directly in tests).
     */
    fun updateTree(tree: WidgetTree) {
        _widgetTree.value = tree
    }

    fun dispose() {
        registry.detach(surfaceId, surfaceHost)
        _connected.value = false
        logger.info(LogCategory.UI, "Remote surface disposed", mapOf("surfaceId" to surfaceId))
    }

    // ---- Internal ----

    /**
     * Hand one interaction to the surface's outgoing queue.
     *
     * Not `suspend`, and deliberately called straight from the Compose callback: the send is a
     * non-blocking enqueue, so interactions reach the wire in the order the user made them. Handing each
     * event to its own coroutine let two keystrokes race, and `TextChange` carries the *whole* field
     * value with last-write-wins semantics — reversed, two fast keystrokes silently revert a character.
     *
     * The `WidgetEvent` → proto `UIEvent` mapping lives in [UIEventMapper] (boss-ui-sdk): it is total
     * over the sealed event type, so no oneof case can be silently skipped — which is exactly how
     * dropdown selections used to cross the wire as events with no payload at all.
     */
    private fun sendUIEvent(
        nodeId: String,
        event: WidgetEvent,
    ) {
        // Payloads can contain what the user typed — log the shape, not the content.
        logger.debug(
            LogCategory.UI,
            "Remote UI event",
            mapOf("surfaceId" to surfaceId, "node" to nodeId, "type" to event::class.simpleName),
        )
        val proto = UIEventMapper.toProto(surfaceId, nodeId, event, System.currentTimeMillis())
        if (!registry.emit(surfaceId, proto)) {
            logger.debug(
                LogCategory.UI,
                "Dropped UI event: no plugin holds this surface",
                mapOf("surfaceId" to surfaceId),
            )
        }
    }
}
