package cn.wty5.term

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.wty5.term.viewmodel.FileManagerViewModel
import cn.wty5.term.viewmodel.FileManagerViewModelFactory
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dual-pane file manager UI. Business state/IO live in [FileManagerViewModel].
 */
class FileManagerActivity : AppCompatActivity() {

    private lateinit var viewModel: FileManagerViewModel
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var leftPanel: PanelViews
    private lateinit var rightPanel: PanelViews
    private lateinit var placesAdapter: PlacesAdapter
    private lateinit var tvSidebarActivePanel: TextView
    private lateinit var tvHeaderActiveHint: TextView

    private var activeEditorDialog: AlertDialog? = null
    private var suppressFilterCallback = false
    private var pendingSdPanel: FileManagerViewModel.Panel = FileManagerViewModel.Panel.LEFT
    private var lastActivePanel: FileManagerViewModel.Panel = FileManagerViewModel.Panel.LEFT
    private var lastLeftPath: String = ""
    private var lastRightPath: String = ""

    private data class PanelViews(
        val root: View,
        val title: TextView,
        val cwdBanner: TextView,
        val storageCapacity: TextView,
        val filter: EditText,
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
        tvSidebarActivePanel = findViewById(R.id.tv_sidebar_active_panel)
        tvHeaderActiveHint = findViewById(R.id.tv_header_active_hint)

        leftPanel = bindPanel(findViewById(R.id.layout_panel_left), FileManagerViewModel.Panel.LEFT)
        rightPanel = bindPanel(findViewById(R.id.layout_panel_right), FileManagerViewModel.Panel.RIGHT)
        setupSidebar()
        setupActions()
        collectState()
        collectEvents()
    }

    override fun onResume() {
        super.onResume()
        // If user just granted all-files access in system settings, continue pending place.
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

        findViewById<Button>(R.id.btn_open_sidebar).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
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
        val filter = root.findViewById<EditText>(R.id.et_file_filter)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressFilterCallback) {
                    viewModel.setFilter(panel, s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        root.setOnClickListener { viewModel.setActivePanel(panel) }
        root.findViewById<Button>(R.id.btn_go_sandbox).setOnClickListener {
            viewModel.goSandbox(panel)
        }
        root.findViewById<Button>(R.id.btn_go_sdcard).setOnClickListener {
            pendingSdPanel = panel
            viewModel.requestSdCard(panel)
        }

        return PanelViews(
            root = root,
            title = root.findViewById(R.id.tv_panel_title),
            cwdBanner = root.findViewById(R.id.tv_cwd_banner),
            storageCapacity = root.findViewById(R.id.tv_storage_capacity),
            filter = filter,
            adapter = adapter
        )
    }

    private fun setupActions() {
        findViewById<Button>(R.id.btn_close_file_manager).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_action_copy).setOnClickListener { viewModel.requestCopy() }
        findViewById<Button>(R.id.btn_action_move).setOnClickListener { viewModel.requestMove() }
        findViewById<Button>(R.id.btn_action_rename).setOnClickListener { viewModel.requestRename() }
        findViewById<Button>(R.id.btn_action_delete).setOnClickListener { viewModel.requestDelete() }
        findViewById<Button>(R.id.btn_action_new_file).setOnClickListener { viewModel.requestNewFile() }
        findViewById<Button>(R.id.btn_action_new_folder).setOnClickListener { viewModel.requestNewFolder() }
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderPanel(
                        leftPanel,
                        state.left,
                        state.activePanel == FileManagerViewModel.Panel.LEFT,
                        true
                    )
                    renderPanel(
                        rightPanel,
                        state.right,
                        state.activePanel == FileManagerViewModel.Panel.RIGHT,
                        false
                    )

                    val activeIsLeft = state.activePanel == FileManagerViewModel.Panel.LEFT
                    tvSidebarActivePanel.text = if (activeIsLeft) {
                        getString(R.string.sidebar_target_left)
                    } else {
                        getString(R.string.sidebar_target_right)
                    }
                    tvHeaderActiveHint.text = if (activeIsLeft) {
                        "Active: LEFT · ${state.left.path}"
                    } else {
                        "Active: RIGHT · ${state.right.path}"
                    }

                    lastActivePanel = state.activePanel
                    lastLeftPath = state.left.path
                    lastRightPath = state.right.path
                    placesAdapter.submit(
                        places = state.places,
                        activePath = if (activeIsLeft) state.left.path else state.right.path
                    )
                }
            }
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

                        is FileManagerViewModel.Event.ConfirmOverwrite ->
                            AlertDialog.Builder(this@FileManagerActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                .setTitle("Overwrite confirmation")
                                .setMessage("${event.src.name} already exists in destination. Overwrite?")
                                .setPositiveButton("Overwrite") { _, _ ->
                                    viewModel.confirmOverwrite(event.op, event.src, event.dest)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()

                        is FileManagerViewModel.Event.ConfirmDelete ->
                            AlertDialog.Builder(this@FileManagerActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                .setTitle("Delete")
                                .setMessage("Delete ${event.file.name}?")
                                .setPositiveButton("Delete") { _, _ ->
                                    viewModel.confirmDelete(event.file)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()

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
        active: Boolean,
        isLeft: Boolean
    ) {
        val accent = ContextCompat.getColor(this, R.color.fm_accent)
        val muted = ContextCompat.getColor(this, R.color.fm_text_secondary)
        val activeBg = ContextCompat.getColor(this, R.color.fm_panel_active)
        val idleBg = ContextCompat.getColor(this, R.color.fm_surface)
        val stroke = ContextCompat.getColor(this, R.color.fm_stroke)
        val density = resources.displayMetrics.density

        views.root.background = GradientDrawable().apply {
            setColor(if (active) activeBg else idleBg)
            cornerRadius = 14f * density
            setStroke(
                if (active) (1.5f * density).toInt() else 1,
                if (active) accent else stroke
            )
        }
        views.title.text = when {
            isLeft && active -> getString(R.string.panel_left_active)
            isLeft -> getString(R.string.panel_left)
            active -> getString(R.string.panel_right_active)
            else -> getString(R.string.panel_right)
        }
        views.title.setTextColor(if (active) accent else muted)
        views.cwdBanner.text = ui.path
        views.storageCapacity.text = ui.capacity

        if (views.filter.text.toString() != ui.filter) {
            suppressFilterCallback = true
            views.filter.setText(ui.filter)
            views.filter.setSelection(ui.filter.length)
            suppressFilterCallback = false
        }

        views.adapter.submit(ui.items)
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
        val input = EditText(this).apply {
            setText(file.name)
            setTextColor(Color.WHITE)
            setSelection(file.name.lastIndexOf('.').let { if (it > 0) it else file.name.length })
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                viewModel.confirmRename(file, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewNameDialog(title: String, hint: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
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

    // -------------------------------------------------------------------------
    // Adapters
    // -------------------------------------------------------------------------

    private inner class PlacesAdapter(
        private val onClick: (FileManagerViewModel.QuickPlace) -> Unit
    ) : RecyclerView.Adapter<PlacesAdapter.VH>() {

        private val items = mutableListOf<FileManagerViewModel.QuickPlace>()
        private var activePath: String = ""
        private val inflater by lazy { LayoutInflater.from(this@FileManagerActivity) }

        fun submit(places: List<FileManagerViewModel.QuickPlace>, activePath: String) {
            this.activePath = activePath
            items.clear()
            items.addAll(places)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(inflater.inflate(R.layout.item_fm_place, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val icon: TextView = view.findViewById(R.id.tv_place_icon)
            private val title: TextView = view.findViewById(R.id.tv_place_title)
            private val path: TextView = view.findViewById(R.id.tv_place_path)

            fun bind(place: FileManagerViewModel.QuickPlace) {
                icon.text = place.icon
                title.text = place.title
                path.text = place.directory.absolutePath
                val selected = place.directory.absolutePath == activePath
                itemView.setBackgroundResource(
                    if (selected) R.drawable.bg_fm_sidebar_item_active
                    else R.drawable.bg_fm_sidebar_item
                )
                itemView.setOnClickListener { onClick(place) }
            }
        }
    }

    private inner class FileListAdapter(
        private val panel: FileManagerViewModel.Panel
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<FileManagerViewModel.ListItem>()
        private val inflater by lazy { LayoutInflater.from(this@FileManagerActivity) }

        fun submit(newItems: List<FileManagerViewModel.ListItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
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
            when (val item = items[position]) {
                is FileManagerViewModel.ListItem.Parent -> (holder as ParentVH).bind(item)
                is FileManagerViewModel.ListItem.Empty -> Unit
                is FileManagerViewModel.ListItem.Entry -> (holder as EntryVH).bind(item)
            }
        }

        override fun getItemCount(): Int = items.size

        private inner class ParentVH(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(item: FileManagerViewModel.ListItem.Parent) {
                itemView.setOnClickListener { viewModel.openParent(panel, item.parentDir) }
            }
        }

        private inner class EmptyVH(view: View) : RecyclerView.ViewHolder(view)

        private inner class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
            private val iconView: TextView = view.findViewById(R.id.tv_item_icon)
            private val nameView: TextView = view.findViewById(R.id.tv_item_name)
            private val detailView: TextView = view.findViewById(R.id.tv_item_detail)
            private val menuView: TextView = view.findViewById(R.id.tv_item_menu)

            fun bind(item: FileManagerViewModel.ListItem.Entry) {
                val file = item.file
                iconView.text = item.icon
                nameView.text = file.name
                detailView.text = item.detail
                itemView.setBackgroundResource(
                    if (item.selected) R.drawable.bg_fm_item_selected else R.drawable.bg_fm_item
                )
                itemView.setOnClickListener { viewModel.onEntryClick(panel, file) }
                itemView.setOnLongClickListener {
                    showFileMenu(panel, file)
                    true
                }
                menuView.setOnClickListener { showFileMenu(panel, file) }
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
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
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

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        activeEditorDialog?.dismiss()
        activeEditorDialog = null
        super.onDestroy()
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
