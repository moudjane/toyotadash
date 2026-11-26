package com.moudjane.toyotadash.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.res.ResourcesCompat
import com.moudjane.toyotadash.R
import com.moudjane.toyotadash.core.dash.DashValues
import com.moudjane.toyotadash.core.dash.DriveSelect
import com.moudjane.toyotadash.core.dash.EnergyFlows
import com.moudjane.toyotadash.core.dash.HybridState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the cockpit onto the Android Auto navigation surface. Same reading as the head-unit /
 * CarPlay cockpit: mode + big speed + power on the left, the hybrid battery on the right, a thin
 * metrics row at the bottom. Dark, flat, colour only when it means something.
 */
class CockpitRenderer(private val context: Context) : SurfaceCallback {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private val lock = Any()
    private var surface: Surface? = null
    private var visibleArea = Rect()

    // Fonts (Barlow, same as the app)
    private val thin = font(R.font.barlow_thin)
    private val light = font(R.font.barlow_light)
    private val regular = font(R.font.barlow_regular)
    private val medium = font(R.font.barlow_medium)

    private fun font(id: Int): Typeface = ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT

    // Palette (matches the Flat theme)
    private val cText = Color.WHITE
    private val cText2 = Color.parseColor("#9A9AA0")
    private val cText3 = Color.parseColor("#55555B")
    private val cTrack = Color.parseColor("#1E1E22")
    private val cRegen = Color.parseColor("#3DDC84")
    private val cEngine = Color.parseColor("#FFB547")
    private val cEv = Color.parseColor("#4FC3F7")
    private val cLine = Color.parseColor("#1C1C1F")
    private val cCell = Color.parseColor("#4A4A50")

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()

    fun start() {
        job = scope.launch { DashHub.values.collectLatest { render(it) } }
    }

    fun stop() {
        job?.cancel()
        synchronized(lock) { surface = null }
    }

    // ── SurfaceCallback ──────────────────────────────────────────────────────
    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(lock) { surface = surfaceContainer.surface }
        render(DashHub.values.value)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        synchronized(lock) { surface = null }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        render(DashHub.values.value)
    }

    // ── Rendering ────────────────────────────────────────────────────────────
    private fun render(values: DashValues?) = synchronized(lock) {
        val s = surface ?: return
        val canvas = try { s.lockCanvas(null) } catch (e: Exception) { null } ?: return
        try {
            draw(canvas, values)
        } finally {
            try { s.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    internal fun draw(c: Canvas, v: DashValues?) {
        c.drawColor(Color.BLACK)
        val area = if (!visibleArea.isEmpty) visibleArea else Rect(0, 0, c.width, c.height)
        val sc = area.height() / 480f
        val l = area.left + area.width() * 0.045f
        val r = area.right - area.width() * 0.045f
        val t = area.top + area.height() * 0.08f
        val b = area.bottom - area.height() * 0.07f
        val w = r - l

        if (v == null) {
            text(c, "ToyotaDash", l, t + 40f * sc, light, 34f * sc, cText2)
            return
        }

        // Soft colour glow behind the cockpit, tinted by what the hybrid system is doing
        // (green = recharging, amber = petrol engine, blue = electric) — like CarPlay's Glow.
        drawGlow(c, area, tintFor(v))

        val midX = l + w * 0.52f

        // ── Left: mode + speed + power ──
        val mode = modeLabel(v.state)
        text(c, mode, l, t + 18f * sc, medium, 18f * sc, stateColor(v.state), 0.18f)
        driveBadge(c, v.driveSelect, l + measure(mode, medium, 18f * sc, 0.18f) + 16f * sc, t + 2f * sc, sc)

        val speed = v.speed?.roundToInt()?.toString() ?: "—"
        val speedBaseline = t + 170f * sc
        text(c, speed, l, speedBaseline, thin, 150f * sc, cText)
        text(c, "km/h", l + measure(speed, thin, 150f * sc) + 14f * sc, speedBaseline, regular, 22f * sc, cText2)

        powerBar(c, l, midX - 36f * sc, speedBaseline + 44f * sc, v.kw ?: 0f, sc)
        text(c, engineLine(v.rpm), l, speedBaseline + 96f * sc, regular, 15f * sc, cText2)

        // ── Right: hybrid battery + cells ──
        val rx = midX + 18f * sc
        text(c, "BATTERIE HYBRIDE", rx, t + 14f * sc, medium, 12f * sc, cText3, 0.16f)
        val soc = v.soc?.roundToInt()?.toString() ?: "—"
        val socBaseline = t + 90f * sc
        text(c, soc, rx, socBaseline, light, 68f * sc, cText)
        text(c, "%", rx + measure(soc, light, 68f * sc) + 10f * sc, socBaseline, regular, 20f * sc, cText2)
        socBar(c, rx, r, socBaseline + 22f * sc, v.soc ?: 0f, sc)
        blockStrip(c, rx, r, socBaseline + 56f * sc, v.blocks, sc)

        val amps = v.hvAmp ?: 0f
        val charging = amps < -1f
        val my = socBaseline + 100f * sc
        val col = (r - rx) / 3f
        metric(c, "TENSION", fmt(v.hvVolt), "V", rx, my, cText, sc)
        metric(c, if (charging) "RECHARGE" else "COURANT", fmt(abs(amps)), "A",
            rx + col, my, if (charging) cRegen else cText, sc)
        metric(c, "TEMP.", fmt(v.hvTemp), "°C", rx + 2f * col, my,
            if ((v.hvTemp ?: 0f) > 45f) cEngine else cText, sc)

        // ── Energy flow (chevrons MOTEUR → ROUES → BATTERIE) ──
        val fy = my + 44f * sc
        text(c, "FLUX D'ÉNERGIE", rx, fy, medium, 12f * sc, cText3, 0.16f)
        text(c, flowSentence(v.flows), rx, fy + 24f * sc, regular, 15f * sc, cText)
        val phase = (System.currentTimeMillis() % 900L) / 900f
        flowStrip(c, rx, r, fy + 54f * sc, v.flows, sc, phase)

        // ── Bottom-left: engine metrics ──
        val bcol = (midX - 36f * sc - l) / 3f
        metric(c, "MOTEUR", fmt(v.coolant), "°C", l, b, cText, sc)
        metric(c, "PÉDALE", fmt(v.pedal), "%", l + bcol, b, cText, sc)
        metric(c, "ESSENCE", fmt(v.fuel), "%", l + 2f * bcol, b, cText, sc)

        // ── Bottom-right: last-minute energy graph ──
        text(c, "ÉNERGIE · DERNIÈRE MINUTE", rx, b - 40f * sc, medium, 11f * sc, cText3, 0.14f)
        energyGraph(c, rx, r, b - 32f * sc, b, DashHub.kwHistory, sc)
    }

    // ── Pieces ───────────────────────────────────────────────────────────────
    private fun powerBar(c: Canvas, x0: Float, x1: Float, yTop: Float, kw: Float, sc: Float) {
        val regen = kw < -0.2f
        text(c, if (regen) "RECHARGE" else "PUISSANCE", x0, yTop, medium, 12f * sc,
            if (regen) cRegen else cText3, 0.14f)
        val kwStr = formatOneDecimal(abs(kw))
        val unitW = measure("kW", regular, 12f * sc)
        val valW = measure(kwStr, light, 22f * sc)
        text(c, "kW", x1 - unitW, yTop, regular, 12f * sc, cText2)
        text(c, kwStr, x1 - unitW - 5f * sc - valW, yTop + 2f * sc, light, 22f * sc, if (regen) cRegen else cText)

        val by = yTop + 16f * sc
        val w = x1 - x0
        val zero = x0 + w * 0.3f
        roundRect(c, x0, by, x1, by + 6f * sc, 3f * sc, cTrack)
        if (kw >= 0f) {
            val to = zero + (w - w * 0.3f) * (kw / 30f).coerceIn(0f, 1f)
            roundRect(c, zero, by, to, by + 6f * sc, 3f * sc, cText)
        } else {
            val from = zero - w * 0.3f * (-kw / 20f).coerceIn(0f, 1f)
            roundRect(c, from, by, zero, by + 6f * sc, 3f * sc, cRegen)
        }
        paint(regular, 12f * sc, cText2)
        c.drawRect(zero - 0.75f * sc, by - 5f * sc, zero + 0.75f * sc, by + 11f * sc, p)
    }

    private fun socBar(c: Canvas, x0: Float, x1: Float, y: Float, soc: Float, sc: Float) {
        roundRect(c, x0, y, x1, y + 5f * sc, 2.5f * sc, cTrack)
        val frac = (soc / 100f).coerceIn(0f, 1f)
        roundRect(c, x0, y, x0 + (x1 - x0) * frac, y + 5f * sc, 2.5f * sc, cText)
        paint(regular, 12f * sc, Color.BLACK)
        for (m in listOf(0.4f, 0.8f)) {
            val mx = x0 + (x1 - x0) * m
            c.drawRect(mx - 1f * sc, y, mx + 1f * sc, y + 5f * sc, p)
        }
    }

    private fun driveBadge(c: Canvas, sel: DriveSelect, x: Float, y: Float, sc: Float) {
        val label: String
        val color: Int
        when (sel) {
            DriveSelect.ECO -> { label = "ECO"; color = cRegen }
            DriveSelect.EV -> { label = "EV"; color = cEv }
            DriveSelect.POWER -> { label = "POWER"; color = cEngine }
            else -> return
        }
        val tw = measure(label, medium, 12f * sc, 0.1f)
        roundRect(c, x, y, x + tw + 20f * sc, y + 22f * sc, 11f * sc, color)
        text(c, label, x + 10f * sc, y + 16f * sc, medium, 12f * sc, Color.BLACK, 0.1f)
    }

    private fun metric(c: Canvas, label: String, value: String, unit: String, x: Float, y: Float, color: Int, sc: Float) {
        text(c, label, x, y - 26f * sc, medium, 11f * sc, cText3, 0.14f)
        text(c, value, x, y, light, 25f * sc, color)
        text(c, unit, x + measure(value, light, 25f * sc) + 6f * sc, y, regular, 13f * sc, cText2)
    }

    /** The 10 battery blocks: height = voltage relative to the pack, the weakest one in amber. */
    private fun blockStrip(c: Canvas, x0: Float, x1: Float, yBottom: Float, blocks: List<Float?>, sc: Float) {
        if (blocks.isEmpty()) return
        val valid = blocks.filterNotNull().filter { it.isFinite() }
        val lo = valid.minOrNull() ?: 0f
        val hi = valid.maxOrNull() ?: 0f
        val n = blocks.size
        val gap = 4f * sc
        val cw = (x1 - x0 - gap * (n - 1)) / n
        val base = 7f * sc
        val span = 15f * sc
        for ((i, bv) in blocks.withIndex()) {
            val x = x0 + i * (cw + gap)
            val tNorm = if (bv != null && bv.isFinite() && hi - lo > 0.01f) ((bv - lo) / (hi - lo)) else 0.5f
            val hgt = base + span * tNorm.coerceIn(0f, 1f)
            val weakest = bv != null && bv == lo && hi - lo > 0.05f
            roundRect(c, x, yBottom - hgt, x + cw, yBottom, 2f * sc, if (weakest) cEngine else cCell)
        }
    }

    /** MOTEUR — ROUES — BATTERIE with chevrons running in the direction of the energy. */
    private fun flowStrip(c: Canvas, x0: Float, x1: Float, y: Float, flows: EnergyFlows, sc: Float, phase: Float) {
        val mid = (x0 + x1) / 2f
        val xs = floatArrayOf(x0 + 6f * sc, mid, x1 - 6f * sc)
        if (flows.engineToWheels) chevrons(c, xs[0], xs[1], y, phase, cEngine, sc)
        when {
            flows.batteryToWheels -> chevrons(c, xs[2], xs[1], y, phase, cText, sc)
            flows.wheelsToBattery || flows.engineToBattery -> chevrons(c, xs[1], xs[2], y, phase, cRegen, sc)
        }
        p.style = Paint.Style.FILL; p.color = cText
        for (x in xs) c.drawCircle(x, y, 4.5f * sc, p)
        text(c, "MOTEUR", x0, y + 26f * sc, medium, 10f * sc, cText2, 0.12f)
        val roues = "ROUES"
        text(c, roues, mid - measure(roues, medium, 10f * sc, 0.12f) / 2f, y + 26f * sc, medium, 10f * sc, cText2, 0.12f)
        val bat = "BATTERIE"
        text(c, bat, x1 - measure(bat, medium, 10f * sc, 0.12f), y + 26f * sc, medium, 10f * sc, cText2, 0.12f)
    }

    private fun chevrons(c: Canvas, from: Float, to: Float, y: Float, phase: Float, color: Int, sc: Float) {
        val dir = if (to > from) 1f else -1f
        val length = abs(to - from) - 20f * sc
        if (length <= 0f) return
        val step = 13f * sc
        val count = (length / step).toInt()
        p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * sc
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        for (i in 0 until count) {
            val x = from + dir * (10f * sc + (i + phase) * step)
            path.reset()
            path.moveTo(x - dir * 3f * sc, y - 4f * sc)
            path.lineTo(x + dir * 2f * sc, y)
            path.lineTo(x - dir * 3f * sc, y + 4f * sc)
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
    }

    /** Power over the last minute: drawn above the line (grey), recovered below (green). */
    private fun energyGraph(c: Canvas, x0: Float, x1: Float, yTop: Float, yBottom: Float, history: List<Float>, sc: Float) {
        val mid = (yTop + yBottom) / 2f
        p.style = Paint.Style.FILL; p.color = cLine
        c.drawRect(x0, mid - 0.5f * sc, x1, mid + 0.5f * sc, p)
        if (history.isEmpty()) return
        val slots = DashHub.HISTORY
        val cw = (x1 - x0) / slots
        val scale = maxOf(history.maxOf { abs(it) }, 10f)
        val start = slots - history.size
        for ((i, kw) in history.withIndex()) {
            val hgt = maxOf(abs(kw) / scale * (mid - yTop), 1f)
            val x = x0 + (start + i) * cw
            p.color = if (kw >= 0f) cText2 else cRegen
            if (kw >= 0f) c.drawRect(x, mid - hgt, x + maxOf(cw - 1f, 1f), mid, p)
            else c.drawRect(x, mid, x + maxOf(cw - 1f, 1f), mid + hgt, p)
        }
    }

    /** What the hybrid system is doing, in plain words. */
    private fun flowSentence(f: EnergyFlows): String = when {
        f.engineToWheels && f.engineToBattery -> "Le moteur avance la voiture et recharge la batterie"
        f.engineToWheels && f.batteryToWheels -> "Moteur et batterie font avancer la voiture"
        f.engineToWheels -> "Le moteur thermique fait avancer la voiture"
        f.batteryToWheels -> "La batterie fait avancer la voiture"
        f.wheelsToBattery -> "Le freinage recharge la batterie"
        f.engineToBattery -> "Le moteur recharge la batterie à l'arrêt"
        else -> "Aucun échange d'énergie"
    }

    // ── Text / shape helpers ─────────────────────────────────────────────────
    private fun paint(tf: Typeface, size: Float, color: Int, spacing: Float = 0f): Paint {
        p.typeface = tf; p.textSize = size; p.color = color; p.letterSpacing = spacing; p.style = Paint.Style.FILL
        return p
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, tf: Typeface, size: Float, color: Int, spacing: Float = 0f) {
        c.drawText(s, x, y, paint(tf, size, color, spacing))
    }

    private fun measure(s: String, tf: Typeface, size: Float, spacing: Float = 0f): Float =
        paint(tf, size, cText, spacing).measureText(s)

    private fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        p.color = color; p.letterSpacing = 0f; p.style = Paint.Style.FILL
        rf.set(l, t, r, b); c.drawRoundRect(rf, rad, rad, p)
    }

    private fun modeLabel(s: HybridState) = when (s) {
        HybridState.EV -> "ÉLECTRIQUE"
        HybridState.HYBRID -> "HYBRIDE"
        HybridState.REGEN -> "RÉCUPÉRATION"
        HybridState.READY -> "READY"
    }

    private fun stateColor(s: HybridState) = when (s) {
        HybridState.EV, HybridState.REGEN -> cRegen
        HybridState.HYBRID -> cText
        HybridState.READY -> cText2
    }

    /** Colour of the background glow, matching CarPlay's logic. */
    private fun tintFor(v: DashValues): Int = when {
        (v.kw ?: 0f) < -0.2f -> cRegen
        (v.rpm ?: 0f) > 200f -> cEngine
        v.state == HybridState.EV -> cEv
        else -> Color.parseColor("#3A3A44")
    }

    private fun drawGlow(c: Canvas, area: Rect, tint: Int) {
        val w = area.width().toFloat()
        val h = area.height().toFloat()
        glowBlob(c, area, area.left + w * 0.16f, area.bottom - h * 0.12f, w * 0.52f, tint, 0.42f)
        glowBlob(c, area, area.right - w * 0.14f, area.top + h * 0.10f, w * 0.46f, tint, 0.24f)
    }

    private fun glowBlob(c: Canvas, area: Rect, cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val center = (color and 0x00FFFFFF) or (a shl 24)
        val edge = color and 0x00FFFFFF // same hue, fully transparent
        p.shader = RadialGradient(cx, cy, radius, center, edge, Shader.TileMode.CLAMP)
        c.drawRect(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(), p)
        p.shader = null
    }

    private fun engineLine(rpm: Float?): String =
        if ((rpm ?: 0f) > 200f) "Moteur thermique  ${rpm!!.roundToInt()} tr/min" else "Moteur thermique arrêté"

    private fun fmt(value: Float?): String =
        if (value == null || value.isNaN()) "—" else value.roundToInt().toString()

    private fun formatOneDecimal(x: Float): String {
        val n = (x * 10f).roundToInt()
        return "${n / 10}.${abs(n % 10)}"
    }
}
