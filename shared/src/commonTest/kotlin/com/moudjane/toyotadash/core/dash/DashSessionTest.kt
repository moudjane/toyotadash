package com.moudjane.toyotadash.core.dash

import com.moudjane.toyotadash.core.FakeElm
import com.moudjane.toyotadash.core.Frames
import com.moudjane.toyotadash.core.elm.ElmException
import com.moudjane.toyotadash.core.elm.ObdDriver
import com.moudjane.toyotadash.core.pid.StandardPids
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashSessionTest {

    @Test fun `session keeps only the PIDs the car supports`() = runTest {
        val elm = FakeElm { cmd -> if (cmd == "0100") "410018800000" else FakeElm.defaultAnswer(cmd) }
        val steps = mutableListOf<String>()
        val pids = openSession(ObdDriver(elm)) { steps += it }
        // 0x18 = PIDs 04 and 05.
        assertEquals(listOf("0104", "0105"), pids.map { it.command })
        assertEquals(2, steps.size)
    }

    @Test fun `silent car gives a clear error`() = runTest {
        val error = runCatching { openSession(ObdDriver(FakeElm())) }.exceptionOrNull()
        assertTrue(error is ElmException && "READY" in error.message.orEmpty())
    }

    @Test fun `dash values from raw readings`() {
        val standard = listOf(decode(StandardPids.VEHICLE_SPEED, "410D2A"), decode(StandardPids.ENGINE_RPM, "NO DATA"))
        val toyota = listOf(
            decode(com.moudjane.toyotadash.core.pid.ToyotaHybridPids.HV_VOLTAGE, Frames.HV_2181),
            decode(com.moudjane.toyotadash.core.pid.ToyotaHybridPids.HV_CURRENT, Frames.HV_2198),
        )
        val v = DashValues.from(standard, toyota, adapterVoltage = 14.1f)
        assertEquals(42f, v.speed)
        assertNull(v.rpm)
        assertEquals(14.1f, v.volts12)
        assertEquals(0.1815f, v.kw!!, 0.001f) // 165 V × 1.10 A
        assertEquals(HybridState.EV, v.state)
        assertEquals(10, v.blocks.size)
    }
}
