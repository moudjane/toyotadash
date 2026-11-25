package com.moudjane.toyotadash.ui.obd

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.moudjane.toyotadash.ui.theme.DashTextStyle

// ── Palette (raw-data view and shared accents) ──────────────────────────────

internal object Dash {
    val bg         = Color(0xFF040507)
    val bgLift     = Color(0xFF0B1017)
    val panel      = Color(0xFF0C1118)
    val panelHi    = Color(0xFF131A24)
    val hairline   = Color(0xFF1B222D)
    val track      = Color(0xFF161C25)
    val tick       = Color(0xFF2C3542)
    val text       = Color(0xFFF4F6FA)
    val textDim    = Color(0xFF8B95A5)
    val textFaint  = Color(0xFF4A5462)
    val cyan       = Color(0xFF38D6FF)
    val blue       = Color(0xFF3B82FF)
    val green      = Color(0xFF34E39A)
    val amber      = Color(0xFFFFB648)
    val red        = Color(0xFFFF3D55)
    val toyotaRed  = Color(0xFFEB0A1E)
}

/** Barlow with tabular figures, so digits never jiggle when values change. */
internal val Tnum: TextStyle = DashTextStyle

/** Smoothly animated version of [value], for gauges and bars. */
@Composable
internal fun animated(value: Float, stiffness: Float = Spring.StiffnessLow) =
    animateFloatAsState(
        targetValue = value,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness),
        label = "gauge",
    ).value

/** Number of samples kept for the energy history chart. */
internal const val HISTORY_POINTS = 120
