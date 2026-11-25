package com.moudjane.toyotadash.core.elm

import com.moudjane.toyotadash.core.FakeElm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDriverTest {

    @Test fun `initialize sends the init sequence`() = runBlocking {
        val elm = FakeElm()
        assertTrue(ObdDriver(elm).initialize())
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"), elm.sent)
    }

    @Test fun `initialize fails cleanly when the dongle is silent`() = runBlocking {
        assertFalse(ObdDriver(FakeElm { null }).initialize())
    }

    @Test fun `silent dongle raises ElmException`() {
        val driver = ObdDriver(FakeElm { null })
        val error = runCatching { runBlocking { driver.readPid("010C") } }.exceptionOrNull()
        assertTrue(error is ElmException)
    }

    @Test fun `setHeader listens to the ECU answer address, once`() = runBlocking {
        val elm = FakeElm()
        val driver = ObdDriver(elm)
        driver.setHeader("7E2")
        driver.setHeader("7E2")
        driver.setHeader("7C0")
        driver.resetHeader()
        assertEquals(listOf("ATSH 7E2", "ATCRA 7EA", "ATSH 7C0", "ATCRA 7C8", "ATSH 7DF", "ATCRA"), elm.sent)
    }

    @Test fun `supported PIDs of every ECU are merged`() = runBlocking {
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

    @Test fun `NO DATA reads as null, adapter voltage is parsed`() = runBlocking {
        val driver = ObdDriver(FakeElm())
        assertNull(driver.readPid("012F"))
        assertEquals(14.1f, driver.readAdapterVoltage()!!, 0.001f)
    }
}
