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
            if (!binDir.exists()) {
                binDir.mkdirs()
            }

            val bashFile = File(binDir, "bash")
            var bashExtracted = false

            // 1. Try to extract bash from assets first
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val targetArchFolder = when {
                primaryAbi.contains("64") && (primaryAbi.contains("arm") || primaryAbi.contains("aarch")) -> "arm64-v8a"
                primaryAbi.contains("64") && primaryAbi.contains("x86") -> "x86_64"
                primaryAbi.contains("x86") -> "x86"
                else -> "arm64-v8a"
            }

            val possibleAssetPaths = listOf(
                "$targetArchFolder/bash",
                "bash"
            )

            if (!bashFile.exists() || bashFile.length() == 0L) {
                for (path in possibleAssetPaths) {
                    try {
                        context.assets.open(path).use { input ->
                            if (bashFile.exists()) {
                                bashFile.delete()
                            }
                            bashFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                            bashFile.setExecutable(true, false)
                            Log.i(
                                "PtySession",
                                "Successfully extracted bash from assets path: $path"
                            )
                            bashExtracted = true
                        }
                        if (bashExtracted) break
                    } catch (e: Exception) {
                        Log.e("PtySession", "Failed to extract bash from assets path: $path", e)
                    }
                }
            } else {
                bashExtracted = true
                bashFile.setExecutable(true, false)
            }

            // 2. Fallback to extracting from nativeLibDir if assets extraction wasn't completed
            if (!bashExtracted) {
                val builtInBash = listOf(
                    File(nativeLibDir, "bash"),
                    File(nativeLibDir, "libbash.so")
                ).firstOrNull { it.exists() }

                if (builtInBash != null && builtInBash.exists()) {
                    if (!bashFile.exists() || bashFile.length() != builtInBash.length()) {
                        try {
                            builtInBash.inputStream().use { input ->
                                bashFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            bashFile.setExecutable(true, false)
                            Log.i(
                                "PtySession",
                                "Successfully copied built-in bash to filesDir/bin/bash and made executable"
                            )
                        } catch (e: Exception) {
                            Log.e(
                                "PtySession",
                                "Failed to copy built-in bash to filesDir/bin/bash",
                                e
                            )
                        }
                    }
                }
            }

            if (binDir.exists() && File(binDir, "coreutils").exists()) {
                envList.add("PATH=${binDir.absolutePath}:/system/bin:/system/xbin:/vendor/bin:/sbin")
            } else {
                envList.add("PATH=/system/bin:/system/xbin:/vendor/bin:/sbin")
            }

            envList.add("HOME=${workingDir.absolutePath}")
            envList.add("PWD=${workingDir.absolutePath}")
            envList.add("TERM_BIN=${binDir.absolutePath}")
            // Ignore leftover/workspace .inputrc; readline defaults are fine.
            envList.add("INPUTRC=/dev/null")

            // Remove legacy workspace configs from older app versions.
            deleteLegacyShellConfigFiles()

            val shellCandidates = listOf(
                File(binDir, "bash").absolutePath,
                File(nativeLibDir, "bash").absolutePath,
                File(nativeLibDir, "libbash.so").absolutePath,
                "/system/bin/bash",
                "/system/xbin/bash",
                "/vendor/bin/bash",
                "/data/data/com.termux/files/usr/bin/bash",
                "/system/bin/sh"
            )
            val shellPath = shellCandidates.firstOrNull { File(it).exists() } ?: "/system/bin/sh"
            val isBash = shellPath.endsWith("bash") || shellPath.endsWith("libbash.so")

            // Shell init lives in app-private storage (not workspace HOME), so
            // the file manager / sandbox never sees .shinit or .inputrc.
            val shellRc = ensurePrivateShellRc(isBash, binDir.absolutePath)
            val args = if (isBash) {
                arrayOf("--rcfile", shellRc.absolutePath, "-i")
            } else {
                // POSIX sh reads $ENV for interactive shells.
                envList.add("ENV=${shellRc.absolutePath}")
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

    /**
     * Write (or refresh) the shell rc under app-private storage.
     * Never writes `.shinit` / `.inputrc` into the user workspace (HOME).
     */
    private fun ensurePrivateShellRc(isBash: Boolean, termBin: String): File {
        val shellDir = File(filesDir, "shell")
        if (!shellDir.exists()) {
            shellDir.mkdirs()
        }
        val rcFile = File(shellDir, if (isBash) "bashrc" else "shrc")
        // Escape for single-quoted shell string: abc'def -> abc'\''def
        val termBinEscaped = termBin.replace("'", "'\\''")
        val content = if (isBash) {
            """
            export TERM_BIN='$termBinEscaped'
            export PS1='\[\e[32m\]\W\[\e[0m\] \$ '

            alias ls='ls --color=auto' 2>/dev/null
            alias ll='ls -l --color=auto' 2>/dev/null
            alias grep='grep --color=auto' 2>/dev/null
            alias egrep='egrep --color=auto' 2>/dev/null
            alias fgrep='fgrep --color=auto' 2>/dev/null

            fix_shebang() {
              if [ -z "${'$'}1" ]; then
                echo "Usage: fix_shebang <script_file>"
                return 1
              fi
              if [ ! -f "${'$'}1" ]; then
                echo "Error: File '${'$'}1' does not exist."
                return 1
              fi
              sed -i "s|^#!/usr/bin/env bash|#!${'$'}TERM_BIN/bash|" "${'$'}1"
              sed -i "s|^#!/usr/bin/env sh|#!/system/bin/sh|" "${'$'}1"
              sed -i "s|^#!/bin/bash|#!${'$'}TERM_BIN/bash|" "${'$'}1"
              sed -i "s|^#!/bin/sh|#!/system/bin/sh|" "${'$'}1"
              echo "Shebang updated for ${'$'}1"
            }

            # Optional user overrides from workspace HOME
            [ -f "${'$'}HOME/.bashrc" ] && . "${'$'}HOME/.bashrc"

            # Keep our prompt last so user rc cannot break it
            PS1='\[\e[32m\]\W\[\e[0m\] \$ '
            """.trimIndent() + "\n"
        } else {
            """
            export TERM_BIN='$termBinEscaped'
            set -o emacs 2>/dev/null

            cwd_prompt() {
              cwd=${'$'}(pwd)
              if [ "${'$'}cwd" = "${'$'}HOME" ]; then
                name="~"
              else
                name="${'$'}{cwd##*/}"
                [ -z "${'$'}name" ] && name="/"
              fi
              printf '%s' "${'$'}name"
            }

            alias ls='ls --color=auto' 2>/dev/null
            alias ll='ls -l --color=auto' 2>/dev/null
            alias grep='grep --color=auto' 2>/dev/null

            fix_shebang() {
              if [ -z "${'$'}1" ]; then
                echo "Usage: fix_shebang <script_file>"
                return 1
              fi
              if [ ! -f "${'$'}1" ]; then
                echo "Error: File '${'$'}1' does not exist."
                return 1
              fi
              sed -i "s|^#!/usr/bin/env bash|#!${'$'}TERM_BIN/bash|" "${'$'}1"
              sed -i "s|^#!/usr/bin/env sh|#!/system/bin/sh|" "${'$'}1"
              sed -i "s|^#!/bin/bash|#!${'$'}TERM_BIN/bash|" "${'$'}1"
              sed -i "s|^#!/bin/sh|#!/system/bin/sh|" "${'$'}1"
              echo "Shebang updated for ${'$'}1"
            }

            ESC=${'$'}(printf '\033')
            PS1="${'$'}{ESC}[32m\$(cwd_prompt)${'$'}{ESC}[0m \${'$'} "
            """.trimIndent() + "\n"
        }

        try {
            // Always rewrite so termBin / prompt stay current.
            rcFile.writeText(content)
        } catch (e: Exception) {
            Log.e("PtySession", "Failed to write private shell rc: ${rcFile.absolutePath}", e)
        }
        return rcFile
    }

    private fun deleteLegacyShellConfigFiles() {
        val parentDir = workingDir.parentFile ?: workingDir
        val candidates = listOf(
            File(workingDir, ".inputrc"),
            File(workingDir, ".shinit"),
            File(parentDir, ".shinit"),
            File(parentDir, ".inputrc")
        )
        for (file in candidates) {
            try {
                if (file.isFile && file.delete()) {
                    Log.i("PtySession", "Removed legacy shell config: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w("PtySession", "Failed to remove legacy shell config: ${file.absolutePath}", e)
            }
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
