package cn.wty5.term.terminal

import android.util.Log

object Pty {
    init {
        try {
            System.loadLibrary("terminal-pty")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Pty", "Failed to load terminal-pty library", e)
        }
    }

    external fun create(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envp: Array<String>?
    ): IntArray?

    external fun read(fd: Int, buffer: ByteArray): Int

    external fun write(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    external fun close(fd: Int)

    external fun resize(fd: Int, rows: Int, cols: Int)

    external fun waitProcess(pid: Int): Int
}
