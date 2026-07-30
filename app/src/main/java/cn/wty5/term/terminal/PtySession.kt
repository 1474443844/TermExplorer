package cn.wty5.term.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Owns one interactive shell bound to a native PTY.
 *
 * Lifecycle:
 *  - constructed → [start] (also called from init)
 *  - [write] / [resize] while running
 *  - [destroy] (or process exit) → [cleanUp]
 *
 * Threading:
 *  - a dedicated reader thread drains the master FD
 *  - a monitor thread does a **blocking** waitpid (no 500 ms poll)
 *  - both converge on [cleanUp], which is idempotent
 */
class PtySession(
    private var workingDir: File,
    private val context: Context,
    private val onOutput: (String) -> Unit
) {

    private val masterFd = AtomicInteger(-1)
    private val childPid = AtomicInteger(-1)
    private val running = AtomicBoolean(false)

    /** Leftover UTF-8 bytes that did not form a complete code point at a read boundary. */
    @Volatile
    private var utf8Carry: ByteArray = EMPTY

    init {
        start()
    }

    @Synchronized
    fun start() {
        if (running.get()) return
        try {
            val env = buildEnv()
            val termEtc = File(TermConfig.termDir, "etc").also { it.mkdirs() }
            installTermEtc(termEtc)

            val bashFile = File(TermConfig.binDir, "bash")
            val shellPath = if (bashFile.exists()) bashFile.absolutePath else "/system/bin/sh"
            val bashRc = File(termEtc, "bash.bashrc")
            // --rcfile keeps system bashrc under $PREFIX; -i forces interactive.
            val args = arrayOf("--rcfile", bashRc.absolutePath, "-i")

            val result = Pty.create(
                shellPath,
                workingDir.absolutePath,
                args,
                env
            ) ?: throw IllegalStateException("Pty.create returned null")

            if (result.size < 2) {
                throw IllegalStateException("Pty.create returned invalid result")
            }

            masterFd.set(result[0])
            childPid.set(result[1])
            utf8Carry = EMPTY
            running.set(true)

            // Sane default until TerminalView reports the real geometry.
            Pty.resize(result[0], 24, 80)

            startReaderThread()
            startMonitorThread(result[1])
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start interactive shell", e)
            onOutput(
                "\u001B[1;31mError starting native interactive shell: " +
                    "${e.localizedMessage}\u001B[0m\n"
            )
            cleanUp(notify = false)
        }
    }

    /**
     * Write UTF-8 text to the PTY. Does **not** auto-restart a dead session —
     * callers should use [MainViewModel.restartSession] explicitly.
     */
    fun write(text: String) {
        if (text.isEmpty()) return
        val fd = masterFd.get()
        if (!running.get() || fd < 0) {
            Log.w(TAG, "write ignored: session not running")
            return
        }
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val written = Pty.write(fd, bytes, 0, bytes.size)
            if (written < 0) {
                Log.e(TAG, "Pty.write failed (fd=$fd)")
            } else if (written < bytes.size) {
                // Native drains short writes; residual means the master is closing.
                Log.w(TAG, "Pty.write partial: $written/${bytes.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write native PTY error", e)
        }
    }

    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        val fd = masterFd.get()
        if (fd >= 0) {
            Pty.resize(fd, rows, cols)
        }
    }

    fun destroy() {
        cleanUp(notify = true)
    }

    // ------------------------------------------------------------------ setup

    private fun buildEnv(): Array<String> {
        val env = HashMap<String, String>(System.getenv())

        val binDir = TermConfig.binDir.absolutePath
        val libDir = TermConfig.libDir.absolutePath
        val termDir = TermConfig.termDir.absolutePath
        val tmpDir = TermConfig.tmpDir.absolutePath
        val homeDir = TermConfig.homeDir.absolutePath
        val bashPath = File(TermConfig.binDir, "bash").absolutePath

        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["HOME"] = homeDir
        env["TMPDIR"] = tmpDir
        env["TEMP"] = tmpDir
        env["TMP"] = tmpDir
        env["LANG"] = "en_US.UTF-8"
        env["LC_ALL"] = "en_US.UTF-8"
        env["SHELL"] = bashPath
        env["PREFIX"] = termDir
        env["PROMPT_COMMAND"] = "history -a"
        env["PWD"] = workingDir.absolutePath

        // Prepend our bin/lib so coreutils + bundled bash win over system paths.
        env["PATH"] = prependPath(binDir, env["PATH"])
        env["LD_LIBRARY_PATH"] = prependPath(libDir, env["LD_LIBRARY_PATH"])

        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    private fun prependPath(prefix: String, existing: String?): String {
        return if (existing.isNullOrEmpty()) prefix else "$prefix:$existing"
    }

    /**
     * Copy bundled system rc files from assets (`term/etc/`) into [termEtc].
     * Always overwrites so app updates can refresh PS1 / aliases.
     */
    private fun installTermEtc(termEtc: File) {
        val name = "bash.bashrc"
        val assetPath = "term/etc/$name"
        val outFile = File(termEtc, name)
        try {
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Installed $assetPath → ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install $assetPath", e)
        }
    }

    // ------------------------------------------------------------------ threads

    private fun startReaderThread() {
        thread(name = "PtyReader", isDaemon = true) {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (running.get()) {
                    val fd = masterFd.get()
                    if (fd < 0) break

                    val n = Pty.read(fd, buffer)
                    when {
                        n > 0 -> {
                            val text = decodeUtf8(buffer, n)
                            if (text.isNotEmpty()) {
                                onOutput(text)
                            }
                        }
                        n == 0 -> {
                            // EOF — peer closed the slave side.
                            Log.i(TAG, "PTY EOF")
                            break
                        }
                        else -> {
                            // EIO after slave hangup is expected; anything else is logged.
                            Log.i(TAG, "PTY read ended (n=$n)")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reader thread exception", e)
            } finally {
                cleanUp(notify = true)
            }
        }
    }

    private fun startMonitorThread(pid: Int) {
        thread(name = "PtyMonitor", isDaemon = true) {
            try {
                // Blocking waitpid — no busy poll.
                val exitCode = Pty.waitProcess(pid, /* hang = */ true)
                Log.i(TAG, "Child process exited with code: $exitCode")
            } catch (e: Exception) {
                Log.e(TAG, "Monitor thread exception", e)
            } finally {
                cleanUp(notify = true)
            }
        }
    }

    // ------------------------------------------------------------------ teardown

    /**
     * Idempotent shutdown:
     *  1. mark not running
     *  2. SIGHUP the process group (shells treat this as "terminal hung up")
     *  3. close master FD (unblocks reader)
     *  4. brief grace, then SIGKILL if still alive
     *  5. non-blocking waitpid to reap
     */
    @Synchronized
    private fun cleanUp(notify: Boolean) {
        if (!running.getAndSet(false) && masterFd.get() < 0 && childPid.get() < 0) {
            return
        }

        val pid = childPid.getAndSet(-1)
        val fd = masterFd.getAndSet(-1)
        utf8Carry = EMPTY

        if (pid > 0) {
            // Prefer hangup so bash can run EXIT traps / history -a.
            Pty.killProcess(pid, Pty.SIGHUP)
        }

        if (fd >= 0) {
            try {
                Pty.close(fd)
            } catch (_: Exception) {
                // ignore
            }
        }

        if (pid > 0) {
            // Give bash a brief window to flush history / EXIT traps after SIGHUP.
            var status = Pty.waitProcess(pid, /* hang = */ false)
            if (status == -1) {
                try {
                    Thread.sleep(GRACE_MS)
                } catch (_: InterruptedException) {
                    // fall through to SIGKILL
                }
                status = Pty.waitProcess(pid, /* hang = */ false)
            }
            if (status == -1) {
                // Still alive — escalate.
                Pty.killProcess(pid, Pty.SIGKILL)
                // Blocking reap so we never leave a zombie.
                status = Pty.waitProcess(pid, /* hang = */ true)
            }
            Log.i(TAG, "Reaped pid=$pid status=$status")
        }

        if (notify) {
            try {
                onOutput("\n\r\u001B[1;33m[Session Terminated]\u001B[0m\n\r")
            } catch (_: Exception) {
                // UI may already be gone.
            }
        }
    }

    // ------------------------------------------------------------------ UTF-8

    /**
     * Decode [len] bytes from [buffer], stitching any incomplete trailing
     * multi-byte sequence into the next read via [utf8Carry].
     *
     * Without this, a code point split across two `read()` calls becomes U+FFFD
     * and corrupts CJK / emoji / Nerd Font glyphs in the terminal.
     */
    private fun decodeUtf8(buffer: ByteArray, len: Int): String {
        val carry = utf8Carry
        val total = carry.size + len
        val merged = if (carry.isEmpty()) {
            buffer
        } else {
            ByteArray(total).also {
                System.arraycopy(carry, 0, it, 0, carry.size)
                System.arraycopy(buffer, 0, it, carry.size, len)
            }
        }
        val dataLen = if (carry.isEmpty()) len else total

        val cut = incompleteUtf8Suffix(merged, dataLen)
        val completeLen = dataLen - cut
        utf8Carry = if (cut == 0) {
            EMPTY
        } else {
            merged.copyOfRange(completeLen, dataLen)
        }

        if (completeLen <= 0) return ""
        return String(merged, 0, completeLen, Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "PtySession"
        private const val READ_BUFFER_SIZE = 16 * 1024
        /** Grace period after SIGHUP before SIGKILL (history flush / EXIT traps). */
        private const val GRACE_MS = 80L
        private val EMPTY = ByteArray(0)

        /**
         * How many trailing bytes form an incomplete UTF-8 sequence?
         * Returns 0 if the buffer ends on a code-point boundary.
         */
        internal fun incompleteUtf8Suffix(buf: ByteArray, len: Int): Int {
            if (len <= 0) return 0
            // Walk back over continuation bytes (10xxxxxx).
            var i = len - 1
            var cont = 0
            while (i >= 0 && cont < 3 && (buf[i].toInt() and 0xC0) == 0x80) {
                cont++
                i--
            }
            if (i < 0) {
                // Entire buffer is continuations — keep it all for the next read.
                return len
            }
            val lead = buf[i].toInt() and 0xFF
            val expected = when {
                lead and 0x80 == 0x00 -> 1 // ASCII
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 1 // invalid lead; let CharsetDecoder replace it
            }
            val have = cont + 1
            return if (have < expected) have else 0
        }
    }
}
