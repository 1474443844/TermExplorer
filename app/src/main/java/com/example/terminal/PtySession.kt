package com.example.terminal

import android.util.Log
import java.io.File
import kotlin.concurrent.thread

class PtySession(
    private var workingDir: File,
    private val filesDir: File,
    private val onOutput: (String) -> Unit
) {
    private var masterFd: Int = -1
    private var pid: Int = -1
    private var isRunning = false

    init {
        start()
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        try {
            // Setup robust env variables
            val envList = ArrayList<String>()
            envList.add("TERM=xterm-256color")
            
            // Check for custom Coreutils binary in PATH
            val binDir = File(filesDir, "bin")
            if (binDir.exists() && File(binDir, "coreutils").exists()) {
                envList.add("PATH=${binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin:/sbin")
            } else {
                envList.add("PATH=/system/bin:/system/xbin:/vendor/bin:/sbin")
            }
            
            envList.add("HOME=${workingDir.absolutePath}")
            envList.add("PWD=${workingDir.absolutePath}")

            val parentDir = workingDir.parentFile ?: workingDir
            val initFile = File(parentDir, ".shinit")
            try {
                val script = "cwd_prompt() {\n" +
                        "    cwd=\$(pwd)\n" +
                        "    if [ \"\$cwd\" = \"\$HOME\" ]; then\n" +
                        "        name=\"~\"\n" +
                        "    else\n" +
                        "        name=\"\${cwd##*/}\"\n" +
                        "        if [ -z \"\$name\" ]; then\n" +
                        "            name=\"/\"\n" +
                        "        fi\n" +
                        "    fi\n" +
                        "    if [ -n \"\$BASH_VERSION\" ]; then\n" +
                        "        printf \"\\001\\033[32m\\002%s\\001\\033[0m\\002\" \"\$name\"\n" +
                        "    else\n" +
                        "        printf \"\\001\\033[32m\\001%s\\001\\033[0m\\001\" \"\$name\"\n" +
                        "    fi\n" +
                        "}\n" +
                        "PS1='\$(cwd_prompt) \$ '\n" +
                        "alias ls='ls --color=auto'\n" +
                        "alias ll='ls -l --color=auto'\n" +
                        "alias grep='grep --color=auto'\n" +
                        "alias egrep='egrep --color=auto'\n" +
                        "alias fgrep='fgrep --color=auto'\n"
                initFile.writeText(script)
            } catch (e: Exception) {
                Log.e("PtySession", "Failed to write .shinit", e)
            }
            envList.add("ENV=${initFile.absolutePath}")

            val shellCandidates = listOf(
                "/system/bin/bash",
                "/system/xbin/bash",
                "/vendor/bin/bash",
                "/data/data/com.termux/files/usr/bin/bash",
                "/system/bin/sh"
            )
            val shellPath = shellCandidates.firstOrNull { File(it).exists() } ?: "/system/bin/sh"

            val args = if (shellPath.endsWith("bash")) {
                arrayOf("--rcfile", initFile.absolutePath, "-i")
            } else {
                arrayOf("-i")
            }

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

    fun updateWorkingDirectory(dir: File) {
        this.workingDir = dir
        write("cd \"${dir.absolutePath}\"\n")
    }

    fun destroy() {
        cleanUp()
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
