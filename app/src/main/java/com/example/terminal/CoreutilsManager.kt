package com.example.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream

object CoreutilsManager {
    private const val TAG = "CoreutilsManager"

    val COREUTILS_COMMANDS = listOf(
        "ls", "cat", "mv", "cp", "rm", "mkdir", "rmdir", "pwd", "whoami", "id",
        "chmod", "chown", "chgrp", "touch", "ln", "head", "tail", "wc", "sort", "uniq",
        "grep", "egrep", "fgrep", "date", "du", "df", "stat", "echo", "sleep", "env",
        "printenv", "printf", "true", "false", "test", "[", "expr", "seq", "tee",
        "basename", "dirname", "realpath", "readlink", "md5sum", "sha256sum",
        "uptime", "who", "yes", "mktemp", "timeout"
    )

    fun isInstalled(context: Context): Boolean {
        val binDir = File(context.filesDir, "bin")
        val coreutilsFile = File(binDir, "coreutils")
        val installed = binDir.exists() && coreutilsFile.exists() && coreutilsFile.canExecute()
        if (!installed) {
            // Attempt to auto-install built-in coreutils on demand!
            return autoInstallBuiltIn(context)
        }
        return true
    }

    fun autoInstallBuiltIn(context: Context): Boolean {
        Log.i(TAG, "Attempting to auto-install built-in coreutils...")
        if (installFromJniLibs(context)) {
            Log.i(TAG, "Successfully auto-installed coreutils from jniLibs!")
            return true
        }
        if (installFromAssets(context)) {
            Log.i(TAG, "Successfully auto-installed coreutils from assets!")
            return true
        }
        Log.i(TAG, "No built-in coreutils binary found in jniLibs or assets.")
        return false
    }

    fun installFromJniLibs(context: Context): Boolean {
        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val builtInLib = listOf(
                File(nativeLibDir, "coreutils"),
                File(nativeLibDir, "libcoreutils.so")
            ).firstOrNull { it.exists() }
            
            if (builtInLib != null && builtInLib.exists()) {
                val binDir = File(context.filesDir, "bin")
                if (!binDir.exists()) {
                    binDir.mkdirs()
                }
                val coreutilsFile = File(binDir, "coreutils")
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

                createSymlinks(context)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from jniLibs", e)
        }
        return false
    }

    fun installFromAssets(context: Context): Boolean {
        try {
            val binDir = File(context.filesDir, "bin")
            if (!binDir.exists()) {
                binDir.mkdirs()
            }
            val coreutilsFile = File(binDir, "coreutils")

            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val targetArchFolder = when {
                primaryAbi.contains("64") && (primaryAbi.contains("arm") || primaryAbi.contains("aarch")) -> "arm64-v8a"
                primaryAbi.contains("64") && primaryAbi.contains("x86") -> "x86_64"
                primaryAbi.contains("x86") -> "x86"
                else -> "arm64-v8a"
            }

            val possibleAssetPaths = listOf(
                "$targetArchFolder/coreutils",
                "bin/$targetArchFolder/coreutils",
                "coreutils"
            )

            var assetPathUsed: String? = null
            for (path in possibleAssetPaths) {
                try {
                    context.assets.open(path).use { input ->
                        if (coreutilsFile.exists()) {
                            coreutilsFile.delete()
                        }
                        coreutilsFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    assetPathUsed = path
                    break
                } catch (e: Exception) {
                    // Try next path
                }
            }

            if (assetPathUsed != null) {
                coreutilsFile.setExecutable(true, false)
                createSymlinks(context)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from assets", e)
        }
        return false
    }

    fun getInstalledCommandCount(context: Context): Int {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) return 0
        return binDir.listFiles { _, name -> name in COREUTILS_COMMANDS }?.size ?: 0
    }

    fun getInstalledVersion(context: Context): String {
        if (!isInstalled(context)) return "Not Installed"
        return try {
            val binDir = File(context.filesDir, "bin")
            val process = Runtime.getRuntime().exec(
                arrayOf(File(binDir, "coreutils").absolutePath, "--version")
            )
            val output = process.inputStream.bufferedReader().use { it.readLine() } ?: ""
            if (output.isNotBlank()) output.trim() else "uutils coreutils"
        } catch (e: Exception) {
            "uutils coreutils"
        }
    }

    fun uninstall(context: Context): Boolean {
        try {
            val binDir = File(context.filesDir, "bin")
            if (binDir.exists()) {
                binDir.deleteRecursively()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to uninstall coreutils", e)
            return false
        }
    }

    fun installFromZipFile(context: Context, zipFile: File): Result<Unit> {
        return try {
            val binDir = File(context.filesDir, "bin")
            if (!binDir.exists()) {
                binDir.mkdirs()
            }

            // Determine appropriate target folder based on device architecture
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val targetArchFolder = when {
                primaryAbi.contains("64") && (primaryAbi.contains("arm") || primaryAbi.contains("aarch")) -> "arm64-v8a"
                primaryAbi.contains("64") && primaryAbi.contains("x86") -> "x86_64"
                primaryAbi.contains("x86") -> "x86"
                else -> "arm64-v8a" // Default fallback
            }

            Log.i(TAG, "Device ABI: $primaryAbi -> Target folder: $targetArchFolder")

            val coreutilsFile = File(binDir, "coreutils")
            if (coreutilsFile.exists()) {
                coreutilsFile.delete()
            }

            var coreutilsExtracted = false
            
            // First attempt: search for target arch folder
            java.io.FileInputStream(zipFile).use { fis ->
                ZipInputStream(BufferedInputStream(fis)).use { zipInput ->
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (entryName.contains(targetArchFolder) && entryName.endsWith("coreutils") && !entry.isDirectory) {
                            FileOutputStream(coreutilsFile).use { out ->
                                zipInput.copyTo(out)
                            }
                            coreutilsExtracted = true
                            Log.i(TAG, "Extracted coreutils binary for $targetArchFolder")
                            break
                        }
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                }
            }

            // Second attempt fallback: find ANY coreutils binary in the zip if specific arch folder was not matched
            if (!coreutilsExtracted) {
                java.io.FileInputStream(zipFile).use { fis ->
                    ZipInputStream(BufferedInputStream(fis)).use { zipInput ->
                        var entry = zipInput.nextEntry
                        while (entry != null) {
                            val entryName = entry.name
                            if (entryName.endsWith("coreutils") && !entry.isDirectory) {
                                FileOutputStream(coreutilsFile).use { out ->
                                    zipInput.copyTo(out)
                                }
                                coreutilsExtracted = true
                                Log.i(TAG, "Extracted fallback coreutils binary: $entryName")
                                break
                            }
                            zipInput.closeEntry()
                            entry = zipInput.nextEntry
                        }
                    }
                }
            }

            if (!coreutilsFile.exists() || coreutilsFile.length() == 0L) {
                return Result.failure(Exception("Could not find any 'coreutils' binary inside the ZIP file."))
            }

            // Make executable
            coreutilsFile.setExecutable(true, false)

            // Setup symlinks
            createSymlinks(context)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
            Result.failure(e)
        }
    }

    fun createSymlinks(context: Context) {
        val binDir = File(context.filesDir, "bin")
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