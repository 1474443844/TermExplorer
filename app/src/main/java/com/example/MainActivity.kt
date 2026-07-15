package com.example

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.database.TermDatabase
import com.example.database.TermRepository
import com.example.ui.views.TerminalView
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.MainViewModelFactory
import com.example.terminal.CoreutilsManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout

    enum class PanelType { LEFT, RIGHT }
    private var activePanel = PanelType.LEFT
    private var leftDirectory: File = File("")
    private var rightDirectory: File = File("")
    private var selectedFileLeft: File? = null
    private var selectedFileRight: File? = null

    // Left views
    private lateinit var layoutPanelLeft: LinearLayout
    private lateinit var tvPanelLeftTitle: TextView
    private lateinit var tvCwdBannerLeft: TextView
    private lateinit var tvStorageCapacityLeft: TextView
    private lateinit var etFileFilterLeft: EditText
    private lateinit var containerFileItemsLeft: LinearLayout

    // Right views
    private lateinit var layoutPanelRight: LinearLayout
    private lateinit var tvPanelRightTitle: TextView
    private lateinit var tvCwdBannerRight: TextView
    private lateinit var tvStorageCapacityRight: TextView
    private lateinit var etFileFilterRight: EditText
    private lateinit var containerFileItemsRight: LinearLayout

    // Fullscreen switcher views
    private lateinit var layoutTerminalScreen: LinearLayout
    private lateinit var layoutFileManagerScreen: LinearLayout

    private val STORAGE_PERMISSION_CODE = 1001

    // Active file editor dialog instance
    private var activeEditorDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Room DB
        val database = Room.databaseBuilder(
            applicationContext,
            TermDatabase::class.java,
            "term_explorer_db"
        ).fallbackToDestructiveMigration()
         .build()

        val repository = TermRepository(database)

        // Set up ViewModel
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(application, repository)
        )[MainViewModel::class.java]

        // Find and bind views
        terminalView = findViewById(R.id.terminal_view)
        drawerLayout = findViewById(R.id.drawer_layout)

        setupTerminalInput()
        setupDrawerNavigation()
        setupToolbarKeys()
        setupDualPaneFileManager()
        observeViewModelState()
    }

    private fun setupTerminalInput() {
        // Direct character/stream redirection from view focus down to PtySession
        terminalView.onInputListener = { inputString ->
            viewModel.writeRawInput(inputString)
        }

        terminalView.onSizeChangedListener = { rows, cols ->
            viewModel.resizeTerminal(rows, cols)
        }

        // Proactively request input focus for the terminal so the user can start typing immediately
        terminalView.post {
            terminalView.focusTerminal()
        }
    }

    private fun setupDrawerNavigation() {
        // Coreutils setup dialog trigger
        findViewById<Button>(R.id.btn_coreutils_setup).setOnClickListener {
            showCoreutilsSetupDialog()
        }
    }

    private fun toggleDrawer() {
        if (drawerLayout.isDrawerOpen(Gravity.LEFT)) {
            drawerLayout.closeDrawer(Gravity.LEFT)
        } else {
            drawerLayout.openDrawer(Gravity.LEFT)
        }
    }

    private fun updateModifierButtonStates() {
        val ctrlBtn = findViewById<Button>(R.id.btn_key_ctrl)
        val altBtn = findViewById<Button>(R.id.btn_key_alt)
        val shiftBtn = findViewById<Button>(R.id.btn_key_shift)

        if (terminalView.isCtrlActive) {
            ctrlBtn.setBackgroundColor(Color.parseColor("#381E72"))
            ctrlBtn.setTextColor(Color.WHITE)
        } else {
            ctrlBtn.setBackgroundColor(Color.parseColor("#252729"))
            ctrlBtn.setTextColor(Color.parseColor("#C4C6CF"))
        }

        if (terminalView.isAltActive) {
            altBtn.setBackgroundColor(Color.parseColor("#381E72"))
            altBtn.setTextColor(Color.WHITE)
        } else {
            altBtn.setBackgroundColor(Color.parseColor("#252729"))
            altBtn.setTextColor(Color.parseColor("#C4C6CF"))
        }

        if (terminalView.isShiftActive) {
            shiftBtn.setBackgroundColor(Color.parseColor("#381E72"))
            shiftBtn.setTextColor(Color.WHITE)
        } else {
            shiftBtn.setBackgroundColor(Color.parseColor("#252729"))
            shiftBtn.setTextColor(Color.parseColor("#C4C6CF"))
        }
    }

    private fun setupToolbarKeys() {
        // Row 1: ESC, TAB, CTRL, ALT, -, up, enter
        findViewById<Button>(R.id.btn_key_esc).setOnClickListener {
            viewModel.writeRawInput("\u001B")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_tab).setOnClickListener {
            viewModel.writeRawInput("\t")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_ctrl).setOnClickListener {
            terminalView.isCtrlActive = !terminalView.isCtrlActive
            updateModifierButtonStates()
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_alt).setOnClickListener {
            terminalView.isAltActive = !terminalView.isAltActive
            updateModifierButtonStates()
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_minus).setOnClickListener {
            viewModel.writeRawInput("-")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_up).setOnClickListener {
            viewModel.writeRawInput("\u001B[A")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_enter).setOnClickListener {
            viewModel.writeRawInput("\n")
            terminalView.focusTerminal()
        }

        // Row 2: INS, END, SHIFT, HOME, left, down, right
        findViewById<Button>(R.id.btn_key_ins).setOnClickListener {
            viewModel.writeRawInput("\u001B[2~")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_end).setOnClickListener {
            viewModel.writeRawInput("\u001B[4~")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_shift).setOnClickListener {
            terminalView.isShiftActive = !terminalView.isShiftActive
            updateModifierButtonStates()
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_home).setOnClickListener {
            viewModel.writeRawInput("\u001B[1~")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_left).setOnClickListener {
            viewModel.writeRawInput("\u001B[D")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_down).setOnClickListener {
            viewModel.writeRawInput("\u001B[B")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_right).setOnClickListener {
            viewModel.writeRawInput("\u001B[C")
            terminalView.focusTerminal()
        }

        // Row 3: PgUp, PgDn, Ctrl+c, Ctrl+d, fm, sb, ss
        findViewById<Button>(R.id.btn_key_pgup).setOnClickListener {
            viewModel.writeRawInput("\u001B[5~")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_pgdn).setOnClickListener {
            viewModel.writeRawInput("\u001B[6~")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_ctrl_c).setOnClickListener {
            viewModel.writeRawInput("\u0003")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_ctrl_d).setOnClickListener {
            viewModel.writeRawInput("\u0004")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_fm).setOnClickListener {
            showDualPaneFileManager()
        }

        findViewById<Button>(R.id.btn_key_sb).setOnClickListener {
            toggleDrawer()
        }

        findViewById<Button>(R.id.btn_key_ss).setOnClickListener {
            viewModel.restartSession()
            Toast.makeText(this, "Terminal Session switched/restarted!", Toast.LENGTH_SHORT).show()
            terminalView.focusTerminal()
        }

        // Register the observer so when text consumption triggers modifiers reset, visual states sync
        terminalView.onModifiersChangedListener = {
            updateModifierButtonStates()
        }
    }

    private fun showDualPaneFileManager() {
        // Toggle screen visibility
        layoutTerminalScreen.visibility = android.view.View.GONE
        layoutFileManagerScreen.visibility = android.view.View.VISIBLE
        
        // Initialize directories if not done yet
        if (leftDirectory.absolutePath.isEmpty()) {
            leftDirectory = viewModel.sandboxDirectory
            rightDirectory = viewModel.sandboxDirectory
            setActivePanelFocus(PanelType.LEFT)
        } else {
            refreshPanel(PanelType.LEFT)
            refreshPanel(PanelType.RIGHT)
        }
    }

    private fun hideDualPaneFileManager() {
        layoutFileManagerScreen.visibility = android.view.View.GONE
        layoutTerminalScreen.visibility = android.view.View.VISIBLE
        terminalView.focusTerminal()
    }

    private fun setupDualPaneFileManager() {
        // Bind screens
        layoutTerminalScreen = findViewById(R.id.layout_terminal_screen)
        layoutFileManagerScreen = findViewById(R.id.layout_file_manager_screen)

        // Bind Left Panel
        layoutPanelLeft = findViewById(R.id.layout_panel_left)
        tvPanelLeftTitle = findViewById(R.id.tv_panel_left_title)
        tvCwdBannerLeft = findViewById(R.id.tv_cwd_banner_left)
        tvStorageCapacityLeft = findViewById(R.id.tv_storage_capacity_left)
        etFileFilterLeft = findViewById(R.id.et_file_filter_left)
        containerFileItemsLeft = findViewById(R.id.container_file_items_left)

        // Bind Right Panel
        layoutPanelRight = findViewById(R.id.layout_panel_right)
        tvPanelRightTitle = findViewById(R.id.tv_panel_right_title)
        tvCwdBannerRight = findViewById(R.id.tv_cwd_banner_right)
        tvStorageCapacityRight = findViewById(R.id.tv_storage_capacity_right)
        etFileFilterRight = findViewById(R.id.et_file_filter_right)
        containerFileItemsRight = findViewById(R.id.container_file_items_right)

        // Focus listeners to switch active panel
        layoutPanelLeft.setOnClickListener {
            setActivePanelFocus(PanelType.LEFT)
        }
        layoutPanelRight.setOnClickListener {
            setActivePanelFocus(PanelType.RIGHT)
        }

        // Sandbox & SD Card shortcuts
        findViewById<Button>(R.id.btn_go_sandbox_left).setOnClickListener {
            leftDirectory = viewModel.sandboxDirectory
            refreshPanel(PanelType.LEFT)
        }
        findViewById<Button>(R.id.btn_go_sdcard_left).setOnClickListener {
            checkAndNavigateToSdCardDualPane(PanelType.LEFT)
        }
        findViewById<Button>(R.id.btn_go_sandbox_right).setOnClickListener {
            rightDirectory = viewModel.sandboxDirectory
            refreshPanel(PanelType.RIGHT)
        }
        findViewById<Button>(R.id.btn_go_sdcard_right).setOnClickListener {
            checkAndNavigateToSdCardDualPane(PanelType.RIGHT)
        }

        // Live filters
        etFileFilterLeft.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshPanel(PanelType.LEFT)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etFileFilterRight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshPanel(PanelType.RIGHT)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Close / Back button
        findViewById<Button>(R.id.btn_close_file_manager).setOnClickListener {
            hideDualPaneFileManager()
        }

        // Action Buttons
        findViewById<Button>(R.id.btn_action_copy).setOnClickListener {
            performCopyAction()
        }
        findViewById<Button>(R.id.btn_action_move).setOnClickListener {
            performMoveAction()
        }
        findViewById<Button>(R.id.btn_action_rename).setOnClickListener {
            performRenameAction()
        }
        findViewById<Button>(R.id.btn_action_delete).setOnClickListener {
            performDeleteAction()
        }
        findViewById<Button>(R.id.btn_action_new_file).setOnClickListener {
            performNewFileDialog()
        }
        findViewById<Button>(R.id.btn_action_new_folder).setOnClickListener {
            performNewFolderDialog()
        }
    }

    private fun checkAndNavigateToSdCardDualPane(panel: PanelType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (panel == PanelType.LEFT) {
                    leftDirectory = viewModel.sdcardDirectory
                } else {
                    rightDirectory = viewModel.sdcardDirectory
                }
                refreshPanel(panel)
            } else {
                Toast.makeText(this, "Please grant SD Card access via settings", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val readPerm = android.Manifest.permission.READ_EXTERNAL_STORAGE
            val writePerm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, readPerm) == PackageManager.PERMISSION_GRANTED) {
                if (panel == PanelType.LEFT) {
                    leftDirectory = viewModel.sdcardDirectory
                } else {
                    rightDirectory = viewModel.sdcardDirectory
                }
                refreshPanel(panel)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(readPerm, writePerm), STORAGE_PERMISSION_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted. Tap SD Card button again to navigate.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModelState() {
        lifecycleScope.launch {
            viewModel.terminalOutputFlow.collect { outputText ->
                if (outputText == "\u001B[2J\u001B[H") {
                    terminalView.clearOutput()
                } else {
                    terminalView.appendOutput(outputText)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.fileList.collectLatest { _ ->
                if (leftDirectory.absolutePath.isNotEmpty()) {
                    refreshPanel(PanelType.LEFT)
                    refreshPanel(PanelType.RIGHT)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.editorFile.collectLatest { file ->
                if (file != null) {
                    showEditorDialog(file)
                } else {
                    dismissEditorDialog()
                }
            }
        }
    }

    private fun setActivePanelFocus(panel: PanelType) {
        activePanel = panel
        if (panel == PanelType.LEFT) {
            layoutPanelLeft.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#151719"))
                setStroke(3, Color.parseColor("#4D8BF5"))
            }
            layoutPanelRight.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#151719"))
                setStroke(0, Color.TRANSPARENT)
            }
            tvPanelLeftTitle.text = "◀ LEFT COLUMN (ACTIVE)"
            tvPanelLeftTitle.setTextColor(Color.parseColor("#A8C7FA"))
            tvPanelRightTitle.text = "RIGHT COLUMN"
            tvPanelRightTitle.setTextColor(Color.parseColor("#8E9199"))
        } else {
            layoutPanelRight.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#151719"))
                setStroke(3, Color.parseColor("#4D8BF5"))
            }
            layoutPanelLeft.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#151719"))
                setStroke(0, Color.TRANSPARENT)
            }
            tvPanelRightTitle.text = "RIGHT COLUMN (ACTIVE) ▶"
            tvPanelRightTitle.setTextColor(Color.parseColor("#A8C7FA"))
            tvPanelLeftTitle.text = "LEFT COLUMN"
            tvPanelLeftTitle.setTextColor(Color.parseColor("#8E9199"))
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + "B"
        return String.format("%.1f %s", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun refreshPanel(panel: PanelType) {
        val dir = if (panel == PanelType.LEFT) leftDirectory else rightDirectory
        if (dir.absolutePath.isEmpty()) return

        val container = if (panel == PanelType.LEFT) containerFileItemsLeft else containerFileItemsRight
        val banner = if (panel == PanelType.LEFT) tvCwdBannerLeft else tvCwdBannerRight
        val capacity = if (panel == PanelType.LEFT) tvStorageCapacityLeft else tvStorageCapacityRight
        val filterEt = if (panel == PanelType.LEFT) etFileFilterLeft else etFileFilterRight
        val filterQuery = filterEt.text.toString().trim()

        banner.text = dir.absolutePath

        try {
            val freeGb = dir.freeSpace / (1024.0 * 1024.0 * 1024.0)
            val totalGb = dir.totalSpace / (1024.0 * 1024.0 * 1024.0)
            capacity.text = String.format("Capacity: %.2f GB Free / %.2f GB Total", freeGb, totalGb)
        } catch (e: Exception) {
            capacity.text = "Capacity: Unknown"
        }

        val files = try {
            val list = dir.listFiles()?.toList() ?: emptyList()
            val filtered = if (filterQuery.isBlank()) {
                list
            } else {
                list.filter { it.name.contains(filterQuery, ignoreCase = true) }
            }
            filtered.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            )
        } catch (e: Exception) {
            emptyList()
        }

        container.removeAllViews()

        val parentDir = dir.parentFile
        if (parentDir != null && dir.absolutePath != "/" && dir.absolutePath != viewModel.sandboxDirectory.parentFile?.absolutePath) {
            val upLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1E2E42"))
                    cornerRadius = 8f
                    setStroke(2, Color.parseColor("#2B4C7E"))
                }
                setPadding(16, 12, 16, 12)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                
                setOnClickListener {
                    if (panel == PanelType.LEFT) {
                        leftDirectory = parentDir
                    } else {
                        rightDirectory = parentDir
                    }
                    refreshPanel(panel)
                }
            }

            val iconView = TextView(this).apply {
                text = "↩️"
                textSize = 14f
                setPadding(0, 0, 8, 0)
            }
            upLayout.addView(iconView)

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = ".. (Parent Directory)"
                setTextColor(Color.parseColor("#A8C7FA"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }
            textLayout.addView(nameView)

            upLayout.addView(textLayout)
            container.addView(upLayout)
        }

        if (files.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "This directory is empty."
                setTextColor(Color.parseColor("#8E9199"))
                textSize = 11f
                setPadding(0, 16, 0, 16)
                gravity = Gravity.CENTER
            }
            container.addView(emptyText)
            return
        }

        for (file in files) {
            val isSelected = if (panel == PanelType.LEFT) (selectedFileLeft == file) else (selectedFileRight == file)
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }
                
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isSelected) Color.parseColor("#223147") else Color.parseColor("#25282C"))
                    cornerRadius = 8f
                    setStroke(2, if (isSelected) Color.parseColor("#4D8BF5") else Color.parseColor("#3D4146"))
                }
                setPadding(16, 14, 16, 14)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                
                setOnClickListener {
                    setActivePanelFocus(panel)
                    if (file.isDirectory) {
                        if (panel == PanelType.LEFT) {
                            leftDirectory = file
                            selectedFileLeft = null
                        } else {
                            rightDirectory = file
                            selectedFileRight = null
                        }
                        refreshPanel(panel)
                    } else {
                        if (panel == PanelType.LEFT) {
                            selectedFileLeft = if (selectedFileLeft == file) null else file
                        } else {
                            selectedFileRight = if (selectedFileRight == file) null else file
                        }
                        refreshPanel(PanelType.LEFT)
                        refreshPanel(PanelType.RIGHT)
                    }
                }
                
                setOnLongClickListener {
                    setActivePanelFocus(panel)
                    if (panel == PanelType.LEFT) selectedFileLeft = file else selectedFileRight = file
                    refreshPanel(PanelType.LEFT)
                    refreshPanel(PanelType.RIGHT)
                    showFileOptionsMenu(file, panel)
                    true
                }
            }

            val extension = file.extension.lowercase()
            val iconEmoji = when {
                file.isDirectory -> "📁"
                extension in listOf("sh", "bash", "cmd", "bat", "bin") -> "⚙️"
                extension in listOf("txt", "md", "env", "conf", "prop", "properties") -> "📄"
                extension in listOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg") -> "🖼️"
                extension in listOf("json", "xml", "yaml", "yml", "ini") -> "🎛️"
                extension in listOf("zip", "rar", "7z", "tar", "gz", "bz2") -> "📦"
                else -> "📝"
            }

            val iconView = TextView(this).apply {
                text = iconEmoji
                textSize = 14f
                setPadding(0, 0, 8, 0)
            }
            itemLayout.addView(iconView)

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = file.name
                setTextColor(Color.parseColor("#E2E2E6"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }
            textLayout.addView(nameView)

            val detailView = TextView(this).apply {
                val sizeStr = if (file.isDirectory) "Folder" else formatFileSize(file.length())
                val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                val dateStr = sdf.format(java.util.Date(file.lastModified()))
                val r = if (file.canRead()) "r" else "-"
                val w = if (file.canWrite()) "w" else "-"
                val x = if (file.canExecute()) "x" else "-"
                text = "$sizeStr • $dateStr • [$r$w$x]"
                setTextColor(Color.parseColor("#8E9199"))
                textSize = 10f
            }
            textLayout.addView(detailView)

            itemLayout.addView(textLayout)

            val menuBtn = TextView(this).apply {
                text = "⋮"
                textSize = 14f
                setTextColor(Color.parseColor("#8E9199"))
                setPadding(12, 12, 12, 12)
                setOnClickListener {
                    setActivePanelFocus(panel)
                    if (panel == PanelType.LEFT) selectedFileLeft = file else selectedFileRight = file
                    refreshPanel(PanelType.LEFT)
                    refreshPanel(PanelType.RIGHT)
                    showFileOptionsMenu(file, panel)
                }
            }
            itemLayout.addView(menuBtn)

            container.addView(itemLayout)
        }
    }

    private fun showFileOptionsMenu(file: File, panel: PanelType) {
        val options = if (file.isDirectory) {
            arrayOf("Rename", "Delete")
        } else {
            arrayOf("Edit", "Rename", "Delete")
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                val adjustedWhich = if (file.isDirectory) which + 1 else which
                when (adjustedWhich) {
                    0 -> {
                        viewModel.openFileInEditor(file)
                    }
                    1 -> {
                        if (panel == PanelType.LEFT) selectedFileLeft = file else selectedFileRight = file
                        performRenameAction()
                    }
                    2 -> {
                        if (panel == PanelType.LEFT) selectedFileLeft = file else selectedFileRight = file
                        performDeleteAction()
                    }
                }
            }
            .show()
    }

    private fun performCopyAction() {
        val activeFile = if (activePanel == PanelType.LEFT) selectedFileLeft else selectedFileRight
        if (activeFile == null) {
            Toast.makeText(this, "Please select a file to copy first", Toast.LENGTH_SHORT).show()
            return
        }
        val destDir = if (activePanel == PanelType.LEFT) rightDirectory else leftDirectory
        val destFile = File(destDir, activeFile.name)
        
        if (destFile.exists()) {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Overwrite confirmation")
                .setMessage("File ${activeFile.name} already exists in destination. Overwrite?")
                .setPositiveButton("Overwrite") { _, _ ->
                    doCopy(activeFile, destFile)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doCopy(activeFile, destFile)
        }
    }

    private fun doCopy(src: File, dest: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (src.isDirectory) {
                    src.copyRecursively(dest, overwrite = true)
                } else {
                    src.copyTo(dest, overwrite = true)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Copied successfully", Toast.LENGTH_SHORT).show()
                    viewModel.appendOutput("\u001B[1;32m[Explorer] Copied ${src.name} to ${dest.parentFile?.name}\u001B[0m\r\n")
                    refreshPanel(PanelType.LEFT)
                    refreshPanel(PanelType.RIGHT)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Copy failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performMoveAction() {
        val activeFile = if (activePanel == PanelType.LEFT) selectedFileLeft else selectedFileRight
        if (activeFile == null) {
            Toast.makeText(this, "Please select a file to move first", Toast.LENGTH_SHORT).show()
            return
        }
        val destDir = if (activePanel == PanelType.LEFT) rightDirectory else leftDirectory
        val destFile = File(destDir, activeFile.name)

        if (destFile.exists()) {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Overwrite confirmation")
                .setMessage("File ${activeFile.name} already exists in destination. Overwrite?")
                .setPositiveButton("Overwrite") { _, _ ->
                    doMove(activeFile, destFile)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doMove(activeFile, destFile)
        }
    }

    private fun doMove(src: File, dest: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = src.renameTo(dest)
                if (!success) {
                    if (src.isDirectory) {
                        src.copyRecursively(dest, overwrite = true)
                    } else {
                        src.copyTo(dest, overwrite = true)
                    }
                    src.deleteRecursively()
                }
                withContext(Dispatchers.Main) {
                    if (activePanel == PanelType.LEFT) selectedFileLeft = null else selectedFileRight = null
                    Toast.makeText(this@MainActivity, "Moved successfully", Toast.LENGTH_SHORT).show()
                    viewModel.appendOutput("\u001B[1;32m[Explorer] Moved ${src.name} to ${dest.parentFile?.name}\u001B[0m\r\n")
                    refreshPanel(PanelType.LEFT)
                    refreshPanel(PanelType.RIGHT)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Move failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performDeleteAction() {
        val activeFile = if (activePanel == PanelType.LEFT) selectedFileLeft else selectedFileRight
        if (activeFile == null) {
            Toast.makeText(this, "Please select a file to delete first", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Delete")
            .setMessage("Are you sure you want to delete ${activeFile.name}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = activeFile.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        if (success) {
                            if (activePanel == PanelType.LEFT) selectedFileLeft = null else selectedFileRight = null
                            Toast.makeText(this@MainActivity, "Deleted successfully", Toast.LENGTH_SHORT).show()
                            viewModel.appendOutput("\u001B[1;32m[Explorer] Deleted: ${activeFile.name}\u001B[0m\r\n")
                            refreshPanel(PanelType.LEFT)
                            refreshPanel(PanelType.RIGHT)
                        } else {
                            Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performRenameAction() {
        val activeFile = if (activePanel == PanelType.LEFT) selectedFileLeft else selectedFileRight
        if (activeFile == null) {
            Toast.makeText(this, "Please select a file to rename first", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(activeFile.name)
            setTextColor(Color.WHITE)
            setSelection(activeFile.name.lastIndexOf('.').let { if (it > 0) it else activeFile.name.length })
        }
        
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Rename File")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val dest = File(activeFile.parentFile, newName)
                        val success = activeFile.renameTo(dest)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                if (activePanel == PanelType.LEFT) selectedFileLeft = dest else selectedFileRight = dest
                                Toast.makeText(this@MainActivity, "Renamed successfully", Toast.LENGTH_SHORT).show()
                                viewModel.appendOutput("\u001B[1;32m[Explorer] Renamed ${activeFile.name} to $newName\u001B[0m\r\n")
                                refreshPanel(PanelType.LEFT)
                                refreshPanel(PanelType.RIGHT)
                            } else {
                                Toast.makeText(this@MainActivity, "Rename failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performNewFileDialog() {
        val dir = if (activePanel == PanelType.LEFT) leftDirectory else rightDirectory
        val input = EditText(this).apply {
            setHint("file.txt")
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("New File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val file = File(dir, name)
                        if (!file.exists()) {
                            try {
                                file.createNewFile()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "File created", Toast.LENGTH_SHORT).show()
                                    viewModel.appendOutput("\u001B[1;32m[Explorer] Created file: $name\u001B[0m\r\n")
                                    refreshPanel(PanelType.LEFT)
                                    refreshPanel(PanelType.RIGHT)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Creation failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performNewFolderDialog() {
        val dir = if (activePanel == PanelType.LEFT) leftDirectory else rightDirectory
        val input = EditText(this).apply {
            setHint("Folder Name")
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val folder = File(dir, name)
                        if (!folder.exists()) {
                            val success = folder.mkdirs()
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(this@MainActivity, "Folder created", Toast.LENGTH_SHORT).show()
                                    viewModel.appendOutput("\u001B[1;32m[Explorer] Created folder: $name\u001B[0m\r\n")
                                    refreshPanel(PanelType.LEFT)
                                    refreshPanel(PanelType.RIGHT)
                                } else {
                                    Toast.makeText(this@MainActivity, "Creation failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditorDialog(file: File) {
        if (activeEditorDialog != null) return

        val view = layoutInflater.inflate(R.layout.dialog_editor, null)
        val tvFilename = view.findViewById<TextView>(R.id.tv_editor_filename)
        val etContent = view.findViewById<EditText>(R.id.et_editor_content)
        val btnSave = view.findViewById<Button>(R.id.btn_editor_save)
        val btnClose = view.findViewById<Button>(R.id.btn_editor_close)

        tvFilename.text = "Editing: ${file.name}"
        etContent.setText(viewModel.editorContent.value)

        // Setup real-time content tracker flow
        lifecycleScope.launch {
            viewModel.editorContent.collectLatest { text ->
                if (etContent.text.toString() != text) {
                    etContent.setText(text)
                }
            }
        }

        btnSave.setOnClickListener {
            viewModel.updateEditorContent(etContent.text.toString())
            viewModel.saveEditorContent()
        }

        btnClose.setOnClickListener {
            viewModel.closeEditor()
        }

        activeEditorDialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(view)
            .setCancelable(false)
            .create().apply {
                show()
            }
    }

    private fun dismissEditorDialog() {
        activeEditorDialog?.dismiss()
        activeEditorDialog = null
        terminalView.focusTerminal()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissEditorDialog()
    }

    private val PICK_ZIP_REQUEST_CODE = 4096

    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }

    private fun showCoreutilsSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            setBackgroundColor(Color.parseColor("#1A1C1E"))
        }

        val titleView = TextView(this).apply {
            text = "⚙️ Rust Coreutils Binary Manager"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8.dpToPx())
        }
        layout.addView(titleView)

        val isInstalled = CoreutilsManager.isInstalled(this)
        val statusText = if (isInstalled) {
            val count = CoreutilsManager.getInstalledCommandCount(this)
            val ver = CoreutilsManager.getInstalledVersion(this)
            "🟢 Status: INSTALLED\n⚡ Commands: $count commands mapped\nℹ️ Version: $ver"
        } else {
            "🔴 Status: NOT INSTALLED\n(Currently using system default toybox)"
        }

        val statusView = TextView(this).apply {
            text = statusText
            setTextColor(if (isInstalled) Color.parseColor("#81C784") else Color.parseColor("#E57373"))
            textSize = 14f
            setPadding(0, 0, 0, 16.dpToPx())
        }
        layout.addView(statusView)

        val pickBtn = Button(this).apply {
            text = "📁 Select downloaded ZIP file..."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2D2F31"))
            setOnClickListener {
                pickCoreutilsZip()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 12.dpToPx())
            }
        }
        layout.addView(pickBtn)

        val zipFiles = findZipFilesInSandbox()
        if (zipFiles.isNotEmpty()) {
            val detectedHeader = TextView(this).apply {
                text = "Detected ZIP in Sandbox:"
                setTextColor(Color.parseColor("#A8C7FA"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
            }
            layout.addView(detectedHeader)

            for (zipFile in zipFiles) {
                val installSandboxBtn = Button(this).apply {
                    text = "📦 Install: ${zipFile.name}"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#381E72"))
                    setOnClickListener {
                        installCoreutilsFromSandboxFile(zipFile)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 8.dpToPx())
                    }
                }
                layout.addView(installSandboxBtn)
            }
        }

        val urlHeader = TextView(this).apply {
            text = "Or Download from Direct Link:"
            setTextColor(Color.parseColor("#C4C6CF"))
            textSize = 12f
            setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
        }
        layout.addView(urlHeader)

        val urlInput = EditText(this).apply {
            hint = "https://example.com/coreutils.zip"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }
        layout.addView(urlInput)

        val downloadBtn = Button(this).apply {
            text = "⬇️ Download & Install"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00796B"))
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    downloadAndInstallCoreutils(url)
                } else {
                    Toast.makeText(this@MainActivity, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8.dpToPx(), 0, 16.dpToPx())
            }
        }
        layout.addView(downloadBtn)

        if (isInstalled) {
            val uninstallBtn = Button(this).apply {
                text = "🗑️ Restore to Default (Uninstall)"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("Confirm Restore")
                        .setMessage("Are you sure you want to uninstall custom coreutils and restore to default system toybox?")
                        .setPositiveButton("Restore") { _, _ ->
                            CoreutilsManager.uninstall(this@MainActivity)
                            viewModel.restartSession()
                            viewModel.appendOutput("\r\n\u001B[1;33m[Coreutils] Custom coreutils uninstalled. Restored to default toybox.\u001B[0m\r\n")
                            Toast.makeText(this@MainActivity, "Restored to default toybox", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            layout.addView(uninstallBtn)
        }

        val scrollView = ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun pickCoreutilsZip() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_ZIP_REQUEST_CODE)
    }

    private fun findZipFilesInSandbox(): List<File> {
        val sandboxDir = File(filesDir, "workspace")
        if (!sandboxDir.exists()) return emptyList()
        return sandboxDir.listFiles { f -> f.name.endsWith(".zip", ignoreCase = true) }?.toList() ?: emptyList()
    }

    private fun installCoreutilsFromSandboxFile(file: File) {
        val progressDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setMessage("Installing Coreutils from Sandbox...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = CoreutilsManager.installFromZipFile(this@MainActivity, file)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (result.isSuccess) {
                    viewModel.restartSession()
                    viewModel.appendOutput("\r\n\u001B[1;32m[Coreutils] Custom coreutils binary successfully installed from Sandbox! Shell session restarted.\u001B[0m\r\n")
                    Toast.makeText(this@MainActivity, "Coreutils installed successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                    viewModel.appendOutput("\r\n\u001B[1;31m[Coreutils] Installation from Sandbox failed: $errorMsg\u001B[0m\r\n")
                    AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("Installation Failed")
                        .setMessage(errorMsg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun installCoreutilsFromUri(uri: Uri) {
        val progressDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setMessage("Installing Coreutils from ZIP...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "coreutils_temp.zip")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val result = CoreutilsManager.installFromZipFile(this@MainActivity, tempFile)
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (result.isSuccess) {
                        viewModel.restartSession()
                        viewModel.appendOutput("\r\n\u001B[1;32m[Coreutils] Custom coreutils binary successfully installed! Shell session restarted.\u001B[0m\r\n")
                        Toast.makeText(this@MainActivity, "Coreutils installed successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                        viewModel.appendOutput("\r\n\u001B[1;31m[Coreutils] Installation failed: $errorMsg\u001B[0m\r\n")
                        AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Installation Failed")
                            .setMessage(errorMsg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("Error")
                        .setMessage(e.localizedMessage)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun downloadAndInstallCoreutils(urlString: String) {
        val progressDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setMessage("Downloading and installing Coreutils...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "coreutils_temp.zip")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                val url = java.net.URL(urlString)
                val connection = url.openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.getInputStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val result = CoreutilsManager.installFromZipFile(this@MainActivity, tempFile)
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (result.isSuccess) {
                        viewModel.restartSession()
                        viewModel.appendOutput("\r\n\u001B[1;32m[Coreutils] Custom coreutils binary successfully downloaded and installed! Shell session restarted.\u001B[0m\r\n")
                        Toast.makeText(this@MainActivity, "Coreutils installed successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                        viewModel.appendOutput("\r\n\u001B[1;31m[Coreutils] Downloaded binary installation failed: $errorMsg\u001B[0m\r\n")
                        AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Installation Failed")
                            .setMessage(errorMsg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("Download Failed")
                        .setMessage(e.localizedMessage)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_ZIP_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            installCoreutilsFromUri(uri)
        }
    }
}
