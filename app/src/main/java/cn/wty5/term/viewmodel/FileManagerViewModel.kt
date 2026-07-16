package cn.wty5.term.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel for [cn.wty5.term.FileManagerActivity].
 *
 * Independent from [MainViewModel] so opening the explorer never spawns a PTY.
 * Holds dual-pane navigation/selection state and runs IO for file ops.
 */
class FileManagerViewModel(
    application: Application
) : AndroidViewModel(application) {

    enum class Panel { LEFT, RIGHT }

    sealed class ListItem {
        data class Parent(val parentDir: File) : ListItem()
        data object Empty : ListItem()
        data class Entry(
            val file: File,
            val selected: Boolean,
            val icon: String,
            val detail: String
        ) : ListItem()
    }

    data class PanelUi(
        val directory: File,
        val path: String,
        val capacity: String,
        val filter: String,
        val items: List<ListItem>
    )

    data class QuickPlace(
        val id: String,
        val title: String,
        val icon: String,
        val directory: File,
        val requiresStorage: Boolean = false
    )

    data class UiState(
        val activePanel: Panel = Panel.LEFT,
        val left: PanelUi,
        val right: PanelUi,
        val places: List<QuickPlace> = emptyList()
    )

    sealed class Event {
        data class Toast(val message: String) : Event()
        data class NeedStoragePermission(val legacyRuntime: Boolean) : Event()
        data class OpenEditor(val file: File) : Event()
        data class ConfirmOverwrite(
            val op: Op,
            val src: File,
            val dest: File
        ) : Event()
        data class ConfirmDelete(val file: File) : Event()
        data class PromptRename(val file: File) : Event()
        data class PromptNewFile(val dir: File) : Event()
        data class PromptNewFolder(val dir: File) : Event()
    }

    enum class Op { COPY, MOVE }

    val sandboxDirectory: File =
        File(application.filesDir, "workspace").also { if (!it.exists()) it.mkdirs() }
    val sdcardDirectory: File = Environment.getExternalStorageDirectory()
    val appFilesDirectory: File = application.filesDir

    private var leftDirectory: File = sandboxDirectory
    private var rightDirectory: File = sandboxDirectory
    private var selectedLeft: File? = null
    private var selectedRight: File? = null
    private var filterLeft: String = ""
    private var filterRight: String = ""
    private var activePanel: Panel = Panel.LEFT

    private val places: List<QuickPlace> = buildPlaces()

    private val _uiState = MutableStateFlow(
        UiState(
            left = emptyPanel(leftDirectory),
            right = emptyPanel(rightDirectory),
            places = places
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    init {
        refreshBoth()
    }

    fun refreshBoth() {
        viewModelScope.launch(Dispatchers.IO) {
            publishState()
        }
    }

    fun setActivePanel(panel: Panel) {
        if (activePanel == panel) return
        activePanel = panel
        // Titles/borders are pure UI; still republish so observers can rebind.
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun setFilter(panel: Panel, query: String) {
        if (panel == Panel.LEFT) filterLeft = query else filterRight = query
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun goSandbox(panel: Panel) {
        navigateTo(panel, sandboxDirectory, requiresStorage = false)
    }

    /**
     * Called after Activity confirms storage access is available.
     */
    fun goSdCard(panel: Panel) {
        navigateTo(panel, sdcardDirectory, requiresStorage = false)
    }

    fun requestSdCard(panel: Panel) {
        activePanel = panel
        if (hasStorageAccess()) {
            goSdCard(panel)
            return
        }
        pendingStoragePlaceId = "sdcard"
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        _events.tryEmit(Event.NeedStoragePermission(legacyRuntime = legacy))
    }

    /**
     * Open a quick place into the active panel (or explicit [panel]).
     * Storage-gated places emit [Event.NeedStoragePermission] when access is missing.
     */
    fun openPlace(placeId: String, panel: Panel = activePanel) {
        val place = places.firstOrNull { it.id == placeId } ?: return
        activePanel = panel
        if (place.requiresStorage && !hasStorageAccess()) {
            pendingStoragePlaceId = place.id
            val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            _events.tryEmit(Event.NeedStoragePermission(legacyRuntime = legacy))
            return
        }
        navigateTo(panel, place.directory, requiresStorage = false)
    }

    /**
     * Resume navigation after storage permission / all-files access is granted.
     * No-op when there is no pending place request.
     * @return true if a pending place was resumed.
     */
    fun onStorageAccessGranted(panel: Panel = activePanel): Boolean {
        val placeId = pendingStoragePlaceId ?: return false
        if (!hasStorageAccess()) return false
        pendingStoragePlaceId = null
        openPlace(placeId, panel)
        return true
    }

    fun navigateTo(panel: Panel, directory: File, requiresStorage: Boolean = false) {
        if (requiresStorage && !hasStorageAccess()) {
            pendingStoragePlaceId = null
            activePanel = panel
            val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            _events.tryEmit(Event.NeedStoragePermission(legacyRuntime = legacy))
            return
        }
        val target = if (directory.exists() && directory.isDirectory) {
            directory
        } else {
            toast("Path unavailable")
            return
        }
        activePanel = panel
        if (panel == Panel.LEFT) {
            leftDirectory = target
            selectedLeft = null
        } else {
            rightDirectory = target
            selectedRight = null
        }
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun swapPanels() {
        val tmpDir = leftDirectory
        leftDirectory = rightDirectory
        rightDirectory = tmpDir

        val tmpSel = selectedLeft
        selectedLeft = selectedRight
        selectedRight = tmpSel

        val tmpFilter = filterLeft
        filterLeft = filterRight
        filterRight = tmpFilter

        activePanel = if (activePanel == Panel.LEFT) Panel.RIGHT else Panel.LEFT
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    /** Put the inactive panel on the same path as the active panel. */
    fun mirrorActiveToOther() {
        val src = activeDir()
        if (activePanel == Panel.LEFT) {
            rightDirectory = src
            selectedRight = null
        } else {
            leftDirectory = src
            selectedLeft = null
        }
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun openParent(panel: Panel, parent: File) {
        activePanel = panel
        if (panel == Panel.LEFT) {
            leftDirectory = parent
            selectedLeft = null
        } else {
            rightDirectory = parent
            selectedRight = null
        }
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun onEntryClick(panel: Panel, file: File) {
        activePanel = panel
        if (file.isDirectory) {
            if (panel == Panel.LEFT) {
                leftDirectory = file
                selectedLeft = null
            } else {
                rightDirectory = file
                selectedRight = null
            }
        } else {
            if (panel == Panel.LEFT) {
                selectedLeft = if (selectedLeft == file) null else file
            } else {
                selectedRight = if (selectedRight == file) null else file
            }
        }
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun onEntryMenu(panel: Panel, file: File) {
        activePanel = panel
        if (panel == Panel.LEFT) selectedLeft = file else selectedRight = file
        viewModelScope.launch(Dispatchers.IO) {
            publishState()
            // Menu is shown by Activity after selection is published.
        }
    }

    fun selectForMenu(panel: Panel, file: File) {
        activePanel = panel
        if (panel == Panel.LEFT) selectedLeft = file else selectedRight = file
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun openEditor(file: File) {
        _events.tryEmit(Event.OpenEditor(file))
    }

    fun requestCopy() {
        val src = activeFile() ?: run {
            toast("Select a file to copy first")
            return
        }
        val dest = File(destDir(), src.name)
        if (dest.exists()) {
            _events.tryEmit(Event.ConfirmOverwrite(Op.COPY, src, dest))
        } else {
            copy(src, dest)
        }
    }

    fun requestMove() {
        val src = activeFile() ?: run {
            toast("Select a file to move first")
            return
        }
        val dest = File(destDir(), src.name)
        if (dest.exists()) {
            _events.tryEmit(Event.ConfirmOverwrite(Op.MOVE, src, dest))
        } else {
            move(src, dest)
        }
    }

    fun requestDelete() {
        val target = activeFile() ?: run {
            toast("Select a file to delete first")
            return
        }
        _events.tryEmit(Event.ConfirmDelete(target))
    }

    fun requestRename() {
        val target = activeFile() ?: run {
            toast("Select a file to rename first")
            return
        }
        _events.tryEmit(Event.PromptRename(target))
    }

    fun requestNewFile() {
        _events.tryEmit(Event.PromptNewFile(activeDir()))
    }

    fun requestNewFolder() {
        _events.tryEmit(Event.PromptNewFolder(activeDir()))
    }

    fun confirmOverwrite(op: Op, src: File, dest: File) {
        when (op) {
            Op.COPY -> copy(src, dest)
            Op.MOVE -> move(src, dest)
        }
    }

    fun confirmDelete(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                file.deleteRecursively()
            } catch (_: Exception) {
                false
            }
            if (ok) {
                clearSelectionIfMatches(file)
                toast("Deleted successfully")
                publishState()
            } else {
                toast("Delete failed")
            }
        }
    }

    fun confirmRename(file: File, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dest = File(file.parentFile, name)
            val ok = try {
                file.renameTo(dest)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                if (selectedLeft == file) selectedLeft = dest
                if (selectedRight == file) selectedRight = dest
                toast("Renamed successfully")
                publishState()
            } else {
                toast("Rename failed")
            }
        }
    }

    fun confirmNewFile(dir: File, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(dir, n)
            if (file.exists()) {
                toast("Already exists")
                return@launch
            }
            try {
                file.createNewFile()
                toast("File created")
                publishState()
            } catch (e: Exception) {
                toast("Creation failed: ${e.localizedMessage}")
            }
        }
    }

    fun confirmNewFolder(dir: File, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val folder = File(dir, n)
            if (folder.exists()) {
                toast("Already exists")
                return@launch
            }
            val ok = folder.mkdirs()
            if (ok) {
                toast("Folder created")
                publishState()
            } else {
                toast("Creation failed")
            }
        }
    }

    fun saveEditor(file: File, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                file.writeText(content)
                toast("Saved")
            } catch (e: Exception) {
                toast("Save failed: ${e.localizedMessage}")
            }
        }
    }

    fun loadEditorContent(file: File, onLoaded: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                file.readText()
            } catch (_: Exception) {
                ""
            }
            withContext(Dispatchers.Main) { onLoaded(text) }
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private var pendingStoragePlaceId: String? = null

    private fun activeFile(): File? =
        if (activePanel == Panel.LEFT) selectedLeft else selectedRight

    private fun activeDir(): File =
        if (activePanel == Panel.LEFT) leftDirectory else rightDirectory

    private fun destDir(): File =
        if (activePanel == Panel.LEFT) rightDirectory else leftDirectory

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun buildPlaces(): List<QuickPlace> {
        val external = Environment.getExternalStorageDirectory()
        fun child(name: String): File = File(external, name)
        return listOf(
            QuickPlace("sandbox", "Sandbox", "📦", sandboxDirectory, requiresStorage = false),
            QuickPlace("app_files", "App Files", "📱", appFilesDirectory, requiresStorage = false),
            QuickPlace("sdcard", "SD Card", "💾", sdcardDirectory, requiresStorage = true),
            QuickPlace("download", "Download", "⬇️", child("Download"), requiresStorage = true),
            QuickPlace("dcim", "DCIM", "📷", child("DCIM"), requiresStorage = true),
            QuickPlace("pictures", "Pictures", "🖼️", child("Pictures"), requiresStorage = true),
            QuickPlace("documents", "Documents", "📄", child("Documents"), requiresStorage = true),
            QuickPlace("movies", "Movies", "🎬", child("Movies"), requiresStorage = true),
            QuickPlace("music", "Music", "🎵", child("Music"), requiresStorage = true)
        )
    }

    private fun clearSelectionIfMatches(file: File) {
        if (selectedLeft == file) selectedLeft = null
        if (selectedRight == file) selectedRight = null
        if (activePanel == Panel.LEFT) selectedLeft = null else selectedRight = null
    }

    private fun copy(src: File, dest: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
                else src.copyTo(dest, overwrite = true)
                toast("Copied successfully")
                publishState()
            } catch (e: Exception) {
                toast("Copy failed: ${e.localizedMessage}")
            }
        }
    }

    private fun move(src: File, dest: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val renamed = src.renameTo(dest)
                if (!renamed) {
                    if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
                    else src.copyTo(dest, overwrite = true)
                    src.deleteRecursively()
                }
                clearSelectionIfMatches(src)
                toast("Moved successfully")
                publishState()
            } catch (e: Exception) {
                toast("Move failed: ${e.localizedMessage}")
            }
        }
    }

    private fun toast(msg: String) {
        _events.tryEmit(Event.Toast(msg))
    }

    private fun emptyPanel(dir: File) = PanelUi(
        directory = dir,
        path = dir.absolutePath,
        capacity = "Capacity: Unknown",
        filter = "",
        items = emptyList()
    )

    private fun publishState() {
        val left = buildPanel(leftDirectory, filterLeft, selectedLeft)
        val right = buildPanel(rightDirectory, filterRight, selectedRight)
        _uiState.value = UiState(
            activePanel = activePanel,
            left = left,
            right = right,
            places = places
        )
    }

    private fun buildPanel(dir: File, filter: String, selected: File?): PanelUi {
        val capacity = try {
            val freeGb = dir.freeSpace / (1024.0 * 1024.0 * 1024.0)
            val totalGb = dir.totalSpace / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "Free %.2f GB / Total %.2f GB", freeGb, totalGb)
        } catch (_: Exception) {
            "Capacity: Unknown"
        }

        val files = try {
            val list = dir.listFiles()?.toList() ?: emptyList()
            val filtered = if (filter.isBlank()) list
            else list.filter { it.name.contains(filter, ignoreCase = true) }
            filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
        } catch (_: Exception) {
            emptyList()
        }

        val items = ArrayList<ListItem>()
        val parent = dir.parentFile
        if (canNavigateToParent(dir, parent)) {
            items.add(ListItem.Parent(parent!!))
        }
        if (files.isEmpty()) {
            items.add(ListItem.Empty)
        } else {
            files.forEach { f ->
                items.add(
                    ListItem.Entry(
                        file = f,
                        selected = selected == f,
                        icon = iconFor(f),
                        detail = detailFor(f)
                    )
                )
            }
        }

        return PanelUi(
            directory = dir,
            path = dir.absolutePath,
            capacity = capacity,
            filter = filter,
            items = items
        )
    }

    private fun canNavigateToParent(dir: File, parent: File?): Boolean {
        return parent != null &&
            dir.absolutePath != "/" &&
            dir.absolutePath != sandboxDirectory.parentFile?.absolutePath
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + "B"
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun iconFor(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        return when {
            file.isDirectory -> "📁"
            ext in listOf("sh", "bash", "cmd", "bat", "bin") -> "⚙️"
            ext in listOf("txt", "md", "env", "conf", "prop", "properties") -> "📄"
            ext in listOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg") -> "🖼️"
            ext in listOf("json", "xml", "yaml", "yml", "ini") -> "🎛️"
            ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2") -> "📦"
            else -> "📝"
        }
    }

    private fun detailFor(file: File): String {
        val size = if (file.isDirectory) "Folder" else formatSize(file.length())
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(file.lastModified()))
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        val x = if (file.canExecute()) "x" else "-"
        return "$size · $date · [$r$w$x]"
    }
}

class FileManagerViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FileManagerViewModel::class.java)) {
            return FileManagerViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
