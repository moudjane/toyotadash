package com.moudjane.toyotadash.core.elm

import kotlinx.coroutines.CancellationException

/**
 * ELM327 / OBD2 protocol layer, over any [ElmTransport].
 *
 *  - initialize()           → ELM327 init sequence
 *  - discoverSupportedPids  → supported Mode 01 PID numbers
 *  - readPid()              → send a command, return the raw response
 *  - setHeader/resetHeader  → target a specific ECU (Toyota enhanced PIDs)
 *
 * Pure Kotlin: threading is the transport's business.
 */
class ObdDriver(private val transport: ElmTransport) {

    /** Last header set with ATSH, to skip redundant commands (each costs a BLE round trip). */
    private var currentHeader: String? = null

    // ── Init ─────────────────────────────────────────────────────────────────

    /** Mandatory ELM327 init sequence before any OBD2 communication. Returns true on success. */
    suspend fun initialize(): Boolean = try {
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Reads the battery voltage measured by the dongle itself (ATRV), e.g. 14.1. */
    suspend fun readAdapterVoltage(): Float? =
        VOLTS.find(sendRaw("ATRV"))?.groupValues?.get(1)?.toFloatOrNull()

    // ── PID discovery ────────────────────────────────────────────────────────

    /**
     * Asks the ECUs which Mode 01 PIDs they support.
     *
     * OBD2 uses PIDs "00", "20", "40"… as bitmasks: the response to "0100" is a 32-bit
     * integer where each bit = one supported PID (01-20). Hybrids answer from several
     * ECUs at once, so the bitmasks of every responding ECU are merged.
     * Returns supported PID numbers as 2-char hex strings (e.g. "05").
     */
    suspend fun discoverSupportedPids(): Set<String> {
        val supported = mutableSetOf<String>()
        var base = 0
        while (base < 0xE0) {
            val raw = sendRaw("01" + base.hex(2))
            val answers = ElmResponse.extractAllData(raw, 0x41, base).filter { it.size >= 4 }
            if (answers.isEmpty()) break
            var nextBlock = false
            for (data in answers) {
                supported += supportedInBlock(data, base)
                // Last bit of the block says whether the next block (PID 20, 40, 60…) exists.
                if (data[3].toInt() and 0x01 != 0) nextBlock = true
            }
            if (!nextBlock) break
            base += 0x20
        }
        return supported
    }

    // ── PID read ─────────────────────────────────────────────────────────────

    /** Sends an OBD2 command (e.g. "0105") and returns the raw response, or null on error. */
    suspend fun readPid(command: String): String? {
        val raw = sendRaw(command)
        return if (ElmResponse.isError(raw)) null else raw
    }

    // ── ECU targeting (Toyota enhanced) ──────────────────────────────────────

    /** Sets the CAN header to target a specific ECU for subsequent commands. */
    suspend fun setHeader(address: String) {
        if (currentHeader == address) return
        sendRaw("ATSH $address")
        // Listen only to that ECU's answers (request + 8, e.g. 7E2 → 7EA). The default
        // filter only accepts 7E8-7EF, so ECUs like 7C0 would never be heard otherwise.
        sendRaw(receiveFilterFor(address))
        currentHeader = address
    }

    /** Resets the header back to the standard broadcast address (7DF). */
    suspend fun resetHeader() = setHeader("7DF")

    fun close() = transport.close()

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Sends a command and reads the response until the ELM327 ">" prompt. */
    private suspend fun sendRaw(command: String): String {
        // Drop leftovers of a previous, timed-out answer so responses never shift by one.
        transport.clearInput()
        transport.write("$command\r".encodeToByteArray())
        return readUntilPrompt()
    }

    /** Reads until ">", keeping one response line per "\n" (several ECUs or frames = several lines). */
    private suspend fun readUntilPrompt(): String {
        val sb = StringBuilder()
        while (true) {
            val chunk = transport.read(ANSWER_TIMEOUT_MS) ?: throw ElmException("No answer from ELM327")
            for (byte in chunk) {
                when (val ch = (byte.toInt() and 0xFF).toChar()) {
                    '>' -> return sb.toString().trim()
                    '\r', '\n' -> if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    else -> sb.append(ch)
                }
            }
        }
    }

    internal companion object {
        /** Silence after which the dongle is considered gone (ATZ can take ~1 s, ECUs less). */
        const val ANSWER_TIMEOUT_MS = 8000L
        private val VOLTS = Regex("""(\d+(\.\d+)?)\s*V""")

        /** "ATCRA 7EA" for ECU 7E2; plain "ATCRA" (accept all) for the 7DF broadcast. */
        fun receiveFilterFor(address: String): String =
            if (address == "7DF") "ATCRA" else "ATCRA " + (address.toInt(16) + 8).hex(3)

        /** PID numbers flagged in a 4-byte "supported PIDs" bitmask starting at [base]. */
        fun supportedInBlock(data: ByteArray, base: Int): Set<String> {
            val out = mutableSetOf<String>()
            for (byteIdx in 0 until 4) {
                val byte = data[byteIdx].toInt() and 0xFF
                for (bit in 7 downTo 0) {
                    if (byte and (1 shl bit) != 0) out += (base + byteIdx * 8 + (8 - bit)).hex(2)
                }
            }
            return out
        }
    }
}
