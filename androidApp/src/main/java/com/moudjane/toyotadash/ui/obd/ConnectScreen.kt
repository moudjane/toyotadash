package com.moudjane.toyotadash.ui.obd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moudjane.toyotadash.data.bluetooth.BtDevice
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runtime permissions needed to list paired dongles and scan for BLE ones. */
private fun requiredBtPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // BLE scan on Android 6-11
    }

/**
 * Home screen while no dongle is connected, in the dashboard's flat style:
 *
 *   OBD2 · YARIS 100H                          DONGLES À PROXIMITÉ   Rescanner
 *   Big statement (state)                      NORAUTOBT0000   OBD     ▂▄▆
 *   one-line explanation                       ───────────────────────────
 *   [ Reconnecter NORAUTO… ]  Mode démo        …
 */
@Composable
fun ConnectScreen(state: ObdUiState, vm: ObdViewModel) {
    val context = LocalContext.current
    fun granted() = requiredBtPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    var hasPermission by remember { mutableStateOf(granted()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = granted() }

    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(requiredBtPermissions()) }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            vm.loadPairedDevices()
            vm.autoConnectIfPossible()
            // Read the live state: autoConnect may have just switched to CONNECTING, and a
            // scan running during a BLE connection makes it fail.
            val now = vm.state.value
            if (now.bleDevices.isEmpty() && now.connectionStatus != ConnectionStatus.CONNECTING) vm.scanBle()
        }
    }

    val connecting = state.connectionStatus == ConnectionStatus.CONNECTING

    Column(Modifier.fillMaxSize().background(Flat.bg).padding(horizontal = 36.dp, vertical = 16.dp)) {
        HomeStatusLine()

        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // ── Left: state + actions
            Column(Modifier.weight(1.1f).padding(end = 36.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("TOYOTA", color = Flat.text, fontSize = 13.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
                    Text("  DASH", color = Flat.text2, fontSize = 13.sp, letterSpacing = 4.sp)
                }
                Spacer(Modifier.height(18.dp))

                val (title, subtitle, color) = when {
                    !hasPermission -> Triple(
                        "Autorisation requise",
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            "Android doit autoriser l'accès aux appareils à proximité pour trouver le dongle."
                        else "Android exige la localisation pour scanner les dongles Bluetooth LE.",
                        Flat.warn,
                    )
                    connecting -> Triple(
                        "Connexion…",
                        state.connectStep ?: "Le premier essai Bluetooth échoue souvent, l'appli réessaie toute seule.",
                        Flat.text,
                    )
                    state.connectionStatus == ConnectionStatus.ERROR -> Triple(
                        "Connexion impossible",
                        state.errorMessage ?: "",
                        Flat.alert,
                    )
                    else -> Triple(
                        "Prêt à partir",
                        "Choisis ton dongle. Ferme Car Scanner avant : un ELM327 n'accepte qu'une seule connexion.",
                        Flat.text,
                    )
                }
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                    label = "title",
                ) { t ->
                    Text(t, color = color, fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.Light)
                }
                Spacer(Modifier.height(10.dp))
                Text(subtitle, color = Flat.text2, fontSize = 16.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(22.dp))
                if (connecting) ProgressLine(Modifier.fillMaxWidth(0.85f).height(3.dp))
                Spacer(Modifier.height(22.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!hasPermission) {
                        PrimaryButton("Autoriser", enabled = true) { permLauncher.launch(requiredBtPermissions()) }
                    } else {
                        state.lastDevice?.let { last ->
                            PrimaryButton("Reconnecter  ${last.name}", enabled = !connecting) { vm.connect(last) }
                            Spacer(Modifier.width(14.dp))
                        }
                    }
                    SecondaryButton(if (state.isSimulated) "Arrêter la démo" else "Mode démo", enabled = !connecting) {
                        vm.toggleSimulator()
                    }
                }
            }

            Box(Modifier.width(1.dp).fillMaxHeight(0.8f).background(Flat.line))

            // ── Right: nearby dongles
            Column(Modifier.weight(1f).fillMaxHeight(0.86f).padding(start = 36.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DONGLES À PROXIMITÉ", color = Flat.text2, fontSize = 13.sp, letterSpacing = 2.5.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if (hasPermission) {
                        Text(
                            if (state.isScanning) "Recherche…" else "Rescanner",
                            color = if (state.isScanning) Flat.text3 else Flat.text,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !state.isScanning && !connecting) { vm.scanBle() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (state.isScanning) ProgressLine(Modifier.fillMaxWidth().height(2.dp))
                else Box(Modifier.fillMaxWidth().height(1.dp).background(Flat.line))
                state.scanError?.let {
                    Text("$it — localisation activée ?", color = Flat.warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }

                val devices = state.bleDevices + state.pairedDevices
                if (devices.isEmpty() && !state.isScanning && hasPermission) {
                    Spacer(Modifier.height(18.dp))
                    Text("Aucun dongle trouvé.", color = Flat.text, fontSize = 18.sp, fontWeight = FontWeight.Light)
                    Text("Voiture allumée ? Dongle bien branché ?", color = Flat.text3, fontSize = 14.sp)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(devices, key = { (if (it.isBle) "ble-" else "bt-") + it.address }) { d ->
                        DeviceRow(
                            d,
                            isLast = d.address == state.lastDevice?.address,
                            enabled = !connecting,
                        ) { vm.connect(d) }
                    }
                }
            }
        }
    }
}

// ── Pieces ─────────────────────────────────────────────────────────────────

/** Same top line as the dashboard: brand left, clock centred. */
@Composable
private fun HomeStatusLine() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5_000) } }
    val clock = remember(now) { SimpleDateFormat("HH:mm", Locale.FRANCE).format(now) }
    Box(Modifier.fillMaxWidth().height(36.dp)) {
        Text("OBD2 · YARIS 100H", color = Flat.text3, fontSize = 12.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.CenterStart))
        Text(clock, color = Flat.text2, fontSize = 18.sp, fontWeight = FontWeight.Medium, style = Tnum,
            modifier = Modifier.align(Alignment.Center))
    }
}

/** White pill, black text: the one obvious action. */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (enabled) Color.Black else Flat.text3, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) Flat.text else Flat.track)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
    )
}

/** Outlined pill for secondary actions. */
@Composable
private fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (enabled) Flat.text else Flat.text3, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (enabled) Flat.text3 else Flat.line, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    )
}

/** One dongle: name, type + address, small tag, signal bars. Hairline under each row. */
@Composable
private fun DeviceRow(d: BtDevice, isLast: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, color = if (d.looksLikeObd) Flat.text else Flat.text2, fontSize = 18.sp,
                        fontWeight = if (d.looksLikeObd) FontWeight.Normal else FontWeight.Light, maxLines = 1)
                    val tag = when { isLast -> "DERNIER"; d.looksLikeObd -> "OBD"; else -> null }
                    tag?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = Color.Black, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (isLast) Flat.text else Flat.text2)
                                .padding(horizontal = 8.dp, vertical = 1.dp))
                    }
                }
                Text(
                    (if (d.isBle) "Bluetooth LE" else "Bluetooth classique") + "  ·  " + d.address,
                    color = Flat.text3, fontSize = 12.sp, style = Tnum,
                )
            }
            d.rssi?.let { SignalBars(it) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Flat.line))
    }
}

/** Four flat bars from RSSI (−60 dBm and better = full). */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    Canvas(Modifier.width(26.dp).height(16.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = size.height * (0.4f + 0.2f * i)
            drawRoundRect(
                if (i < level) Flat.text else Flat.track,
                Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(1.5f),
            )
        }
    }
}

/** Flat indeterminate progress: a white segment sliding along a dark track. */
@Composable
private fun ProgressLine(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "progress").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "progress",
    )
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Flat.track, cornerRadius = r)
        val segW = size.width * 0.28f
        val x = (size.width + segW) * t - segW
        val left = x.coerceAtLeast(0f)
        val right = (x + segW).coerceAtMost(size.width)
        if (right > left) drawRoundRect(Flat.text, Offset(left, 0f), Size(right - left, size.height), r)
    }
}
