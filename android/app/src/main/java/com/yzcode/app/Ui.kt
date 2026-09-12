package com.yzcode.app

import android.content.Context

object Ui {
    val BG: Int = 0xFF121212.toInt()
    val FG: Int = 0xFFE8E8E8.toInt()
    val SUBTLE: Int = 0xFF9E9E9E.toInt()
    val ERROR: Int = 0xFFFF8A80.toInt()
    val OK: Int = 0xFF7BD88F.toInt()
    val DIVIDER: Int = 0xFF2A2A2A.toInt()
    val INPUT_BG: Int = 0xFF1E1E1E.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
