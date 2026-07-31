package cn.wty5.term

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cn.wty5.term.MainActivity.Companion.KEY_REPEAT_INITIAL_MS
import cn.wty5.term.MainActivity.Companion.KEY_REPEAT_INTERVAL_MS
import cn.wty5.term.ui.views.TerminalView
import cn.wty5.term.viewmodel.MainViewModel
import cn.wty5.term.viewmodel.MainViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var terminalView: TerminalView
    private lateinit var drawerLayout: DrawerLayout


    // Long-press key repeat for navigation keys (↑↓←→ / PgUp / PgDn).
    private val keyRepeatHandler = Handler(Looper.getMainLooper())
    private var keyRepeatRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(application)
        )[MainViewModel::class.java]

        // Find and bind views
        terminalView = findViewById(R.id.terminal_view)
        drawerLayout = findViewById(R.id.drawer_layout)

        setupTerminalInput()
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

        // Navigation keys support long-press auto-repeat.
        bindRepeatKey(R.id.btn_key_up, "\u001B[A")
        bindRepeatKey(R.id.btn_key_down, "\u001B[B")
        bindRepeatKey(R.id.btn_key_right, "\u001B[C")
        bindRepeatKey(R.id.btn_key_left, "\u001B[D")
        bindRepeatKey(R.id.btn_key_pgup, "\u001B[5~")
        bindRepeatKey(R.id.btn_key_pgdn, "\u001B[6~")

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

        findViewById<Button>(R.id.btn_key_ctrl_c).setOnClickListener {
            viewModel.writeRawInput("\u0003")
            terminalView.focusTerminal()
        }

        findViewById<Button>(R.id.btn_key_pipe).setOnClickListener {
            viewModel.writeRawInput("|")
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
            terminalView.resetForNewSession()
            Toast.makeText(this, "Terminal Session switched/restarted!", Toast.LENGTH_SHORT).show()
            terminalView.focusTerminal()
        }

        terminalView.onModifiersChangedListener = {
            updateModifierButtonStates()
        }
    }

    /**
     * Bind a toolbar key that auto-repeats while pressed.
     * First fire is immediate; after [KEY_REPEAT_INITIAL_MS] it repeats every
     * [KEY_REPEAT_INTERVAL_MS] until finger up / cancel.
     */
    private fun bindRepeatKey(buttonId: Int, sequence: String) {
        val button = findViewById<Button>(buttonId)
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    viewModel.writeRawInput(sequence)
                    startKeyRepeat(sequence)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopKeyRepeat()
                    terminalView.focusTerminal()
                    true
                }

                else -> false
            }
        }
    }

    private fun startKeyRepeat(sequence: String) {
        stopKeyRepeat()
        val repeat = object : Runnable {
            override fun run() {
                viewModel.writeRawInput(sequence)
                keyRepeatHandler.postDelayed(this, KEY_REPEAT_INTERVAL_MS)
            }
        }
        keyRepeatRunnable = repeat
        keyRepeatHandler.postDelayed(repeat, KEY_REPEAT_INITIAL_MS)
    }

    private fun stopKeyRepeat() {
        keyRepeatRunnable?.let { keyRepeatHandler.removeCallbacks(it) }
        keyRepeatRunnable = null
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
    }

    override fun onPause() {
        stopKeyRepeat()
        super.onPause()
    }

    override fun onDestroy() {
        stopKeyRepeat()
        super.onDestroy()
    }

    companion object {
        /** Delay before long-press starts repeating (ms). */
        private const val KEY_REPEAT_INITIAL_MS = 350L
        /** Interval between repeated key events while held (ms). */
        private const val KEY_REPEAT_INTERVAL_MS = 45L
    }

}
