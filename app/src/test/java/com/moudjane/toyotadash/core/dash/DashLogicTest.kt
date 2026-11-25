package com.moudjane.toyotadash.core.dash

import com.moudjane.toyotadash.core.FakeElm
import com.moudjane.toyotadash.core.Frames
import com.moudjane.toyotadash.core.elm.ObdDriver
import com.moudjane.toyotadash.core.pid.StandardPids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashLogicTest {

    @Test fun `regen only when the engine is off`() {
        assertEquals(HybridState.REGEN, HybridState.from(rpm = 0f, speed = 50f, amps = -20f))
        // Engine running + negative current = the engine charges the battery, not regen.
        assertEquals(HybridState.HYBRID, HybridState.from(rpm = 1500f, speed = 50f, amps = -20f))
        assertEquals(HybridState.EV, HybridState.from(rpm = 0f, speed = 20f, amps = 15f))
        assertEquals(HybridState.READY, HybridState.from(rpm = null, speed = 0f, amps = null))
    }

    @Test fun `energy flows`() {
        val charging = EnergyFlows.from(rpm = 1500f, speed = 50f, amps = -10f)
        assertTrue(charging.engineToWheels && charging.engineToBattery)
        assertFalse(charging.wheelsToBattery)

        val braking = EnergyFlows.from(rpm = 0f, speed = 50f, amps = -10f)
        assertTrue(braking.wheelsToBattery)
        assertFalse(braking.engineToBattery)
    }

    @Test fun `drive select mapping`() {
        assertEquals(DriveSelect.ECO, DriveSelect.fromHeadUnit(1))
        assertEquals(DriveSelect.EV, DriveSelect.fromHeadUnit(3))
        assertEquals(DriveSelect.UNKNOWN, DriveSelect.fromHeadUnit(null))
        assertEquals(DriveSelect.UNKNOWN, DriveSelect.fromHeadUnit(9))
    }

    @Test fun `poller reads Toyota data and shares one request per command`() = runBlocking {
        val elm = FakeElm { cmd ->
            when (cmd) {
                "010D" -> "410D2A"
                "2181" -> Frames.HV_2181
                "2198" -> Frames.HV_2198
                "2187" -> Frames.HV_2187
                else -> FakeElm.defaultAnswer(cmd)
            }
        }
        val poller = DashPoller(ObdDriver(elm), listOf(StandardPids.VEHICLE_SPEED))
        assertEquals(14.1f, poller.pollOnce()!!, 0.001f)
        assertEquals(1, elm.sent.count { it == "2181" }) // voltage + 10 blocks from one request
        assertEquals(1, elm.sent.count { it == "2187" })
    }

    @Test fun `silent ECU is skipped after three misses`() = runBlocking {
        val elm = FakeElm()
        val poller = DashPoller(ObdDriver(elm), emptyList())
        repeat(10) { poller.pollOnce() }
        // 3 misses, then skipped until cycle 40: 2181 asked only 3 times in 10 cycles.
        assertEquals(3, elm.sent.count { it == "2181" })
    }
}
