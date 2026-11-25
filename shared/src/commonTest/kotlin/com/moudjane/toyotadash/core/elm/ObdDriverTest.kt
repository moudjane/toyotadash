package com.moudjane.toyotadash.core.elm

import com.moudjane.toyotadash.core.FakeElm
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ObdDriverTest {

    @Test fun `initialize sends the init sequence`() = runTest {
        val elm = FakeElm()
        assertTrue(ObdDriver(elm).initialize())
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"), elm.sent)
    }

    @Test fun `initialize fails cleanly when the dongle is silent`() = runTest {
        assertFalse(ObdDriver(FakeElm { null }).initialize())
    }

    @Test fun `silent dongle raises ElmException`() = runTest {
        val error = runCatching { ObdDriver(FakeElm { null }).readPid("010C") }.exceptionOrNull()
        assertTrue(error is ElmException)
    }

    @Test fun `setHeader listens to the ECU answer address, once`() = runTest {
        val elm = FakeElm()
        val driver = ObdDriver(elm)
        driver.setHeader("7E2")
        driver.setHeader("7E2")
        driver.setHeader("7C0")
        driver.resetHeader()
        assertEquals(listOf("ATSH 7E2", "ATCRA 7EA", "ATSH 7C0", "ATCRA 7C8", "ATSH 7DF", "ATCRA"), elm.sent)
    }

    @Test fun `supported PIDs of every ECU are merged`() = runTest {
        val elm = FakeElm { cmd ->
            when (cmd) {
                // Engine ECU: 01, 0C, 0D + next block; hybrid ECU: 05 only.
                "0100" -> "410080180001\n410008000000"
                "0120" -> "412000000000" // 0x5B would be here on the car; none for the test
                else -> FakeElm.defaultAnswer(cmd)
            }
        }
        val pids = ObdDriver(elm).discoverSupportedPids()
        assertEquals(setOf("01", "05", "0C", "0D", "20"), pids)
    }

    @Test fun `NO DATA reads as null, adapter voltage is parsed`() = runTest {
        val driver = ObdDriver(FakeElm())
        assertNull(driver.readPid("012F"))
        assertEquals(14.1f, driver.readAdapterVoltage()!!, 0.001f)
    }
}
