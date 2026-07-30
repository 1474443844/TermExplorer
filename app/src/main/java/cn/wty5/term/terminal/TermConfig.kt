package cn.wty5.term.terminal

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object TermConfig {
    lateinit var filesDir: File
        private set
    lateinit var nativeLibDir: File
        private set
    lateinit var termDir: File
        private set
    lateinit var binDir: File
        private set
    lateinit var libDir: File
        private set
    lateinit var tmpDir: File
        private set
    lateinit var homeDir: File
        private set

    fun init(app: Application) {
        nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
        filesDir = app.filesDir
        termDir = File(filesDir, "term")
        binDir = File(termDir, "bin")
        libDir = File(termDir, "lib")
        tmpDir = File(termDir, "tmp")
        homeDir = File(termDir, "home")

        // 确保目录存在
        binDir.mkdirs()
        libDir.mkdirs()
        tmpDir.mkdirs()
        homeDir.mkdirs()

        CoreutilsManager.isInstalled()
        initBash(app)
    }

    fun initBash(context: Context) {
        val bashFile = File(binDir, "bash")

        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val targetArchFolder = when {
            primaryAbi.contains("64") && (primaryAbi.contains("arm") || primaryAbi.contains("aarch")) -> "arm64-v8a"
            primaryAbi.contains("64") && primaryAbi.contains("x86") -> "x86_64"
            else -> "arm64-v8a"
        }

        val path = "$targetArchFolder/bash"
        if (!bashFile.exists() || bashFile.length() == 0L) {
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
                }
            } catch (e: Exception) {
                Log.e("PtySession", "Failed to extract bash from assets path: $path", e)
            }
        } else {
            bashFile.setExecutable(true, false)
        }
    }
}