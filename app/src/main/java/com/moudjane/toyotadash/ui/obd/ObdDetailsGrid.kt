package com.moudjane.toyotadash.ui.obd

import androidx.compose.foundation.background
import com.moudjane.toyotadash.core.dash.PidReading
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Every PID as a card with its raw hex — the diagnostic view behind "DÉTAILS". */
@Composable
fun ObdDetailsGrid(state: ObdUiState, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns               = GridCells.Adaptive(minSize = 168.dp),
        modifier              = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp),
    ) {
        if (state.toyotaReadings.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("TOYOTA HYBRID", Dash.toyotaRed)
            }
            items(state.toyotaReadings) { reading -> PidCard(reading, Dash.toyotaRed) }
        }

        if (state.standardReadings.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("STANDARD OBD2", Dash.cyan)
            }
            items(state.standardReadings) { reading -> PidCard(reading, Dash.cyan) }
        }

        if (state.toyotaReadings.isEmpty() && state.standardReadings.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text      = "Lecture des PID…",
                    fontSize  = 20.sp,
                    color     = Dash.textDim,
                    modifier  = Modifier.padding(32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text          = title,
            fontSize      = 13.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color         = Dash.text,
        )
        Spacer(Modifier.width(12.dp))
        // Hairline that fills the rest of the row, fading out.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(listOf(Dash.hairline, Color.Transparent))
                )
        )
    }
}

@Composable
private fun PidCard(r: PidReading, accent: Color) {
    val valueColor = when {
        !r.isOk                   -> Dash.textDim
        else                      -> thresholdColor(r)
            ?: if (r.notes.startsWith("⚠")) Dash.amber  // amber: formula not yet verified
               else Dash.text
    }
    val cardFill = Brush.verticalGradient(listOf(Dash.panelHi, Dash.panel))

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardFill)
            .border(1.dp, Dash.hairline, RoundedCornerShape(12.dp)),
    ) {
        // Left accent spine — colours the card by category, dimmed when stale.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(if (r.isOk) 86.dp else 72.dp)
                .background(if (r.isOk) accent else Dash.hairline)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text          = r.name.uppercase(),
                fontSize      = 10.sp,
                letterSpacing = 0.8.sp,
                fontWeight    = FontWeight.Medium,
                color         = Dash.textDim,
                textAlign     = TextAlign.Center,
                maxLines      = 1,
                overflow      = TextOverflow.Ellipsis,
                modifier      = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))

            if (r.isOk) {
                // Value + unit on one baseline: big number, small unit suffix.
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.Light)) {
                            append(formatValue(r.value, r.unit))
                        }
                        if (r.unit.isNotEmpty()) {
                            withStyle(
                                SpanStyle(fontSize = 13.sp, color = Dash.textDim, fontWeight = FontWeight.Medium)
                            ) {
                                append("  ${r.unit}")
                            }
                        }
                    },
                    
                    color      = valueColor,
                    textAlign  = TextAlign.Center,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text      = "N/A",
                    fontSize  = 24.sp,
                    fontWeight = FontWeight.Bold,
                    
                    color     = Dash.textDim,
                    textAlign = TextAlign.Center,
                )
            }

            // Raw hex bytes — useful for verifying Toyota PID formulas.
            Text(
                text      = r.rawHex.take(20),
                fontSize  = 9.sp,
                
                color     = Dash.textFaint,
                textAlign = TextAlign.Center,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                modifier  = Modifier.padding(top = 4.dp).fillMaxWidth(),
            )
        }
    }
}

/**
 * Colour a value by how healthy it is for a Yaris hybrid.
 * Returns null for metrics that have no meaningful "good/bad" range (speed, raw
 * voltages, motor RPM…) — those keep the neutral white.
 */
private fun thresholdColor(r: PidReading): Color? {
    val n = r.name.lowercase()
    val v = r.value
    return when {
        // Coolant: normal ~70-100 °C, watch 100-110, overheat above.
        "coolant" in n -> when {
            v >= 110f -> Dash.red
            v >= 100f -> Dash.amber
            else      -> Dash.green
        }
        // HV battery pack temperature.
        "battery temp" in n -> when {
            v >= 55f -> Dash.red
            v >= 45f -> Dash.amber
            else     -> Dash.green
        }
        // HV battery state of charge — low is the concern.
        "soc" in n -> when {
            v < 20f -> Dash.red
            v < 40f -> Dash.amber
            else    -> Dash.green
        }
        // Fuel level — low is the concern.
        "fuel level" in n -> when {
            v < 10f -> Dash.red
            v < 25f -> Dash.amber
            else    -> Dash.green
        }
        // Petrol engine RPM — approaching redline.
        n == "engine rpm" -> when {
            v >= 6000f -> Dash.red
            v >= 4500f -> Dash.amber
            else       -> Dash.green
        }
        // 12 V accessory rail — both too low and too high are bad.
        "module voltage" in n -> when {
            v < 11.5f || v > 15f -> Dash.red
            v < 12.2f            -> Dash.amber
            else                 -> Dash.green
        }
        // Fuel trims — large corrections hint at a mixture fault.
        "fuel trim" in n -> when {
            abs(v) > 25f -> Dash.red
            abs(v) > 10f -> Dash.amber
            else         -> Dash.green
        }
        else -> null
    }
}

private fun formatValue(value: Float, unit: String): String = when (unit) {
    "rpm"  -> "%.0f".format(abs(value))
    "km/h" -> "%.0f".format(value)
    "°C"   -> "%.0f".format(value)
    "%"    -> "%.1f".format(value)
    "V"    -> "%.1f".format(value)
    "A"    -> "%.1f".format(value)
    "g/s"  -> "%.2f".format(value)
    "kPa"  -> "%.1f".format(value)
    "s"    -> "%.0f".format(value)
    else   -> "%.1f".format(value)
}
