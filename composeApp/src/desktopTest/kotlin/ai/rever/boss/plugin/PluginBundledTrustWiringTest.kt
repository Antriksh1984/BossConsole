package ai.rever.boss.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Source checks for startup's private, persistence-dependent skip paths; byte policy is tested in plugin-loader. */
class PluginBundledTrustWiringTest {
    private fun copySource(): String {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
        return File(
            assertNotNull(root),
            "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginStoreSetup.kt",
        ).readText().substringAfter("private fun copyBundledPluginsToPluginDir(")
    }

    @Test
    fun `directory and persisted skip paths bind existing bytes before returning`() {
        val source = copySource()
        val directorySkip =
            source
                .substringAfter("for (existingJar in existingJarsInPluginDir) {")
                .substringBefore("break")
        assertTrue(directorySkip.contains("PluginBundledTrust.bindToBundle(existingJar.absolutePath, jarFile)"))
        val persistedSkip =
            source
                .substringAfter("if (existingJarsInPluginDir.isEmpty() && existingPlugin != null) {")
                .substringBefore("continue")
        assertTrue(persistedSkip.contains("PluginBundledTrust.bindToBundle(existingJar.absolutePath, jarFile)"))
    }

    @Test
    fun `fresh copies bind against source after copying`() {
        val afterCopy =
            copySource()
                .substringAfter("jarFile.copyTo(destFile, overwrite = true)")
                .substringBefore("PluginPersistence.addInstalledPlugin(")
        assertTrue(afterCopy.contains("PluginBundledTrust.bindToBundle(destFile.absolutePath, jarFile)"))
    }
}
