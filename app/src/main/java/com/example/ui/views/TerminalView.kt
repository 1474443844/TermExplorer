package com.example.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import com.example.terminal.AnsiParser

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onInputListener: ((String) -> Unit)? = null
    var onSizeChangedListener: ((rows: Int, cols: Int) -> Unit)? = null

    // Grid Character Model representation
    data class StyledChar(
        val char: Char,
        val fgColor: Int,
        val bgColor: Int,
        val isBold: Boolean,
        val isUnderline: Boolean
    )

    private val defaultFg = Color.parseColor("#E2E2E6")
    private val defaultBg = Color.parseColor("#000000")

    class VisualLine(
        val chars: MutableList<StyledChar> = ArrayList(),
        var isSoftWrapped: Boolean = false
    )

    private val lines = ArrayList<VisualLine>()
    private var cursorRow = 0
    private var cursorCol = 0

    private var currentCols = 80
    private var currentRows = 24

    // Measure properties
    private val textPaint = TextPaint().apply {
        typeface = Typeface.MONOSPACE
        textSize = spToPx(13f)
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private var charWidth: Float = 0f
    private var charHeight: Int = 0
    private var fm: Paint.FontMetricsInt

    // Scroll properties
    private var firstVisibleLine = 0
    private var lastTouchY = 0f
    private var startX = 0f
    private var startY = 0f
    private var scrollAccumulator = 0f

    // Cursor blink properties
    private var cursorVisible = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            postDelayed(this, 500)
        }
    }

    // Scale Gesture Detector for pinch-to-zoom
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val oldSize = textPaint.textSize
            var newSize = oldSize * factor
            val minSize = spToPx(8f)
            val maxSize = spToPx(40f)
            newSize = newSize.coerceIn(minSize, maxSize)

            if (Math.abs(newSize - oldSize) > 0.1f) {
                textPaint.textSize = newSize
                recalculateDimensions()
                invalidate()
            }
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(defaultBg)
        setPadding(24, 24, 24, 24)

        fm = textPaint.fontMetricsInt
        charHeight = fm.bottom - fm.top + fm.leading
        charWidth = textPaint.measureText("A")

        // Initialize with one empty line
        lines.add(VisualLine())
    }

    private fun spToPx(sp: Float): Float {
        return sp * resources.displayMetrics.scaledDensity
    }

    private fun reflow(newCols: Int) {
        if (lines.isEmpty()) return
        
        // 1. Merge visual lines into logical lines
        val logicalLines = ArrayList<List<StyledChar>>()
        var currentLogical = ArrayList<StyledChar>()
        for (line in lines) {
            currentLogical.addAll(line.chars)
            if (!line.isSoftWrapped) {
                logicalLines.add(currentLogical)
                currentLogical = ArrayList()
            }
        }
        if (currentLogical.isNotEmpty() || logicalLines.isEmpty()) {
            logicalLines.add(currentLogical)
        }

        // 2. Locate cursor in logical lines
        var cursorLogicalRow = 0
        var cursorLogicalCol = 0
        var currentLogicalIndex = 0
        var accumChars = 0
        val coercedCursorRow = cursorRow.coerceIn(0, lines.size - 1)

        for (i in 0 until lines.size) {
            val line = lines[i]
            if (i == coercedCursorRow) {
                cursorLogicalRow = currentLogicalIndex
                cursorLogicalCol = accumChars + cursorCol
            }
            accumChars += line.chars.size
            if (!line.isSoftWrapped) {
                currentLogicalIndex++
                accumChars = 0
            }
        }

        // 3. Re-split logical lines into new visual lines of width newCols
        val newLines = ArrayList<VisualLine>()
        var newCursorRow = 0
        var newCursorCol = 0

        for (lIdx in 0 until logicalLines.size) {
            val logical = logicalLines[lIdx]
            
            if (lIdx == cursorLogicalRow) {
                val beforeLines = cursorLogicalCol / newCols
                newCursorRow = newLines.size + beforeLines
                newCursorCol = cursorLogicalCol % newCols
            }
            
            if (logical.isEmpty()) {
                newLines.add(VisualLine())
            } else {
                var offset = 0
                while (offset < logical.size) {
                    val chunkSize = (logical.size - offset).coerceAtMost(newCols)
                    val visualLine = VisualLine()
                    for (j in 0 until chunkSize) {
                        visualLine.chars.add(logical[offset + j])
                    }
                    offset += chunkSize
                    if (offset < logical.size) {
                        visualLine.isSoftWrapped = true
                    }
                    newLines.add(visualLine)
                }
            }
        }

        // Replace lines with newLines
        lines.clear()
        lines.addAll(newLines)
        cursorRow = newCursorRow.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        cursorCol = newCursorCol
    }

    private fun recalculateDimensions() {
        fm = textPaint.fontMetricsInt
        charHeight = fm.bottom - fm.top + fm.leading
        charWidth = textPaint.measureText("A")

        val paddingH = paddingLeft + paddingRight
        val paddingV = paddingTop + paddingBottom
        val cols = ((width - paddingH) / charWidth).toInt().coerceAtLeast(10)
        val rows = ((height - paddingV) / charHeight).toInt().coerceAtLeast(5)

        if (cols != currentCols || rows != currentRows) {
            currentCols = cols
            currentRows = rows
            reflow(cols)
            onSizeChangedListener?.invoke(rows, cols)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val paddingH = paddingLeft + paddingRight
        val paddingV = paddingTop + paddingBottom
        val cols = ((w - paddingH) / charWidth).toInt().coerceAtLeast(10)
        val rows = ((h - paddingV) / charHeight).toInt().coerceAtLeast(5)
        if (cols != currentCols || rows != currentRows) {
            currentCols = cols
            currentRows = rows
            reflow(cols)
            onSizeChangedListener?.invoke(rows, cols)
        }
        scrollToBottom()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw solid background
        canvas.drawColor(defaultBg)

        val maxVisibleLines = getVisibleLineCount()
        val startLine = firstVisibleLine
        val endLine = (startLine + maxVisibleLines).coerceAtMost(lines.size)

        val textBaseline = -fm.top

        for (lineIdx in startLine until endLine) {
            val line = lines[lineIdx].chars
            val screenY = paddingTop + (lineIdx - startLine) * charHeight

            for (colIdx in 0 until line.size) {
                val sc = line[colIdx]
                val screenX = paddingLeft + colIdx * charWidth

                // Draw non-default background if exists
                if (sc.bgColor != defaultBg) {
                    bgPaint.color = sc.bgColor
                    canvas.drawRect(
                        screenX,
                        screenY.toFloat(),
                        screenX + charWidth,
                        (screenY + charHeight).toFloat(),
                        bgPaint
                    )
                }

                // Draw character
                textPaint.color = sc.fgColor
                textPaint.isFakeBoldText = sc.isBold
                textPaint.isUnderlineText = sc.isUnderline
                canvas.drawText(
                    charArrayOf(sc.char),
                    0,
                    1,
                    screenX,
                    (screenY + textBaseline).toFloat(),
                    textPaint
                )
            }
        }

        // Draw cursor
        if (cursorVisible && cursorRow >= startLine && cursorRow < endLine) {
            val screenX = paddingLeft + cursorCol * charWidth
            val screenY = paddingTop + (cursorRow - startLine) * charHeight

            cursorPaint.color = Color.parseColor("#A8C7FA")
            cursorPaint.alpha = 180
            canvas.drawRect(
                screenX,
                screenY.toFloat(),
                screenX + charWidth,
                (screenY + charHeight).toFloat(),
                cursorPaint
            )

            // Draw inverse text under cursor if any
            if (cursorRow < lines.size && cursorCol < lines[cursorRow].chars.size) {
                val sc = lines[cursorRow].chars[cursorCol]
                textPaint.color = Color.parseColor("#000000")
                textPaint.isFakeBoldText = sc.isBold
                textPaint.isUnderlineText = sc.isUnderline
                canvas.drawText(
                    charArrayOf(sc.char),
                    0,
                    1,
                    screenX,
                    (screenY + textBaseline).toFloat(),
                    textPaint
                )
            }
        }
    }

    private fun getVisibleLineCount(): Int {
        val viewHeight = height - paddingTop - paddingBottom
        return if (charHeight > 0) viewHeight / charHeight else 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            lastTouchY = event.y
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                startX = event.x
                startY = event.y
                scrollAccumulator = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.y - lastTouchY
                lastTouchY = event.y

                scrollAccumulator += deltaY
                val linesToScroll = (scrollAccumulator / charHeight).toInt()
                if (linesToScroll != 0) {
                    scrollLines(-linesToScroll)
                    scrollAccumulator -= linesToScroll * charHeight
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(event.x - startX)
                val dy = Math.abs(event.y - startY)
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (dx < slop && dy < slop) {
                    focusTerminal()
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun scrollLines(count: Int) {
        val maxVisibleLines = getVisibleLineCount()
        val maxFirstLine = (lines.size - maxVisibleLines).coerceAtLeast(0)
        firstVisibleLine = (firstVisibleLine + count).coerceIn(0, maxFirstLine)
        invalidate()
    }

    fun focusTerminal() {
        if (!isFocused) {
            requestFocus()
        }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, true) { input ->
            onInputListener?.invoke(input)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            val char = event.getUnicodeChar(0).toChar().lowercaseChar()
            if (char == 'c') {
                onInputListener?.invoke("\u0003") // Ctrl+C
                return true
            } else if (char == 'd') {
                onInputListener?.invoke("\u0004") // Ctrl+D
                return true
            } else if (char == 'l') {
                onInputListener?.invoke("\u000C") // Ctrl+L (Form Feed / Clear)
                return true
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                onInputListener?.invoke("\n")
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                onInputListener?.invoke("\u007F") // ASCII Delete / Backspace
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                onInputListener?.invoke("\t")
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onInputListener?.invoke("\u001B")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onInputListener?.invoke("\u001B[A") // ANSI up
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onInputListener?.invoke("\u001B[B") // ANSI down
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onInputListener?.invoke("\u001B[C") // ANSI right
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onInputListener?.invoke("\u001B[D") // ANSI left
                return true
            }
        }

        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            onInputListener?.invoke(unicodeChar.toChar().toString())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    fun appendOutput(ansiText: String) {
        post {
            val spanned = AnsiParser.parse(ansiText)
            for (i in 0 until spanned.length) {
                val c = spanned[i]
                if (c == '\n') {
                    if (cursorRow < lines.size) {
                        lines[cursorRow].isSoftWrapped = false
                    }
                    cursorRow++
                    cursorCol = 0
                    while (lines.size <= cursorRow) {
                        lines.add(VisualLine())
                    }
                } else if (c == '\r') {
                    cursorCol = 0
                } else if (c == '\b' || c == '\u007F') {
                    if (cursorCol > 0) {
                        cursorCol--
                    }
                } else if (c.code < 32 && c != '\t') {
                    // Ignore other control characters (e.g. \u0001, \u0002) to prevent rendering artifacts
                    continue
                } else {
                    // Auto-wrap: if we reach or exceed the column limit of the terminal view
                    if (cursorCol >= currentCols) {
                        if (cursorRow < lines.size) {
                            lines[cursorRow].isSoftWrapped = true
                        }
                        cursorRow++
                        cursorCol = 0
                        while (lines.size <= cursorRow) {
                            lines.add(VisualLine())
                        }
                    }

                    val fgs = spanned.getSpans(i, i + 1, ForegroundColorSpan::class.java)
                    val bgs = spanned.getSpans(i, i + 1, BackgroundColorSpan::class.java)
                    val bld = spanned.getSpans(i, i + 1, StyleSpan::class.java)
                    val und = spanned.getSpans(i, i + 1, UnderlineSpan::class.java)

                    val fg = fgs.firstOrNull()?.foregroundColor ?: defaultFg
                    val bg = bgs.firstOrNull()?.backgroundColor ?: defaultBg
                    val isBold = bld.firstOrNull()?.style == Typeface.BOLD
                    val isUnderline = und.isNotEmpty()

                    while (lines.size <= cursorRow) {
                        lines.add(VisualLine())
                    }
                    val line = lines[cursorRow].chars
                    while (line.size < cursorCol) {
                        line.add(StyledChar(' ', defaultFg, defaultBg, false, false))
                    }

                    val styledChar = StyledChar(c, fg, bg, isBold, isUnderline)
                    if (cursorCol < line.size) {
                        line[cursorCol] = styledChar
                    } else {
                        line.add(styledChar)
                    }
                    cursorCol++
                }
            }

            // Cap the scrollback list size to 2000 lines
            while (lines.size > 2000) {
                lines.removeAt(0)
                cursorRow--
            }
            if (cursorRow < 0) cursorRow = 0

            scrollToBottom()
        }
    }

    fun scrollToBottom() {
        val maxVisibleLines = getVisibleLineCount()
        firstVisibleLine = (lines.size - maxVisibleLines).coerceAtLeast(0)
        invalidate()
    }

    fun clearOutput() {
        post {
            lines.clear()
            lines.add(VisualLine())
            cursorRow = 0
            cursorCol = 0
            firstVisibleLine = 0
            invalidate()
        }
    }

    fun getTextView(): TextView = TextView(context)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blinkRunnable, 500)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
    }
}

class TerminalInputConnection(
    targetView: View,
    fullEditor: Boolean,
    private val onInput: (String) -> Unit
) : BaseInputConnection(targetView, fullEditor) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.let {
            onInput(it.toString())
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) {
            for (i in 0 until beforeLength) {
                onInput("\u007F")
            }
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    onInput("\u007F")
                    return true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    onInput("\n")
                    return true
                }
                KeyEvent.KEYCODE_TAB -> {
                    onInput("\t")
                    return true
                }
            }
        }
        return super.sendKeyEvent(event)
    }
}
