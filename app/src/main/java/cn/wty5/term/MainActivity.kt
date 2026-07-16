package cn.wty5.term

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import cn.wty5.term.database.TermDatabase
import cn.wty5.term.database.TermRepository
import cn.wty5.term.terminal.CoreutilsManager
import cn.wty5.term.ui.views.TerminalView
import cn.wty5.term.viewmodel.MainViewModel
import cn.wty5.term.viewmodel.MainViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout

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
        ).fallbackToDestructiveMigration(false)
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
        observeViewModelState()
    }

    private fun setupTerminalInput() {
        terminalView.onInputListener = { inputString ->
            viewModel.writeRawInput(inputString)
        }

        terminalView.onSizeChangedListener = { rows, cols ->
            viewModel.resizeTerminal(rows, cols)
        }

        terminalView.post {
            terminalView.focusTerminal()
        }
    }

    private fun setupDrawerNavigation() {
        findViewById<Button>(R.id.btn_coreutils_setup).setOnClickListener {
            showCoreutilsSetupDialog()
        }
    }

    private fun toggleDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun updateModifierButtonStates() {
        val ctrlBtn = findViewById<Button>(R.id.btn_key_ctrl)
        val altBtn = findViewById<Button>(R.id.btn_key_alt)
        val shiftBtn = findViewById<Button>(R.id.btn_key_shift)

        if (terminalView.isCtrlActive) {
            ctrlBtn.setBackgroundColor("#381E72".toColorInt())
            ctrlBtn.setTextColor(Color.WHITE)
        } else {
            ctrlBtn.setBackgroundColor("#252729".toColorInt())
            ctrlBtn.setTextColor("#C4C6CF".toColorInt())
        }

        if (terminalView.isAltActive) {
            altBtn.setBackgroundColor("#381E72".toColorInt())
            altBtn.setTextColor(Color.WHITE)
        } else {
            altBtn.setBackgroundColor("#252729".toColorInt())
            altBtn.setTextColor("#C4C6CF".toColorInt())
        }

        if (terminalView.isShiftActive) {
            shiftBtn.setBackgroundColor("#381E72".toColorInt())
            shiftBtn.setTextColor(Color.WHITE)
        } else {
            shiftBtn.setBackgroundColor("#252729".toColorInt())
            shiftBtn.setTextColor("#C4C6CF".toColorInt())
        }
    }

    private fun setupToolbarKeys() {
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
            FileManagerActivity.start(this)
        }

        findViewById<Button>(R.id.btn_key_sb).setOnClickListener {
            toggleDrawer()
        }

        findViewById<Button>(R.id.btn_key_ss).setOnClickListener {
            viewModel.restartSession()
            Toast.makeText(this, "Terminal Session switched/restarted!", Toast.LENGTH_SHORT).show()
            terminalView.focusTerminal()
        }

        terminalView.onModifiersChangedListener = {
            updateModifierButtonStates()
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
            viewModel.editorFile.collectLatest { file ->
                if (file != null) {
                    showEditorDialog(file)
                } else {
                    dismissEditorDialog()
                }
            }
        }
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

        activeEditorDialog =
            AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
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
                    Toast.makeText(
                        this@MainActivity,
                        "Please enter a valid URL",
                        Toast.LENGTH_SHORT
                    ).show()
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
                            Toast.makeText(
                                this@MainActivity,
                                "Restored to default toybox",
                                Toast.LENGTH_SHORT
                            ).show()
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
        return sandboxDir.listFiles { f -> f.name.endsWith(".zip", ignoreCase = true) }?.toList()
            ?: emptyList()
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
                    Toast.makeText(
                        this@MainActivity,
                        "Coreutils installed successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
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
                        Toast.makeText(
                            this@MainActivity,
                            "Coreutils installed successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                        viewModel.appendOutput("\r\n\u001B[1;31m[Coreutils] Installation failed: $errorMsg\u001B[0m\r\n")
                        AlertDialog.Builder(
                            this@MainActivity,
                            AlertDialog.THEME_DEVICE_DEFAULT_DARK
                        )
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
                val url = URL(urlString)
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
                        Toast.makeText(
                            this@MainActivity,
                            "Coreutils installed successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                        viewModel.appendOutput("\r\n\u001B[1;31m[Coreutils] Downloaded binary installation failed: $errorMsg\u001B[0m\r\n")
                        AlertDialog.Builder(
                            this@MainActivity,
                            AlertDialog.THEME_DEVICE_DEFAULT_DARK
                        )
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
