package com.moudjane.toyotadash.core.pid

import com.moudjane.toyotadash.core.Frames
import com.moudjane.toyotadash.core.dash.decode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.Test

/** Formulas checked against values read on the car (Yaris III 100h, 23/11/2026). */
class PidFormulaTest {

    private fun std(pid: PidDef, raw: String) = decode(pid, raw).value
    private fun toyota(pid: ToyotaPidDef, raw: String) = decode(pid, raw).value

    @Test fun `standard PIDs`() {
        assertEquals(1726f, std(StandardPids.ENGINE_RPM, "410C1AF8"), 0.01f)
        assertEquals(42f, std(StandardPids.VEHICLE_SPEED, "410D2A"), 0.01f)
        assertEquals(83f, std(StandardPids.COOLANT_TEMP, "41057B"), 0.01f)
        assertEquals(60f, std(StandardPids.HYBRID_SOC, "415B99"), 0.01f)
    }

    @Test fun `HV pack voltage equals the sum of the blocks`() {
        val pack = toyota(ToyotaHybridPids.HV_VOLTAGE, Frames.HV_2181)
        val blocks = ToyotaHybridPids.HV_BLOCKS.map { toyota(it, Frames.HV_2181) }
        assertEquals(165.0f, pack, 0.01f)
        blocks.forEach { assertEquals(16.50f, it, 0.01f) }
        assertEquals(pack, blocks.sum(), 0.1f)
    }

    @Test fun `HV current and positive = discharge`() {
        assertEquals(1.10f, toyota(ToyotaHybridPids.HV_CURRENT, Frames.HV_2198), 0.001f)
        assertEquals(-12.5f, toyota(ToyotaHybridPids.HV_CURRENT, "61987B1E"), 0.001f)
    }

    @Test fun `battery temperatures`() {
        assertEquals(21.1f, toyota(ToyotaHybridPids.HV_INTAKE_TEMP, Frames.HV_2187), 0.05f)
        assertEquals(33.5f, toyota(ToyotaHybridPids.HV_TEMPS[0], Frames.HV_2187), 0.05f)
        // Hottest sensor wins: TB2 (0x53A8).
        assertEquals(toyota(ToyotaHybridPids.HV_TEMPS[1], Frames.HV_2187),
                     toyota(ToyotaHybridPids.HV_TEMP, Frames.HV_2187), 0.001f)
    }

    @Test fun `MG speeds are offset by 32768`() {
        assertEquals(-1000f, toyota(ToyotaHybridPids.MG2_SPEED, "61620000007C18"), 0.01f)
    }

    @Test fun `short or missing answers are not ok`() {
        assertFalse(decode(ToyotaHybridPids.HV_VOLTAGE, Frames.HV_2198).isOk)
        assertFalse(decode(ToyotaHybridPids.HV_VOLTAGE, null).isOk)
    }
}
