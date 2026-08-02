package cn.wty5.term

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.wty5.term.viewmodel.FileManagerViewModel
import cn.wty5.term.viewmodel.FileManagerViewModelFactory
import kotlinx.coroutines.launch
import java.io.File

class FileManagerActivity : AppCompatActivity() {

    private lateinit var viewModel: FileManagerViewModel
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var leftPanel: PanelViews
    private lateinit var rightPanel: PanelViews
    private lateinit var placesAdapter: PlacesAdapter
    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvToolbarSubtitle: TextView

    private var activeEditorDialog: AlertDialog? = null
    private var activeConfirmDialog: AlertDialog? = null
    private var pendingSdPanel: FileManagerViewModel.Panel = FileManagerViewModel.Panel.LEFT

    // 缓存上一次渲染完毕时的路径，以此判断是否由于目录切换需要暂存滚动数据
    private var lastRenderedLeftPath: String? = null
    private var lastRenderedRightPath: String? = null

    private data class PanelViews(
        val root: View,
        val adapter: FileListAdapter
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        viewModel = ViewModelProvider(
            this,
            FileManagerViewModelFactory(application)
        )[FileManagerViewModel::class.java]

        drawerLayout = findViewById(R.id.fm_drawer_layout)
        toolbar = findViewById(R.id.fm_toolbar)

        tvToolbarTitle = findViewById(R.id.tv_toolbar_title)
        tvToolbarSubtitle = findViewById(R.id.tv_toolbar_subtitle)

        setupToolbar()
        leftPanel = bindPanel(findViewById(R.id.layout_panel_left), FileManagerViewModel.Panel.LEFT)
        rightPanel = bindPanel(findViewById(R.id.layout_panel_right), FileManagerViewModel.Panel.RIGHT)
        setupSidebar()
        setupActions()
        setupBackPressed()
        collectState()
        collectEvents()
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.sidebar_open,
            R.string.sidebar_close
        )
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        drawerToggle.drawerArrowDrawable.color =
            ContextCompat.getColor(this, R.color.fm_text_primary)
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_file_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) return true
        return when (item.itemId) {
            R.id.action_back_terminal -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onStorageAccessGranted(pendingSdPanel)
        viewModel.refreshBoth()
    }

    private fun setupSidebar() {
        placesAdapter = PlacesAdapter { place ->
            pendingSdPanel = viewModel.uiState.value.activePanel
            viewModel.openPlace(place.id)
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<RecyclerView>(R.id.rv_sidebar_places).apply {
            layoutManager = LinearLayoutManager(this@FileManagerActivity)
            adapter = placesAdapter
            itemAnimator = null
        }

        findViewById<Button>(R.id.btn_sidebar_refresh).setOnClickListener {
            viewModel.refreshBoth()
        }
        findViewById<Button>(R.id.btn_sidebar_swap_panels).setOnClickListener {
            viewModel.swapPanels()
        }
        findViewById<Button>(R.id.btn_sidebar_same_both).setOnClickListener {
            viewModel.mirrorActiveToOther()
        }
    }

    private fun bindPanel(root: View, panel: FileManagerViewModel.Panel): PanelViews {
        val adapter = FileListAdapter(panel)
        root.findViewById<RecyclerView>(R.id.rv_file_items).apply {
            layoutManager = LinearLayoutManager(this@FileManagerActivity)
            this.adapter = adapter
            setHasFixedSize(true)
            itemAnimator = null
        }

        root.setOnClickListener { viewModel.setActivePanel(panel) }

        return PanelViews(
            root = root,
            adapter = adapter
        )
    }

    private fun setupActions() {
        // 修正了各按键错位的绑定映射关系
        findViewById<ImageButton>(R.id.btn_action_undo).setOnClickListener { viewModel.requestUndo() }
        findViewById<ImageButton>(R.id.btn_action_redo).setOnClickListener { viewModel.requestRedo() }

        findViewById<ImageButton>(R.id.btn_action_create).setOnClickListener {
            showCreateOptionsDialog()
        }
        findViewById<ImageButton>(R.id.btn_action_sync).setOnClickListener { viewModel.mirrorActiveToOther() }
        findViewById<ImageButton>(R.id.btn_action_goback).setOnClickListener {
            val activeDir = viewModel.activeDir()
            activeDir.parentFile?.let { parent ->
                viewModel.openParent(viewModel.uiState.value.activePanel, parent)
            }
        }
    }

    private fun showCreateOptionsDialog() {
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Create New")
            .setItems(arrayOf("File", "Folder")) { _, which ->
                if (which == 0) viewModel.requestNewFile() else viewModel.requestNewFolder()
            }
            .show()
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderPanel(leftPanel, state.left, FileManagerViewModel.Panel.LEFT)
                    renderPanel(rightPanel, state.right, FileManagerViewModel.Panel.RIGHT)

                    // 核心视觉交互：动态更新阴影与透明度
                    val activeIsLeft = state.activePanel == FileManagerViewModel.Panel.LEFT
                    applyPanelFocusEffect(leftPanel.root, activeIsLeft)
                    applyPanelFocusEffect(rightPanel.root, !activeIsLeft)

                    val activePath = if (activeIsLeft) state.left.path else state.right.path
                    tvToolbarTitle.text = activePath

                    val activePanelUi = if (activeIsLeft) state.left else state.right
                    tvToolbarSubtitle.text = activePanelUi.capacity

                    // 动态更新 Undo/Redo 工具按键的可视状态
                    findViewById<ImageButton>(R.id.btn_action_undo).apply {
                        isEnabled = state.canUndo
                        alpha = if (state.canUndo) 1.0f else 0.4f
                    }
                    findViewById<ImageButton>(R.id.btn_action_redo).apply {
                        isEnabled = state.canRedo
                        alpha = if (state.canRedo) 1.0f else 0.4f
                    }

                    placesAdapter.submit(
                        places = state.places,
                        activePath = activePath
                    )
                }
            }
        }
    }

    /**
     * 动态应用焦点效果：阴影高度、缩放和明暗对比
     */
    private fun applyPanelFocusEffect(panelView: View, isFocused: Boolean) {
        if (isFocused) {
            // 1. 激活状态：提升 Z 轴高度产生大阴影 (12dp)
            panelView.elevation = resources.displayMetrics.density * 12f
            // 2. 保持 100% 不透明度
            panelView.alpha = 1.0f
            // 3. 可选：微弱地放大激活面板 (1.005倍) 增强立体浮起感
            panelView.scaleX = 1.005f
            panelView.scaleY = 1.005f
        } else {
            // 1. 未激活状态：阴影归零 (贴在底层)
            panelView.elevation = 0f
            // 2. 降低透明度 (90%不透明)，实现暗光效果
            panelView.alpha = 0.9f
            // 3. 恢复标准缩放
            panelView.scaleX = 1.0f
            panelView.scaleY = 1.0f
        }
    }

    private fun collectEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is FileManagerViewModel.Event.Toast ->
                            Toast.makeText(this@FileManagerActivity, event.message, Toast.LENGTH_SHORT).show()

                        is FileManagerViewModel.Event.NeedStoragePermission ->
                            handleStoragePermission(event.legacyRuntime)

                        is FileManagerViewModel.Event.OpenEditor ->
                            openEditor(event.file)

                        is FileManagerViewModel.Event.ConfirmOverwrite -> {
                            activeConfirmDialog?.dismiss()
                            activeConfirmDialog = AlertDialog.Builder(
                                this@FileManagerActivity,
                                AlertDialog.THEME_DEVICE_DEFAULT_DARK
                            )
                                .setTitle("Overwrite confirmation")
                                .setMessage("${event.src.name} already exists in destination. Overwrite?")
                                .setPositiveButton("Overwrite") { _, _ ->
                                    viewModel.confirmOverwrite(event.op, event.src, event.dest)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }

                        is FileManagerViewModel.Event.ConfirmDelete -> {
                            activeConfirmDialog?.dismiss()
                            activeConfirmDialog = AlertDialog.Builder(
                                this@FileManagerActivity,
                                AlertDialog.THEME_DEVICE_DEFAULT_DARK
                            )
                                .setTitle("Delete")
                                .setMessage("Delete ${event.file.name}?")
                                .setPositiveButton("Delete") { _, _ ->
                                    viewModel.confirmDelete(event.file)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }

                        is FileManagerViewModel.Event.PromptRename -> showRenameDialog(event.file)
                        is FileManagerViewModel.Event.PromptNewFile -> showNewNameDialog("New File", "file.txt") {
                            viewModel.confirmNewFile(event.dir, it)
                        }
                        is FileManagerViewModel.Event.PromptNewFolder -> showNewNameDialog("New Folder", "Folder Name") {
                            viewModel.confirmNewFolder(event.dir, it)
                        }
                    }
                }
            }
        }
    }

    private fun renderPanel(
        views: PanelViews,
        ui: FileManagerViewModel.PanelUi,
        panel: FileManagerViewModel.Panel
    ) {
        val recyclerView = views.root.findViewById<RecyclerView>(R.id.rv_file_items)
        val lastPath =
            if (panel == FileManagerViewModel.Panel.LEFT) lastRenderedLeftPath else lastRenderedRightPath

        // 关键判断：路径是否真的发生了改变
        val pathChanged = lastPath != ui.path

        // 1. 如果路径改变，将前一个目录的当前滚动位置存入 ViewModel 缓存
        if (lastPath != null && pathChanged) {
            saveScrollState(recyclerView, lastPath)
        }

        // 更新路径基准记录
        if (panel == FileManagerViewModel.Panel.LEFT) {
            lastRenderedLeftPath = ui.path
        } else {
            lastRenderedRightPath = ui.path
        }

        // 2. 根据路径是否改变，决定是否强行还原滚动位置
        if (pathChanged) {
            // 场景 A：路径改变了（跳转目录/历史回溯），在渲染完毕后精准恢复目标路径的位置
            views.adapter.submit(ui.items) {
                restoreScrollState(recyclerView, ui.path)
            }
        } else {
            // 场景 B：路径完全没变（从后台回到前台、同目录下新建/删除文件、点击选中文件）
            // 直接提交列表，让 RecyclerView/DiffUtil 自动平滑保持当前的滚动位置，不作任何干预！
            views.adapter.submit(ui.items)
        }
    }

    private fun saveScrollState(recyclerView: RecyclerView, path: String) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position != RecyclerView.NO_POSITION) {
            val view = layoutManager.findViewByPosition(position)
            val offset = view?.top ?: 0
            viewModel.saveScrollPosition(path, position, offset)
        }
    }

    private fun restoreScrollState(recyclerView: RecyclerView, path: String) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val scrollPos = viewModel.getScrollPosition(path)
        if (scrollPos != null) {
            val (position, offset) = scrollPos
            layoutManager.scrollToPositionWithOffset(position, offset)
        } else {
            layoutManager.scrollToPositionWithOffset(0, 0)
        }
    }

    private fun handleStoragePermission(legacyRuntime: Boolean) {
        if (!legacyRuntime) {
            Toast.makeText(this, "Please grant SD Card access via settings", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    }
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return
        }

        val readPerm = Manifest.permission.READ_EXTERNAL_STORAGE
        val writePerm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, readPerm) == PackageManager.PERMISSION_GRANTED) {
            if (!viewModel.onStorageAccessGranted(pendingSdPanel)) {
                viewModel.goSdCard(pendingSdPanel)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(readPerm, writePerm),
                STORAGE_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (!viewModel.onStorageAccessGranted(pendingSdPanel)) {
                viewModel.goSdCard(pendingSdPanel)
            }
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(file: File) {
        activeConfirmDialog?.dismiss()
        val input = EditText(this).apply {
            setText(file.name)
            setTextColor(Color.WHITE)
            setSelection(file.name.lastIndexOf('.').let { if (it > 0) it else file.name.length })
        }
        activeConfirmDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                viewModel.confirmRename(file, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewNameDialog(title: String, hint: String, onConfirm: (String) -> Unit) {
        activeConfirmDialog?.dismiss()
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
        }
        activeConfirmDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Create") { _, _ -> onConfirm(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openEditor(file: File) {
        if (activeEditorDialog != null) return
        val view = layoutInflater.inflate(R.layout.dialog_editor, null)
        val tvFilename = view.findViewById<TextView>(R.id.tv_editor_filename)
        val etContent = view.findViewById<EditText>(R.id.et_editor_content)
        val btnSave = view.findViewById<Button>(R.id.btn_editor_save)
        val btnClose = view.findViewById<Button>(R.id.btn_editor_close)

        tvFilename.text = "Editing: ${file.name}"
        viewModel.loadEditorContent(file) { etContent.setText(it) }

        btnSave.setOnClickListener {
            viewModel.saveEditor(file, etContent.text.toString())
        }
        btnClose.setOnClickListener {
            activeEditorDialog?.dismiss()
            activeEditorDialog = null
        }

        activeEditorDialog =
            AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                .setView(view)
                .setCancelable(false)
                .create().apply { show() }
    }

    override fun onDestroy() {
        activeEditorDialog?.dismiss()
        activeEditorDialog = null
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Adapters (已全部重构为高性能的 ListAdapter)
    // -------------------------------------------------------------------------

    private class PlaceDiffCallback : DiffUtil.ItemCallback<FileManagerViewModel.QuickPlace>() {
        override fun areItemsTheSame(
            oldItem: FileManagerViewModel.QuickPlace,
            newItem: FileManagerViewModel.QuickPlace
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: FileManagerViewModel.QuickPlace,
            newItem: FileManagerViewModel.QuickPlace
        ): Boolean {
            return oldItem == newItem
        }
    }

    private inner class PlacesAdapter(
        private val onClick: (FileManagerViewModel.QuickPlace) -> Unit
    ) : ListAdapter<FileManagerViewModel.QuickPlace, PlacesAdapter.VH>(PlaceDiffCallback()) {

        private var activePath: String = ""
        private val inflater by lazy { LayoutInflater.from(this@FileManagerActivity) }

        fun submit(places: List<FileManagerViewModel.QuickPlace>, activePath: String) {
            this.activePath = activePath
            submitList(places)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(inflater.inflate(R.layout.item_fm_place, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.tv_place_title)
            private val path: TextView = view.findViewById(R.id.tv_place_path)

            fun bind(place: FileManagerViewModel.QuickPlace) {
                title.text = place.title
                path.text = place.directory.absolutePath
                itemView.setOnClickListener { onClick(place) }
            }
        }
    }

    private class FileItemDiffCallback : DiffUtil.ItemCallback<FileManagerViewModel.ListItem>() {
        override fun areItemsTheSame(
            oldItem: FileManagerViewModel.ListItem,
            newItem: FileManagerViewModel.ListItem
        ): Boolean {
            return when (oldItem) {
                is FileManagerViewModel.ListItem.Parent if newItem is FileManagerViewModel.ListItem.Parent ->
                    oldItem.parentDir.absolutePath == newItem.parentDir.absolutePath

                is FileManagerViewModel.ListItem.Empty if newItem is FileManagerViewModel.ListItem.Empty -> true
                is FileManagerViewModel.ListItem.Entry if newItem is FileManagerViewModel.ListItem.Entry ->
                    oldItem.file.absolutePath == newItem.file.absolutePath

                else -> false
            }
        }

        override fun areContentsTheSame(
            oldItem: FileManagerViewModel.ListItem,
            newItem: FileManagerViewModel.ListItem
        ): Boolean {
            return oldItem == newItem
        }
    }

    private inner class FileListAdapter(
        private val panel: FileManagerViewModel.Panel
    ) : ListAdapter<FileManagerViewModel.ListItem, RecyclerView.ViewHolder>(FileItemDiffCallback()) {

        private val inflater by lazy { LayoutInflater.from(this@FileManagerActivity) }

        fun submit(
            newItems: List<FileManagerViewModel.ListItem>,
            commitCallback: Runnable? = null
        ) {
            submitList(newItems, commitCallback)
        }

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is FileManagerViewModel.ListItem.Parent -> VIEW_TYPE_PARENT
            is FileManagerViewModel.ListItem.Empty -> VIEW_TYPE_EMPTY
            is FileManagerViewModel.ListItem.Entry -> VIEW_TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_PARENT -> ParentVH(inflater.inflate(R.layout.item_file_parent, parent, false))
                VIEW_TYPE_EMPTY -> EmptyVH(inflater.inflate(R.layout.item_file_empty, parent, false))
                else -> EntryVH(inflater.inflate(R.layout.item_file_entry, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is FileManagerViewModel.ListItem.Parent -> (holder as ParentVH).bind(item)
                is FileManagerViewModel.ListItem.Empty -> Unit
                is FileManagerViewModel.ListItem.Entry -> (holder as EntryVH).bind(item)
            }
        }

        private inner class ParentVH(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(item: FileManagerViewModel.ListItem.Parent) {
                itemView.setOnClickListener { viewModel.openParent(panel, item.parentDir) }
            }
        }

        private inner class EmptyVH(view: View) : RecyclerView.ViewHolder(view)

        private inner class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
            private val iconView: ImageView = view.findViewById(R.id.iv_item_icon)
            private val nameView: TextView = view.findViewById(R.id.tv_item_name)
            private val detailView: TextView = view.findViewById(R.id.tv_item_detail)

            fun bind(item: FileManagerViewModel.ListItem.Entry) {
                val file = item.file

                // 1. 设置前端图标
                if (item.isFolder) {
                    iconView.setImageResource(R.drawable.baseline_folder_24)
                } else {
                    iconView.setImageResource(R.drawable.baseline_file_24)
                }

                // 2. 使用 backgroundTintList 动态改变背景色，彻底免疫复用 Bug
                val bgColor = if (item.isFolder) {
                    Color.parseColor("#151515") // 文件夹黑色
                } else {
                    Color.parseColor("#4A5568") // 文件冷灰色
                }
                iconView.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)

                nameView.text = file.name
                detailView.text = item.detail

                itemView.isSelected = item.selected
                itemView.setOnClickListener { viewModel.onEntryClick(panel, file) }
                itemView.setOnLongClickListener {
                    showFileMenu(panel, file)
                    true
                }
            }
        }
    }

    private fun showFileMenu(panel: FileManagerViewModel.Panel, file: File) {
        viewModel.selectForMenu(panel, file)
        val options = if (file.isDirectory) {
            arrayOf("Rename", "Delete")
        } else {
            arrayOf("Edit", "Rename", "Delete")
        }
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                val adjusted = if (file.isDirectory) which + 1 else which
                when (adjusted) {
                    0 -> viewModel.openEditor(file)
                    1 -> {
                        viewModel.selectForMenu(panel, file)
                        viewModel.requestRename()
                    }
                    2 -> {
                        viewModel.selectForMenu(panel, file)
                        viewModel.requestDelete()
                    }
                }
            }
            .show()
    }

    companion object {
        private const val STORAGE_PERMISSION_CODE = 1001
        private const val VIEW_TYPE_PARENT = 0
        private const val VIEW_TYPE_EMPTY = 1
        private const val VIEW_TYPE_ENTRY = 2

        fun start(from: AppCompatActivity) {
            from.startActivity(Intent(from, FileManagerActivity::class.java))
        }
    }
}