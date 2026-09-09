package ai.rever.boss.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader

/** A deferred update bypasses installPlugin, so validate its identity before recording it. */
internal fun readDeferredPluginManifest(pluginId: String, jarPath: String): PluginManifest {
    val manifest = PluginManifestReader.readFromJar(jarPath)
    require(manifest.pluginId == pluginId) { "Deferred update declares ${manifest.pluginId}, expected $pluginId" }
    return manifest
}
