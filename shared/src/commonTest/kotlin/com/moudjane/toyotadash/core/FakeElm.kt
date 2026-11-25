package com.moudjane.toyotadash.core

import com.moudjane.toyotadash.core.elm.ElmTransport

/**
 * Scripted ELM327: [answer] maps a command to its response (null = silence, the driver
 * then times out). Every command sent is recorded in [sent].
 */
class FakeElm(private val answer: (String) -> String? = { defaultAnswer(it) }) : ElmTransport {

    val sent = mutableListOf<String>()
    private val pending = ArrayDeque<ByteArray>()

    override suspend fun write(bytes: ByteArray) {
        val command = bytes.decodeToString().trimEnd('\r')
        sent += command
        answer(command)?.let { pending += "$it\r\r>".encodeToByteArray() }
    }

    override suspend fun read(timeoutMs: Long): ByteArray? = pending.removeFirstOrNull()
    override fun clearInput() = pending.clear()
    override fun close() {}

    companion object {
        fun defaultAnswer(command: String): String? = when {
            command == "ATZ" -> "ELM327 v1.5"
            command == "ATRV" -> "14.1V"
            command.startsWith("AT") -> "OK"
            else -> "NO DATA"
        }
    }
}
