package com.moudjane.toyotadash.data.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A byte pipe to an ELM327, whatever the radio underneath (Bluetooth Classic SPP or BLE GATT).
 * ObdDriver only talks to this, so the OBD2 logic is shared by both kinds of dongle.
 */
interface ElmTransport {
    val input: InputStream
    val output: OutputStream
    fun close()
}

/** Bluetooth Classic: the SPP socket already is a stream. */
class ClassicTransport(private val socket: BluetoothSocket) : ElmTransport {
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun close() { runCatching { socket.close() } }
}

/**
 * InputStream fed by BLE notifications. Reads block until data arrives, and fail after
 * [timeoutMs] of silence or as soon as the link drops, so a dead dongle never hangs the
 * reading loop.
 */
internal class NotificationInputStream(
    private val isAlive: () -> Boolean,
    private val timeoutMs: Long = 8000,
) : InputStream() {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray? = null
    private var pos = 0

    fun push(bytes: ByteArray) { if (bytes.isNotEmpty()) queue.offer(bytes) }

    override fun available(): Int =
        ((current?.size ?: 0) - pos).coerceAtLeast(0) + queue.sumOf { it.size }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var waited = 0L
        while (current == null || pos >= current!!.size) {
            val next = queue.poll(250, TimeUnit.MILLISECONDS)
            if (next != null) {
                current = next
                pos = 0
                break
            }
            if (!isAlive()) throw IOException("BLE link lost")
            waited += 250
            if (waited >= timeoutMs) throw IOException("No answer from ELM327")
        }
        val chunk = current!!
        val n = minOf(len, chunk.size - pos)
        System.arraycopy(chunk, pos, b, off, n)
        pos += n
        return n
    }
}

/** OutputStream that sends each flush as BLE writes (the link chunks to 20 bytes). */
internal class GattOutputStream(private val send: (ByteArray) -> Unit) : OutputStream() {
    private val buffer = java.io.ByteArrayOutputStream()
    override fun write(b: Int) { buffer.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { buffer.write(b, off, len) }
    override fun flush() {
        if (buffer.size() == 0) return
        val data = buffer.toByteArray()
        buffer.reset()
        send(data)
    }
}
