package com.moudjane.toyotadash.core.pid

// ════════════════════════════════════════════════════════════════════════════
//  PidConfig.kt — CENTRAL PID CONFIGURATION
//
//  To add or modify a PID, edit THIS file only.
//
//  Two types of PIDs:
//    • PidDef        : standard OBD2 Mode 01, universal on all OBD2 vehicles
//    • ToyotaPidDef  : Toyota manufacturer PIDs for HV hybrid data
//                      (require targeting a specific ECU via ATSH)
//
//  ⚠ Toyota PIDs are based on Prius 2/3 community documentation
//    (same THS-II base as Yaris 3 100h, 1NZ-FXE engine, NiMH battery).
//    They need to be VERIFIED on your specific vehicle — byte offsets may vary.
// ════════════════════════════════════════════════════════════════════════════

/** Reads byte i as unsigned int (0–255). */
private fun b(d: ByteArray, i: Int): Float = (d[i].toInt() and 0xFF).toFloat()

/** Reads two bytes as a signed 16-bit integer (big-endian). */
private fun s16(d: ByteArray, hi: Int, lo: Int): Float {
    val raw = (b(d, hi) * 256 + b(d, lo)).toInt()
    return if (raw > 32767) (raw - 65536).toFloat() else raw.toFloat()
}

// ════════════════════════════════════════════════════════════════════════════
//  PidDef — standard OBD2 PID (Mode 01)
// ════════════════════════════════════════════════════════════════════════════

data class PidDef(
    val command: String,            // e.g. "0105" = mode 01, PID 0x05
    val name: String,
    val unit: String,
    val min: Float = 0f,
    val max: Float = 100f,
    val parse: (ByteArray) -> Float
)

// ════════════════════════════════════════════════════════════════════════════
//  ToyotaPidDef — Toyota manufacturer PID
// ════════════════════════════════════════════════════════════════════════════

data class ToyotaPidDef(
    val ecuAddress: String,         // CAN address of target ECU (e.g. "7C0")
    val command: String,            // Command after ATSH (e.g. "2101")
    val name: String,
    val unit: String,
    val min: Float = 0f,
    val max: Float = 100f,
    val parse: (ByteArray) -> Float,
    val notes: String = "",
    val everyCycles: Int = 1,       // read every N cycles (slow-moving values: temps, blocks)
)

// ════════════════════════════════════════════════════════════════════════════
//  STANDARD OBD2 PIDs (Mode 01)
//  Formulas: https://en.wikipedia.org/wiki/OBD-II_PIDs#Service_01
// ════════════════════════════════════════════════════════════════════════════

object StandardPids {

    val ENGINE_LOAD = PidDef(
        command = "0104", name = "Engine Load", unit = "%",
        min = 0f, max = 100f,
        parse = { d -> b(d, 0) * 100f / 255f }
    )
    val COOLANT_TEMP = PidDef(
        command = "0105", name = "Coolant Temp", unit = "°C",
        min = -40f, max = 120f,
        parse = { d -> b(d, 0) - 40f }
    )
    val SHORT_TRIM_B1 = PidDef(
        command = "0106", name = "Short Fuel Trim", unit = "%",
        min = -25f, max = 25f,
        parse = { d -> (b(d, 0) - 128f) * 100f / 128f }
    )
    val LONG_TRIM_B1 = PidDef(
        command = "0107", name = "Long Fuel Trim", unit = "%",
        min = -25f, max = 25f,
        parse = { d -> (b(d, 0) - 128f) * 100f / 128f }
    )
    val FUEL_PRESSURE = PidDef(
        command = "010A", name = "Fuel Pressure", unit = "kPa",
        min = 0f, max = 765f,
        parse = { d -> b(d, 0) * 3f }
    )
    val ENGINE_RPM = PidDef(
        command = "010C", name = "Engine RPM", unit = "rpm",
        min = 0f, max = 7000f,
        parse = { d -> (256f * b(d, 0) + b(d, 1)) / 4f }
    )
    val VEHICLE_SPEED = PidDef(
        command = "010D", name = "Vehicle Speed", unit = "km/h",
        min = 0f, max = 200f,
        parse = { d -> b(d, 0) }
    )
    val TIMING_ADVANCE = PidDef(
        command = "010E", name = "Timing Advance", unit = "°",
        min = -64f, max = 64f,
        parse = { d -> b(d, 0) / 2f - 64f }
    )
    val INTAKE_AIR_TEMP = PidDef(
        command = "010F", name = "Intake Air Temp", unit = "°C",
        min = -40f, max = 80f,
        parse = { d -> b(d, 0) - 40f }
    )
    val MAF_AIRFLOW = PidDef(
        command = "0110", name = "MAF Air Flow", unit = "g/s",
        min = 0f, max = 655f,
        parse = { d -> (256f * b(d, 0) + b(d, 1)) / 100f }
    )
    val THROTTLE_POS = PidDef(
        command = "0111", name = "Throttle Position", unit = "%",
        min = 0f, max = 100f,
        parse = { d -> b(d, 0) * 100f / 255f }
    )
    val RUN_TIME = PidDef(
        command = "011F", name = "Engine Runtime", unit = "s",
        min = 0f, max = 65535f,
        parse = { d -> 256f * b(d, 0) + b(d, 1) }
    )
    val FUEL_LEVEL = PidDef(
        command = "012F", name = "Fuel Level", unit = "%",
        min = 0f, max = 100f,
        parse = { d -> b(d, 0) * 100f / 255f }
    )
    val BARO_PRESSURE = PidDef(
        command = "0133", name = "Baro Pressure", unit = "kPa",
        min = 60f, max = 110f,
        parse = { d -> b(d, 0) }
    )
    val CATALYST_TEMP_B1S1 = PidDef(
        command = "013C", name = "Catalyst Temp B1S1", unit = "°C",
        min = -40f, max = 1200f,
        parse = { d -> (256f * b(d, 0) + b(d, 1)) / 10f - 40f }
    )
    val MODULE_VOLTAGE = PidDef(
        command = "0142", name = "Module Voltage", unit = "V",
        min = 0f, max = 65f,
        parse = { d -> (256f * b(d, 0) + b(d, 1)) / 1000f }
    )
    val AMBIENT_TEMP = PidDef(
        command = "0146", name = "Ambient Temp", unit = "°C",
        min = -40f, max = 60f,
        parse = { d -> b(d, 0) - 40f }
    )
    val ACCEL_PEDAL_D = PidDef(
        command = "0149", name = "Accel Pedal D", unit = "%",
        min = 0f, max = 100f,
        parse = { d -> b(d, 0) * 100f / 255f }
    )
    val ACCEL_PEDAL_E = PidDef(
        command = "014A", name = "Accel Pedal E", unit = "%",
        min = 0f, max = 100f,
        parse = { d -> b(d, 0) * 100f / 255f }
    )
    val THROTTLE_CMD = PidDef(
        command = "014C", name = "Throttle Command", unit = "%",
        min = 0f, max = 100f,
        parse = { d -> b(d, 0) * 100f / 255f }
    )

    /** Hybrid battery pack remaining life — on Toyota hybrids this is the HV state of charge. */
    val HYBRID_SOC = PidDef(
        command = "015B", name = "HV Battery SoC", unit = "%",
        min = 0f, max = 100f,
        parse = { d -> b(d, 0) * 100f / 255f }
    )

    /** All standard PIDs to attempt. Auto-discovery (0100/0120…) will filter this list. */
    val ALL: List<PidDef> = listOf(
        ENGINE_LOAD, COOLANT_TEMP, SHORT_TRIM_B1, LONG_TRIM_B1,
        FUEL_PRESSURE, ENGINE_RPM, VEHICLE_SPEED, TIMING_ADVANCE,
        INTAKE_AIR_TEMP, MAF_AIRFLOW, THROTTLE_POS, RUN_TIME,
        FUEL_LEVEL, BARO_PRESSURE, CATALYST_TEMP_B1S1, MODULE_VOLTAGE,
        AMBIENT_TEMP, ACCEL_PEDAL_D, ACCEL_PEDAL_E, THROTTLE_CMD, HYBRID_SOC,
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  TOYOTA HYBRID PIDs — Yaris 3 100h (THS-II, 1NZ-FXE, NiMH 10 blocks)
//
//  All read from the HYBRID ECU 7E2 (answers on 7EA). On this car the battery
//  ECU is not reachable on its own: 7E2 relays the battery data. The ELM327 must
//  listen on 7EA (ATCRA), which ObdDriver.setHeader() does.
//
//  Decoded on the car itself (23/11/2026, logged in P with engine start + loads),
//  cross-checked where possible:
//    2181 → 10 block voltages (u16 × 79.99/65535) + pack voltage (u16 / 10).
//           Sum of blocks = pack voltage (164.9 V vs 165.0 V).
//    2192 → min/max block, matches the blocks decoded from 2181.
//    2198 → battery current, (u16 − 32768) / 100, positive = discharge.
//    2187 → battery intake air temp + 3 pack temps, u16 × 255.9/65535 − 50.
//    2161 / 2162 → MG1 / MG2 speed, u16 − 32768 (MG1 spins up with the engine).
//  SoC comes from standard PID 015B (same byte as 7E2 2101[21]).
// ════════════════════════════════════════════════════════════════════════════

/** Unsigned 16-bit big-endian. */
private fun u16(d: ByteArray, hi: Int): Float = b(d, hi) * 256f + b(d, hi + 1)

private fun blockVolts(d: ByteArray, i: Int): Float = u16(d, i * 2) * 79.99f / 65535f
private fun toyotaTemp(d: ByteArray, hi: Int): Float = u16(d, hi) * 255.9f / 65535f - 50f

object ToyotaHybridPids {

    private const val HV = "7E2"

    val HV_VOLTAGE = ToyotaPidDef(
        ecuAddress = HV, command = "2181", name = "HV Voltage", unit = "V",
        min = 100f, max = 220f,
        parse = { d -> require(d.size >= 24); u16(d, 22) / 10f },
        notes = "2181 bytes 22-23 / 10 — verified (= sum of blocks)",
    )

    val HV_CURRENT = ToyotaPidDef(
        ecuAddress = HV, command = "2198", name = "HV Current", unit = "A",
        min = -100f, max = 100f,
        parse = { d -> require(d.size >= 2); (u16(d, 0) - 32768f) / 100f },
        notes = "2198 (u16 − 32768)/100 — positive = discharge",
    )

    val HV_INTAKE_TEMP = ToyotaPidDef(
        ecuAddress = HV, command = "2187", name = "HV Intake Temp", unit = "°C",
        min = -20f, max = 60f, everyCycles = 8,
        parse = { d -> require(d.size >= 2); toyotaTemp(d, 0) },
        notes = "2187 bytes 0-1 — battery cooling air",
    )

    /** Hottest of the three pack sensors (what matters for battery health). */
    val HV_TEMP = ToyotaPidDef(
        ecuAddress = HV, command = "2187", name = "HV Battery Temp", unit = "°C",
        min = -20f, max = 60f, everyCycles = 8,
        parse = { d -> require(d.size >= 8); maxOf(toyotaTemp(d, 2), toyotaTemp(d, 4), toyotaTemp(d, 6)) },
        notes = "2187 max(TB1, TB2, TB3)",
    )

    val HV_TEMPS = (1..3).map { n ->
        ToyotaPidDef(
            ecuAddress = HV, command = "2187", name = "HV Temp TB$n", unit = "°C",
            min = -20f, max = 60f, everyCycles = 8,
            parse = { d -> require(d.size >= 8); toyotaTemp(d, n * 2) },
        )
    }

    val HV_BLOCKS = (1..10).map { n ->
        ToyotaPidDef(
            ecuAddress = HV, command = "2181", name = "HV Block $n", unit = "V",
            min = 12f, max = 19f,
            parse = { d -> require(d.size >= 20); blockVolts(d, n - 1) },
        )
    }

    val MG1_SPEED = ToyotaPidDef(
        ecuAddress = HV, command = "2161", name = "MG1 Speed", unit = "rpm",
        min = -10000f, max = 10000f,
        parse = { d -> require(d.size >= 5); u16(d, 3) - 32768f },
        notes = "2161 bytes 3-4 − 32768",
    )

    val MG2_SPEED = ToyotaPidDef(
        ecuAddress = HV, command = "2162", name = "MG2 Speed", unit = "rpm",
        min = -10000f, max = 10000f,
        parse = { d -> require(d.size >= 5); u16(d, 3) - 32768f },
        notes = "2162 bytes 3-4 − 32768",
    )

    val ALL: List<ToyotaPidDef> =
        listOf(HV_VOLTAGE, HV_CURRENT, HV_TEMP, HV_INTAKE_TEMP, MG1_SPEED, MG2_SPEED) + HV_TEMPS + HV_BLOCKS
}
