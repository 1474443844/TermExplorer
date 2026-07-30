package cn.wty5.term.terminal

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

object TermConfig {
    private const val TAG = "TermConfig"

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

        binDir.mkdirs()
        libDir.mkdirs()
        tmpDir.mkdirs()
        homeDir.mkdirs()

        // Flavor-scoped assets ship binaries flat: assets/bash, assets/coreutils, assets/curl
        installAssetBinary(app, assetName = "bash", dest = File(binDir, "bash"))
        installAssetBinary(app, assetName = "curl", dest = File(binDir, "curl"))
        CoreutilsManager.isInstalled()
    }

    /**
     * Copy [assetName] from the APK's assets root into [dest] if missing/empty.
     * ABI is fixed at build time by product flavor, so no runtime path lookup.
     */
    fun installAssetBinary(context: Context, assetName: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0L) {
            dest.setExecutable(true, false)
            return true
        }
        return try {
            context.assets.open(assetName).use { input ->
                val tmp = File(dest.absolutePath + ".tmp")
                if (dest.exists()) dest.delete()
                if (tmp.exists()) tmp.delete()
                tmp.outputStream().use { output -> input.copyTo(output) }
                if (!tmp.renameTo(dest)) {
                    tmp.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    tmp.delete()
                }
            }
            dest.setExecutable(true, false)
            Log.i(TAG, "Installed assets/$assetName → ${dest.absolutePath}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to install assets/$assetName", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install assets/$assetName", e)
            false
        }
    }
}
