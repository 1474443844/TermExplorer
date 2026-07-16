package cn.wty5.term.ui.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import cn.wty5.term.terminal.AnsiParser

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
        val isUnderline: Boolean,
        val isFullWidth: Boolean = false,
        val isPlaceholder: Boolean = false
    )

    private fun isFullWidth(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||
                (code in 0x3400..0x4DBF) ||
                (code in 0x20000..0x2A6DF) ||
                (code in 0x3000..0x303F) ||
                (code in 0xFF00..0xFFEF) ||
                (code in 0x3040..0x309F) ||
                (code in 0x30A0..0x30FF) ||
                (code in 0xAC00..0xD7AF)
    }

    private val defaultFg = Color.parseColor("#E2E2E6")
    private val defaultBg = Color.parseColor("#000000")

    class VisualLine(
        val chars: MutableList<StyledChar> = ArrayList(),
        var isSoftWrapped: Boolean = false
    )

    private val lines = ArrayList<VisualLine>()
    private var cursorRow = 0
    private var cursorCol = 0

    var isCtrlActive = false
    var isAltActive = false
    var isShiftActive = false
    var onModifiersChangedListener: (() -> Unit)? = null

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
    private val selectionPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4285F4")
        alpha = 100
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4285F4")
        isAntiAlias = true
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    data class TerminalPosition(val row: Int, val col: Int) : Comparable<TerminalPosition> {
        override fun compareTo(other: TerminalPosition): Int {
            return if (row != other.row) {
                row.compareTo(other.row)
            } else {
                col.compareTo(other.col)
            }
        }
    }

    private var selectionStart: TerminalPosition? = null
    private var selectionEnd: TerminalPosition? = null
    private var isSelecting = false
    private var isDraggingStartHandle = false
    private var isDraggingEndHandle = false
    private var selectionPopup: PopupWindow? = null

    private val longPressRunnable = Runnable {
        val startPos = getPositionForOffset(startX, startY)
        if (startPos != null) {
            isSelecting = true
            selectionStart = startPos
            selectionEnd = startPos
            isDraggingEndHandle = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
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
    private val scaleDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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

            if (logical.isEmpty()) {
                if (lIdx == cursorLogicalRow) {
                    newCursorRow = newLines.size
                    newCursorCol = 0
                }
                newLines.add(VisualLine())
            } else {
                var offset = 0
                while (offset < logical.size) {
                    val visualLine = VisualLine()
                    var colCount = 0

                    while (colCount < newCols && offset < logical.size) {
                        if (lIdx == cursorLogicalRow && offset == cursorLogicalCol) {
                            newCursorRow = newLines.size
                            newCursorCol = colCount
                        }

                        val sc = logical[offset]
                        if (sc.isFullWidth) {
                            if (colCount + 2 > newCols) {
                                break // Wrap to next visual line
                            }
                            visualLine.chars.add(sc)
                            colCount++
                            offset++

                            if (lIdx == cursorLogicalRow && offset == cursorLogicalCol) {
                                newCursorRow = newLines.size
                                newCursorCol = colCount
                            }

                            if (offset < logical.size && logical[offset].isPlaceholder) {
                                visualLine.chars.add(logical[offset])
                                colCount++
                                offset++
                            }
                        } else {
                            visualLine.chars.add(sc)
                            colCount++
                            offset++
                        }
                    }

                    if (lIdx == cursorLogicalRow && offset == cursorLogicalCol && offset == logical.size) {
                        newCursorRow = newLines.size
                        newCursorCol = colCount
                    }

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

                // Determine if selected
                var isCharSelected = false
                val selStart = selectionStart
                val selEnd = selectionEnd
                if (selStart != null && selEnd != null) {
                    val maxRow = lines.size - 1
                    if (maxRow >= 0) {
                        val startRowCoerced = selStart.row.coerceIn(0, maxRow)
                        val startColCoerced =
                            selStart.col.coerceIn(0, lines[startRowCoerced].chars.size)
                        val endRowCoerced = selEnd.row.coerceIn(0, maxRow)
                        val endColCoerced = selEnd.col.coerceIn(0, lines[endRowCoerced].chars.size)

                        val sPos = TerminalPosition(startRowCoerced, startColCoerced)
                        val ePos = TerminalPosition(endRowCoerced, endColCoerced)

                        val minPos = if (sPos < ePos) sPos else ePos
                        val maxPos = if (sPos < ePos) ePos else sPos

                        val currentPos = TerminalPosition(lineIdx, colIdx)
                        if (currentPos >= minPos && currentPos <= maxPos) {
                            isCharSelected = true
                        }
                    }
                }

                // Draw non-default background or selection highlight if exists
                if (isCharSelected) {
                    canvas.drawRect(
                        screenX,
                        screenY.toFloat(),
                        screenX + charWidth,
                        (screenY + charHeight).toFloat(),
                        selectionPaint
                    )
                } else if (sc.bgColor != defaultBg) {
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
                if (!sc.isPlaceholder) {
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
        }

        // Draw cursor
        if (cursorVisible && cursorRow >= startLine && cursorRow < endLine) {
            val isCursorFullWidth =
                if (cursorRow < lines.size && cursorCol < lines[cursorRow].chars.size) {
                    lines[cursorRow].chars[cursorCol].isFullWidth
                } else {
                    false
                }
            val cursorWidth = if (isCursorFullWidth) 2 * charWidth else charWidth
            val screenX = paddingLeft + cursorCol * charWidth
            val screenY = paddingTop + (cursorRow - startLine) * charHeight

            cursorPaint.color = Color.parseColor("#A8C7FA")
            cursorPaint.alpha = 180
            canvas.drawRect(
                screenX,
                screenY.toFloat(),
                screenX + cursorWidth,
                (screenY + charHeight).toFloat(),
                cursorPaint
            )

            // Draw inverse text under cursor if any
            if (cursorRow < lines.size && cursorCol < lines[cursorRow].chars.size) {
                val sc = lines[cursorRow].chars[cursorCol]
                if (!sc.isPlaceholder) {
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

        // Draw selection handles
        val selStart = selectionStart
        val selEnd = selectionEnd
        if (isSelecting && selStart != null && selEnd != null) {
            val r = dpToPx(12f)

            val minPos = if (selStart < selEnd) selStart else selEnd
            val maxPos = if (selStart < selEnd) selEnd else selStart

            // Draw Left Handle (Tip pointing to top-right)
            if (minPos.row >= startLine && minPos.row < endLine) {
                val leftTipX = paddingLeft + minPos.col * charWidth
                val leftTipY = (paddingTop + (minPos.row - startLine + 1) * charHeight).toFloat()

                val cx = leftTipX - r
                val cy = leftTipY + r

                // Draw circle
                canvas.drawCircle(cx, cy, r, handlePaint)

                // Draw triangle connecting the tip at (leftTipX, leftTipY) to the circle
                val path = Path().apply {
                    moveTo(leftTipX, leftTipY)
                    lineTo(leftTipX - r, leftTipY)
                    lineTo(leftTipX, leftTipY + r)
                    close()
                }
                canvas.drawPath(path, handlePaint)
            }

            // Draw Right Handle (Tip pointing to top-left)
            if (maxPos.row >= startLine && maxPos.row < endLine) {
                val rightTipX = paddingLeft + (maxPos.col + 1) * charWidth
                val rightTipY = (paddingTop + (maxPos.row - startLine + 1) * charHeight).toFloat()

                val cx = rightTipX + r
                val cy = rightTipY + r

                // Draw circle
                canvas.drawCircle(cx, cy, r, handlePaint)

                // Draw triangle connecting the tip at (rightTipX, rightTipY) to the circle
                val path = Path().apply {
                    moveTo(rightTipX, rightTipY)
                    lineTo(rightTipX + r, rightTipY)
                    lineTo(rightTipX, rightTipY + r)
                    close()
                }
                canvas.drawPath(path, handlePaint)
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
            removeCallbacks(longPressRunnable)
            lastTouchY = event.y
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                startX = event.x
                startY = event.y
                scrollAccumulator = 0f

                // Check if we touched a handle
                isDraggingStartHandle = false
                isDraggingEndHandle = false

                val sStart = selectionStart
                val sEnd = selectionEnd
                if (isSelecting && sStart != null && sEnd != null) {
                    val r = dpToPx(12f)
                    val hitRadius = dpToPx(36f) // generous hit box for fingers

                    val minPos = if (sStart < sEnd) sStart else sEnd
                    val maxPos = if (sStart < sEnd) sEnd else sStart

                    val leftTipX = paddingLeft + minPos.col * charWidth
                    val leftTipY = paddingTop + (minPos.row - firstVisibleLine + 1) * charHeight
                    val leftCx = leftTipX - r
                    val leftCy = leftTipY + r

                    val rightTipX = paddingLeft + (maxPos.col + 1) * charWidth
                    val rightTipY = paddingTop + (maxPos.row - firstVisibleLine + 1) * charHeight
                    val rightCx = rightTipX + r
                    val rightCy = rightTipY + r

                    val distLeft =
                        Math.hypot((event.x - leftCx).toDouble(), (event.y - leftCy).toDouble())
                    val distRight =
                        Math.hypot((event.x - rightCx).toDouble(), (event.y - rightCy).toDouble())

                    if (distLeft < hitRadius && distLeft < distRight) {
                        if (sStart < sEnd) {
                            isDraggingStartHandle = true
                        } else {
                            isDraggingEndHandle = true
                        }
                        dismissSelectionMenu()
                        return true
                    } else if (distRight < hitRadius) {
                        if (sStart < sEnd) {
                            isDraggingEndHandle = true
                        } else {
                            isDraggingStartHandle = true
                        }
                        dismissSelectionMenu()
                        return true
                    }
                }

                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.x - startX)
                val dy = Math.abs(event.y - startY)
                val slop = ViewConfiguration.get(context).scaledTouchSlop

                if (dx > slop || dy > slop) {
                    removeCallbacks(longPressRunnable)
                }

                if (isDraggingStartHandle || isDraggingEndHandle) {
                    val currentPos = getPositionForOffset(event.x, event.y)
                    if (currentPos != null) {
                        if (isDraggingStartHandle) {
                            selectionStart = currentPos
                        } else {
                            selectionEnd = currentPos
                        }
                        invalidate()
                    }

                    val dragY = event.y
                    if (dragY < paddingTop + charHeight) {
                        scrollLines(-1)
                    } else if (dragY > height - paddingBottom - charHeight) {
                        scrollLines(1)
                    }
                    return true
                }

                if (isSelecting) {
                    val deltaY = event.y - lastTouchY
                    lastTouchY = event.y

                    scrollAccumulator += deltaY
                    val linesToScroll = (scrollAccumulator / charHeight).toInt()
                    if (linesToScroll != 0) {
                        scrollLines(-linesToScroll)
                        scrollAccumulator -= linesToScroll * charHeight
                    }
                } else {
                    val deltaY = event.y - lastTouchY
                    lastTouchY = event.y

                    scrollAccumulator += deltaY
                    val linesToScroll = (scrollAccumulator / charHeight).toInt()
                    if (linesToScroll != 0) {
                        scrollLines(-linesToScroll)
                        scrollAccumulator -= linesToScroll * charHeight
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)

                if (isDraggingStartHandle || isDraggingEndHandle) {
                    isDraggingStartHandle = false
                    isDraggingEndHandle = false
                    showSelectionMenu(event.x, event.y)
                    return true
                }

                val dx = Math.abs(event.x - startX)
                val dy = Math.abs(event.y - startY)
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val isClick = dx < slop && dy < slop

                if (isSelecting) {
                    if (isClick) {
                        val clickPos = getPositionForOffset(event.x, event.y)
                        val sStart = selectionStart
                        val sEnd = selectionEnd
                        if (clickPos != null && sStart != null && sEnd != null) {
                            val minPos = if (sStart < sEnd) sStart else sEnd
                            val maxPos = if (sStart < sEnd) sEnd else sStart

                            if (clickPos >= minPos && clickPos <= maxPos) {
                                showSelectionMenu(event.x, event.y)
                            } else {
                                clearSelection()
                                dismissSelectionMenu()
                                focusTerminal()
                                performClick()
                            }
                        } else {
                            clearSelection()
                            dismissSelectionMenu()
                            focusTerminal()
                            performClick()
                        }
                    }
                } else {
                    if (isClick) {
                        clearSelection()
                        dismissSelectionMenu()
                        focusTerminal()
                        performClick()
                    }
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

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, true) { input ->
            onInputListener?.invoke(input)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isCtrlActive && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            val charIndex = keyCode - KeyEvent.KEYCODE_A
            val controlCode = charIndex + 1
            onInputListener?.invoke(controlCode.toChar().toString())
            isCtrlActive = false
            onModifiersChangedListener?.invoke()
            return true
        } else if (isAltActive && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            val char = ('a'.code + (keyCode - KeyEvent.KEYCODE_A)).toChar()
            onInputListener?.invoke("\u001B" + char)
            isAltActive = false
            onModifiersChangedListener?.invoke()
            return true
        } else if (isShiftActive && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            val char = ('A'.code + (keyCode - KeyEvent.KEYCODE_A)).toChar()
            onInputListener?.invoke(char.toString())
            isShiftActive = false
            onModifiersChangedListener?.invoke()
            return true
        }

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
            val events = AnsiParser.parseEvents(ansiText)
            for (event in events) {
                when (event) {
                    is AnsiParser.Event.Text -> putChar(event.char, event.style)
                    is AnsiParser.Event.NewLine -> {
                        if (cursorRow < lines.size) {
                            lines[cursorRow].isSoftWrapped = false
                        }
                        cursorRow++
                        cursorCol = 0
                        ensureLine(cursorRow)
                    }

                    is AnsiParser.Event.CarriageReturn -> {
                        cursorCol = 0
                    }

                    is AnsiParser.Event.Backspace -> {
                        moveCursorBack(1)
                    }

                    is AnsiParser.Event.Tab -> {
                        // Advance to next 8-column tab stop
                        val next = ((cursorCol / 8) + 1) * 8
                        cursorCol = next.coerceAtMost((currentCols - 1).coerceAtLeast(0))
                    }

                    is AnsiParser.Event.CursorUp -> {
                        cursorRow = (cursorRow - event.n).coerceAtLeast(0)
                        ensureLine(cursorRow)
                    }

                    is AnsiParser.Event.CursorDown -> {
                        cursorRow += event.n
                        ensureLine(cursorRow)
                    }

                    is AnsiParser.Event.CursorForward -> {
                        cursorCol = (cursorCol + event.n).coerceAtMost(currentCols)
                    }

                    is AnsiParser.Event.CursorBack -> {
                        moveCursorBack(event.n)
                    }

                    is AnsiParser.Event.CursorHorizontalAbsolute -> {
                        // CSI n G is 1-based
                        cursorCol = (event.col - 1).coerceIn(0, currentCols)
                    }

                    is AnsiParser.Event.CursorPosition -> {
                        // Map 1-based row onto the last currentRows lines when possible.
                        val origin = (lines.size - currentRows).coerceAtLeast(0)
                        cursorRow = (origin + event.row - 1).coerceAtLeast(0)
                        cursorCol = (event.col - 1).coerceIn(0, currentCols)
                        ensureLine(cursorRow)
                    }

                    is AnsiParser.Event.EraseInLine -> {
                        eraseInLine(event.mode)
                    }

                    is AnsiParser.Event.EraseInDisplay -> {
                        eraseInDisplay(event.mode)
                    }
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

    private fun ensureLine(row: Int) {
        while (lines.size <= row) {
            lines.add(VisualLine())
        }
    }

    private fun moveCursorBack(n: Int) {
        var remaining = n
        while (remaining > 0 && cursorCol > 0) {
            cursorCol--
            remaining--
            if (cursorRow < lines.size) {
                val line = lines[cursorRow].chars
                if (cursorCol < line.size && line[cursorCol].isPlaceholder && cursorCol > 0) {
                    cursorCol--
                }
            }
        }
    }

    private fun blankChar(): StyledChar =
        StyledChar(' ', defaultFg, defaultBg, false, false)

    private fun eraseInLine(mode: Int) {
        ensureLine(cursorRow)
        val line = lines[cursorRow].chars
        when (mode) {
            // Erase from cursor to end of line (inclusive)
            0 -> {
                if (cursorCol < line.size) {
                    // Truncate trailing content so deleted chars disappear
                    while (line.size > cursorCol) {
                        line.removeAt(line.lastIndex)
                    }
                }
            }
            // Erase from start of line to cursor (inclusive)
            1 -> {
                val end = cursorCol.coerceAtMost((line.size - 1).coerceAtLeast(0))
                for (i in 0..end) {
                    if (i < line.size) {
                        line[i] = blankChar()
                    }
                }
            }
            // Erase entire line
            2 -> {
                line.clear()
            }
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            // From cursor to end of screen
            0 -> {
                eraseInLine(0)
                while (lines.size > cursorRow + 1) {
                    lines.removeAt(lines.lastIndex)
                }
            }
            // From start of screen to cursor
            1 -> {
                eraseInLine(1)
                val origin = (lines.size - currentRows).coerceAtLeast(0)
                for (r in origin until cursorRow) {
                    if (r < lines.size) {
                        lines[r].chars.clear()
                    }
                }
            }
            // Entire screen
            2 -> {
                val origin = (lines.size - currentRows).coerceAtLeast(0)
                for (r in origin until lines.size) {
                    lines[r].chars.clear()
                }
            }
            // Entire screen + scrollback
            3 -> {
                lines.clear()
                lines.add(VisualLine())
                cursorRow = 0
                cursorCol = 0
                firstVisibleLine = 0
            }
        }
    }

    private fun putChar(c: Char, style: AnsiParser.TextStyle) {
        val isFull = isFullWidth(c)
        val charCols = if (isFull) 2 else 1

        // Auto-wrap: if we reach or exceed the column limit of the terminal view
        if (cursorCol + charCols > currentCols) {
            if (cursorRow < lines.size) {
                lines[cursorRow].isSoftWrapped = true
            }
            cursorRow++
            cursorCol = 0
        }
        ensureLine(cursorRow)

        val fg = style.fg ?: defaultFg
        val bg = style.bg ?: defaultBg
        val isBold = style.bold
        val isUnderline = style.underline

        val line = lines[cursorRow].chars
        while (line.size < cursorCol) {
            line.add(blankChar())
        }

        val styledChar = StyledChar(
            c,
            fg,
            bg,
            isBold,
            isUnderline,
            isFullWidth = isFull,
            isPlaceholder = false
        )
        if (cursorCol < line.size) {
            line[cursorCol] = styledChar
        } else {
            line.add(styledChar)
        }
        cursorCol++

        if (isFull) {
            val placeholderChar = StyledChar(
                ' ',
                fg,
                bg,
                isBold,
                isUnderline,
                isFullWidth = false,
                isPlaceholder = true
            )
            if (cursorCol < line.size) {
                line[cursorCol] = placeholderChar
            } else {
                line.add(placeholderChar)
            }
            cursorCol++
        }
    }

    fun scrollToBottom() {
        val maxVisibleLines = getVisibleLineCount()
        firstVisibleLine = (lines.size - maxVisibleLines).coerceAtLeast(0)
        invalidate()
    }

    fun clearOutput() {
        post {
            clearSelection()
            dismissSelectionMenu()
            AnsiParser.reset()
            lines.clear()
            lines.add(VisualLine())
            cursorRow = 0
            cursorCol = 0
            firstVisibleLine = 0
            invalidate()
        }
    }

    private fun getPositionForOffset(x: Float, y: Float): TerminalPosition? {
        if (charHeight <= 0 || charWidth <= 0f || lines.isEmpty()) return null
        val startLine = firstVisibleLine

        val relativeY = y - paddingTop
        val relativeX = x - paddingLeft

        val lineOffset = (relativeY / charHeight).toInt()
        val row = (startLine + lineOffset).coerceIn(0, lines.size - 1)

        val line = lines[row].chars
        val col = (relativeX / charWidth).toInt().coerceIn(0, line.size)
        return TerminalPosition(row, col)
    }

    fun getSelectedText(): String {
        val selStart = selectionStart
        val selEnd = selectionEnd
        if (selStart == null || selEnd == null || lines.isEmpty()) return ""

        val maxRow = lines.size - 1
        val startRowCoerced = selStart.row.coerceIn(0, maxRow)
        val startColCoerced = selStart.col.coerceIn(0, lines[startRowCoerced].chars.size)
        val endRowCoerced = selEnd.row.coerceIn(0, maxRow)
        val endColCoerced = selEnd.col.coerceIn(0, lines[endRowCoerced].chars.size)

        val sPos = TerminalPosition(startRowCoerced, startColCoerced)
        val ePos = TerminalPosition(endRowCoerced, endColCoerced)

        val minPos = if (sPos < ePos) sPos else ePos
        val maxPos = if (sPos < ePos) ePos else sPos

        val sb = StringBuilder()
        for (r in minPos.row..maxPos.row) {
            if (r >= lines.size) break
            val line = lines[r]
            val startCol = if (r == minPos.row) minPos.col else 0
            val endCol =
                if (r == maxPos.row) maxPos.col.coerceAtMost(line.chars.size - 1) else line.chars.size - 1

            for (c in startCol..endCol) {
                if (c >= 0 && c < line.chars.size) {
                    val sc = line.chars[c]
                    if (!sc.isPlaceholder) {
                        sb.append(sc.char)
                    }
                }
            }
            if (r < maxPos.row && !line.isSoftWrapped) {
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    fun copySelectedText() {
        val text = getSelectedText()
        if (text.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Terminal Text", text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
        clearSelection()
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                onInputListener?.invoke(text)
            }
        }
    }

    private fun selectAllText() {
        if (lines.isNotEmpty()) {
            selectionStart = TerminalPosition(0, 0)
            val lastRow = lines.size - 1
            val lastCol = (lines[lastRow].chars.size - 1).coerceAtLeast(0)
            selectionEnd = TerminalPosition(lastRow, lastCol)
            isSelecting = true
            invalidate()
        }
    }

    fun clearSelection() {
        selectionStart = null
        selectionEnd = null
        isSelecting = false
        invalidate()
    }

    private fun showSelectionMenu(x: Float, y: Float) {
        dismissSelectionMenu()

        val ctx = context
        val menuLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#25282C"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#3D4146"))
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val hasClipboardText = clipboard?.hasPrimaryClip() == true &&
                !clipboard.primaryClip?.getItemAt(0)?.text.isNullOrEmpty()

        val hasSelection =
            selectionStart != null && selectionEnd != null && selectionStart != selectionEnd
        if (hasSelection) {
            val copyButton = TextView(ctx).apply {
                text = " 复制 "
                setTextColor(Color.parseColor("#E2E2E6"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(24, 16, 24, 16)
                isClickable = true
                isFocusable = false
                background = GradientDrawable().apply {
                    cornerRadius = 16f
                }
                setOnClickListener {
                    copySelectedText()
                    dismissSelectionMenu()
                }
            }
            menuLayout.addView(copyButton)
        }

        if (hasClipboardText) {
            val pasteButton = TextView(ctx).apply {
                text = " 粘贴 "
                setTextColor(Color.parseColor("#E2E2E6"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(24, 16, 24, 16)
                isClickable = true
                isFocusable = false
                background = GradientDrawable().apply {
                    cornerRadius = 16f
                }
                setOnClickListener {
                    pasteFromClipboard()
                    dismissSelectionMenu()
                    clearSelection()
                }
            }
            menuLayout.addView(pasteButton)
        }

        val selectAllButton = TextView(ctx).apply {
            text = " 全选 "
            setTextColor(Color.parseColor("#A8C7FA"))
            textSize = 14f
            setPadding(24, 16, 24, 16)
            isClickable = true
            isFocusable = false
            background = GradientDrawable().apply {
                cornerRadius = 16f
            }
            setOnClickListener {
                selectAllText()
                dismissSelectionMenu()
                showSelectionMenu(width / 2f, height / 3f)
            }
        }
        menuLayout.addView(selectAllButton)

        val cancelButton = TextView(ctx).apply {
            text = " 取消 "
            setTextColor(Color.parseColor("#8E9199"))
            textSize = 14f
            setPadding(24, 16, 24, 16)
            isClickable = true
            isFocusable = false
            background = GradientDrawable().apply {
                cornerRadius = 16f
            }
            setOnClickListener {
                clearSelection()
                dismissSelectionMenu()
            }
        }
        menuLayout.addView(cancelButton)

        selectionPopup = PopupWindow(
            menuLayout,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = true
            isFocusable = false
            elevation = 20f
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            val location = IntArray(2)
            getLocationOnScreen(location)
            val popupX = location[0] + x.toInt() - 200
            val popupY = location[1] + y.toInt() - 150
            showAtLocation(
                this@TerminalView,
                Gravity.NO_GRAVITY,
                popupX.coerceAtLeast(10),
                popupY.coerceAtLeast(10)
            )
        }
    }

    private fun dismissSelectionMenu() {
        selectionPopup?.dismiss()
        selectionPopup = null
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
    private val terminalView: TerminalView,
    fullEditor: Boolean,
    private val onInput: (String) -> Unit
) : BaseInputConnection(terminalView, fullEditor) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.let {
            var finalStr = it.toString()
            if (terminalView.isCtrlActive) {
                if (finalStr.length == 1) {
                    val c = finalStr[0]
                    if (c in 'a'..'z' || c in 'A'..'Z') {
                        val controlCode = c.lowercaseChar().code - 'a'.code + 1
                        finalStr = controlCode.toChar().toString()
                    }
                }
                terminalView.isCtrlActive = false
                terminalView.onModifiersChangedListener?.invoke()
            } else if (terminalView.isAltActive) {
                finalStr = "\u001B" + finalStr
                terminalView.isAltActive = false
                terminalView.onModifiersChangedListener?.invoke()
            } else if (terminalView.isShiftActive) {
                finalStr = finalStr.uppercase()
                terminalView.isShiftActive = false
                terminalView.onModifiersChangedListener?.invoke()
            }
            onInput(finalStr)
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
