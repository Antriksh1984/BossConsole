package ai.rever.boss.platform

import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutableDownloadDialogTest {
    @Test
    fun `only explicit acceptance permits the download`() {
        assertTrue(confirmExecutableDownloadOnEdt { JOptionPane.OK_OPTION })
        assertFalse(confirmExecutableDownloadOnEdt { JOptionPane.CANCEL_OPTION })
        assertFalse(confirmExecutableDownloadOnEdt { JOptionPane.CLOSED_OPTION })
    }

    @Test
    fun `a worker dispatches the prompt to the EDT and waits for the answer`() {
        assertFalse(SwingUtilities.isEventDispatchThread())
        assertTrue(
            confirmExecutableDownloadOnEdt {
                assertTrue(SwingUtilities.isEventDispatchThread())
                JOptionPane.OK_OPTION
            },
        )
    }

    @Test
    fun `an EDT caller can answer without invoking and waiting on itself`() {
        SwingUtilities.invokeAndWait {
            assertTrue(confirmExecutableDownloadOnEdt { JOptionPane.OK_OPTION })
            assertFalse(confirmExecutableDownloadOnEdt { error("Dialog unavailable") })
        }
    }

    @Test
    fun `dialog creation failure cancels the download`() {
        assertFalse(confirmExecutableDownloadOnEdt { throw java.awt.HeadlessException() })
    }
}
