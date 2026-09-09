package ai.rever.boss.plugin.loader

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginBundledTrustTest {
    private val tempDir = createTempDirectory("bundled-trust-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a freshly-marked jar is trusted`() {
        val jar = File(tempDir, "bundled.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        assertTrue(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a jar with no marker is not trusted`() {
        val jar = File(tempDir, "unmarked.jar").apply { writeText("jar-bytes") }
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `content changing after the marker was written loses trust`() {
        val jar = File(tempDir, "changed.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        jar.appendBytes("more-bytes".toByteArray())
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a marker for one jar does not trust a different jar at the same path`() {
        // Same scenario as content changing, framed the way it actually happens: a stale jar is
        // deleted and a different plugin's jar lands at the same filename.
        val path = File(tempDir, "reused.jar").absolutePath
        File(path).writeText("original-plugin")
        PluginBundledTrust.markTrusted(path, FileHashing.sha256(File(path)))
        File(path).writeText("a-completely-different-plugin")
        assertFalse(PluginBundledTrust.isTrusted(path))
    }

    @Test
    fun `delete removes the marker`() {
        val jar = File(tempDir, "toDelete.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        PluginBundledTrust.delete(jar.absolutePath)
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
        assertFalse(File(PluginBundledTrust.pathFor(jar.absolutePath)).exists())
    }

    @Test
    fun `an empty marker file is not trusted`() {
        val jar = File(tempDir, "emptyMarker.jar").apply { writeText("jar-bytes") }
        File(PluginBundledTrust.pathFor(jar.absolutePath)).writeText("   ")
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }
}
