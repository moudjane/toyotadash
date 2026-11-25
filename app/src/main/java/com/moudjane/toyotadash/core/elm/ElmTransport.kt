package com.moudjane.toyotadash.core.elm

/**
 * A byte pipe to an ELM327 dongle, whatever the radio underneath (Bluetooth Classic SPP,
 * BLE GATT, or a fake in tests). [ObdDriver] only talks to this.
 *
 * Pure Kotlin on purpose: platform code (Android today, iOS later) provides the
 * implementations, the OBD2 logic stays shared.
 */
interface ElmTransport {
    /** Sends raw bytes to the dongle. Throws when the link is lost. */
    suspend fun write(bytes: ByteArray)

    /**
     * Returns the next bytes received, waiting up to [timeoutMs].
     * Null on timeout; throws when the link is lost.
     */
    suspend fun read(timeoutMs: Long): ByteArray?

    /** Drops bytes already received but not read yet (leftovers of a timed-out answer). */
    fun clearInput()

    fun close()
}

/** The dongle did not answer, or answered something unusable. */
class ElmException(message: String) : Exception(message)
