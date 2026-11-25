package com.moudjane.toyotadash.data.bluetooth

import android.bluetooth.BluetoothSocket
import com.moudjane.toyotadash.core.elm.ElmTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Bluetooth Classic: the SPP socket is a blocking stream, polled so reads can time out. */
class ClassicTransport(private val socket: BluetoothSocket) : ElmTransport {

    private val input = socket.inputStream
    private val output = socket.outputStream
    private val buffer = ByteArray(512)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun read(timeoutMs: Long): ByteArray? = withContext(Dispatchers.IO) {
        var waited = 0L
        while (input.available() == 0) {
            if (waited >= timeoutMs) return@withContext null
            delay(POLL_MS)
            waited += POLL_MS
        }
        val n = input.read(buffer, 0, minOf(input.available(), buffer.size))
        if (n < 0) throw IOException("Bluetooth socket closed")
        buffer.copyOf(n)
    }

    override fun clearInput() {
        runCatching { while (input.available() > 0) input.skip(input.available().toLong()) }
    }

    override fun close() { runCatching { socket.close() } }

    private companion object { const val POLL_MS = 10L }
}

/**
 * BLE: answers arrive as GATT notifications, queued in a channel. Reads wake up every
 * 250 ms to notice a dropped link, so a dead dongle never hangs the reading loop.
 */
internal class BleTransport(private val link: BleElmLink) : ElmTransport {

    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    /** Called by [BleElmLink] for each notification. */
    fun push(bytes: ByteArray) { if (bytes.isNotEmpty()) incoming.trySend(bytes) }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { link.write(bytes) }

    override suspend fun read(timeoutMs: Long): ByteArray? {
        var waited = 0L
        while (true) {
            incoming.tryReceive().getOrNull()?.let { return it }
            if (!link.isConnected) throw IOException("BLE link lost")
            if (waited >= timeoutMs) return null
            withTimeoutOrNull(SLICE_MS) { incoming.receive() }?.let { return it }
            waited += SLICE_MS
        }
    }

    override fun clearInput() { while (incoming.tryReceive().isSuccess) { /* drop */ } }

    override fun close() {
        link.close()
        incoming.close()
    }

    private companion object { const val SLICE_MS = 250L }
}
