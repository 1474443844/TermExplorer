package cn.wty5.term.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import cn.wty5.term.TermApp
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object CoreutilsManager {
    private const val TAG = "CoreutilsManager"

    val COREUTILS_COMMANDS = listOf(
        "[", "arch", "b2sum", "base32", "base64", "basename",
        "basenc", "cat", "chcon", "chgrp", "chmod", "chown",
        "chroot", "cksum", "comm", "cp", "csplit", "cut", "date",
        "dd", "df", "dir", "dircolors", "dirname", "du", "echo",
        "env", "expand", "expr", "factor", "false", "fmt", "fold",
        "ginstall", "groups", "head", "hostid", "hostname",
        "id", "join", "kill", "link", "ln", "logname", "ls", "md5sum",
        "mkdir", "mkfifo", "mknod", "mktemp", "mv", "nice", "nl",
        "nohup", "nproc", "numfmt", "od", "paste", "pathchk",
        "pr", "printenv", "printf", "ptx", "pwd", "readlink",
        "realpath", "rm", "rmdir", "runcon", "seq", "sha1sum",
        "sha224sum", "sha256sum", "sha384sum", "sha512sum",
        "shred", "shuf", "sleep", "sort", "split", "stat",
        "stty", "sum", "sync", "tac", "tail", "tee", "test", "timeout",
        "touch", "tr", "true", "truncate", "tsort", "tty", "uname",
        "unexpand", "uniq", "unlink", "uptime", "vdir", "wc",
        "whoami", "yes"
    )

    fun isInstalled(): Boolean {
        val coreutilsFile = File(TermConfig.binDir, "coreutils")
        val installed = coreutilsFile.exists() && coreutilsFile.canExecute()
        if (!installed) {
            // Attempt to auto-install built-in coreutils on demand!
            return autoInstallBuiltIn()
        }
        return true
    }

    fun autoInstallBuiltIn(): Boolean {
        Log.i(TAG, "Attempting to auto-install built-in coreutils...")
        if (installFromJniLibs()) {
            Log.i(TAG, "Successfully auto-installed coreutils from jniLibs!")
            return true
        }
        if (installFromAssets()) {
            Log.i(TAG, "Successfully auto-installed coreutils from assets!")
            return true
        }
        Log.i(TAG, "No built-in coreutils binary found in jniLibs or assets.")
        return false
    }

    fun installFromJniLibs(): Boolean {
        try {
            val nativeLibDir = TermConfig.nativeLibDir
            val builtInLib = File(nativeLibDir, "coreutils")

            if (builtInLib.exists()) {
                val coreutilsFile = File(TermConfig.binDir, "coreutils")
                if (coreutilsFile.exists()) {
                    coreutilsFile.delete()
                }

                // Create a symlink from filesDir/bin/coreutils to the native library
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        Files.createSymbolicLink(
                            Paths.get(coreutilsFile.absolutePath),
                            Paths.get(builtInLib.absolutePath)
                        )
                    } catch (e: Exception) {
                        builtInLib.inputStream().use { input ->
                            coreutilsFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        coreutilsFile.setExecutable(true, false)
                    }
                } else {
                    builtInLib.inputStream().use { input ->
                        coreutilsFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    coreutilsFile.setExecutable(true, false)
                }

                createSymlinks()
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from jniLibs", e)
        }
        return false
    }

    fun installFromAssets(): Boolean {
        try {
            val coreutilsFile = File(TermConfig.binDir, "coreutils")

            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val targetArchFolder = when {
                primaryAbi.contains("64") && (primaryAbi.contains("arm") || primaryAbi.contains("aarch")) -> "arm64-v8a"
                primaryAbi.contains("64") && primaryAbi.contains("x86") -> "x86_64"
                primaryAbi.contains("x86") -> "x86"
                else -> "arm64-v8a"
            }

            val path = "$targetArchFolder/coreutils"
            try {
                TermApp.openAssets(path).use { input ->
                    if (coreutilsFile.exists()) {
                        coreutilsFile.delete()
                    }
                    coreutilsFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                // Try next path
            }

            coreutilsFile.setExecutable(true, false)
            createSymlinks()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from assets", e)
        }
        return false
    }

    fun getInstalledCommandCount(): Int {
        val binDir = TermConfig.binDir
        if (!binDir.exists()) return 0
        return binDir.listFiles { _, name -> name in COREUTILS_COMMANDS }?.size ?: 0
    }

    fun createSymlinks() {
        val binDir = TermConfig.binDir
        val coreutilsFile = File(binDir, "coreutils")
        if (!coreutilsFile.exists()) return

        // Clean up any existing files/symlinks in binDir that are NOT in COREUTILS_COMMANDS and not "coreutils" itself
        binDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name != "coreutils" && name !in COREUTILS_COMMANDS) {
                file.delete()
            }
        }

        for (cmd in COREUTILS_COMMANDS) {
            val linkFile = File(binDir, cmd)
            if (linkFile.exists()) {
                linkFile.delete()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Files.createSymbolicLink(
                        Paths.get(linkFile.absolutePath),
                        Paths.get("coreutils")
                    )
                } catch (e: Exception) {
                    runLnCommand(linkFile, "coreutils")
                }
            } else {
                runLnCommand(linkFile, "coreutils")
            }
        }
    }

    private fun runLnCommand(linkFile: File, target: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("ln", "-sf", target, linkFile.absolutePath)).waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed runLnCommand: ln -sf $target ${linkFile.absolutePath}", e)
        }
    }
}