package com.moudjane.toyotadash.ios

import com.moudjane.toyotadash.core.dash.DashPoller
import com.moudjane.toyotadash.core.dash.DashValues
import com.moudjane.toyotadash.core.dash.DemoDrive
import com.moudjane.toyotadash.core.dash.openSession
import com.moudjane.toyotadash.core.elm.ObdDriver
import com.moudjane.toyotadash.data.bluetooth.BleDongle
import com.moudjane.toyotadash.data.bluetooth.IosBleLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The entry point for the Swift app: plain callbacks on the main thread, no coroutines or
 * Flows to bridge. Everything behind it (protocol, PIDs, reading loop) is the shared code
 * the Android head-unit app runs too.
 */
class DashController {

    private val scope = MainScope()
    private val ble = IosBleLink()
    private var job: Job? = null
    private var driver: ObdDriver? = null

    /** Scans for BLE dongles for a few seconds. */
    fun scan(onResult: (List<BleDongle>) -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                onResult(ble.scan())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Scan impossible")
            }
        }
    }

    /**
     * Connects to a dongle and streams dashboard values until [stop] or a lost link.
     * [onStep] gets progress messages, [onError] the reason when it fails or drops.
     */
    fun connect(
        dongleId: String,
        onStep: (String) -> Unit,
        onConnected: () -> Unit,
        onValues: (DashValues) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        job = scope.launch {
            try {
                onStep("Connexion BLE…")
                val d = ObdDriver(ble.connect(dongleId)).also { driver = it }
                val pids = openSession(d, onStep)
                onConnected()
                var volts: Float? = null
                DashPoller(d, pids).run { snap ->
                    volts = snap.adapterVoltage ?: volts
                    onValues(DashValues.from(snap.standard, snap.toyota, volts))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLink()
                onError(e.message ?: "Erreur inconnue")
            }
        }
    }

    /** Simulated drive, for the demo mode and the simulators (no Bluetooth there). */
    fun startDemo(onValues: (DashValues) -> Unit) {
        stop()
        job = scope.launch {
            var tick = 0
            while (isActive) {
                val frame = DemoDrive.frame(++tick)
                onValues(DashValues.from(frame.standard, frame.toyota, 14.1f, frame.driveSelect))
                delay(100)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeLink()
    }

    /** Call when the controller is no longer needed. */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun closeLink() {
        driver?.close()
        driver = null
    }
}

/** Swift-friendly block voltages: NaN for a missing block (Swift sees [KotlinFloat], no optionals in arrays). */
fun DashValues.blockVoltsOrNaN(): List<Float> = blocks.map { it ?: Float.NaN }
