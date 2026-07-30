package cn.wty5.term.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.wty5.term.terminal.CoreutilsManager
import cn.wty5.term.terminal.PtySession
import cn.wty5.term.terminal.TermConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Terminal-focused ViewModel.
 *
 * Command history is owned by bash readline (or the interactive shell).
 * This class does **not** intercept ESC[A/B] — arrow keys go straight to the PTY.
 *
 * Responsibilities:
 *  - own the interactive [PtySession]
 *  - stream PTY output to the terminal view
 *  - optional in-app text editor state (still used by MainActivity)
 */
class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    /**
     * Hot path: PTY reader threads call [appendOutput] frequently.
     * Buffered SharedFlow + [tryEmit] avoids spawning a coroutine per chunk.
     * No replay — the View owns the rendered buffer; replaying would re-parse
     * history into a fresh TerminalView and corrupt the screen.
     */
    private val _terminalOutputFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val terminalOutputFlow: SharedFlow<String> = _terminalOutputFlow.asSharedFlow()

    private var ptySession: PtySession? = null

    init {
        CoreutilsManager.isInstalled()
        setupPtySession()
        emitWelcome()
    }

    fun appendOutput(text: String) {
        if (text.isEmpty()) return
        if (!_terminalOutputFlow.tryEmit(text)) {
            viewModelScope.launch { _terminalOutputFlow.emit(text) }
        }
    }

    /**
     * Forward raw key/input bytes to the PTY unchanged.
     * Arrow-up/down are handled by bash readline (or shell line editing).
     */
    fun writeRawInput(raw: String) {
        if (raw.isEmpty()) return
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
        // AnsiParser lives on TerminalView now; MainActivity clears the view.
        setupPtySession()
    }

    private fun setupPtySession() {
        ptySession = PtySession(TermConfig.homeDir, getApplication()) { outputText ->
            appendOutput(outputText)
        }
    }

    private fun emitWelcome() {
        appendOutput(
            """
            ${"\u001B"}[1;36m${"=".repeat(40)}
             _____                                 
            |_   _|                      ___       
              | | ___ _ __ _ __ ___     ( _ )      
              | |/ _ \ '__| '_ ` _ \    / _ \/\    
              | |  __/ |  | | | | | |  | (_>  <    
              \_/\___|_|  |_| |_| |_|   \___/\/    
             _____           _                     
            |  ___|         | |                    
            | |____  ___ __ | | ___  _ __ ___ _ __ 
            |  __\ \/ / '_ \| |/ _ \| '__/ _ \ '__|
            | |___>  <| |_) | | (_) | | |  __/ |   
            \____/_/\_\ .__/|_|\___/|_|  \___|_|   
                      | |                          
                      |_|                          
            ${"=".repeat(40)}${"\u001B"}[0m

            Welcome to ${"\u001B"}[1;32mTermExplorer${"\u001B"}[0m - A high-fidelity Terminal Emulator
            & Visual File Manager designed beautifully.

            ${"\u001B"}[1;34m[COOL SHELL COMMANDS TO TRY]${"\u001B"}[0m
            - ${"\u001B"}[96mls -la${"\u001B"}[0m          : List files in detail.
            - ${"\u001B"}[96mgetprop | grep version${"\u001B"}[0m : Query Android system properties.
            - ${"\u001B"}[96mdf -h${"\u001B"}[0m          : Show storage space details.
            - ${"\u001B"}[96mhelp${"\u001B"}[0m          : View help instructions.

            Enjoy hacking away!${"\r\n"}
            """.trimIndent()
        )
        appendOutput(
            "\u001B[1;36mTermExplorer Initialized.\u001B[0m\r\n"
        )
        appendOutput(
            "Tip: press \u001B[1;33mfm\u001B[0m to open FileManager.\r\n\r\n"
        )
    }

    override fun onCleared() {
        super.onCleared()
        ptySession?.destroy()
        ptySession = null
    }
}

class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
