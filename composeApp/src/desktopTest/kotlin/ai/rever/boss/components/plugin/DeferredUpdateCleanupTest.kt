package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeferredUpdateCleanupTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a live swap removes its old artifact without sweeping a deferred plugin`() {
        val old = File(dir, "old.jar").apply { writeText("old") }
        val installed = File(dir, "installed.jar").apply { writeText("new") }
        val deferred = File(dir, "browser-old.jar").apply { writeText("still running") }
        PluginSignatureSidecar.write(old.absolutePath, "b2xk")

        PluginUpdateBridge.discardReplacedPluginJar(old.absolutePath, installed.absolutePath)

        assertFalse(old.exists())
        assertFalse(File(PluginSignatureSidecar.pathFor(old.absolutePath)).exists())
        assertTrue(installed.exists())
        assertTrue(deferred.exists())
    }

    @Test
    fun `the installed artifact is never removed as its own predecessor`() {
        val installed = File(dir, "installed.jar").apply { writeText("new") }
        PluginUpdateBridge.discardReplacedPluginJar(installed.absolutePath, installed.absolutePath)
        assertTrue(installed.exists())
    }
}
