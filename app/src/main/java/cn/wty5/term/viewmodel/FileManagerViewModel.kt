package cn.wty5.term.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wty5.term.terminal.TermConfig
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
import kotlin.math.ln
import kotlin.math.pow

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
            val isFolder: Boolean,
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
        val directory: File,
        val requiresStorage: Boolean = false
    )

    data class UiState(
        val activePanel: Panel = Panel.LEFT,
        val left: PanelUi,
        val right: PanelUi,
        val places: List<QuickPlace> = emptyList(),
        val canUndo: Boolean = false,
        val canRedo: Boolean = false
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

    val sandboxDirectory: File = TermConfig.homeDir.also { if (!it.exists()) it.mkdirs() }
    val sdcardDirectory: File = Environment.getExternalStorageDirectory()
    val appFilesDirectory: File = application.filesDir

    private var leftDirectory: File = sandboxDirectory
    private var rightDirectory: File = sandboxDirectory
    private var selectedLeft: File? = null
    private var selectedRight: File? = null
    private var filterLeft: String = ""
    private var filterRight: String = ""
    private var activePanel: Panel = Panel.LEFT

    // 内存缓存：存放当前目录的原始物理文件列表，避免重复 I/O
    private var leftFilesCache: List<File> = emptyList()
    private var rightFilesCache: List<File> = emptyList()

    // 物理监听：监听文件夹底层变动（如后台下载、其他 App 操作文件）
    private var leftObserver: FileObserver? = null
    private var rightObserver: FileObserver? = null

    // 历史栈：存储时间线维度的目录历史记录 (Undo/Redo)
    private val leftBackStack = ArrayDeque<File>()
    private val leftForwardStack = ArrayDeque<File>()
    private val rightBackStack = ArrayDeque<File>()
    private val rightForwardStack = ArrayDeque<File>()

    // 滚动位置缓存：Path -> Pair(position, topOffset)
    private val scrollPositionCache = mutableMapOf<String, Pair<Int, Int>>()

    // 线程安全的日期格式化工具
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

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
            updateCache(Panel.LEFT, leftDirectory)
            updateCache(Panel.RIGHT, rightDirectory)
            startObserver(Panel.LEFT, leftDirectory)
            startObserver(Panel.RIGHT, rightDirectory)
            publishState()
        }
    }

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        scrollPositionCache[path] = Pair(index, offset)
    }

    fun getScrollPosition(path: String): Pair<Int, Int>? {
        return scrollPositionCache[path]
    }

    fun setActivePanel(panel: Panel) {
        if (activePanel == panel) return
        activePanel = panel
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun setFilter(panel: Panel, query: String) {
        if (panel == Panel.LEFT) filterLeft = query else filterRight = query
        viewModelScope.launch(Dispatchers.IO) { publishState() }
    }

    fun goSandbox(panel: Panel) {
        navigateTo(panel, sandboxDirectory, requiresStorage = false)
    }

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

    fun onStorageAccessGranted(panel: Panel = activePanel): Boolean {
        val placeId = pendingStoragePlaceId ?: return false
        if (!hasStorageAccess()) return false
        pendingStoragePlaceId = null
        openPlace(placeId, panel)
        return true
    }

    /**
     * 进入目标目录
     * @param isHistoryNavigation 是否是撤销/重做触发的物理回溯
     */
    fun navigateTo(
        panel: Panel,
        directory: File,
        requiresStorage: Boolean = false,
        isHistoryNavigation: Boolean = false
    ) {
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

        val currentDir = if (panel == Panel.LEFT) leftDirectory else rightDirectory

        // 非历史导航引起的目录变更，将其存入 Back 历史栈，并清空 Forward 栈
        if (!isHistoryNavigation && currentDir != target) {
            val backStack = if (panel == Panel.LEFT) leftBackStack else rightBackStack
            val forwardStack = if (panel == Panel.LEFT) leftForwardStack else rightForwardStack
            backStack.addLast(currentDir)
            forwardStack.clear()
        }

        activePanel = panel
        if (panel == Panel.LEFT) {
            leftDirectory = target
            selectedLeft = null
        } else {
            rightDirectory = target
            selectedRight = null
        }

        viewModelScope.launch(Dispatchers.IO) {
            updateCache(panel, target)
            startObserver(panel, target)
            publishState()
        }
    }

    fun requestUndo() {
        val panel = activePanel
        val backStack = if (panel == Panel.LEFT) leftBackStack else rightBackStack
        if (backStack.isEmpty()) {
            toast("No backward history")
            return
        }
        val currentDir = if (panel == Panel.LEFT) leftDirectory else rightDirectory
        val prevDir = backStack.removeLast()

        val forwardStack = if (panel == Panel.LEFT) leftForwardStack else rightForwardStack
        forwardStack.addLast(currentDir)

        navigateTo(panel, prevDir, isHistoryNavigation = true)
    }

    fun requestRedo() {
        val panel = activePanel
        val forwardStack = if (panel == Panel.LEFT) leftForwardStack else rightForwardStack
        if (forwardStack.isEmpty()) {
            toast("No forward history")
            return
        }
        val currentDir = if (panel == Panel.LEFT) leftDirectory else rightDirectory
        val nextDir = forwardStack.removeLast()

        val backStack = if (panel == Panel.LEFT) leftBackStack else rightBackStack
        backStack.addLast(currentDir)

        navigateTo(panel, nextDir, isHistoryNavigation = true)
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

        // 交换缓存
        val tmpCache = leftFilesCache
        leftFilesCache = rightFilesCache
        rightFilesCache = tmpCache

        // 交换历史栈
        swapHistory(leftBackStack, rightBackStack)
        swapHistory(leftForwardStack, rightForwardStack)

        activePanel = if (activePanel == Panel.LEFT) Panel.RIGHT else Panel.LEFT
        viewModelScope.launch(Dispatchers.IO) {
            startObserver(Panel.LEFT, leftDirectory)
            startObserver(Panel.RIGHT, rightDirectory)
            publishState()
        }
    }

    private fun <T> swapHistory(stackA: ArrayDeque<T>, stackB: ArrayDeque<T>) {
        val temp = ArrayDeque(stackA)
        stackA.clear()
        stackA.addAll(stackB)
        stackB.clear()
        stackB.addAll(temp)
    }

    fun mirrorActiveToOther() {
        val src = activeDir()
        if (activePanel == Panel.LEFT) {
            rightDirectory = src
            selectedRight = null
        } else {
            leftDirectory = src
            selectedLeft = null
        }
        viewModelScope.launch(Dispatchers.IO) {
            updateCache(if (activePanel == Panel.LEFT) Panel.RIGHT else Panel.LEFT, src)
            startObserver(if (activePanel == Panel.LEFT) Panel.RIGHT else Panel.LEFT, src)
            publishState()
        }
    }

    fun openParent(panel: Panel, parent: File) {
        navigateTo(panel, parent)
    }

    fun onEntryClick(panel: Panel, file: File) {
        activePanel = panel
        if (file.isDirectory) {
            navigateTo(panel, file)
        } else {
            if (panel == Panel.LEFT) {
                selectedLeft = if (selectedLeft == file) null else file
            } else {
                selectedRight = if (selectedRight == file) null else file
            }
            viewModelScope.launch(Dispatchers.IO) { publishState() }
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
                val panel =
                    if (file.absolutePath.startsWith(leftDirectory.absolutePath)) Panel.LEFT else Panel.RIGHT
                updateCache(panel, if (panel == Panel.LEFT) leftDirectory else rightDirectory)
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
                val panel =
                    if (file.absolutePath.startsWith(leftDirectory.absolutePath)) Panel.LEFT else Panel.RIGHT
                updateCache(panel, if (panel == Panel.LEFT) leftDirectory else rightDirectory)
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
                updateCache(activePanel, dir)
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
                updateCache(activePanel, dir)
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
                val panel =
                    if (file.absolutePath.startsWith(leftDirectory.absolutePath)) Panel.LEFT else Panel.RIGHT
                updateCache(panel, if (panel == Panel.LEFT) leftDirectory else rightDirectory)
                publishState()
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

    fun activeDir(): File =
        if (activePanel == Panel.LEFT) leftDirectory else rightDirectory

    private fun destDir(): File =
        if (activePanel == Panel.LEFT) rightDirectory else leftDirectory

    fun hasStorageAccess(): Boolean {
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
            QuickPlace("sandbox", "Sandbox", sandboxDirectory, requiresStorage = false),
            QuickPlace("app_files", "App Files", appFilesDirectory, requiresStorage = false),
            QuickPlace("sdcard", "SD Card", sdcardDirectory, requiresStorage = true),
            QuickPlace("download", "Download", child("Download"), requiresStorage = true),
            QuickPlace("dcim", "DCIM", child("DCIM"), requiresStorage = true),
            QuickPlace("pictures", "Pictures", child("Pictures"), requiresStorage = true),
            QuickPlace("documents", "Documents", child("Documents"), requiresStorage = true),
            QuickPlace("movies", "Movies", child("Movies"), requiresStorage = true),
            QuickPlace("music", "Music", child("Music"), requiresStorage = true)
        )
    }

    private fun clearSelectionIfMatches(file: File) {
        if (selectedLeft == file) selectedLeft = null
        if (selectedRight == file) selectedRight = null
    }

    private fun updateCache(panel: Panel, dir: File) {
        // 关键校验：获取当前面板最新的物理路径
        val currentDir = if (panel == Panel.LEFT) leftDirectory else rightDirectory

        // 如果该事件对应的目录与当前面板所处目录不符，说明是已失效的过期后台事件，直接拦截丢弃
        if (currentDir.absolutePath != dir.absolutePath) {
            return
        }

        val files = try {
            val list = dir.listFiles()?.toList() ?: emptyList()
            list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
        } catch (_: Exception) {
            emptyList()
        }

        if (panel == Panel.LEFT) {
            leftFilesCache = files
        } else {
            rightFilesCache = files
        }
    }

    private fun startObserver(panel: Panel, dir: File) {
        if (panel == Panel.LEFT) leftObserver?.stopWatching() else rightObserver?.stopWatching()
        val mask =
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM

        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handlePhysicalFileEvent(panel, dir)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    handlePhysicalFileEvent(panel, dir)
                }
            }
        }
        if (panel == Panel.LEFT) leftObserver = observer else rightObserver = observer
        observer.startWatching()
    }

    private fun handlePhysicalFileEvent(panel: Panel, dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            updateCache(panel, dir)
            publishState()
        }
    }

    private fun copy(src: File, dest: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
                else src.copyTo(dest, overwrite = true)
                toast("Copied successfully")
                val panel =
                    if (dest.absolutePath.startsWith(leftDirectory.absolutePath)) Panel.LEFT else Panel.RIGHT
                updateCache(panel, if (panel == Panel.LEFT) leftDirectory else rightDirectory)
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
                updateCache(Panel.LEFT, leftDirectory)
                updateCache(Panel.RIGHT, rightDirectory)
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
        val left = buildPanel(leftDirectory, filterLeft, selectedLeft, leftFilesCache)
        val right = buildPanel(rightDirectory, filterRight, selectedRight, rightFilesCache)

        val activeBackStack = if (activePanel == Panel.LEFT) leftBackStack else rightBackStack
        val activeForwardStack =
            if (activePanel == Panel.LEFT) leftForwardStack else rightForwardStack

        _uiState.value = UiState(
            activePanel = activePanel,
            left = left,
            right = right,
            places = places,
            canUndo = activeBackStack.isNotEmpty(),
            canRedo = activeForwardStack.isNotEmpty()
        )
    }

    private fun buildPanel(
        dir: File,
        filter: String,
        selected: File?,
        cachedFiles: List<File>
    ): PanelUi {
        val capacity = try {
            val freeGb = dir.freeSpace / (1024.0 * 1024.0 * 1024.0)
            val totalGb = dir.totalSpace / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "Free %.2f GB / Total %.2f GB", freeGb, totalGb)
        } catch (_: Exception) {
            "Capacity: Unknown"
        }

        val filtered = if (filter.isBlank()) cachedFiles
        else cachedFiles.filter { it.name.contains(filter, ignoreCase = true) }

        val items = ArrayList<ListItem>()
        val parent = dir.parentFile

        // 此时只要不是物理根目录 "/"，顶部就一定会雷打不动地出现 "⬆️ Parent Directory"
        if (parent != null) {
            items.add(ListItem.Parent(parent))
        }

        if (filtered.isEmpty()) {
            items.add(ListItem.Empty)
        } else {
            filtered.forEach { f ->
                items.add(
                    ListItem.Entry(
                        file = f,
                        selected = selected == f,
                        isFolder = f.isDirectory,
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


    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + "B"
        return String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(exp.toDouble()), pre)
    }

    private fun detailFor(file: File): String {
        val size = if (file.isDirectory) "" else formatSize(file.length())
        val formattedDate = dateFormat.get()?.format(Date(file.lastModified())) ?: ""
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        val x = if (file.canExecute()) "x" else "-"
        return "$size · $formattedDate · [$r$w$x]"
    }

    override fun onCleared() {
        super.onCleared()
        leftObserver?.stopWatching()
        rightObserver?.stopWatching()
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