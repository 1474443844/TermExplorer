package com.example.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.util.regex.Pattern

object AnsiParser {
    private val ANSI_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)m")

    fun parse(text: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val matcher = ANSI_PATTERN.matcher(text)
        
        var currentStart = 0
        
        // Active styles
        var textColor: Int? = null
        var bgColor: Int? = null
        var isBold = false
        var isUnderline = false
        
        fun applyStyle(styleText: String) {
            if (styleText.isEmpty() || styleText == "0") {
                textColor = null
                bgColor = null
                isBold = false
                isUnderline = false
                return
            }
            
            val parts = styleText.split(";")
            for (part in parts) {
                val code = part.toIntOrNull() ?: continue
                when (code) {
                    0 -> { // Reset
                        textColor = null
                        bgColor = null
                        isBold = false
                        isUnderline = false
                    }
                    1 -> isBold = true
                    4 -> isUnderline = true
                    // Foreground standard colors
                    30 -> textColor = Color.parseColor("#1E293B") // Slate Black
                    31 -> textColor = Color.parseColor("#EF4444") // Red
                    32 -> textColor = Color.parseColor("#22C55E") // Green
                    33 -> textColor = Color.parseColor("#EAB308") // Yellow
                    34 -> textColor = Color.parseColor("#3B82F6") // Blue
                    35 -> textColor = Color.parseColor("#A855F7") // Magenta
                    36 -> textColor = Color.parseColor("#06B6D4") // Cyan
                    37 -> textColor = Color.parseColor("#F8FAFC") // White
                    // Foreground bright colors
                    90 -> textColor = Color.parseColor("#64748B") // Bright Black (Gray)
                    91 -> textColor = Color.parseColor("#F87171") // Bright Red
                    92 -> textColor = Color.parseColor("#4ADE80") // Bright Green
                    93 -> textColor = Color.parseColor("#FACC15") // Bright Yellow
                    94 -> textColor = Color.parseColor("#60A5FA") // Bright Blue
                    95 -> textColor = Color.parseColor("#C084FC") // Bright Magenta
                    96 -> textColor = Color.parseColor("#22D3EE") // Bright Cyan
                    97 -> textColor = Color.parseColor("#FFFFFF") // Bright White
                    // Background standard colors
                    40 -> bgColor = Color.parseColor("#0F1113") // Dark Slate
                    41 -> bgColor = Color.parseColor("#7F1D1D") // Dark Red
                    42 -> bgColor = Color.parseColor("#14532D") // Dark Green
                    43 -> bgColor = Color.parseColor("#713F12") // Dark Yellow
                    44 -> bgColor = Color.parseColor("#1E3A8A") // Dark Blue
                    45 -> bgColor = Color.parseColor("#581C87") // Dark Magenta
                    46 -> bgColor = Color.parseColor("#164E63") // Dark Cyan
                    47 -> bgColor = Color.parseColor("#334155") // Dark White
                }
            }
        }
        
        fun appendWithStyle(segment: String) {
            val startIdx = ssb.length
            ssb.append(segment)
            val endIdx = ssb.length
            
            if (startIdx < endIdx) {
                textColor?.let {
                    ssb.setSpan(ForegroundColorSpan(it), startIdx, endIdx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bgColor?.let {
                    ssb.setSpan(BackgroundColorSpan(it), startIdx, endIdx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (isBold) {
                    ssb.setSpan(StyleSpan(Typeface.BOLD), startIdx, endIdx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (isUnderline) {
                    ssb.setSpan(UnderlineSpan(), startIdx, endIdx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            
            // Append previous plain text segment
            if (start > currentStart) {
                val plainText = text.substring(currentStart, start)
                appendWithStyle(plainText)
            }
            
            // Apply new style codes
            val styleCodes = matcher.group(1) ?: ""
            applyStyle(styleCodes)
            
            currentStart = end
        }
        
        // Append remaining text
        if (currentStart < text.length) {
            val plainText = text.substring(currentStart)
            appendWithStyle(plainText)
        }
        
        return ssb
    }
}
