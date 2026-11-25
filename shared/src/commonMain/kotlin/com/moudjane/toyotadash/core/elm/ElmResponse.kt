package com.moudjane.toyotadash.core.elm

/** Parsing of raw ELM327 answers (headers off, spaces off). No I/O, easy to test. */
object ElmResponse {

    private val WHITESPACE = Regex("\\s")
    private val FRAME_LINE = Regex("^([0-9A-F]):([0-9A-F]+)$")

    /**
     * Extracts the data bytes of the first answer to a request.
     *
     * Example: response "41057B" for command "0105"
     *   response mode = 0x41 (= 0x40 + 0x01), PID = 0x05, data = [0x7B] = 123 → 83 °C
     */
    fun extractData(raw: String?, responseMode: Int, pid: Int): ByteArray? =
        extractAllData(raw, responseMode, pid).firstOrNull()

    /**
     * Every answer to the request, one ByteArray per responding ECU. Multi-frame
     * responses (Toyota 2181…) are reassembled from their "0:", "1:"… lines.
     */
    fun extractAllData(raw: String?, responseMode: Int, pid: Int): List<ByteArray> {
        if (raw == null || isError(raw)) return emptyList()
        val prefix = responseMode.hex(2) + pid.hex(2)

        val lines = raw.uppercase().lines()
            .map { it.replace(WHITESPACE, "") }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }

        // ISO-TP multi-frame with headers off: "014" (length), "0:6101...", "1:...", "2:...".
        val messages = if (lines.any { FRAME_LINE.matches(it) }) {
            val joined = StringBuilder()
            val result = mutableListOf<String>()
            for (line in lines) {
                val m = FRAME_LINE.matchEntire(line)
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

    /** NO DATA, CAN ERROR, "?"… : the request reached the dongle but got no usable answer. */
    fun isError(raw: String): Boolean {
        val up = raw.uppercase().replace(WHITESPACE, "")
        return up.isEmpty()
            || up.contains("NODATA")
            || up.contains("ERROR")
            || up.contains("UNABLETOCONNECT")
            || up.contains("STOPPED")
            || up.contains("BUSBUSY")
            || up == "?"
    }
}
