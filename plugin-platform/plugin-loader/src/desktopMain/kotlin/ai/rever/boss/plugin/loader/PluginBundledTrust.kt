package ai.rever.boss.plugin.loader

import java.io.File

/**
 * Exempts one specific JAR from store-signature enforcement because the HOST itself placed it
 * there, copied verbatim from the app's own bundled-plugins directory (BossConsole#102) - which
 * ships inside the signed, notarized app image and carries no store `.sig`. The store-signature
 * scheme defends against an attacker with store/DB write access substituting a malicious
 * downloadable JAR; a bundled JAR's integrity already comes from a different, stronger boundary
 * (the OS verifying the app's own code signature before any of it runs), so requiring a second
 * signature scheme for a file the app shipped inside itself would only reproduce that guarantee
 * with paperwork - and would hard-fail loading every bundled plugin the moment enforcement is on,
 * since none of them carry a sidecar today.
 *
 * A `<jar>.bundled-trust` marker beside the JAR, content-addressed by sha256 so replacing the
 * bytes at that path invalidates it: a marker surviving a later, unrelated JAR at the same
 * filename (a stale reconciler leftover, a manual side-load, a store update reusing the name)
 * must NOT inherit trust it was never given.
 *
 * Written ONLY by `PluginStoreSetup`'s bundled-copy step, immediately after copying FROM the
 * trusted bundled directory - nothing else in this codebase writes it, and nothing a plugin's own
 * bytes can do forges it, since the marker binds the exact digest the host just computed off its
 * own trusted copy.
 */
object PluginBundledTrust {
    private const val SUFFIX = ".bundled-trust"

    fun pathFor(jarPath: String): String = "$jarPath$SUFFIX"

    /** Mark [jarPath]'s current bytes as trusted. Best-effort. */
    fun markTrusted(
        jarPath: String,
        sha256: String,
    ) {
        runCatching { File(pathFor(jarPath)).writeText(sha256) }
    }

    /**
     * Whether [jarPath]'s CURRENT bytes match a marker this object wrote for them.
     *
     * Re-hashes the file rather than trusting the marker's mere presence, so a JAR swapped in
     * after the marker was written (same filename, different content) reads as untrusted.
     */
    fun isTrusted(jarPath: String): Boolean {
        val marker = File(pathFor(jarPath))
        if (!marker.exists()) return false
        val recorded = runCatching { marker.readText().trim() }.getOrNull()
        val actual = runCatching { FileHashing.sha256(File(jarPath)) }.getOrNull()
        return !recorded.isNullOrEmpty() && recorded == actual
    }

    /** Remove the marker (e.g. alongside a deleted/replaced JAR). Best-effort. */
    fun delete(jarPath: String) {
        File(pathFor(jarPath)).delete()
    }
}
