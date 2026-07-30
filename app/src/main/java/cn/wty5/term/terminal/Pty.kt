package cn.wty5.term.terminal

import android.util.Log

/**
 * Thin JNI façade over the native PTY helper (`libterminal-pty.so`).
 *
 * All methods are thread-safe at the OS level (each call is an independent
 * syscall). Callers still need to serialize lifecycle (create → use → close/kill).
 */
object Pty {
    init {
        try {
            System.loadLibrary("terminal-pty")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Pty", "Failed to load terminal-pty library", e)
        }
    }

    /**
     * Fork + exec under a new PTY.
     * @return `intArrayOf(masterFd, pid)` or null on failure
     */
    external fun create(
        cmd: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>?
    ): IntArray?

    /** Blocking read. Returns byte count, 0 on EOF, -1 on error. */
    external fun read(fd: Int, buffer: ByteArray): Int

    /**
     * Write [length] bytes starting at [offset].
     * Native side drains short writes; returns bytes written or -1.
     */
    external fun write(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    /** Close the master FD (unblocks a concurrent [read]). */
    external fun close(fd: Int)

    external fun resize(fd: Int, rows: Int, cols: Int)

    /**
     * @param hang if true, block until the process exits; if false, WNOHANG
     * @return exit code (0-255), -signal if signalled, -1 still running (non-hang), -2 error
     */
    external fun waitProcess(pid: Int, hang: Boolean): Int

    /**
     * Deliver [signal] to the child process group (falls back to the single PID).
     * @return 0 on success / already gone, -1 on failure
     */
    external fun killProcess(pid: Int, signal: Int): Int

    // Common signals used by [PtySession].
    const val SIGTERM = 15
    const val SIGKILL = 9
    const val SIGHUP = 1
}
