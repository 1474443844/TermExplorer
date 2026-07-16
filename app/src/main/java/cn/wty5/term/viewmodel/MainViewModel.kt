package cn.wty5.term.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wty5.term.database.TermRepository
import cn.wty5.term.terminal.AnsiParser
import cn.wty5.term.terminal.CoreutilsManager
import cn.wty5.term.terminal.PtySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Terminal-focused ViewModel.
 *
 * File-manager UI now lives in [cn.wty5.term.FileManagerActivity] and no longer
 * depends on this class. Responsibilities here:
 *  - own the interactive [PtySession]
 *  - stream PTY output to the terminal view
 *  - app-side command history (arrow-up / arrow-down)
 *  - optional in-app text editor state (still used by MainActivity)
 */
class MainViewModel(
    application: Application,
    private val repository: TermRepository
) : AndroidViewModel(application) {

    // -------------------------------------------------------------------------
    // Terminal I/O
    // -------------------------------------------------------------------------

    /**
     * Hot path: PTY reader threads call [appendOutput] frequently.
     * Use a buffered SharedFlow + [tryEmit] so we don't spawn a coroutine per chunk.
     */
    private val _terminalOutputFlow = MutableSharedFlow<String>(
        replay = 8,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val terminalOutputFlow: SharedFlow<String> = _terminalOutputFlow.asSharedFlow()

    private val workspaceDir: File = File(application.filesDir, "workspace")
    private var ptySession: PtySession? = null

    // -------------------------------------------------------------------------
    // Optional in-app editor (MainActivity dialog)
    // -------------------------------------------------------------------------

    private val _editorFile = MutableStateFlow<File?>(null)
    val editorFile: StateFlow<File?> = _editorFile.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    // -------------------------------------------------------------------------
    // App-side command history
    //
    // Even with readline-enabled bash, intercepting ESC[A/B keeps history shared
    // with Room and avoids relying solely on shell-local history.
    // -------------------------------------------------------------------------

    private val commandHistory = ArrayList<String>()
    private var historyIndex = 0
    private val liveLine = StringBuilder()
    private var savedLiveLine: String = ""

    init {
        setupWorkspaceSandbox()
        // Touch once so bundled coreutils are linked on first run when present.
        CoreutilsManager.isInstalled(application)
        seedCommandHistory()
        setupPtySession()
        emitWelcome()
    }

    // -------------------------------------------------------------------------
    // Public terminal API
    // -------------------------------------------------------------------------

    fun appendOutput(text: String) {
        if (text.isEmpty()) return
        // Fast path from PTY reader / any thread.
        if (!_terminalOutputFlow.tryEmit(text)) {
            // Extremely rare with DROP_OLDEST; keep a suspend fallback.
            viewModelScope.launch { _terminalOutputFlow.emit(text) }
        }
    }

    fun writeRawInput(raw: String) {
        if (raw.isEmpty()) return
        when (raw) {
            "\u001B[A" -> {
                historyPrev()
                return
            }
            "\u001B[B" -> {
                historyNext()
                return
            }
        }
        trackLiveLine(raw)
        ptySession?.write(raw)
    }

    fun resizeTerminal(rows: Int, cols: Int) {
        if (rows > 0 && cols > 0) {
            ptySession?.resize(rows, cols)
        }
    }

    fun restartSession() {
        ptySession?.destroy()
        ptySession = null
        liveLine.clear()
        savedLiveLine = ""
        historyIndex = commandHistory.size
        AnsiParser.reset()
        setupPtySession()
    }

    // -------------------------------------------------------------------------
    // Editor API (kept for MainActivity dialog)
    // -------------------------------------------------------------------------

    fun openFileInEditor(file: File) {
        _editorFile.value = file
        viewModelScope.launch(Dispatchers.IO) {
            val content = try {
                file.readText()
            } catch (e: Exception) {
                "Error reading file content: ${e.localizedMessage}"
            }
            _editorContent.value = content
        }
    }

    fun updateEditorContent(content: String) {
        _editorContent.value = content
    }

    fun saveEditorContent() {
        val file = _editorFile.value ?: return
        val content = _editorContent.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                file.writeText(content)
                withContext(Dispatchers.Main) {
                    appendOutput("\u001B[1;32m[Editor] Saved: ${file.name}\u001B[0m\r\n")
                    closeEditor()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput(
                        "\u001B[1;31m[Editor] Save failed: ${e.localizedMessage}\u001B[0m\r\n"
                    )
                }
            }
        }
    }

    fun closeEditor() {
        _editorFile.value = null
        _editorContent.value = ""
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    private fun setupPtySession() {
        ptySession = PtySession(workspaceDir, getApplication()) { outputText ->
            appendOutput(outputText)
        }
    }

    private fun seedCommandHistory() {
        viewModelScope.launch {
            // One-shot load; avoid a permanent collector that rewrites history forever.
            val list = try {
                repository.recentHistory.first()
            } catch (_: Exception) {
                emptyList()
            }
            if (commandHistory.isEmpty() && list.isNotEmpty()) {
                // DB returns newest first → reverse for chronological order.
                list.asReversed().forEach { entity ->
                    val cmd = entity.command.trim()
                    if (cmd.isNotEmpty() && commandHistory.lastOrNull() != cmd) {
                        commandHistory.add(cmd)
                    }
                }
                historyIndex = commandHistory.size
            }
        }
    }

    private fun emitWelcome() {
        appendOutput(
            "\u001B[1;36mTermExplorer Pro v2.0 (Custom Views & PTY) Initialized.\u001B[0m\r\n"
        )
        appendOutput(
            "Workspace: \u001B[1;32m${workspaceDir.absolutePath}\u001B[0m\r\n"
        )
        appendOutput(
            "Tip: press \u001B[1;33mfm\u001B[0m to open Dual-Pane Explorer.\r\n\r\n"
        )
        val readme = File(workspaceDir, "README.txt")
        if (readme.exists()) {
            viewModelScope.launch(Dispatchers.IO) {
                val text = try {
                    readme.readText()
                } catch (_: Exception) {
                    null
                }
                if (!text.isNullOrEmpty()) {
                    appendOutput(text + "\r\n")
                }
            }
        }
    }

    private fun setupWorkspaceSandbox() {
        if (workspaceDir.exists()) return
        workspaceDir.mkdirs()

        File(workspaceDir, "README.txt").writeText(
            """
            ${"\u001B"}[1;36m====================================================
              TermExplorer Pro
            ====================================================${"\u001B"}[0m

            Welcome to ${"\u001B"}[1;32mTermExplorer Pro${"\u001B"}[0m.

            ${"\u001B"}[1;33m[Tips]${"\u001B"}[0m
            - ${"\u001B"}[96mls -la${"\u001B"}[0m     list files
            - ${"\u001B"}[96mdf -h${"\u001B"}[0m       disk usage
            - ${"\u001B"}[96mfm key${"\u001B"}[0m      open dual-pane explorer
            - ${"\u001B"}[96mss key${"\u001B"}[0m      restart shell session

            Enjoy!
            """.trimIndent()
        )

        val scriptsDir = File(workspaceDir, "scripts").also { it.mkdirs() }
        File(scriptsDir, "hello.sh").writeText(
            """
            #!/system/bin/sh
            echo "${"\u001B"}[1;32mHello, TermExplorer User!${"\u001B"}[0m"
            echo "Date: ${"\u001B"}[1;33m$(date)${"\u001B"}[0m"
            echo "Model: ${"\u001B"}[1;35m$(getprop ro.product.model)${"\u001B"}[0m"
            """.trimIndent()
        )

        val notesDir = File(workspaceDir, "notes").also { it.mkdirs() }
        File(notesDir, "todo.txt").writeText(
            """
            [✓] Interactive PTY terminal
            [✓] Dual-pane file manager (separate Activity)
            [✓] Room-backed command history
            [ ] Foldable / tablet layout
            """.trimIndent()
        )

        File(workspaceDir, ".bashrc").writeText(
            """
            # Optional user overrides (sourced by private shell rc).
            alias c='clear'
            alias h='history'
            """.trimIndent()
        )
    }

    // -------------------------------------------------------------------------
    // Live-line tracking + history recall
    // -------------------------------------------------------------------------

    private fun trackLiveLine(raw: String) {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\r' || c == '\n' -> {
                    commitLiveLineToHistory()
                    if (c == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') i++
                }
                c == '\u007F' || c == '\b' -> {
                    if (liveLine.isNotEmpty()) {
                        liveLine.deleteCharAt(liveLine.length - 1)
                    }
                    historyIndex = commandHistory.size
                }
                c == '\u0003' -> {
                    // Ctrl+C cancels the current line
                    liveLine.clear()
                    historyIndex = commandHistory.size
                    savedLiveLine = ""
                }
                c == '\u001B' -> {
                    // Swallow remaining CSI / escape sequence without corrupting liveLine
                    i++
                    if (i < raw.length && raw[i] == '[') {
                        i++
                        while (i < raw.length) {
                            val ch = raw[i]
                            if (ch in '@'..'~') break
                            i++
                        }
                    }
                }
                c.code < 32 -> Unit // other controls
                else -> {
                    liveLine.append(c)
                    historyIndex = commandHistory.size
                }
            }
            i++
        }
    }

    private fun commitLiveLineToHistory() {
        val cmd = liveLine.toString().trim()
        liveLine.clear()
        savedLiveLine = ""
        if (cmd.isNotEmpty() && commandHistory.lastOrNull() != cmd) {
            commandHistory.add(cmd)
            viewModelScope.launch {
                repository.insertHistory(cmd)
            }
        }
        historyIndex = commandHistory.size
    }

    private fun historyPrev() {
        if (commandHistory.isEmpty()) return
        if (historyIndex == commandHistory.size) {
            savedLiveLine = liveLine.toString()
        }
        if (historyIndex > 0) {
            historyIndex--
            replaceCurrentLine(commandHistory[historyIndex])
        }
    }

    private fun historyNext() {
        if (commandHistory.isEmpty()) return
        when {
            historyIndex < commandHistory.size - 1 -> {
                historyIndex++
                replaceCurrentLine(commandHistory[historyIndex])
            }
            historyIndex == commandHistory.size - 1 -> {
                historyIndex = commandHistory.size
                replaceCurrentLine(savedLiveLine)
            }
        }
    }

    /**
     * Rewrite the shell's current input line.
     * With ICANON, Ctrl+U (VKILL) clears the whole canonical line at the kernel
     * level — works with or without bash readline.
     */
    private fun replaceCurrentLine(command: String) {
        liveLine.clear()
        liveLine.append(command)
        // \u0015 = Ctrl+U (kill line), then write the recalled command
        ptySession?.write("\u0015" + command)
    }

    override fun onCleared() {
        super.onCleared()
        ptySession?.destroy()
        ptySession = null
    }
}

class MainViewModelFactory(
    private val application: Application,
    private val repository: TermRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
