package com.moudjane.toyotadash.core.elm

import com.moudjane.toyotadash.core.Frames
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ElmResponseTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun `single frame answer`() {
        assertContentEquals(bytes(0x1A, 0xF8), ElmResponse.extractData("410C1AF8", 0x41, 0x0C))
    }

    @Test fun `spaces and lowercase and SEARCHING line are tolerated`() {
        assertContentEquals(bytes(0x7B), ElmResponse.extractData("SEARCHING...\n41 05 7b", 0x41, 0x05))
    }

    @Test fun `answer for another PID is ignored`() {
        assertNull(ElmResponse.extractData("410D2A", 0x41, 0x0C))
    }

    @Test fun `one answer per responding ECU`() {
        val all = ElmResponse.extractAllData("4100BE3EB813\n410080000001", 0x41, 0x00)
        assertEquals(2, all.size)
        assertContentEquals(bytes(0x80, 0x00, 0x00, 0x01), all[1])
    }

    @Test fun `multi-frame answer is reassembled`() {
        val data = ElmResponse.extractData(Frames.HV_2181, 0x61, 0x81)!!
        assertEquals(24, data.size)
        assertEquals(0x34.toByte(), data[0])
        assertEquals(0x72.toByte(), data[23])
    }

    @Test fun `errors are detected`() {
        listOf("NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS BUSY", "STOPPED", "?", "")
            .forEach { assertTrue(ElmResponse.isError(it), it) }
        assertFalse(ElmResponse.isError("410C1AF8"))
        assertTrue(ElmResponse.extractAllData("NO DATA", 0x41, 0x0C).isEmpty())
    }
}
