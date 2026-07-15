package com.example.terminal

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
                            Log.i("PtySession", "Successfully extracted bash from assets path: $path")
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
                            Log.i("PtySession", "Successfully copied built-in bash to filesDir/bin/bash and made executable")
                        } catch (e: Exception) {
                            Log.e("PtySession", "Failed to copy built-in bash to filesDir/bin/bash", e)
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

            // Explicitly configure readline (.inputrc) for horizontal wrapping
            val inputrcFile = File(workingDir, ".inputrc")
            try {
                val inputrcContent = """
                    set horizontal-scroll-mode off
                """.trimIndent()
                inputrcFile.writeText(inputrcContent + "\n")
            } catch (e: Exception) {
                Log.e("PtySession", "Failed to write .inputrc", e)
            }
            envList.add("INPUTRC=${inputrcFile.absolutePath}")

            val parentDir = workingDir.parentFile ?: workingDir
            val initFile = File(parentDir, ".shinit")
            try {
                val script = """
                    export TERM_BIN="${binDir.absolutePath}"
                    # Pre-evaluate the literal escape character to avoid backslash prompt bugs in different shells
                    ESC=${'$'}(printf "\033")
                    
                    if [ -n "${'$'}BASH_VERSION" ]; then
                        PS1="\[${'$'}{ESC}[32m\]\W\[${'$'}{ESC}[0m\] \${'$'} "
                    else
                        # Enable command-line editing and history navigation (arrow keys) in default system mksh shell
                        set -o emacs
                        cwd_prompt() {
                            cwd=${'$'}(pwd)
                            if [ "${'$'}cwd" = "${'$'}HOME" ]; then
                                name="~"
                            else
                                name="${'$'}{cwd##*/}"
                                if [ -z "${'$'}name" ]; then
                                    name="/"
                               fi
                            fi
                            printf "%s" "${'$'}name"
                        }
                        PS1="${'$'}{ESC}[32m\$(cwd_prompt)${'$'}{ESC}[0m \${'$'} "
                    fi
                    
                    fix-shebang() {
                        if [ -z "${'$'}1" ]; then
                            echo "Usage: fix-shebang <script_file>"
                            return 1
                        fi
                        if [ ! -f "${'$'}1" ]; then
                            echo "Error: File '${'$'}1' does not exist."
                            return 1
                        fi
                        # Replace standard shebangs with our custom local paths
                        sed -i "s|^#!/usr/bin/env bash|#!${'$'}TERM_BIN/bash|" "${'$'}1"
                        sed -i "s|^#!/usr/bin/env sh|#!/system/bin/sh|" "${'$'}1"
                        sed -i "s|^#!/bin/bash|#!${'$'}TERM_BIN/bash|" "${'$'}1"
                        sed -i "s|^#!/bin/sh|#!/system/bin/sh|" "${'$'}1"
                        echo "Shebang updated for ${'$'}1"
                    }
                    
                    alias ls='ls --color=auto'
                    alias ll='ls -l --color=auto'
                    alias grep='grep --color=auto'
                    alias egrep='egrep --color=auto'
                    alias fgrep='fgrep --color=auto'
                    
                    if [ -f "${'$'}{HOME}/.bashrc" ]; then
                        . "${'$'}{HOME}/.bashrc"
                    fi
                """.trimIndent()
                initFile.writeText(script)
            } catch (e: Exception) {
                Log.e("PtySession", "Failed to write .shinit", e)
            }
            envList.add("ENV=${initFile.absolutePath}")

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

            val args = if (shellPath.endsWith("bash") || shellPath.endsWith("libbash.so")) {
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
