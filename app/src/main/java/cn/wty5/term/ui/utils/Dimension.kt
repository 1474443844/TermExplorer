package cn.wty5.term.ui.utils

import android.content.Context

import android.util.TypedValue

fun Context.spToPx(sp: Float): Float {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}