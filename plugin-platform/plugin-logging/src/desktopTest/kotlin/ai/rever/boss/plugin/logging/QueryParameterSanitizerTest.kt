package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class QueryParameterSanitizerTest {
    @Test
    fun `URL punctuation inside a secret cannot expose a colon suffix`() {
        listOf("abc):tail", "abc':tail", "abc]:tail", "abc\":tail", "abc>:tail").forEach { value ->
            val message = "Failed https://example.com/cb?token=$value retry"
            assertEquals("Failed https:[PATH] retry", LogSanitizer.sanitizeExceptionMessage(message), value)
            assertEquals("Failed https:[PATH] retry", LogSanitizer.sanitizeStackTrace(message), value)
        }
    }

    @Test
    fun `query and fragment separators allow every sensitive value to be redacted`() {
        listOf("?", "&", "#").forEach { prefix ->
            listOf("token", "ACCESS_TOKEN", "apiKey", "client-secret").forEach { name ->
                val message = "${prefix}$name=alpha:omega&next=a:b#password=gamma:delta"
                assertEquals(
                    "${prefix}$name=[REDACTED]&next=a:b#password=[REDACTED]",
                    LogSanitizer.sanitizeLogMessage(message),
                )
            }
        }
    }

    @Test
    fun `non secret values and diagnostic ports retain their meaning`() {
        listOf("null", "true", "false").forEach { value ->
            assertEquals("?token=$value", LogSanitizer.sanitizeExceptionMessage("?token=$value"))
        }
        assertEquals("?keyboard=a:b", LogSanitizer.sanitizeExceptionMessage("?keyboard=a:b"))
        assertEquals(
            "Connect to [HOST]:3128 failed",
            LogSanitizer.sanitizeExceptionMessage("Connect to proxy.corp.internal:3128 failed"),
        )
    }
}
