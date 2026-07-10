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
    private lateinit var tvItemsCount: TextView
    private lateinit var tvCwdBanner: TextView
    private lateinit var containerFileItems: LinearLayout
    private lateinit var tvStorageCapacity: TextView
    private lateinit var etFileFilter: EditText

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
        tvItemsCount = findViewById(R.id.tv_items_count)
        tvCwdBanner = findViewById(R.id.tv_cwd_banner)
        containerFileItems = findViewById(R.id.container_file_items)

        setupTerminalInput()
        setupDrawerNavigation()
        setupToolbarKeys()
        setupFileActionButtons()
        setupAdvancedFileExplorer()
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
        // Menu toggle button
        findViewById<Button>(R.id.btn_menu).setOnClickListener {
            toggleDrawer()
        }

        // Search button clears active terminal focus and re-requests it
        findViewById<Button>(R.id.btn_search).setOnClickListener {
            terminalView.focusTerminal()
        }

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

    private fun setupToolbarKeys() {
        // ESC: Send Esc character
        findViewById<Button>(R.id.btn_key_esc).setOnClickListener {
            viewModel.writeRawInput("\u001B")
            terminalView.focusTerminal()
            drawerLayout.closeDrawer(Gravity.LEFT) // Close drawer to resume terminal focus
        }

        // TAB: Send Tab character for instant shell completion
        findViewById<Button>(R.id.btn_key_tab).setOnClickListener {
            viewModel.writeRawInput("\t")
            terminalView.focusTerminal()
            drawerLayout.closeDrawer(Gravity.LEFT) // Close drawer to resume terminal focus
        }

        // CTRL: Send Ctrl+C to interrupt active processes
        findViewById<Button>(R.id.btn_key_ctrl).setOnClickListener {
            viewModel.writeRawInput("\u0003")
            terminalView.focusTerminal()
            drawerLayout.closeDrawer(Gravity.LEFT) // Close drawer to resume terminal focus
        }

        // ALT: Send alt help trigger
        findViewById<Button>(R.id.btn_key_alt).setOnClickListener {
            viewModel.writeRawInput("help\n")
            terminalView.focusTerminal()
            drawerLayout.closeDrawer(Gravity.LEFT) // Close drawer to resume terminal focus
        }

        // ▲: Send Up Arrow sequence to recall command history natively in shell
        findViewById<Button>(R.id.btn_key_up).setOnClickListener {
            viewModel.writeRawInput("\u001B[A")
            terminalView.focusTerminal()
            drawerLayout.closeDrawer(Gravity.LEFT) // Close drawer to resume terminal focus
        }

        // CLR: Clear terminal display & send CTRL+L character
        findViewById<Button>(R.id.btn_key_clear).setOnClickListener {
            terminalView.clearOutput()
            viewModel.writeRawInput("\u000C")
            terminalView.focusTerminal()
            drawerLayout.closeDrawer(Gravity.LEFT) // Close drawer to resume terminal focus
        }
    }

    private fun setupFileActionButtons() {
        // Visual Manager "New File" and "New Folder" triggers
        findViewById<Button>(R.id.btn_new_file).setOnClickListener {
            showNewFileDialog()
        }

        findViewById<Button>(R.id.btn_new_folder).setOnClickListener {
            showNewFolderDialog()
        }
    }

    private fun observeViewModelState() {
        // Collect real-time output stream from persistent background PTY shell
        lifecycleScope.launch {
            viewModel.terminalOutputFlow.collect { outputText ->
                if (outputText == "\u001B[2J\u001B[H") {
                    terminalView.clearOutput()
                } else {
                    terminalView.appendOutput(outputText)
                }
            }
        }

        // Collect current directory and derived files list to update Visual storage drawer
        lifecycleScope.launch {
            viewModel.fileList.collectLatest { files ->
                updateVisualFileList(files)
            }
        }

        // Collect visual text editor state
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

    private fun setupAdvancedFileExplorer() {
        tvStorageCapacity = findViewById(R.id.tv_storage_capacity)
        etFileFilter = findViewById(R.id.et_file_filter)

        // Sandbox Shortcut button
        findViewById<Button>(R.id.btn_go_sandbox).setOnClickListener {
            viewModel.navigateTo(viewModel.sandboxDirectory)
            terminalView.focusTerminal()
        }

        // SD Card Shortcut button
        findViewById<Button>(R.id.btn_go_sdcard).setOnClickListener {
            checkAndNavigateToSdCard()
        }

        // Filter edit text live search
        etFileFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setFilterQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun checkAndNavigateToSdCard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                viewModel.navigateTo(viewModel.sdcardDirectory)
                terminalView.focusTerminal()
            } else {
                showManageStorageExplanationDialog()
            }
        } else {
            val readPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            val writePerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (readPerm == PackageManager.PERMISSION_GRANTED && writePerm == PackageManager.PERMISSION_GRANTED) {
                viewModel.navigateTo(viewModel.sdcardDirectory)
                terminalView.focusTerminal()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    STORAGE_PERMISSION_CODE
                )
            }
        }
    }

    private fun showManageStorageExplanationDialog() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("SD Card Access Required")
            .setMessage("To fully explore, rename, delete, and modify files on your SD Card/External Storage, please enable 'All Files Access' for TermExplorer in the settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
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
            .setNegativeButton("Cancel") { _, _ ->
                viewModel.navigateTo(viewModel.sdcardDirectory)
                terminalView.focusTerminal()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.navigateTo(viewModel.sdcardDirectory)
                terminalView.focusTerminal()
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + "B"
        return String.format("%.1f %s", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun updateVisualFileList(files: List<File>) {
        containerFileItems.removeAllViews()
        
        tvItemsCount.text = "${files.size} items"
        
        val currentDir = viewModel.currentDirectory.value
        tvCwdBanner.text = currentDir.absolutePath

        // Update capacity metrics dynamically
        try {
            val freeGb = currentDir.freeSpace / (1024.0 * 1024.0 * 1024.0)
            val totalGb = currentDir.totalSpace / (1024.0 * 1024.0 * 1024.0)
            tvStorageCapacity.text = String.format("Capacity: %.2f GB Free / %.2f GB Total", freeGb, totalGb)
        } catch (e: Exception) {
            tvStorageCapacity.text = "Capacity: Unknown"
        }

        // Add Up (..) directory if parent exists
        val parentDir = currentDir.parentFile
        if (parentDir != null) {
            val upLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1E2E42"))
                    cornerRadius = 12f
                    setStroke(2, Color.parseColor("#2B4C7E"))
                }
                setPadding(24, 20, 24, 20)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                
                setOnClickListener {
                    viewModel.navigateUp()
                    terminalView.focusTerminal()
                }
            }

            val iconView = TextView(this).apply {
                text = "↩️"
                textSize = 18f
                setPadding(0, 0, 16, 0)
            }
            upLayout.addView(iconView)

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = ".. (Parent Directory)"
                setTextColor(Color.parseColor("#A8C7FA"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            textLayout.addView(nameView)

            val detailView = TextView(this).apply {
                text = "Tap to navigate up to: ${parentDir.name.ifEmpty { "/" }}"
                setTextColor(Color.parseColor("#8E9199"))
                textSize = 11f
            }
            textLayout.addView(detailView)

            upLayout.addView(textLayout)
            containerFileItems.addView(upLayout)
        }

        if (files.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "This directory is empty."
                setTextColor(Color.parseColor("#8E9199"))
                textSize = 12f
                setPadding(0, 32, 0, 32)
                gravity = Gravity.CENTER
            }
            containerFileItems.addView(emptyText)
            return
        }

        for (file in files) {
            // Create a gorgeous visual file card programmatically
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                
                // Redesigned modern flat background with thin border and rounded corners
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#25282C"))
                    cornerRadius = 12f
                    setStroke(2, Color.parseColor("#3D4146"))
                }
                setPadding(24, 24, 24, 24)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                
                setOnClickListener {
                    if (file.isDirectory) {
                        viewModel.navigateTo(file)
                        terminalView.focusTerminal()
                    } else {
                        viewModel.openFileInEditor(file)
                        drawerLayout.closeDrawer(Gravity.LEFT) // Close drawer to edit
                    }
                }
                
                setOnLongClickListener {
                    showFileOptionsMenu(file)
                    true
                }
            }

            // Custom colorful file icon based on file type / extension
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
                textSize = 18f
                setPadding(0, 0, 16, 0)
            }
            itemLayout.addView(iconView)

            // Name & Info Columns
            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = file.name
                setTextColor(Color.parseColor("#E2E2E6"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            textLayout.addView(nameView)

            // Enhanced File Info (Formatted size, Last modified, and Read/Write/Exec permissions)
            val detailView = TextView(this).apply {
                val sizeStr = if (file.isDirectory) "Folder" else formatFileSize(file.length())
                
                // Read last modified time
                val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                val dateStr = sdf.format(java.util.Date(file.lastModified()))
                
                // Get permissions string
                val r = if (file.canRead()) "r" else "-"
                val w = if (file.canWrite()) "w" else "-"
                val x = if (file.canExecute()) "x" else "-"
                val permStr = "[$r$w$x]"

                text = "$sizeStr  •  $dateStr  •  $permStr"
                setTextColor(Color.parseColor("#8E9199"))
                textSize = 11f
            }
            textLayout.addView(detailView)

            itemLayout.addView(textLayout)

            // Option button trigger
            val menuBtn = TextView(this).apply {
                text = "⋮"
                textSize = 16f
                setTextColor(Color.parseColor("#8E9199"))
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    showFileOptionsMenu(file)
                }
            }
            itemLayout.addView(menuBtn)

            containerFileItems.addView(itemLayout)
        }
    }

    private fun showFileOptionsMenu(file: File) {
        val options = arrayOf("Rename", "Delete")
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(file)
                    1 -> showDeleteConfirmation(file)
                }
            }
            .show()
    }

    private fun showRenameDialog(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setTextColor(Color.WHITE)
            setSelection(file.name.lastIndexOf('.').let { if (it > 0) it else file.name.length })
        }
        
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Rename File")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    viewModel.renameFileInExplorer(file, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(file: File) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Delete")
            .setMessage("Are you sure you want to delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteFileInExplorer(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewFileDialog() {
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
                    viewModel.createNewFileInExplorer(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewFolderDialog() {
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
                    viewModel.createNewFolderInExplorer(name)
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
