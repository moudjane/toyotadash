package com.moudjane.toyotadash.core.dash

import com.moudjane.toyotadash.core.elm.ObdDriver
import com.moudjane.toyotadash.core.pid.PidDef
import com.moudjane.toyotadash.core.pid.ToyotaHybridPids
import com.moudjane.toyotadash.core.pid.ToyotaPidDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * The reading loop behind the dashboard.
 *
 *  - Standard PIDs that drive the needles ([FAST]) are read every cycle, the others
 *    every [SLOW_EVERY] cycles.
 *  - Toyota PIDs are grouped by ECU and command: one request feeds every PID sharing it
 *    (2181 → pack voltage + 10 blocks), each at its own [ToyotaPidDef.everyCycles] pace.
 *  - An ECU that stops answering is only retried every [RETRY_EVERY] cycles, since each
 *    miss costs a timeout.
 */
class DashPoller(
    private val driver: ObdDriver,
    standardPids: List<PidDef>,
    toyotaPids: List<ToyotaPidDef> = ToyotaHybridPids.ALL,
) {
    data class Snapshot(
        val standard: List<PidReading>,
        val toyota: List<PidReading>,
        val adapterVoltage: Float?, // null = not read this cycle
        val refreshHz: Float?,      // null = not measured this cycle
    )

    private val fast = standardPids.filter { it.command in FAST }
    private val slow = standardPids.filterNot { it.command in FAST }
    private val toyotaByEcu = toyotaPids.groupBy { it.ecuAddress }

    private val standard = LinkedHashMap<String, PidReading>()
    private val toyota = LinkedHashMap<String, PidReading>()
    private val ecuMisses = HashMap<String, Int>()
    private var cycle = 0

    /** Polls until cancelled; a lost link ends it with an exception. */
    suspend fun run(onCycle: (Snapshot) -> Unit): Nothing {
        var hzMark = TimeSource.Monotonic.markNow()
        var cyclesSinceMark = 0
        while (true) {
            val volts = pollOnce()
            cyclesSinceMark++
            val elapsedMs = hzMark.elapsedNow().inWholeMilliseconds
            val hz = if (elapsedMs >= 2000) {
                (cyclesSinceMark * 1000f / elapsedMs).also {
                    hzMark = TimeSource.Monotonic.markNow(); cyclesSinceMark = 0
                }
            } else null
            onCycle(Snapshot(standard.values.toList(), toyota.values.toList(), volts, hz))
            delay(20)
        }
    }

    /** One cycle. Returns the adapter voltage when it was read this time. */
    internal suspend fun pollOnce(): Float? {
        driver.resetHeader()
        val slowCycle = cycle % SLOW_EVERY == 0
        for (pid in if (slowCycle) fast + slow else fast) {
            standard[pid.command] = decode(pid, driver.readPid(pid.command))
        }

        for ((ecu, allPids) in toyotaByEcu) {
            val due = allPids.filter { cycle % it.everyCycles == 0 }
            if (due.isEmpty()) continue
            if ((ecuMisses[ecu] ?: 0) >= 3 && cycle % RETRY_EVERY != 0) continue
            driver.setHeader(ecu)
            var anyOk = false
            for ((command, sameCommand) in due.groupBy { it.command }) {
                // A Toyota PID that times out must not end the session: it just reads as missing.
                val raw = try {
                    driver.readPid(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                for (pid in sameCommand) {
                    val reading = decode(pid, raw)
                    anyOk = anyOk || reading.isOk
                    toyota[pid.name] = reading
                }
            }
            ecuMisses[ecu] = if (anyOk) 0 else (ecuMisses[ecu] ?: 0) + 1
        }

        val volts = if (slowCycle) driver.readAdapterVoltage() else null
        cycle++
        return volts
    }

    companion object {
        /** RPM, speed, throttle, load, pedal: what the needles show. */
        val FAST = setOf("010C", "010D", "0111", "0104", "0149")
        const val SLOW_EVERY = 8
        const val RETRY_EVERY = 40
    }
}
