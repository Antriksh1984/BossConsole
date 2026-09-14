package ai.rever.boss.git

import ai.rever.boss.plugin.git.GitOperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BossConsole#641: `cloneRepository`'s progress-reading loop ran inline in the cancellable
 * coroutine, blocking on `readLine()` over the clone process's own pipe. That call is not
 * interruptible on any platform, so once a connection stalled with the pipe open but silent,
 * neither the 10-minute `withTimeout` nor an external cancellation of the caller could reach the
 * process to kill it - both were real, but nothing could act on either until the read itself
 * returned, which for a genuinely stalled connection never happened.
 *
 * [GitService.cloneProcessBounded] is the fix, exercised here with a short injected timeout
 * instead of the real 10-minute one - the same reason [GitProcessBoundedTest] parameterizes
 * [GitService.runProcessBounded]'s bound rather than waiting out a production timeout.
 *
 * A first pass of this fix killed only the top-level `git` process. For an HTTP(S) clone that is
 * not the process actually holding the connection open - git forks `git-remote-http(s)` as a
 * separate helper that inherits the pipe - so [SilentServer.closed] is the load-bearing assertion
 * in every test below: it can only resolve `true` once *every* process on the other end of the
 * socket, not just the one this test named, has actually exited.
 */
class GitCloneBoundedTest {
    @Test
    fun `a silent-but-alive server does not defeat the deadline, and the whole process tree dies`(
        @TempDir tmp: File,
    ) = runBlocking {
        SilentServer().use { silent ->
            val target = File(tmp, "clone-target").absolutePath
            val startedAt = System.currentTimeMillis()

            val failure =
                assertFailsWith<TimeoutCancellationException> {
                    withContext(Dispatchers.IO) {
                        GitService.cloneProcessBounded(
                            silent.url("silent.git"),
                            target,
                            onProgress = {},
                            timeoutMs = 2_000L,
                        )
                    }
                }

            val elapsedMs = System.currentTimeMillis() - startedAt
            assertTrue(failure.message?.isNotEmpty() == true)
            // 2s bound + drain grace, with slack for a loaded CI worker. Well under the 10-minute
            // production bound this deadline stands in for.
            assertTrue(elapsedMs < 20_000, "the timeout did not actually end the wait: ${elapsedMs}ms")
            silent.assertConnectionClosed()
        }
    }

    @Test
    fun `caller cancellation during the blocked read ends the process promptly, not after the deadline`(
        @TempDir tmp: File,
    ) = runBlocking {
        SilentServer().use { silent ->
            val target = File(tmp, "clone-target").absolutePath
            val startedAt = System.currentTimeMillis()

            val deferred =
                async(Dispatchers.IO) {
                    GitService.cloneProcessBounded(
                        silent.url("silent.git"),
                        target,
                        onProgress = {},
                        // Deliberately much longer than the cancellation below, so a pass here
                        // proves cancellation - not this deadline - ended the call.
                        timeoutMs = 30_000L,
                    )
                }

            // Handshake before cancelling: without this the test could cancel before git has
            // even connected, which would still pass but would not exercise "cancel a call that
            // is genuinely blocked on a silent pipe" - the scenario this test is named for.
            silent.awaitConnection()
            deferred.cancel()
            assertFailsWith<CancellationException> { deferred.await() }

            val elapsedMs = System.currentTimeMillis() - startedAt
            assertTrue(elapsedMs < 20_000, "cancellation did not end the wait before the 30s deadline: ${elapsedMs}ms")
            silent.assertConnectionClosed()
        }
    }

    @Test
    fun `external cancellation of the public entry point cleans up the partial target directory`(
        @TempDir tmp: File,
    ) = runBlocking {
        SilentServer().use { silent ->
            val target = File(tmp, "clone-target")

            val deferred =
                async(Dispatchers.IO) {
                    GitService.cloneRepository(silent.url("silent.git"), target.absolutePath) {}
                }

            silent.awaitConnection()
            deferred.cancel()
            assertFailsWith<CancellationException> { deferred.await() }

            assertTrue(
                !target.exists(),
                "a cancelled clone must not leave a partial directory behind - it would block every retry",
            )
            silent.assertConnectionClosed()
        }
    }

    @Test
    fun `a pre-existing target directory is refused and left untouched by cancellation cleanup`(
        @TempDir tmp: File,
    ) = runBlocking {
        // Pins the invariant the cancellation-cleanup branches rely on rather than re-check
        // themselves: cleanup only ever deletes a directory THIS clone created, because nothing
        // reaches cloneProcessBounded - and so nothing can be cancelled - once this guard has
        // already refused a pre-existing target.
        val target = File(tmp, "clone-target").apply { mkdirs() }
        val marker = File(target, "user-file.txt").apply { writeText("do not delete me") }

        val result = GitService.cloneRepository("https://example.invalid/repo.git", target.absolutePath) {}

        assertTrue(result is GitOperationResult.Error, "expected a refusal, got $result")
        assertTrue(marker.exists(), "a pre-existing target's contents must survive the refusal")
    }

    @Test
    fun `a successful clone reports success, streams progress, and completion is the final callback`(
        @TempDir tmp: File,
    ) = runBlocking {
        val source = initBareRepo(File(tmp, "source.git"))
        val target = File(tmp, "clone-target").absolutePath
        // onProgress is invoked from both this coroutine's thread and the reader's own daemon
        // thread - see cloneProcessBounded's join-before-final-callback ordering, which is what
        // this test's last assertion pins.
        val progressLines = Collections.synchronizedList(mutableListOf<String>())

        val result =
            withContext(Dispatchers.IO) {
                GitService.cloneProcessBounded(
                    source.absolutePath,
                    target,
                    onProgress = { progressLines.add(it) },
                    timeoutMs = 30_000L,
                )
            }

        assertTrue(result is GitOperationResult.Success, "expected success, got $result")
        assertTrue(File(target, ".git").exists(), "clone target must contain a checkout")
        assertTrue(progressLines.contains("Initializing clone..."))
        assertEquals(
            "Clone completed successfully",
            progressLines.last(),
            "the completion message must be the last callback - a trailing reader line must not arrive after it",
        )
    }

    @Test
    fun `a nonexistent source reports a nonzero-exit failure, not a hang`(
        @TempDir tmp: File,
    ) = runBlocking {
        val target = File(tmp, "clone-target").absolutePath

        val result =
            withContext(Dispatchers.IO) {
                GitService.cloneProcessBounded(
                    File(tmp, "does-not-exist").absolutePath,
                    target,
                    onProgress = {},
                    timeoutMs = 30_000L,
                )
            }

        assertTrue(result is GitOperationResult.Error, "expected an error result, got $result")
    }

    /** `git init --bare` in [dir], so a local clone has a real repository to talk to. */
    private fun initBareRepo(dir: File): File {
        dir.mkdirs()
        val process = ProcessBuilder("git", "init", "--bare", dir.absolutePath).start()
        assertEquals(0, process.waitFor(), "failed to set up the source repo for this test")
        return dir
    }

    /**
     * A loopback listener that accepts exactly one connection, reads whatever request arrives,
     * then answers nothing - the "open but silent output pipe" #641 describes. The clone process
     * (and, for HTTP(S), the `git-remote-http(s)` helper actually holding the socket) stays
     * alive; only the response goes quiet.
     *
     * [closed] is the fixture's real purpose: rather than a fixed sleep, the acceptor thread
     * blocks on a second read after the first, which only returns once the peer closes the
     * connection. That resolves the instant the whole process tree on the other end actually
     * exits - normally (a surviving helper keeps the socket's file descriptor open) - and removes
     * the need for an arbitrary sleep in the fixture itself.
     */
    private class SilentServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val connection = CompletableFuture<Socket>()
        private val closed = CompletableFuture<Boolean>()
        private val acceptor =
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        connection.complete(socket)
                        val peerGone =
                            try {
                                // TCP does not preserve request boundaries: a single read only
                                // returns whatever has arrived so far, not the whole request, so
                                // reading exactly once and then checking for EOF can see a second
                                // fragment of the SAME request rather than the peer closing - a
                                // false failure of assertConnectionClosed on a segmented request
                                // (a credential helper adding headers, an MTU-constrained runner).
                                // Drain until the stream actually reports EOF.
                                val buf = ByteArray(4096)
                                while (socket.getInputStream().read(buf) != -1) {
                                    // discard the request; only its end matters here
                                }
                                true
                            } catch (_: IOException) {
                                // A forcibly killed process tree can tear the connection down
                                // with a TCP RST rather than a clean FIN close - the OS abandons
                                // it instead of closing it gracefully, and a read on the reset
                                // side throws rather than returning EOF. Either shape means the
                                // same thing here: nobody is on the other end of this socket any
                                // more, which is exactly what this fixture exists to observe.
                                true
                            }
                        closed.complete(peerGone)
                    }
                }.onFailure {
                    connection.completeExceptionally(it)
                    closed.completeExceptionally(it)
                }
            }

        fun url(path: String): String = "http://127.0.0.1:${server.localPort}/$path"

        /** Blocks until git has actually connected, so a caller cannot cancel before it has. */
        fun awaitConnection() {
            connection.get(10, TimeUnit.SECONDS)
        }

        /**
         * The one assertion every test in this file exists for: the connection this server
         * accepted must close, meaning every process that could still be holding it open -
         * `git` itself and any helper it forked - has actually exited, not just the top-level
         * process this fixture never even sees the PID of.
         *
         * A generous bound, deliberately looser than [destroyProcessTree]'s own internal ones:
         * this is observing OS-level socket teardown propagating back to a *different* process
         * (this JVM) after a SIGKILL, which a loaded CI worker can noticeably delay without the
         * underlying fix being wrong - the thing this test would actually catch is the wait never
         * ending, not it taking a few extra seconds.
         */
        fun assertConnectionClosed() {
            assertTrue(
                closed.get(30, TimeUnit.SECONDS),
                "the accepted connection must close - a surviving helper process would keep it open",
            )
        }

        override fun close() {
            runCatching { server.close() }
            acceptor.join(5_000)
        }
    }
}
