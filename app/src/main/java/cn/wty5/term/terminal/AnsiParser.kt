package cn.wty5.term.terminal

import android.graphics.Color

/**
 * Stream-safe ANSI/VT parser that emits structured events.
 *
 * One instance owns incomplete-escape carry-over and active SGR state,
 * so multi-session / restart cannot cross-contaminate.
 *
 * Readline-enabled bash redraws the prompt/line using sequences such as:
 *   ESC[K / ESC[0K / ESC[1K / ESC[2K   erase in line
 *   ESC[C / ESC[D / ESC[nG             cursor motion
 *   ESC[?1049h / ESC[?1049l            alternate screen buffer
 *   ESC[?2004h                         private modes (bracketed paste, ignored)
 */
class AnsiParser {

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
        /** DECSET/DECRST private modes, e.g. 1049 (alt screen), 25 (cursor). */
        data class SetPrivateMode(val modes: IntArray) : Event() {
            override fun equals(other: Any?): Boolean =
                other is SetPrivateMode && modes.contentEquals(other.modes)

            override fun hashCode(): Int = modes.contentHashCode()
        }

        data class ResetPrivateMode(val modes: IntArray) : Event() {
            override fun equals(other: Any?): Boolean =
                other is ResetPrivateMode && modes.contentEquals(other.modes)

            override fun hashCode(): Int = modes.contentHashCode()
        }
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
        val events = ArrayList<Event>(chunk.length.coerceAtMost(4096))
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

            // DECSET / DECRST (private modes): ESC [ ? n ; n h/l
            'h' -> {
                if (params.startsWith('?')) {
                    events.add(Event.SetPrivateMode(parsePrivateModes(params)))
                }
            }

            'l' -> {
                if (params.startsWith('?')) {
                    events.add(Event.ResetPrivateMode(parsePrivateModes(params)))
                }
            }

            else -> Unit
        }
    }

    private fun parsePrivateModes(params: String): IntArray {
        val cleaned = params.trimStart('?')
        if (cleaned.isEmpty()) return IntArray(0)
        val parts = cleaned.split(';')
        val out = ArrayList<Int>(parts.size)
        for (p in parts) {
            p.toIntOrNull()?.let { out.add(it) }
        }
        return out.toIntArray()
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

                30 -> textColor = ANSI_FG[0]
                31 -> textColor = ANSI_FG[1]
                32 -> textColor = ANSI_FG[2]
                33 -> textColor = ANSI_FG[3]
                34 -> textColor = ANSI_FG[4]
                35 -> textColor = ANSI_FG[5]
                36 -> textColor = ANSI_FG[6]
                37 -> textColor = ANSI_FG[7]
                39 -> textColor = null

                90 -> textColor = ANSI_FG_BRIGHT[0]
                91 -> textColor = ANSI_FG_BRIGHT[1]
                92 -> textColor = ANSI_FG_BRIGHT[2]
                93 -> textColor = ANSI_FG_BRIGHT[3]
                94 -> textColor = ANSI_FG_BRIGHT[4]
                95 -> textColor = ANSI_FG_BRIGHT[5]
                96 -> textColor = ANSI_FG_BRIGHT[6]
                97 -> textColor = ANSI_FG_BRIGHT[7]

                40 -> bgColor = ANSI_BG[0]
                41 -> bgColor = ANSI_BG[1]
                42 -> bgColor = ANSI_BG[2]
                43 -> bgColor = ANSI_BG[3]
                44 -> bgColor = ANSI_BG[4]
                45 -> bgColor = ANSI_BG[5]
                46 -> bgColor = ANSI_BG[6]
                47 -> bgColor = ANSI_BG[7]
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

    companion object {
        private const val ESC = '\u001B'
        private const val BEL = '\u0007'

        private val ANSI_FG = intArrayOf(
            Color.parseColor("#1E293B"),
            Color.parseColor("#EF4444"),
            Color.parseColor("#22C55E"),
            Color.parseColor("#EAB308"),
            Color.parseColor("#3B82F6"),
            Color.parseColor("#A855F7"),
            Color.parseColor("#06B6D4"),
            Color.parseColor("#F8FAFC")
        )
        private val ANSI_FG_BRIGHT = intArrayOf(
            Color.parseColor("#64748B"),
            Color.parseColor("#F87171"),
            Color.parseColor("#4ADE80"),
            Color.parseColor("#FACC15"),
            Color.parseColor("#60A5FA"),
            Color.parseColor("#C084FC"),
            Color.parseColor("#22D3EE"),
            Color.parseColor("#FFFFFF")
        )
        private val ANSI_BG = intArrayOf(
            Color.parseColor("#0F1113"),
            Color.parseColor("#7F1D1D"),
            Color.parseColor("#14532D"),
            Color.parseColor("#713F12"),
            Color.parseColor("#1E3A8A"),
            Color.parseColor("#581C87"),
            Color.parseColor("#164E63"),
            Color.parseColor("#334155")
        )

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
}
