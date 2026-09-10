package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [WebsiteMatchingUtil.calculateMatchScore] and [WebsiteMatchingUtil.getDisplayName]
 * against the over-broad matching BossConsole#460 reports: an unanchored `contains` check
 * that let a secret for one site surface on an unrelated one sharing a substring, and a
 * token-overlap fallback that let any shared TLD label ("com", "org", ...) count as a match.
 */
class WebsiteMatchingUtilTest {
    // ---- Finding A: unanchored substring must not match ----

    @Test
    fun `a secret for apple does not match a site that merely contains the word as a substring`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("apple.com", "snapple.com")
        assertEquals(0.0f, score.score)
        assertEquals("no_match", score.reason)
    }

    // ---- Finding B: a bare TLD/public-suffix label must not count as a shared token ----

    @Test
    fun `a dot-com secret does not match an unrelated dot-com site`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "example.com")
        assertEquals(0.0f, score.score)
        assertEquals("no_match", score.reason)
    }

    @Test
    fun `a dot-org secret does not match an unrelated dot-org site`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("wikipedia.org", "example.org")
        assertEquals(0.0f, score.score)
    }

    // ---- Legitimate matches must survive the fix ----

    @Test
    fun `exact match still scores 1_0`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "google.com")
        assertEquals(1.0f, score.score)
        assertEquals("exact", score.reason)
    }

    @Test
    fun `a subdomain still matches its parent domain`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "login.google.com")
        assertEquals(0.9f, score.score)
        assertEquals("subdomain", score.reason)
    }

    @Test
    fun `a shared non-TLD label still partially matches`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "google-workspace.com")
        assertEquals(0.5f, score.score)
        assertEquals("partial", score.reason)
    }

    @Test
    fun `two entirely unrelated domains score zero`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("github.com", "example.net")
        assertEquals(0.0f, score.score)
        assertTrue(score.reason == "no_match")
    }

    // ---- Finding C: getDisplayName must not duplicate a digit-initial word ----

    @Test
    fun `a digit-initial domain name is not duplicated`() {
        assertEquals("1password", WebsiteMatchingUtil.getDisplayName("1password.com"))
        assertEquals("9gag", WebsiteMatchingUtil.getDisplayName("9gag.com"))
    }

    @Test
    fun `known brand display names are unaffected`() {
        assertEquals("GitHub", WebsiteMatchingUtil.getDisplayName("github.com"))
        assertEquals("Google", WebsiteMatchingUtil.getDisplayName("google.com"))
    }

    @Test
    fun `hyphenated generic domains are still title-cased per word`() {
        assertEquals("Example Site", WebsiteMatchingUtil.getDisplayName("example-site.com"))
    }
}
