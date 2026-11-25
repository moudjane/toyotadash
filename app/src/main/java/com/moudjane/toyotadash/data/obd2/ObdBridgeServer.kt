package com.moudjane.toyotadash.data.obd2

import android.content.Context
import android.util.Log
import com.moudjane.toyotadash.data.bluetooth.BleElmLink
import com.moudjane.toyotadash.data.bluetooth.BluetoothRepository
import com.moudjane.toyotadash.data.canbus.CanBusKeys
import com.moudjane.toyotadash.data.canbus.CanBusRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "ObdBridge"

/**
 * Debug-only TCP ↔ Bluetooth bridge to the ELM327 dongle.
 *
 * Listens on 127.0.0.1:[port] (loopback only, never reachable from the network).
 * When a client connects (e.g. from Termux over SSH), it lists Bluetooth Classic paired
 * devices ("C<n>") and nearby BLE devices ("B<n>"), waits for the client to send a choice
 * (a token like "B0", or a MAC address), connects, then pipes raw bytes both ways so
 * AT/PID commands can be typed by hand. One client at a time; Bluetooth is closed when
 * the client leaves.
 *
 * The ELM327 accepts a single connection: close Car Scanner / the OBD dashboard first.
 */
class ObdBridgeServer(
    context: Context,
    private val port: Int = 35000,
) {
    private val appContext = context.applicationContext
    private val btRepo = BluetoothRepository(appContext)
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        thread(name = "obd-bridge-accept", isDaemon = true) {
            try {
                val ss = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
                server = ss
                Log.i(TAG, "Listening on 127.0.0.1:$port")
                while (!ss.isClosed) {
                    val client = ss.accept()
                    try {
                        handle(client) // Blocking: one client at a time
                    } catch (e: Exception) {
                        Log.w(TAG, "Session error", e)
                    } finally {
                        runCatching { client.close() }
                    }
                }
            } catch (e: IOException) {
                if (server != null) Log.w(TAG, "Bridge stopped: ${e.message}")
            }
        }
    }

    fun stop() {
        val ss = server
        server = null
        runCatching { ss?.close() }
        val ds = dumpServer
        dumpServer = null
        runCatching { ds?.close() }
    }

    @Volatile private var dumpServer: ServerSocket? = null

    /**
     * 127.0.0.1:[port]+1 — prints every vehicle key the head unit publishes in
     * Settings.System (car_*, key_can_*…), then closes. Used to calibrate values such as
     * the driving mode (press ECO / EV, dump again, compare). Termux cannot read
     * Settings.System itself, the app can.
     */
    fun startSettingsDump() {
        if (dumpServer != null) return
        thread(name = "obd-bridge-settings", isDaemon = true) {
            try {
                val ss = ServerSocket(port + 1, 1, InetAddress.getByName("127.0.0.1"))
                dumpServer = ss
                val repo = CanBusRepository(appContext)
                while (!ss.isClosed) {
                    ss.accept().use { client ->
                        val text = repo.readAll()
                            .filterKeys { CanBusKeys.isVehicleKey(it) }
                            .toSortedMap()
                            .entries.joinToString("\n") { "${it.key}=${it.value}" }
                        client.getOutputStream().write((text + "\n").toByteArray())
                    }
                }
            } catch (e: IOException) {
                if (dumpServer != null) Log.w(TAG, "Settings dump stopped: ${e.message}")
            }
        }
    }

    private fun handle(client: Socket) {
        val out = client.getOutputStream()
        val input = client.getInputStream()
        fun say(msg: String) = runCatching {
            synchronized(out) { out.write("# $msg\r\n".toByteArray()); out.flush() }
        }

        val paired = btRepo.getPairedDevices()
        say("Bluetooth Classic paired devices (${paired.size}):")
        paired.forEachIndexed { i, d -> say("  C$i  ${d.name}  ${d.address}") }

        say("Scanning BLE for 8 s...")
        val ble = BleElmLink(appContext)
        val found = try {
            ble.scan(8000)
        } catch (e: Exception) {
            say("BLE scan error: ${e.message} (location permission granted? location on?)")
            emptyList()
        }
        say("BLE devices (${found.size}):")
        found.forEachIndexed { i, d -> say("  B$i  ${d.name}  ${d.address}  rssi=${d.rssi}") }
        say("CHOOSE: send C<n>, B<n> or a MAC address")

        val choice = readLine(input)?.trim()?.uppercase().orEmpty()
        val index = choice.drop(1).toIntOrNull()
        when {
            choice.startsWith("C") && index != null && index in paired.indices ->
                bridgeClassic(paired[index].address, input, out, ::say)
            choice.startsWith("B") && index != null && index in found.indices ->
                bridgeBle(ble, found[index].address, input, out, ::say)
            MAC.matches(choice) ->
                if (paired.any { it.address.equals(choice, true) }) bridgeClassic(choice, input, out, ::say)
                else bridgeBle(ble, choice, input, out, ::say)
            else -> say("Invalid choice: '$choice'")
        }
    }

    private fun bridgeClassic(address: String, input: InputStream, out: OutputStream, say: (String) -> Any) {
        say("Connecting (Classic SPP) to $address ...")
        val bt = try {
            btRepo.connect(address)
        } catch (e: Exception) {
            say("Bluetooth connect failed: ${e.message} (is another app connected?)")
            return
        }
        say("CONNECTED. Type ELM327 commands (ATZ, ATSP0, 010C...).")
        val btToTcp = thread(name = "obd-bridge-bt2tcp", isDaemon = true) {
            val buf = ByteArray(512)
            try {
                while (true) {
                    val n = bt.inputStream.read(buf)
                    if (n < 0) break
                    synchronized(out) { out.write(buf, 0, n); out.flush() }
                }
            } catch (_: IOException) { }
            runCatching { input.close() }
        }
        pumpCommands(input) { bt.outputStream.write(it); bt.outputStream.flush() }
        runCatching { bt.close() }
        btToTcp.join(1000)
        Log.i(TAG, "Classic session closed")
    }

    private fun bridgeBle(ble: BleElmLink, address: String, input: InputStream, out: OutputStream, say: (String) -> Any) {
        say("Connecting (BLE) to $address ...")
        try {
            ble.connect(
                address,
                onData = { bytes -> runCatching { synchronized(out) { out.write(bytes); out.flush() } } },
                onLog = { say(it) },
            )
        } catch (e: Exception) {
            say("BLE connect failed: ${e.message} (is Car Scanner still connected?)")
            return
        }
        say("CONNECTED. Type ELM327 commands (ATZ, ATSP0, 010C...).")
        pumpCommands(input) { if (!ble.isConnected) throw IOException("BLE lost"); ble.write(it) }
        ble.close()
        say("BLE closed")
        Log.i(TAG, "BLE session closed")
    }

    /**
     * Reads terminal input and forwards it line by line, turning "\n" / "\r\n" into the
     * single "\r" the ELM327 expects. Returns when the client closes or a write fails.
     */
    private fun pumpCommands(input: InputStream, send: (ByteArray) -> Unit) {
        val line = ByteArrayOutputStream()
        try {
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\r'.code || b == '\n'.code) {
                    if (line.size() == 0) continue
                    line.write('\r'.code)
                    send(line.toByteArray())
                    line.reset()
                } else {
                    line.write(b)
                }
            }
        } catch (_: IOException) {
            // Either side closed: end of session
        }
    }

    /** Reads one line byte by byte (no buffering, so nothing is lost before piping starts). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code || b == '\r'.code) {
                if (sb.isNotEmpty()) return sb.toString()
            } else {
                sb.append(b.toChar())
            }
        }
    }

    private companion object {
        val MAC = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    }
}
