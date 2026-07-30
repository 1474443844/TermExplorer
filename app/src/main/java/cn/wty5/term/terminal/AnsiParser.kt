package cn.wty5.term.terminal

import android.graphics.Color

/**
 * Stream-safe ANSI/VT parser that emits structured events.
 *
 * Readline-enabled bash redraws the prompt/line using sequences such as:
 *   ESC[K / ESC[0K / ESC[1K / ESC[2K   erase in line
 *   ESC[C / ESC[D / ESC[nG             cursor motion
 *   ESC[?2004h                         private modes (ignored)
 *
 * Only stripping SGR used to leave garbage like "04h", and swallowing erase
 * without applying it made deletes/redraws appear stuck.
 */
object AnsiParser {

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    data class TextStyle(
        val fg: Int? = null,
        val bg: Int? = null,
        val bold: Boolean = false,
        val underline: Boolean = false
    )

    sealed class Event {
        data class Text(val char: Char, val style: TextStyle) : Event()
        data object CarriageReturn : Event()
        data object NewLine : Event()
        data object Backspace : Event()
        data object Tab : Event()
        data class CursorUp(val n: Int) : Event()
        data class CursorDown(val n: Int) : Event()
        data class CursorForward(val n: Int) : Event()
        data class CursorBack(val n: Int) : Event()
        data class CursorHorizontalAbsolute(val col: Int) : Event() // 1-based
        data class CursorPosition(val row: Int, val col: Int) : Event() // 1-based
        /** mode: 0 = to end, 1 = to start, 2 = entire line */
        data class EraseInLine(val mode: Int) : Event()
        /** mode: 0 = to end, 1 = to start, 2 = entire screen, 3 = screen+scrollback */
        data class EraseInDisplay(val mode: Int) : Event()
        /** CSI n X — erase n characters from cursor (replace with blanks) */
        data class EraseChars(val n: Int) : Event()
        /** CSI n P — delete n characters at cursor (shift remainder left) */
        data class DeleteChars(val n: Int) : Event()
        /** CSI n @ — insert n blank characters at cursor */
        data class InsertChars(val n: Int) : Event()
    }

    // Incomplete escape sequence carry-over across chunked PTY reads.
    private val pending = StringBuilder()

    // Active SGR style carried across chunks.
    private var textColor: Int? = null
    private var bgColor: Int? = null
    private var isBold = false
    private var isUnderline = false

    @Synchronized
    fun reset() {
        pending.clear()
        textColor = null
        bgColor = null
        isBold = false
        isUnderline = false
    }

    private fun currentStyle(): TextStyle = TextStyle(textColor, bgColor, isBold, isUnderline)

    /**
     * Parse a PTY output chunk into terminal events. Incomplete trailing escape
     * sequences are buffered until the next chunk.
     */
    @Synchronized
    fun parseEvents(chunk: String): List<Event> {
        val events = ArrayList<Event>()
        val input = if (pending.isEmpty()) chunk else {
            val combined = pending.toString() + chunk
            pending.clear()
            combined
        }

        var i = 0
        val n = input.length

        while (i < n) {
            val c = input[i]
            when (c) {
                '\r' -> {
                    events.add(Event.CarriageReturn)
                    i++
                }

                '\n' -> {
                    events.add(Event.NewLine)
                    i++
                }

                '\b', '\u007F' -> {
                    events.add(Event.Backspace)
                    i++
                }

                '\t' -> {
                    events.add(Event.Tab)
                    i++
                }

                ESC -> {
                    if (i + 1 >= n) {
                        pending.append(c)
                        i = n
                        break
                    }
                    when (val next = input[i + 1]) {
                        '[' -> {
                            // CSI: ESC [ params final
                            var j = i + 2
                            while (j < n) {
                                val ch = input[j]
                                if (ch in '@'..'~') {
                                    val params = input.substring(i + 2, j)
                                    handleCsi(params, ch, events)
                                    i = j + 1
                                    break
                                }
                                j++
                            }
                            if (j >= n) {
                                pending.append(input.substring(i))
                                i = n
                            }
                        }

                        ']' -> {
                            // OSC: ESC ] ... BEL or ST (ESC \)
                            var j = i + 2
                            var closed = false
                            while (j < n) {
                                val ch = input[j]
                                if (ch == BEL) {
                                    i = j + 1
                                    closed = true
                                    break
                                }
                                if (ch == ESC && j + 1 < n && input[j + 1] == '\\') {
                                    i = j + 2
                                    closed = true
                                    break
                                }
                                j++
                            }
                            if (!closed) {
                                pending.append(input.substring(i))
                                i = n
                            }
                        }

                        'N', 'O' -> {
                            // SS2 / SS3: ESC N/O + one byte
                            if (i + 2 < n) {
                                i += 3
                            } else {
                                pending.append(input.substring(i))
                                i = n
                            }
                        }

                        else -> {
                            // Generic 2-byte ESC sequence
                            i += 2
                        }
                    }
                }

                else -> {
                    if (c.code < 32) {
                        // Other C0 controls (NUL/SOH/STX/...) — drop
                        i++
                    } else {
                        events.add(Event.Text(c, currentStyle()))
                        i++
                    }
                }
            }
        }

        return events
    }

    /**
     * Back-compat helper used by any remaining call sites that still expect a
     * flat styled string. Control/cursor/erase events are dropped.
     */
    @Synchronized
    fun parse(chunk: String): android.text.SpannableStringBuilder {
        val ssb = android.text.SpannableStringBuilder()
        // Snapshot style state before parseEvents mutates it, then re-apply via events.
        // parseEvents already uses the live style machine, so just consume Text events.
        for (event in parseEvents(chunk)) {
            if (event is Event.Text) {
                val start = ssb.length
                ssb.append(event.char)
                val end = ssb.length
                event.style.fg?.let {
                    ssb.setSpan(
                        android.text.style.ForegroundColorSpan(it),
                        start,
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                event.style.bg?.let {
                    ssb.setSpan(
                        android.text.style.BackgroundColorSpan(it),
                        start,
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (event.style.bold) {
                    ssb.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start,
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (event.style.underline) {
                    ssb.setSpan(
                        android.text.style.UnderlineSpan(),
                        start,
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        return ssb
    }

    private fun handleCsi(params: String, finalByte: Char, events: MutableList<Event>) {
        when (finalByte) {
            'm' -> applyStyle(params)

            // Cursor up/down/forward/back
            'A' -> events.add(Event.CursorUp(csiCount(params)))
            'B' -> events.add(Event.CursorDown(csiCount(params)))
            'C' -> events.add(Event.CursorForward(csiCount(params)))
            'D' -> events.add(Event.CursorBack(csiCount(params)))

            // Cursor horizontal absolute (1-based column)
            'G' -> events.add(Event.CursorHorizontalAbsolute(csiCount(params).coerceAtLeast(1)))

            // Cursor position CUP / HVP
            'H', 'f' -> {
                val (row, col) = csiPair(params, 1, 1)
                events.add(Event.CursorPosition(row.coerceAtLeast(1), col.coerceAtLeast(1)))
            }

            // Erase in display — mode 0 is valid and common
            'J' -> events.add(Event.EraseInDisplay(csiCount(params, default = 0, allowZero = true)))

            // Erase in line — critical for readline history redraw (long → short)
            'K' -> events.add(Event.EraseInLine(csiCount(params, default = 0, allowZero = true)))

            // Erase / delete / insert characters — also used by readline redisplay
            'X' -> events.add(Event.EraseChars(csiCount(params, default = 1)))
            'P' -> events.add(Event.DeleteChars(csiCount(params, default = 1)))
            '@' -> events.add(Event.InsertChars(csiCount(params, default = 1)))

            // Private mode set/reset: ESC [ ? 2004 h/l etc. — ignore
            'h', 'l' -> Unit

            else -> Unit
        }
    }

    /**
     * @param allowZero when true, an explicit `0` parameter is kept (needed for EL/ED).
     *                  VT100 treats 0 like the default for most cursor ops.
     */
    private fun csiCount(params: String, default: Int = 1, allowZero: Boolean = false): Int {
        if (params.isEmpty()) return default
        // Take the first numeric field; ignore leading '?', '>', '!' for private/modes
        val cleaned = params.trimStart('?', '>', '!')
        if (cleaned.isEmpty()) return default
        val first = cleaned.split(';', limit = 2)[0]
        val n = first.toIntOrNull() ?: return default
        if (n == 0) return if (allowZero) 0 else default
        return if (n < 0) default else n
    }

    private fun csiPair(params: String, defaultRow: Int, defaultCol: Int): Pair<Int, Int> {
        if (params.isEmpty()) return defaultRow to defaultCol
        val parts = params.split(';')
        // CUP uses 0 as 1
        val row = parts.getOrNull(0)?.toIntOrNull()?.let { if (it <= 0) defaultRow else it } ?: defaultRow
        val col = parts.getOrNull(1)?.toIntOrNull()?.let { if (it <= 0) defaultCol else it } ?: defaultCol
        return row to col
    }

    private fun applyStyle(styleText: String) {
        if (styleText.isEmpty() || styleText == "0") {
            textColor = null
            bgColor = null
            isBold = false
            isUnderline = false
            return
        }

        val parts = styleText.split(';')
        var idx = 0
        while (idx < parts.size) {
            val code = parts[idx].toIntOrNull()
            if (code == null) {
                idx++
                continue
            }
            when (code) {
                0 -> {
                    textColor = null
                    bgColor = null
                    isBold = false
                    isUnderline = false
                }

                1 -> isBold = true
                4 -> isUnderline = true
                22 -> isBold = false
                24 -> isUnderline = false

                30 -> textColor = Color.parseColor("#1E293B")
                31 -> textColor = Color.parseColor("#EF4444")
                32 -> textColor = Color.parseColor("#22C55E")
                33 -> textColor = Color.parseColor("#EAB308")
                34 -> textColor = Color.parseColor("#3B82F6")
                35 -> textColor = Color.parseColor("#A855F7")
                36 -> textColor = Color.parseColor("#06B6D4")
                37 -> textColor = Color.parseColor("#F8FAFC")
                39 -> textColor = null

                90 -> textColor = Color.parseColor("#64748B")
                91 -> textColor = Color.parseColor("#F87171")
                92 -> textColor = Color.parseColor("#4ADE80")
                93 -> textColor = Color.parseColor("#FACC15")
                94 -> textColor = Color.parseColor("#60A5FA")
                95 -> textColor = Color.parseColor("#C084FC")
                96 -> textColor = Color.parseColor("#22D3EE")
                97 -> textColor = Color.parseColor("#FFFFFF")

                40 -> bgColor = Color.parseColor("#0F1113")
                41 -> bgColor = Color.parseColor("#7F1D1D")
                42 -> bgColor = Color.parseColor("#14532D")
                43 -> bgColor = Color.parseColor("#713F12")
                44 -> bgColor = Color.parseColor("#1E3A8A")
                45 -> bgColor = Color.parseColor("#581C87")
                46 -> bgColor = Color.parseColor("#164E63")
                47 -> bgColor = Color.parseColor("#334155")
                49 -> bgColor = null

                38, 48 -> {
                    val isFg = code == 38
                    val mode = parts.getOrNull(idx + 1)?.toIntOrNull()
                    if (mode == 5) {
                        val n = parts.getOrNull(idx + 2)?.toIntOrNull()
                        if (n != null) {
                            val color = xterm256(n)
                            if (isFg) textColor = color else bgColor = color
                        }
                        idx += 2
                    } else if (mode == 2) {
                        val r = parts.getOrNull(idx + 2)?.toIntOrNull()
                        val g = parts.getOrNull(idx + 3)?.toIntOrNull()
                        val b = parts.getOrNull(idx + 4)?.toIntOrNull()
                        if (r != null && g != null && b != null) {
                            val color = Color.rgb(
                                r.coerceIn(0, 255),
                                g.coerceIn(0, 255),
                                b.coerceIn(0, 255)
                            )
                            if (isFg) textColor = color else bgColor = color
                        }
                        idx += 4
                    }
                }
            }
            idx++
        }
    }

    private fun xterm256(n: Int): Int {
        return when {
            n < 0 -> Color.WHITE
            n < 16 -> XTERM16[n]
            n < 232 -> {
                val v = n - 16
                val r = v / 36
                val g = (v % 36) / 6
                val b = v % 6
                fun level(x: Int) = if (x == 0) 0 else 55 + x * 40
                Color.rgb(level(r), level(g), level(b))
            }

            n < 256 -> {
                val gray = 8 + (n - 232) * 10
                Color.rgb(gray, gray, gray)
            }

            else -> Color.WHITE
        }
    }

    private val XTERM16 = intArrayOf(
        Color.parseColor("#000000"),
        Color.parseColor("#CD0000"),
        Color.parseColor("#00CD00"),
        Color.parseColor("#CDCD00"),
        Color.parseColor("#0000EE"),
        Color.parseColor("#CD00CD"),
        Color.parseColor("#00CDCD"),
        Color.parseColor("#E5E5E5"),
        Color.parseColor("#7F7F7F"),
        Color.parseColor("#FF0000"),
        Color.parseColor("#00FF00"),
        Color.parseColor("#FFFF00"),
        Color.parseColor("#5C5CFF"),
        Color.parseColor("#FF00FF"),
        Color.parseColor("#00FFFF"),
        Color.parseColor("#FFFFFF")
    )
}
