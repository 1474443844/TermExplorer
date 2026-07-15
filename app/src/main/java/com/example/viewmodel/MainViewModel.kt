package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.database.BookmarkEntity
import com.example.database.HistoryEntity
import com.example.database.TermRepository
import com.example.terminal.AnsiParser
import com.example.terminal.PtySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import android.os.Environment
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(
    application: Application,
    private val repository: TermRepository
) : AndroidViewModel(application) {

    // Current working directory state
    private val _currentDirectory = MutableStateFlow<File>(application.filesDir)
    val currentDirectory: StateFlow<File> = _currentDirectory.asStateFlow()

    // Real-time terminal output stream (emits new string blocks as they arrive)
    private val _terminalOutputFlow = MutableSharedFlow<String>(replay = 10)
    val terminalOutputFlow: SharedFlow<String> = _terminalOutputFlow.asSharedFlow()

    // Command Input buffer
    private val _inputBuffer = MutableStateFlow("")
    val inputBuffer: StateFlow<String> = _inputBuffer.asStateFlow()

    // Command execution status
    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    // Database Streams
    val recentHistory: StateFlow<List<HistoryEntity>> = repository.recentHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // File Manager derived file listing with live filter
    private val _filterQuery = MutableStateFlow("")
    val filterQuery: StateFlow<String> = _filterQuery.asStateFlow()

    val fileList: StateFlow<List<File>> = combine(_currentDirectory, _filterQuery) { dir, query ->
        try {
            val list = dir.listFiles()?.toList() ?: emptyList()
            val filtered = if (query.isBlank()) {
                list
            } else {
                list.filter { it.name.contains(query, ignoreCase = true) }
            }
            filtered.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            )
        } catch (e: Exception) {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilterQuery(query: String) {
        _filterQuery.value = query
    }

    val sandboxDirectory: File get() = workspaceDir
    val sdcardDirectory: File get() = Environment.getExternalStorageDirectory()

    // Visual Text Editor state
    private val _editorFile = MutableStateFlow<File?>(null)
    val editorFile: StateFlow<File?> = _editorFile.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    // Directory for initial workspace sandbox
    private val workspaceDir: File

    // Persistent interactive PTY-like shell session
    private var ptySession: PtySession? = null

    init {
        // Initialize sample sandbox directory in internal memory
        workspaceDir = File(application.filesDir, "workspace")
        setupWorkspaceSandbox()
        _currentDirectory.value = workspaceDir
        
        // Auto-install built-in coreutils if not already present
        com.example.terminal.CoreutilsManager.isInstalled(application)
        
        // Setup background PTY Session
        setupPtySession()

        // Welcome output
        appendOutput("\u001B[1;36mTermExplorer Pro v2.0 (Custom Views & PTY) Initialized.\u001B[0m\r\n")
        appendOutput("Current Workspace Sandbox: \u001B[1;32m${workspaceDir.absolutePath}\u001B[0m\r\n\r\n")
        
        // Read the README to output its content
        val readmeFile = File(workspaceDir, "README.txt")
        if (readmeFile.exists()) {
            appendOutput(readmeFile.readText() + "\r\n")
        }
    }

    private fun setupPtySession() {
        ptySession = PtySession(workspaceDir, getApplication<Application>()) { outputText ->
            appendOutput(outputText)
            // Automatically rescan the directory in case commands changed file structures
            viewModelScope.launch {
                refreshFileList()
            }
        }
    }

    private fun setupWorkspaceSandbox() {
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
            
            // 1. README.txt with beautiful ANSI colors
            File(workspaceDir, "README.txt").writeText("""
${"\u001B"}[1;36m====================================================
  _____                     ______            _                      
 |_   _|                    |  ____|          | |                     
   | |  ___ _ __ _ __ ___   | |__  __  _ __  | | ___  _ __ ___ _ __ 
   | | / _ \ '__| '_ ` _ \  |  __| \ \/ / '_ \| |/ _ \| '__/ _ \ '__|
   | ||  __/ |  | | | | | | | |____ >  <| |_) | | (_) | | |  __/ |   
   \_/ \___|_|  |_| |_| |_| |______/_/\_\ .__/|_|\___/|_|  \___|_|   
                                        | |                          
                                        |_|                          
====================================================${"\u001B"}[0m

Welcome to ${"\u001B"}[1;32mTermExplorer Pro${"\u001B"}[0m - A high-fidelity Terminal Emulator
& Visual File Manager designed beautifully using native Custom Views.

${"\u001B"}[1;33m[KEY ADVANTAGES]${"\u001B"}[0m
1. ${"\u001B"}[1mNative Interactive Shell (PTY)${"\u001B"}[0m: Real-time asynchronous
   duplex pipeline linked directly to system sh.
2. ${"\u001B"}[1mANSI Color & Styling${"\u001B"}[0m: Clean ANSI sequences parser
   supporting rich terminal colors and styles.
3. ${"\u001B"}[1mVisual Android Custom Controls${"\u001B"}[0m: Traditional High-Performance
   Views replacing Compose for lightweight and snappy rendering.

${"\u001B"}[1;34m[COOL SHELL COMMANDS TO TRY]${"\u001B"}[0m
- ${"\u001B"}[96mls -la${"\u001B"}[0m          : List files in detail.
- ${"\u001B"}[96mgetprop | grep version${"\u001B"}[0m : Query Android system properties.
- ${"\u001B"}[96mdf -h${"\u001B"}[0m          : Show storage space details.
- ${"\u001B"}[96mhelp${"\u001B"}[0m          : View help instructions.

Enjoy hacking away!
            """.trimIndent())

            // 2. Sample Scripts directory and executable
            val scriptsDir = File(workspaceDir, "scripts")
            scriptsDir.mkdirs()
            File(scriptsDir, "hello.sh").writeText("""
#!/system/bin/sh
echo "${"\u001B"}[1;32mHello, TermExplorer User!${"\u001B"}[0m"
echo "Current date: ${"\u001B"}[1;33m$(date)${"\u001B"}[0m"
echo "Device model: ${"\u001B"}[1;35m$(getprop ro.product.model)${"\u001B"}[0m"
echo "${"\u001B"}[1;36mAll systems green!${"\u001B"}[0m"
            """.trimIndent())

            // 3. Sample Notes directory with txt list
            val notesDir = File(workspaceDir, "notes")
            notesDir.mkdirs()
            File(notesDir, "todo.txt").writeText("""
[✓] Implement interactive custom terminal prompt
[✓] Build visual file cards with file operations
[✓] Style terminal toolbar with fast command shortcuts
[✓] Integrate Room DB for shell history persistence
[ ] Add adaptive layout for foldable and tablet displays
            """.trimIndent())

            // 4. Sample .bashrc configuration file
            File(workspaceDir, ".bashrc").writeText("""
# Welcome to your TermExplorer BashRC file!
# You can define custom environment variables, aliases, or functions here.
# It will be sourced automatically whenever a new terminal session starts.

# Custom Environment Variables
# export MY_VAR="Awesome Terminal"

# Custom Command Aliases
alias c='clear'
alias h='history'
alias welcome='echo "Happy hacking! :)"'
            """.trimIndent())
        }
    }

    fun setInputBuffer(input: String) {
        _inputBuffer.value = input
    }

    fun appendOutput(text: String) {
        viewModelScope.launch {
            _terminalOutputFlow.emit(text)
        }
    }

    // Main execution endpoint via persistent PTY Session
    fun executeCommand(commandRaw: String) {
        val command = commandRaw.trim()
        if (command.isEmpty()) return

        viewModelScope.launch {
            _isExecuting.value = true
            
            // Insert command into DB history
            repository.insertHistory(command)
            _inputBuffer.value = ""

            // Sync visual directory in case of custom 'cd' or similar commands
            val parts = command.split("\\s+".toRegex())
            val cmdName = parts[0].lowercase()

            if (cmdName == "cd") {
                handleCdCommand(parts)
            } else if (cmdName == "clear" || cmdName == "cls") {
                // We'll let the view clear itself on clear signal
                _terminalOutputFlow.emit("\u001B[2J\u001B[H")
            }

            // Send command raw line down the PTY pipe!
            ptySession?.write(command + "\n")
            
            _isExecuting.value = false
        }
    }

    fun writeRawInput(raw: String) {
        ptySession?.write(raw)
    }

    fun resizeTerminal(rows: Int, cols: Int) {
        ptySession?.resize(rows, cols)
    }

    private fun handleCdCommand(parts: List<String>) {
        if (parts.size < 2) {
            _currentDirectory.value = workspaceDir
            return
        }
        val targetPath = parts[1]
        val targetFile = if (targetPath.startsWith("/")) {
            File(targetPath)
        } else {
            File(_currentDirectory.value, targetPath)
        }

        val canonicalFile = try { targetFile.canonicalFile } catch (e: Exception) { targetFile }

        if (canonicalFile.exists() && canonicalFile.isDirectory) {
            _currentDirectory.value = canonicalFile
        }
    }

    // File Manager Operations
    fun navigateUp() {
        val parent = _currentDirectory.value.parentFile
        if (parent != null) {
            _currentDirectory.value = parent
            ptySession?.updateWorkingDirectory(parent)
        }
    }

    fun navigateTo(directory: File) {
        if (directory.isDirectory) {
            _currentDirectory.value = directory
            ptySession?.updateWorkingDirectory(directory)
        }
    }

    fun createNewFileInExplorer(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(_currentDirectory.value, name)
            if (!file.exists()) {
                try {
                    file.createNewFile()
                    withContext(Dispatchers.Main) {
                        refreshFileList()
                        appendOutput("\u001B[1;32m[Explorer] Created file: $name\u001B[0m\r\n")
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "File creation failed", e)
                }
            }
        }
    }

    fun createNewFolderInExplorer(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(_currentDirectory.value, name)
            if (!dir.exists()) {
                val success = dir.mkdirs()
                if (success) {
                    withContext(Dispatchers.Main) {
                        refreshFileList()
                        appendOutput("\u001B[1;32m[Explorer] Created folder: $name\u001B[0m\r\n")
                    }
                }
            }
        }
    }

    fun deleteFileInExplorer(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = file.deleteRecursively()
            if (success) {
                withContext(Dispatchers.Main) {
                    refreshFileList()
                    appendOutput("\u001B[1;32m[Explorer] Deleted: ${file.name}\u001B[0m\r\n")
                }
            }
        }
    }

    fun renameFileInExplorer(file: File, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dest = File(file.parentFile, newName)
            val success = file.renameTo(dest)
            if (success) {
                withContext(Dispatchers.Main) {
                    refreshFileList()
                    appendOutput("\u001B[1;32m[Explorer] Renamed ${file.name} to $newName\u001B[0m\r\n")
                }
            }
        }
    }

    fun openFileInEditor(file: File) {
        _editorFile.value = file
        viewModelScope.launch(Dispatchers.IO) {
            val content = try {
                file.readText()
            } catch (e: Exception) {
                "Error reading file content: ${e.localizedMessage}"
            }
            withContext(Dispatchers.Main) {
                _editorContent.value = content
            }
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
                    appendOutput("\u001B[1;32m[Explorer] Saved file: ${file.name}\u001B[0m\r\n")
                    closeEditor()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("\u001B[1;31m[Explorer] Failed to save file: ${e.localizedMessage}\u001B[0m\r\n")
                }
            }
        }
    }

    fun closeEditor() {
        _editorFile.value = null
        _editorContent.value = ""
    }

    fun refreshFileList() {
        // Trigger listFiles update by re-setting currentDirectory
        val current = _currentDirectory.value
        _currentDirectory.value = current
    }

    fun restartSession() {
        ptySession?.destroy()
        setupPtySession()
    }

    override fun onCleared() {
        super.onCleared()
        ptySession?.destroy()
    }
}

// Custom ViewModel Factory as we require an Application context and Database Repository
class MainViewModelFactory(
    private val application: Application,
    private val repository: TermRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
