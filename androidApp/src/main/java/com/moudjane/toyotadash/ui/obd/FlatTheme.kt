package com.moudjane.toyotadash.ui.obd

import androidx.compose.ui.graphics.Color

/**
 * Flat palette shared by every screen. Inspired by Polestar / Rivian driver displays:
 * pure black, flat shapes, white type, and colour only when it means something —
 * green = recovering energy, amber = attention / petrol engine, red = problem.
 * No glow, no gradients.
 */
internal object Flat {
    val bg     = Color(0xFF000000)
    val line   = Color(0xFF1C1C1F)
    val track  = Color(0xFF1E1E22)
    val text   = Color(0xFFFFFFFF)
    val text2  = Color(0xFF9A9AA0)
    val text3  = Color(0xFF55555B)
    val regen  = Color(0xFF3DDC84)
    val engine = Color(0xFFFFB547)
    val warn   = Color(0xFFFFB547)
    val alert  = Color(0xFFFF453A)
}
