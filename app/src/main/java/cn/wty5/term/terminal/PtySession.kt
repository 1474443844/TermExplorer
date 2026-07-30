package cn.wty5.term.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

class PtySession(
    private var workingDir: File,
    private val context: Context,
    private val onOutput: (String) -> Unit
) {
    private val filesDir: File = context.filesDir
    private val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    @Volatile
    private var masterFd: Int = -1

    @Volatile
    private var pid: Int = -1

    @Volatile
    private var isRunning = false

    init {
        start()
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        try {
            // Setup robust env variables
            val env = HashMap<String, String>(System.getenv())

            val targetSdkVersion: Int = context.applicationInfo.targetSdkVersion

            env["TERM"] = "xterm-256color"

            val termDir = TermConfig.termDir
            val binDir = TermConfig.binDir
            val libDir = TermConfig.libDir
            val tmpDir = TermConfig.tmpDir
            val homeDir = TermConfig.homeDir


            env["HOME"] = homeDir.absolutePath
            env["TEPDIR"] = tmpDir.absolutePath
            env["LANG"] = "en_US.UTF-8"

            val bashFile = File(binDir, "bash")


            env["SHELL"] = bashFile.absolutePath
            env["PREFIX"] = termDir.absolutePath
            env["PROMPT_COMMAND"] = "history -a"
            env["PWD"] = workingDir.absolutePath
            env["PATH"] = "${binDir.absolutePath}:${env["PATH"]}"
            env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${env["LD_LIBRARY_PATH"]}"


            val envList = mutableListOf<String>()

            env.forEach { (k, v) ->
                envList.add("$k=$v")
            }

            // Term prefix layout (Termux-like):
            //   $PREFIX/etc/bash.bashrc  - system bashrc (PS1 lives here)
            //   $PREFIX/etc/shrc         - non-bash interactive rc
            //   $PREFIX/var/bash_history - readline history file
            val termEtc = File(TermConfig.termDir, "etc")
            termEtc.mkdirs()

            // Install / refresh system rc files from assets -> $PREFIX/etc/
            installTermEtc(termEtc)

            val shellPath = if (bashFile.exists()) {
                bashFile.absolutePath
            }else{
                "/system/bin/sh"
            }

            val bashRc = File(termEtc, "bash.bashrc")
            val args = arrayOf("--rcfile", bashRc.absolutePath, "-i")

            val result = Pty.create(
                shellPath,
                workingDir.absolutePath,
                args,
                envList.toTypedArray()
            )

            if (result == null || result.size < 2) {
                throw Exception("Pty.create returned invalid result")
            }

            masterFd = result[0]
            pid = result[1]
            isRunning = true

            // Set terminal size (sane default: 24 rows, 80 cols)
            Pty.resize(masterFd, 24, 80)

            // Thread to read PTY output in real-time
            thread(name = "PtyReaderThread") {
                val buffer = ByteArray(4096)
                try {
                    while (isRunning && masterFd != -1) {
                        val readBytes = Pty.read(masterFd, buffer)
                        if (readBytes <= 0) {
                            // EOF or error
                            break
                        }
                        val text = String(buffer, 0, readBytes, Charsets.UTF_8)
                        onOutput(text)
                    }
                } catch (e: Exception) {
                    Log.e("PtySession", "Reader thread exception", e)
                } finally {
                    cleanUp()
                }
            }

            // Monitor process exit code
            thread(name = "PtyMonitorThread") {
                try {
                    while (isRunning) {
                        val exitCode = Pty.waitProcess(pid)
                        if (exitCode != -1) {
                            Log.i("PtySession", "Child process exited with code: $exitCode")
                            break
                        }
                        Thread.sleep(500)
                    }
                } catch (e: InterruptedException) {
                    // Ignored
                } finally {
                    cleanUp()
                }
            }

        } catch (e: Exception) {
            Log.e("PtySession", "Failed to start background shell", e)
            onOutput("\u001B[1;31mError starting native interactive shell: ${e.localizedMessage}\u001B[0m\n")
        }
    }

    fun write(text: String) {
        if (!isRunning) {
            start()
        }
        val fd = masterFd
        if (fd != -1) {
            try {
                val bytes = text.toByteArray(Charsets.UTF_8)
                Pty.write(fd, bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.e("PtySession", "Write native PTY error", e)
            }
        }
    }

    fun resize(rows: Int, cols: Int) {
        val fd = masterFd
        if (fd != -1) {
            Pty.resize(fd, rows, cols)
        }
    }


    fun destroy() {
        cleanUp()
    }

    /**
     * Copy bundled system rc files from assets (`term/etc/\*`) into [termEtc].
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
            Log.i("PtySession", "Installed $assetPath → ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("PtySession", "Failed to install $assetPath", e)
        }
    }

    @Synchronized
    private fun cleanUp() {
        if (!isRunning) return
        isRunning = false
        val fd = masterFd
        if (fd != -1) {
            masterFd = -1
            try {
                Pty.close(fd)
            } catch (e: Exception) {
                // Ignore
            }
        }
        onOutput("\n\r\u001B[1;33m[Session Terminated]\u001B[0m\n\r")
    }
}
