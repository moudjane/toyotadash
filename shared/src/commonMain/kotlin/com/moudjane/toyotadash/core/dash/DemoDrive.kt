package com.moudjane.toyotadash.core.dash

import kotlin.math.sin

/**
 * Fake drive for the demo mode and screenshots: accelerate, cruise, brake (regen),
 * EV crawl, on a loop. [frame] is called ten times a second with an increasing tick.
 */
object DemoDrive {

    data class Frame(
        val standard: List<PidReading>,
        val toyota: List<PidReading>,
        val driveSelect: DriveSelect,
    )

    fun frame(tick: Int): Frame {
        val t = tick.toFloat()
        val phase = (sin(t / 40) + 1f) / 2f
        val speed = 90f * phase
        val engineOn = phase > 0.35f
        val rpm = if (engineOn) 1100f + 3200f * phase + 300f * sin(t / 3) else 0f
        val current = 60f * sin(t / 25) // + discharge (assist), − charge (regen)
        val packV = 165f - current * 0.12f

        val standard = listOf(
            sim("010C", "Engine RPM",         "rpm",  rpm.coerceAtLeast(0f)),
            sim("010D", "Vehicle Speed",      "km/h", speed),
            sim("0105", "Coolant Temp",       "°C",   78f + 8f * sin(t / 200)),
            sim("0104", "Engine Load",        "%",    if (engineOn) 20f + 60f * phase else 0f),
            sim("010F", "Intake Air Temp",    "°C",   24f),
            sim("0111", "Throttle Position",  "%",    12f + 70f * phase),
            sim("0149", "Accel Pedal D",      "%",    10f + 75f * phase),
            sim("0142", "Module Voltage",     "V",    14.1f),
            sim("0146", "Ambient Temp",       "°C",   17f),
            sim("013C", "Catalyst Temp B1S1", "°C",   520f + 80f * phase),
            sim("012F", "Fuel Level",         "%",    63f),
            sim("015B", "HV Battery SoC",     "%",    62f + 6f * sin(t / 90)),
        )
        val toyota = listOf(
            sim("2181@7E2", "HV Voltage",      "V",   packV),
            sim("2198@7E2", "HV Current",      "A",   current),
            sim("2187@7E2", "HV Battery Temp", "°C",  34f + 2f * sin(t / 300)),
            sim("2187@7E2", "HV Intake Temp",  "°C",  21.5f),
            sim("2161@7E2", "MG1 Speed",       "rpm", if (engineOn) 2500f * phase else 0f),
            sim("2162@7E2", "MG2 Speed",       "rpm", speed * 70f),
        ) + (1..3).map { sim("2187@7E2", "HV Temp TB$it", "°C", 32f + it + sin(t / 300)) } +
            (1..10).map { sim("2181@7E2", "HV Block $it", "V", packV / 10f + 0.04f * sin(t / 7 + it)) }

        val select = when {
            !engineOn && speed > 1f -> DriveSelect.EV
            tick / 150 % 2 == 0 -> DriveSelect.ECO
            else -> DriveSelect.NORMAL
        }
        return Frame(standard, toyota, select)
    }

    private fun sim(id: String, name: String, unit: String, value: Float) =
        PidReading(id = id, name = name, unit = unit, value = value, rawHex = "SIM", isOk = true)
}
