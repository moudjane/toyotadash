package com.moudjane.toyotadash.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.moudjane.toyotadash.R

/**
 * Barlow (SIL OFL, see assets/licenses/Barlow-OFL.txt): a grotesk drawn from highway
 * signage and licence plates — very legible at a glance, with elegant thin weights
 * for the big numbers.
 */
val Barlow = FontFamily(
    Font(R.font.barlow_thin,        FontWeight.Thin),
    Font(R.font.barlow_extra_light, FontWeight.ExtraLight),
    Font(R.font.barlow_light,       FontWeight.Light),
    Font(R.font.barlow_regular,     FontWeight.Normal),
    Font(R.font.barlow_medium,      FontWeight.Medium),
    Font(R.font.barlow_semi_bold,   FontWeight.SemiBold),
    Font(R.font.barlow_bold,        FontWeight.Bold),
)

/** Default text style for the whole app (provided in MainActivity). */
val DashTextStyle = TextStyle(fontFamily = Barlow, fontFeatureSettings = "tnum")

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily    = Barlow,
        fontWeight    = FontWeight.Normal,
        fontSize      = 16.sp,
        lineHeight    = 24.sp,
        letterSpacing = 0.5.sp,
    )
)
