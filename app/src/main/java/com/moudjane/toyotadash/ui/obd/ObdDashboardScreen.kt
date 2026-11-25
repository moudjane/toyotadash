package com.moudjane.toyotadash.ui.obd

import androidx.compose.animation.AnimatedVisibility
import com.moudjane.toyotadash.core.dash.DriveSelect
import com.moudjane.toyotadash.core.dash.EnergyFlows
import com.moudjane.toyotadash.core.dash.HybridState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen hybrid cockpit for a landscape head unit.
 *
 *   BATTERIE  |      speed inside RPM + power rings      |  MOTEUR
 *   ─────────────────── energy history ───────────────────────────
 *
 * No boxes: elements float on a deep background whose glow follows the drive mode.
 * The "≡" button opens the raw PID grid (and the disconnect action).
 */
@Composable
fun ObdDashboardScreen(state: ObdUiState, vm: ObdViewModel) {
    var showDetails by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Flat.bg)) {
        Cockpit(state, onOpenDetails = { showDetails = true })

        AnimatedVisibility(
            visible = showDetails,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { it / 12 },
        ) {
            DetailsOverlay(
                state = state,
                onClose = { showDetails = false },
                onDisconnect = { showDetails = false; vm.disconnect() },
            )
        }
    }
}

// ── Drive mode ─────────────────────────────────────────────────────────────

private val HybridState.label: String get() = when (this) {
    HybridState.EV     -> "ÉLECTRIQUE"
    HybridState.HYBRID -> "HYBRIDE"
    HybridState.REGEN  -> "RÉCUPÉRATION"
    HybridState.READY  -> "READY"
}

private val HybridState.color: Color get() = when (this) {
    HybridState.EV, HybridState.REGEN -> Flat.regen
    HybridState.HYBRID                -> Flat.text
    HybridState.READY                 -> Flat.text2
}

// ── Cockpit ────────────────────────────────────────────────────────────────

/**
 *   READY / ÉLECTRIQUE …                 BATTERIE HYBRIDE
 *   ███ speed ███  km/h                  67 %  ─────────────
 *                                        ▮▮▮▮▮▮▮▮▮▮  blocks
 *   PUISSANCE  ◀ regen ───|─── power ▶   165 V   12 A   34 °C
 *   Moteur thermique 1 850 tr/min        flow strip
 *   ─────────────────────────────────────────────────────────────
 *   MOTEUR 82 °C · PÉDALE 30 % · …       energy, last minute
 */
@Composable
private fun Cockpit(s: ObdUiState, onOpenDetails: () -> Unit) {
    val rpm      = s.std("010C")
    val speed    = s.std("010D")
    val pedal    = s.std("0149") ?: s.std("0111")
    val coolant  = s.std("0105")
    val fuel     = s.std("012F")
    val load     = s.std("0104")
    val ambient  = s.std("0146")
    val volts12  = s.std("0142") ?: s.adapterVoltage
    val soc      = s.std("015B")
    val hvVolt   = s.toyota("HV Voltage")
    val hvAmp    = s.toyota("HV Current")
    val hvTemp   = s.toyota("HV Battery Temp")
    val blocks   = (1..10).map { s.toyota("HV Block $it") }
    // Positive = power drawn from the battery (assist), negative = regen / charging.
    val kw = if (hvAmp != null && hvVolt != null) hvAmp * hvVolt / 1000f else null
    val mode = HybridState.from(rpm, speed, hvAmp)
    val flows = EnergyFlows.from(rpm, speed, hvAmp)

    val history = remember { mutableStateListOf<Float>() }
    val latestKw by rememberUpdatedState(kw)
    LaunchedEffect(Unit) {
        while (true) {
            latestKw?.let {
                history.add(it)
                if (history.size > HISTORY_POINTS) history.removeAt(0)
            }
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(Flat.bg).padding(horizontal = 36.dp, vertical = 16.dp)) {
        StatusLine(s, ambient = ambient, volts12 = volts12, onOpenDetails = onOpenDetails)

        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // ── Left: speed + power
            Column(Modifier.weight(1.1f)) {
                val modeColor by animateColorAsState(mode.color, tween(400), label = "mode")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mode.label, color = modeColor, fontSize = 15.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(16.dp))
                    DriveSelectPills(s.driveSelect)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        speed?.roundToInt()?.toString() ?: "—",
                        color = Flat.text, fontSize = 168.sp, lineHeight = 150.sp,
                        fontWeight = FontWeight.Light, style = Tnum, letterSpacing = (-4).sp,
                    )
                    Text("km/h", color = Flat.text2, fontSize = 20.sp, fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(start = 10.dp, bottom = 26.dp))
                }
                Spacer(Modifier.height(12.dp))
                PowerMeter(kw, Modifier.fillMaxWidth().padding(end = 40.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    when {
                        rpm == null -> "Moteur thermique —"
                        rpm > 200f -> "Moteur thermique  " + "%,d".format(rpm.roundToInt()).replace(',', ' ') + " tr/min"
                        else -> "Moteur thermique arrêté"
                    },
                    color = if ((rpm ?: 0f) > 200f) Flat.text2 else Flat.text3, fontSize = 15.sp, style = Tnum,
                )
            }

            Box(Modifier.width(1.dp).fillMaxHeight(0.8f).background(Flat.line))

            // ── Right: battery
            Column(Modifier.weight(1f).padding(start = 36.dp)) {
                Text("BATTERIE HYBRIDE", color = Flat.text2, fontSize = 13.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(soc?.roundToInt()?.toString() ?: "—", color = Flat.text, fontSize = 76.sp, lineHeight = 72.sp,
                        fontWeight = FontWeight.Light, style = Tnum, letterSpacing = (-2).sp)
                    Text("%", color = Flat.text2, fontSize = 24.sp, fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                }
                SocBar(soc, Modifier.fillMaxWidth().height(6.dp))
                Spacer(Modifier.height(12.dp))
                BlockStrip(blocks, Modifier.fillMaxWidth().height(22.dp))
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    Metric("TENSION", hvVolt?.let { "%.0f".format(it) }, "V", Modifier.weight(1f))
                    Metric(
                        if ((hvAmp ?: 0f) < -0.5f) "RECHARGE" else "COURANT",
                        hvAmp?.let { "%.0f".format(abs(it)) }, "A", Modifier.weight(1f),
                        color = if ((hvAmp ?: 0f) < -0.5f) Flat.regen else Flat.text,
                    )
                    Metric("TEMP.", hvTemp?.let { "%.0f".format(it) }, "°C", Modifier.weight(1f),
                        color = when { (hvTemp ?: 0f) >= 55f -> Flat.alert; (hvTemp ?: 0f) >= 45f -> Flat.warn; else -> Flat.text })
                }
                Spacer(Modifier.height(18.dp))
                FlowStrip(flows, Modifier.fillMaxWidth())
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Flat.line))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Metric("MOTEUR", coolant?.let { "%.0f".format(it) }, "°C", Modifier.weight(1f),
                color = when { (coolant ?: 0f) >= 110f -> Flat.alert; (coolant ?: 0f) >= 100f -> Flat.warn; else -> Flat.text })
            Metric("PÉDALE", pedal?.let { "%.0f".format(it) }, "%", Modifier.weight(1f))
            if (fuel != null) Metric("ESSENCE", "%.0f".format(fuel), "%", Modifier.weight(1f),
                color = if (fuel < 12f) Flat.alert else if (fuel < 25f) Flat.warn else Flat.text)
            else Metric("CHARGE", load?.let { "%.0f".format(it) }, "%", Modifier.weight(1f))
            Column(Modifier.weight(2.2f)) {
                Text("ÉNERGIE · DERNIÈRE MINUTE", color = Flat.text3, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                FlatEnergyChart(history, Modifier.fillMaxWidth().height(40.dp))
            }
        }
    }
}

/**
 * The car's ECO / EV / POWER mode buttons, as pills. The active one is filled
 * (black text on colour), the others are hidden: only what is on is shown.
 */
@Composable
private fun DriveSelectPills(select: DriveSelect) {
    val (label, color) = when (select) {
        DriveSelect.ECO   -> "ECO" to Flat.regen
        DriveSelect.EV    -> "EV MODE" to Color(0xFF4FC3F7)
        DriveSelect.POWER -> "POWER" to Flat.engine
        else              -> return
    }
    AnimatedContent(
        targetState = label to color,
        transitionSpec = { (fadeIn(tween(250)) + scaleIn(initialScale = 0.85f)) togetherWith fadeOut(tween(150)) },
        label = "drive-select",
    ) { (text, c) ->
        Text(
            text, color = Color.Black, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(c)
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
}

/** Small label over a large light number. */
@Composable
private fun Metric(label: String, value: String?, unit: String, modifier: Modifier = Modifier, color: Color = Flat.text) {
    Column(modifier) {
        Text(label, color = Flat.text3, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value ?: "—", color = if (value != null) color else Flat.text3, fontSize = 28.sp,
                fontWeight = FontWeight.Light, style = Tnum)
            Text(" $unit", color = Flat.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

/**
 * Power meter, Polestar-style: one flat bar with zero at 30 %. To the right, power
 * drawn from the battery (white); to the left, energy recovered (green).
 */
@Composable
private fun PowerMeter(kw: Float?, modifier: Modifier = Modifier, maxPower: Float = 25f, maxRegen: Float = 15f) {
    val zero = 0.3f
    val k = kw ?: 0f
    val target = if (k >= 0f) (k / maxPower).coerceIn(0f, 1f) else -(-k / maxRegen).coerceIn(0f, 1f)
    val f = animated(target, Spring.StiffnessMediumLow)
    val regen = k < -0.1f
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(if (regen) "RECHARGE" else "PUISSANCE", color = if (regen) Flat.regen else Flat.text2,
                fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(kw?.let { "%.1f".format(abs(it)) } ?: "—", color = if (regen) Flat.regen else Flat.text,
                fontSize = 22.sp, fontWeight = FontWeight.Light, style = Tnum)
            Text(" kW", color = Flat.text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = 8.dp.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            val z = size.width * zero
            drawRoundRect(Flat.track, Offset(0f, y), Size(size.width, h), r)
            if (f > 0f) {
                drawRoundRect(Flat.text, Offset(z, y), Size((size.width - z) * f, h), r)
            } else if (f < 0f) {
                val w = z * -f
                drawRoundRect(Flat.regen, Offset(z - w, y), Size(w, h), r)
            }
            // Zero mark
            drawRect(Flat.bg, Offset(z - 1.5f, 0f), Size(3f, size.height))
            drawRect(Flat.text2, Offset(z - 0.75f, 0f), Size(1.5f, size.height))
        }
    }
}

@Composable
private fun SocBar(soc: Float?, modifier: Modifier = Modifier) {
    val f = animated(((soc ?: 0f) / 100f).coerceIn(0f, 1f))
    val color = when { soc == null -> Flat.text3; soc < 30f -> Flat.alert; soc < 40f -> Flat.warn; else -> Flat.text }
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Flat.track, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
        // Toyota keeps the NiMH pack roughly between 40 and 80 %: mark that band.
        for (m in listOf(0.4f, 0.8f)) drawRect(Flat.bg, Offset(size.width * m - 1f, 0f), Size(2f, size.height))
    }
}

/** The 10 NiMH blocks: bar height = block voltage between the weakest and the strongest. */
@Composable
private fun BlockStrip(blocks: List<Float?>, modifier: Modifier = Modifier) {
    val known = blocks.filterNotNull()
    val lo = known.minOrNull()
    val hi = known.maxOrNull()
    Canvas(modifier) {
        val n = 10
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val v = blocks.getOrNull(i)
            val f = if (v == null || lo == null || hi == null || hi - lo < 0.001f) 0.6f
                    else 0.35f + 0.65f * (v - lo) / (hi - lo)
            val h = size.height * f
            val weak = v != null && v == lo && hi != null && hi - lo > 0.06f
            drawRoundRect(
                if (v == null) Flat.track else if (weak) Flat.warn else Flat.text3,
                Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(2f),
            )
        }
    }
}

/** What the hybrid system is doing, in plain words. */
private fun flowSentence(f: EnergyFlows): String = when {
    f.engineToWheels && f.engineToBattery -> "Le moteur fait avancer la voiture et recharge la batterie"
    f.engineToWheels && f.batteryToWheels -> "Moteur et batterie font avancer la voiture ensemble"
    f.engineToWheels                      -> "Le moteur thermique fait avancer la voiture"
    f.batteryToWheels                     -> "La batterie fait avancer la voiture"
    f.wheelsToBattery                     -> "Le freinage recharge la batterie"
    f.engineToBattery                     -> "Le moteur recharge la batterie à l'arrêt"
    else                                  -> "Aucun échange d'énergie"
}

/**
 * Energy flow, readable at a glance: a sentence saying what happens, then
 * MOTEUR — ROUES — BATTERIE with chevrons (›››) running in the direction of the energy.
 * Amber = petrol engine, white = electric drive, green = energy going into the battery.
 * Engine → battery charging is drawn as an arc above, so it never mixes with the rest.
 */
@Composable
private fun FlowStrip(flows: EnergyFlows, modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "chevrons").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)), label = "chevrons",
    )
    val engineOn = flows.engineToWheels || flows.engineToBattery
    val wheelsOn = flows.engineToWheels || flows.batteryToWheels || flows.wheelsToBattery
    val charging = flows.wheelsToBattery || flows.engineToBattery
    val battOn = flows.batteryToWheels || charging

    Column(modifier) {
        Text("FLUX D'ÉNERGIE", color = Flat.text3, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(flowSentence(flows), color = Flat.text, fontSize = 15.sp, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val node = 7.dp.toPx()
            val y = size.height * 0.72f
            val e = Offset(node + 2f, y)
            val w = Offset(size.width / 2f, y)
            val b = Offset(size.width - node - 2f, y)

            // Resting track
            drawLine(Flat.track, Offset(e.x + node, y), Offset(w.x - node, y), 2.dp.toPx())
            drawLine(Flat.track, Offset(w.x + node, y), Offset(b.x - node, y), 2.dp.toPx())

            fun straight(from: Offset, to: Offset) = Path().apply {
                val dir = if (to.x > from.x) 1f else -1f
                moveTo(from.x + dir * node * 1.8f, from.y)
                lineTo(to.x - dir * node * 1.8f, to.y)
            }
            if (flows.engineToWheels) chevrons(straight(e, w), phase, Flat.engine)
            if (flows.batteryToWheels) chevrons(straight(b, w), phase, Flat.text)
            if (flows.wheelsToBattery) chevrons(straight(w, b), phase, Flat.regen)
            if (flows.engineToBattery) {
                val arc = Path().apply {
                    moveTo(e.x + node * 0.8f, y - node * 1.4f)
                    quadraticTo(size.width / 2f, -size.height * 0.55f, b.x - node * 0.8f, y - node * 1.4f)
                }
                drawPath(arc, Flat.regen.copy(alpha = 0.25f), style = Stroke(1.5.dp.toPx()))
                chevrons(arc, phase, Flat.regen)
            }

            fun nodeAt(c: Offset, on: Boolean, color: Color) {
                drawCircle(Flat.bg, node, c)
                drawCircle(if (on) color else Flat.text3, node, c, style = Stroke(2.dp.toPx()))
                if (on) drawCircle(color, node * 0.4f, c)
            }
            nodeAt(e, engineOn, Flat.engine)
            nodeAt(w, wheelsOn, Flat.text)
            nodeAt(b, battOn, if (charging) Flat.regen else Flat.text)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            FlowLabel("MOTEUR", engineOn, Modifier.weight(1f), TextAlign.Start)
            FlowLabel("ROUES", wheelsOn, Modifier.weight(1f), TextAlign.Center)
            FlowLabel("BATTERIE", battOn, Modifier.weight(1f), TextAlign.End)
        }
    }
}

@Composable
private fun FlowLabel(text: String, on: Boolean, modifier: Modifier, align: TextAlign) {
    Text(text, color = if (on) Flat.text else Flat.text3, fontSize = 11.sp, letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium, textAlign = align, modifier = modifier)
}

/** Chevrons ("›") marching along [path] in its drawing direction. */
private fun DrawScope.chevrons(path: Path, phase: Float, color: Color) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val len = measure.length
    if (len <= 0f) return
    val spacing = 13.dp.toPx()
    val arm = 4.5.dp.toPx()
    var d = phase * spacing
    while (d < len) {
        val pos = measure.getPosition(d)
        val tan = measure.getTangent(d)
        // Fade in / out at both ends so chevrons appear to flow out of and into the nodes.
        val edge = minOf(d, len - d) / (spacing * 1.5f)
        val alpha = edge.coerceIn(0f, 1f)
        // Back of the chevron: two points behind the tip, rotated ±40°.
        val bx = -tan.x * arm
        val by = -tan.y * arm
        val c = 0.766f
        val sn = 0.643f
        val p1 = Offset(pos.x + bx * c - by * sn, pos.y + bx * sn + by * c)
        val p2 = Offset(pos.x + bx * c + by * sn, pos.y - bx * sn + by * c)
        val stroke = 2.dp.toPx()
        drawLine(color.copy(alpha = alpha), p1, pos, stroke, StrokeCap.Round)
        drawLine(color.copy(alpha = alpha), p2, pos, stroke, StrokeCap.Round)
        d += spacing
    }
}

/** Flat line chart of battery power: white above zero, green below. */
@Composable
private fun FlatEnergyChart(history: List<Float>, modifier: Modifier = Modifier, scaleKw: Float = 15f) {
    Canvas(modifier) {
        val mid = size.height / 2f
        drawLine(Flat.line, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        if (history.isEmpty()) return@Canvas
        val step = size.width / (HISTORY_POINTS - 1)
        val x0 = (HISTORY_POINTS - history.size) * step
        val barW = (step * 0.6f).coerceAtLeast(1.5f)
        history.forEachIndexed { i, kw ->
            val h = (kw / scaleKw).coerceIn(-1f, 1f) * mid
            if (abs(h) < 0.5f) return@forEachIndexed
            val x = x0 + i * step
            drawRect(
                if (kw < 0) Flat.regen else Flat.text2,
                Offset(x - barW / 2, if (h > 0) mid - h else mid), Size(barW, abs(h)),
            )
        }
    }
}

// ── Status line ────────────────────────────────────────────────────────────

@Composable
private fun StatusLine(s: ObdUiState, ambient: Float?, volts12: Float?, onOpenDetails: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse",
    )
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5_000) } }
    val clock = remember(now) { SimpleDateFormat("HH:mm", Locale.FRANCE).format(now) }

    Box(Modifier.fillMaxWidth().height(36.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).alpha(pulse).background(Flat.regen, CircleShape))
            Spacer(Modifier.width(8.dp))
            val d = s.connectedDevice
            Text(
                if (s.isSimulated) "DÉMO" else d?.name ?: "",
                color = Dash.textFaint, fontSize = 12.sp, letterSpacing = 1.sp,
            )
        }
        Text(clock, color = Dash.textDim, fontSize = 18.sp, fontWeight = FontWeight.Medium, style = Tnum,
            modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            ambient?.let {
                Text("${it.roundToInt()}°", color = Dash.textDim, fontSize = 15.sp, style = Tnum)
                Spacer(Modifier.width(16.dp))
            }
            volts12?.let {
                Text("%.1f V".format(it), color = if (it < 12.2f) Dash.amber else Dash.textDim, fontSize = 15.sp, style = Tnum)
                Spacer(Modifier.width(16.dp))
            }
            Text(
                "≡", color = Dash.textDim, fontSize = 22.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenDetails).padding(horizontal = 10.dp),
            )
        }
    }
}

// ── Details overlay ────────────────────────────────────────────────────────

@Composable
private fun DetailsOverlay(state: ObdUiState, onClose: () -> Unit, onDisconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Flat.bg).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DONNÉES BRUTES", color = Dash.text, fontSize = 16.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(14.dp))
            val ok = state.standardReadings.count { it.isOk } + state.toyotaReadings.count { it.isOk }
            val total = state.standardReadings.size + state.toyotaReadings.size
            Text("$ok / $total PID  ·  %.1f Hz".format(state.refreshHz), color = Dash.textDim, fontSize = 13.sp, style = Tnum)
            Spacer(Modifier.weight(1f))
            OverlayButton("Déconnecter", Dash.red, onDisconnect)
            Spacer(Modifier.width(10.dp))
            OverlayButton("Fermer", Dash.text, onClose)
        }
        Spacer(Modifier.height(8.dp))
        ObdDetailsGrid(state, Modifier.weight(1f))
    }
}

@Composable
private fun OverlayButton(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String, alignEnd: Boolean = false) {
    Box(Modifier.fillMaxWidth()) {
        Text(
            text, color = Dash.textFaint, fontSize = 11.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}

/** Blue when cold, white in range, amber / red when hot. */
private fun tempColor(v: Float?, warn: Float, crit: Float, cold: Float? = null): Color = when {
    v == null                -> Dash.text
    v >= crit                -> Dash.red
    v >= warn                -> Dash.amber
    cold != null && v < cold -> Dash.blue
    else                     -> Dash.text
}
