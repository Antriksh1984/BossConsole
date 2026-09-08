package ai.rever.boss.plugin.repository.remote

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BossConsole#337: `PluginDetailResponse.toPluginInfo()`'s `publishedAt` used to be a stub that
 * always returned `0L` regardless of what `updatedAt` held, so every plugin fetched from the
 * store showed "Last Updated" as the Unix epoch in the Toolbox UI.
 *
 * Exercised through the real client's `toPluginInfo()`, not a lookalike parser, matching this
 * file's own convention next to it (`PluginStoreResponseDecodingTest`) - the property under test
 * is that the real response type produces a real timestamp.
 */
class PluginStoreTimestampParsingTest {
    private fun responseWithUpdatedAt(updatedAt: String) =
        PluginDetailResponse(
            id = "0f6a1c62-0000-4000-8000-000000000001",
            pluginId = "ai.rever.boss.plugin.dynamic.example",
            displayName = "Example",
            description = "",
            authorName = "RISA Labs",
            type = "tab",
            apiVersion = "1.0",
            verified = true,
            updatedAt = updatedAt,
        )

    @Test
    fun `an ISO-8601 timestamp with a Z offset parses to real epoch millis`() {
        val info = responseWithUpdatedAt("2024-05-12T14:30:00Z").toPluginInfo()

        assertEquals(1715524200000L, info.publishedAt)
    }

    @Test
    fun `an ISO-8601 timestamp with a numeric offset parses too`() {
        val info = responseWithUpdatedAt("2024-05-12T14:30:00+00:00").toPluginInfo()

        assertEquals(1715524200000L, info.publishedAt)
    }

    @Test
    fun `a blank updatedAt falls back to 0 rather than throwing`() {
        val info = responseWithUpdatedAt("").toPluginInfo()

        assertEquals(0L, info.publishedAt)
    }

    @Test
    fun `a malformed timestamp falls back to 0 rather than throwing`() {
        val info = responseWithUpdatedAt("not-a-timestamp").toPluginInfo()

        assertEquals(0L, info.publishedAt)
    }
}
