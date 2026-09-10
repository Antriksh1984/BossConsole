package ai.rever.boss.platform

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutableDownloadDialogTest {
    @Test
    fun `only explicit acceptance permits the download`() {
        assertTrue(confirmExecutableDownloadOnEdt { 0 })
        assertFalse(confirmExecutableDownloadOnEdt { 1 })
        assertFalse(confirmExecutableDownloadOnEdt { -1 })
        assertFalse(confirmExecutableDownloadOnEdt { 2 })
    }

    @Test
    fun `a worker dispatches the prompt to the EDT and waits for the answer`() {
        assertFalse(SwingUtilities.isEventDispatchThread())
        assertTrue(
            confirmExecutableDownloadOnEdt {
                assertTrue(SwingUtilities.isEventDispatchThread())
                0
            },
        )
    }

    @Test
    fun `an EDT caller can answer without invoking and waiting on itself`() {
        SwingUtilities.invokeAndWait {
            assertTrue(confirmExecutableDownloadOnEdt { 0 })
            assertFalse(confirmExecutableDownloadOnEdt { error("Dialog unavailable") })
        }
    }

    @Test
    fun `dialog creation failure cancels the download`() {
        assertFalse(confirmExecutableDownloadOnEdt { throw java.awt.HeadlessException() })
    }
}
