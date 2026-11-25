package com.moudjane.toyotadash.data.obd2

import com.moudjane.toyotadash.data.bluetooth.ElmTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * ELM327 / OBD2 protocol layer.
 *
 * Takes an already-open [ElmTransport] (Bluetooth Classic or BLE) and exposes:
 *  - initialize()           → ELM327 init sequence
 *  - discoverSupportedPids  → list of supported Mode 01 PID numbers
 *  - readPid()              → send a command, return raw response
 *  - setHeader/resetHeader  → target a specific ECU (Toyota enhanced)
 *  - close()
 */
class ObdDriver(private val transport: ElmTransport) {

    private val output: OutputStream = transport.output
    private val input: InputStream   = transport.input

    /** Last header set with ATSH, to skip redundant commands (each costs a BLE round trip). */
    private var currentHeader: String? = null

    // ── Init ─────────────────────────────────────────────────────────────────

    /** Mandatory ELM327 init sequence before any OBD2 communication. Returns true on success. */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val id = sendRaw("ATZ")   // Full reset, answers "ELM327 vX.X"
            if (!id.uppercase().contains("ELM")) sendRaw("ATZ") // Some clones drop the first one
            sendRaw("ATE0")  // Echo off
            sendRaw("ATL0")  // Linefeeds off
            sendRaw("ATS0")  // Spaces off — bytes are concatenated without spaces
            sendRaw("ATH0")  // Headers off — hides CAN addresses in responses
            sendRaw("ATAT1") // Adaptive timing — shorter waits once the ECU timing is learnt
            sendRaw("ATSP0") // Auto-detect OBD2 protocol (ISO 15765-4 CAN for Toyota)
            currentHeader = null
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Reads the battery voltage measured by the dongle itself (ATRV), e.g. 14.1. */
    suspend fun readAdapterVoltage(): Float? = withContext(Dispatchers.IO) {
        Regex("""(\d+(\.\d+)?)\s*V""").find(sendRaw("ATRV"))?.groupValues?.get(1)?.toFloatOrNull()
    }

    // ── PID discovery ────────────────────────────────────────────────────────

    /**
     * Asks the ECUs which Mode 01 PIDs they support.
     *
     * OBD2 uses PIDs "00", "20", "40"… as bitmasks: the response to "0100" is a 32-bit
     * integer where each bit = one supported PID (01-20). Hybrids answer from several
     * ECUs at once, so the bitmasks of every responding ECU are merged.
     * Returns supported PID numbers as 2-char hex strings (e.g. "05").
     */
    suspend fun discoverSupportedPids(): Set<String> = withContext(Dispatchers.IO) {
        val supported = mutableSetOf<String>()
        var base = 0
        while (base < 0xE0) {
            val raw = sendRaw("01%02X".format(base))
            val answers = extractAllData(raw, 0x41, base).filter { it.size >= 4 }
            if (answers.isEmpty()) break

            var nextBlock = false
            for (data in answers) {
                for (byteIdx in 0 until 4) {
                    val byte = data[byteIdx].toInt() and 0xFF
                    for (bit in 7 downTo 0) {
                        if (byte and (1 shl bit) != 0) {
                            supported.add("%02X".format(base + byteIdx * 8 + (8 - bit)))
                        }
                    }
                }
                // Last bit of the block says whether the next block (PID 20, 40, 60…) exists.
                if (data[3].toInt() and 0x01 != 0) nextBlock = true
            }
            if (!nextBlock) break
            base += 0x20
        }
        supported
    }

    // ── PID read ─────────────────────────────────────────────────────────────

    /** Sends an OBD2 command (e.g. "0105") and returns the raw response, or null on error. */
    suspend fun readPid(command: String): String? = withContext(Dispatchers.IO) {
        val raw = sendRaw(command)
        if (isErrorResponse(raw)) null else raw
    }

    // ── ECU targeting (Toyota enhanced) ──────────────────────────────────────

    /** Sets the CAN header to target a specific ECU for subsequent commands. */
    suspend fun setHeader(address: String) = withContext(Dispatchers.IO) {
        if (currentHeader != address) {
            sendRaw("ATSH $address")
            // Listen only to that ECU's answers (request + 8, e.g. 7E2 → 7EA). The default
            // filter only accepts 7E8-7EF, so ECUs like 7C0 would never be heard otherwise.
            if (address == "7DF") sendRaw("ATCRA")
            else sendRaw("ATCRA %03X".format(address.toInt(16) + 8))
            currentHeader = address
        }
    }

    /** Resets the header back to the standard broadcast address (7DF). */
    suspend fun resetHeader() = withContext(Dispatchers.IO) {
        setHeader("7DF")
    }

    fun close() { transport.close() }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Sends a command and reads the response until the ELM327 ">" prompt. */
    private fun sendRaw(command: String): String {
        // Drop leftovers of a previous, timed-out answer so responses never shift by one.
        while (input.available() > 0) input.read(ByteArray(input.available()))
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        return readUntilPrompt()
    }

    /** Reads until ">", keeping one response line per "\n" (several ECUs or frames = several lines). */
    private fun readUntilPrompt(): String {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            for (i in 0 until n) {
                when (val ch = buf[i].toInt().toChar()) {
                    '>'  -> return sb.toString().trim()
                    '\r', '\n' -> if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    else -> sb.append(ch)
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Extracts data bytes from an OBD2 response.
     *
     * Example: response "41057B" for command "0105"
     *   response mode = 0x41 (= 0x40 + 0x01), PID = 0x05, data = [0x7B] = 123 → 83 °C
     *
     * When several ECUs answer, the first answer is returned. Multi-frame responses
     * (Toyota 2101…) are reassembled from their "0:", "1:"… lines.
     */
    fun extractData(raw: String?, responseMode: Int, pid: Int): ByteArray? =
        extractAllData(raw, responseMode, pid).firstOrNull()

    /** Every answer to the request, one ByteArray per responding ECU. */
    fun extractAllData(raw: String?, responseMode: Int, pid: Int): List<ByteArray> {
        if (raw == null || isErrorResponse(raw)) return emptyList()
        val prefix = "%02X%02X".format(responseMode, pid)

        val lines = raw.uppercase().lines()
            .map { it.replace("\\s".toRegex(), "") }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }

        // ISO-TP multi-frame with headers off: "014" (length), "0:6101...", "1:...", "2:...".
        val frameLine = Regex("^([0-9A-F]):([0-9A-F]+)$")
        val messages = if (lines.any { frameLine.matches(it) }) {
            val joined = StringBuilder()
            val result = mutableListOf<String>()
            for (line in lines) {
                val m = frameLine.matchEntire(line)
                when {
                    m != null -> {
                        if (m.groupValues[1] == "0" && joined.isNotEmpty()) {
                            result.add(joined.toString()); joined.clear()
                        }
                        joined.append(m.groupValues[2])
                    }
                    line.length <= 3 -> { /* byte count line, ignore */ }
                    else -> result.add(line) // a single-frame answer from another ECU
                }
            }
            if (joined.isNotEmpty()) result.add(joined.toString())
            result
        } else {
            lines
        }

        return messages.mapNotNull { msg ->
            val start = msg.indexOf(prefix)
            if (start < 0) return@mapNotNull null
            val hex = msg.substring(start + prefix.length)
            if (!hex.all { it in '0'..'9' || it in 'A'..'F' }) return@mapNotNull null
            hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    private fun isErrorResponse(raw: String): Boolean {
        val up = raw.uppercase().replace("\\s".toRegex(), "")
        return up.isEmpty()
            || up.contains("NODATA")
            || up.contains("ERROR")
            || up.contains("UNABLETOCONNECT")
            || up.contains("STOPPED")
            || up.contains("BUSBUSY")
            || up == "?"
    }
}
