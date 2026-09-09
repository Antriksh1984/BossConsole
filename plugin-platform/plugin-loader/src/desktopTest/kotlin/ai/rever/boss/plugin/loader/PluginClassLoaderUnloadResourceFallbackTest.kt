package ai.rever.boss.plugin.loader

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression cover for the silent parent fallback in [PluginClassLoader.getResource]/
 * [PluginClassLoader.getResources] (BossConsole#58) - the resource-lookup sibling of
 * [PluginClassLoaderUnloadFallbackTest], which covers the same shape for class loading.
 *
 * A closed `URLClassLoader` answers `findResource` with `null` for every name, including ones its
 * own jar carries - so the un-fixed `getResource` silently fell through to the parent, handing a
 * plugin the HOST's copy of a resource it owns. `getResources` is sharper: post-close its own half
 * is empty, so appending the parent's entries turned a `META-INF/services/...` lookup into a full
 * `ServiceLoader` provider swap.
 *
 * The plugin jar and a synthetic "host" jar each carry a resource at the SAME path with DIFFERENT
 * content, so which one answered a lookup is directly observable - the same technique
 * [PluginClassLoaderUnloadFallbackTest] uses with two classes of the same shape.
 */
class PluginClassLoaderUnloadResourceFallbackTest {
    private val tempJars = mutableListOf<File>()
    private val realHostLoader: ClassLoader = PluginClassLoaderUnloadResourceFallbackTest::class.java.classLoader

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    private fun jarWithResource(
        path: String,
        content: String,
    ): File {
        val jar = File.createTempFile("plugin-cl-resource-test", ".jar")
        // Backstop for cleanup(): a test that throws before close() leaves the jar locked on
        // Windows, where delete() then silently fails.
        jar.deleteOnExit()
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry(path))
            out.write(content.toByteArray())
            out.closeEntry()
        }
        return jar
    }

    /** A synthetic "host" classloader carrying its own copy of the shared resource path. */
    private fun hostLoaderWith(
        path: String,
        content: String,
    ): URLClassLoader = URLClassLoader(arrayOf(jarWithResource(path, content).toURI().toURL()), realHostLoader)

    private fun pluginLoaderOver(
        host: ClassLoader,
        path: String,
        content: String?,
    ): PluginClassLoader {
        val urls = if (content == null) emptyArray() else arrayOf(jarWithResource(path, content).toURI().toURL())
        return PluginClassLoader(pluginId = PLUGIN_ID, urls = urls, parent = host)
    }

    private fun readAll(url: URL): String = url.openStream().use { it.readBytes().decodeToString() }

    // --- the legitimate case, which must keep working exactly as before ------

    @Test
    fun `an open loader still falls back to the parent for a resource it does not carry`() {
        val host = hostLoaderWith(RESOURCE_PATH, "host-copy")
        val loader = pluginLoaderOver(host, RESOURCE_PATH, content = null)

        val resolved = loader.getResource(RESOURCE_PATH)

        assertEquals("host-copy", resolved?.let(::readAll), "an ACTIVE loader must keep delegating a genuine miss")
        loader.close()
    }

    @Test
    fun `an open loader prefers its own copy of a resource the jar carries`() {
        val host = hostLoaderWith(RESOURCE_PATH, "host-copy")
        val loader = pluginLoaderOver(host, RESOURCE_PATH, "plugin-copy")

        val resolved = loader.getResource(RESOURCE_PATH)

        assertEquals("plugin-copy", resolved?.let(::readAll), "child-first must win over the parent's copy")
        loader.close()
    }

    // --- the bug: delegation after teardown ----------------------------------

    @Test
    fun `a closed loader does not hand a plugin's own resource name to the host`() {
        val host = hostLoaderWith(RESOURCE_PATH, "host-copy")
        val loader = pluginLoaderOver(host, RESOURCE_PATH, "plugin-copy")
        // Touch it once so the loader is genuinely live before it is closed.
        assertEquals("plugin-copy", loader.getResource(RESOURCE_PATH)?.let(::readAll))

        loader.close()

        // Falling back here would silently hand the plugin the HOST's copy of a resource it owns.
        assertNull(loader.getResource(RESOURCE_PATH), "a closed loader must not fall through to the parent")
    }

    @Test
    fun `getResources drops the parent entries entirely once the loader is closed`() {
        val host = hostLoaderWith(SERVICE_PATH, "host-provider")
        val loader = pluginLoaderOver(host, SERVICE_PATH, "plugin-provider")
        assertTrue(
            Collections.list(loader.getResources(SERVICE_PATH)).isNotEmpty(),
            "must resolve while open",
        )

        loader.close()

        val entries = Collections.list(loader.getResources(SERVICE_PATH))
        assertTrue(
            entries.isEmpty(),
            "an empty enumeration (recoverable) beats silently returning the host's provider " +
                "(a ServiceLoader swap): $entries",
        )
    }

    @Test
    fun `getResources still merges parent entries while the loader is open`() {
        val host = hostLoaderWith(SERVICE_PATH, "host-provider")
        val loader = pluginLoaderOver(host, SERVICE_PATH, "plugin-provider")

        val entries = Collections.list(loader.getResources(SERVICE_PATH))

        assertEquals(2, entries.size, "an open loader must still see both its own and the parent's entry")
        loader.close()
    }

    // --- teardown must not be wedged, and UNLOAD_IN_PROGRESS is not treated as closed --------

    @Test
    fun `an unloading (not yet closed) loader still answers from its own open jar`() {
        val host = hostLoaderWith(RESOURCE_PATH, "host-copy")
        val loader = pluginLoaderOver(host, RESOURCE_PATH, "plugin-copy")

        loader.markUnloading()

        // The jar is open until close() - what is refused is the parent FALLBACK, not the
        // plugin's own resource lookup.
        assertEquals("plugin-copy", loader.getResource(RESOURCE_PATH)?.let(::readAll))
        loader.close()
    }

    @Test
    fun `an unloading loader refuses a genuine miss rather than falling back`() {
        val host = hostLoaderWith(RESOURCE_PATH, "host-copy")
        val loader = pluginLoaderOver(host, RESOURCE_PATH, content = null)

        loader.markUnloading()

        assertNull(loader.getResource(RESOURCE_PATH), "UNLOAD_IN_PROGRESS refuses the fallback too, fail-fast")
        loader.close()
    }

    @Test
    fun `shared-package resource paths are unaffected - still parent-first, always`() {
        // "java/" is a defaultSharedPackages prefix, so a lookup under it must never go through
        // the child-first/refusal path at all, open or closed.
        val host = hostLoaderWith(RESOURCE_PATH, "host-copy")
        val loader = pluginLoaderOver(host, RESOURCE_PATH, "plugin-copy")

        val resolved = loader.getResource("java/lang/Object.class")

        assertTrue(resolved != null, "a real JDK resource must resolve via the shared parent-first path")
        loader.close()
    }

    private companion object {
        const val PLUGIN_ID = "com.example.unload.resource"
        const val RESOURCE_PATH = "boss-test/marker.txt"
        const val SERVICE_PATH = "META-INF/services/boss.test.ExampleProvider"
    }
}
