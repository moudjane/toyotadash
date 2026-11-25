package com.moudjane.toyotadash.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.moudjane.toyotadash.core.elm.ElmTransport
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class BleFound(val name: String, val address: String, val rssi: Int)

private fun u16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

/**
 * Bluetooth Low Energy link to an ELM327 BLE dongle (the kind that never shows up in
 * Android's paired devices and only works with apps like Car Scanner).
 *
 * BLE ELM327 clones expose a "serial over GATT" service: one characteristic to write
 * commands, one that notifies responses. Known layouts are tried first, then any
 * service having both a notify and a write characteristic.
 */
@SuppressLint("MissingPermission")
class BleElmLink(private val context: Context) {

    private data class Layout(val service: UUID, val notify: UUID, val write: UUID)

    private companion object {
        val CCCD: UUID = u16("2902")
        val KNOWN = listOf(
            Layout(u16("fff0"), u16("fff1"), u16("fff2")), // Most Chinese ELM327 BLE clones
            Layout(u16("ffe0"), u16("ffe1"), u16("ffe1")), // HM-10 style modules
            Layout(u16("18f0"), u16("2af0"), u16("2af1")), // Vgate iCar Pro BLE, Veepeak
            Layout(                                         // OBDLink CX / MX+
                UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            ),
        )
        const val CHUNK = 20 // Default ATT MTU (23) minus 3 bytes of header
    }

    private val adapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val writeDone = Semaphore(0)
    @Volatile private var disconnected = false

    /** Blocking BLE scan. Requires location permission + location enabled on Android ≤ 11. */
    fun scan(durationMs: Long): List<BleFound> {
        val scanner = adapter?.bluetoothLeScanner ?: throw IOException("Bluetooth is off")
        val found = LinkedHashMap<String, BleFound>()
        var error: Int? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: "?"
                synchronized(found) {
                    found[result.device.address] = BleFound(name, result.device.address, result.rssi)
                }
            }
            override fun onScanFailed(errorCode: Int) { error = errorCode }
        }
        scanner.startScan(cb)
        Thread.sleep(durationMs)
        runCatching { scanner.stopScan(cb) }
        error?.let { throw IOException("BLE scan failed (code $it)") }
        return synchronized(found) { found.values.sortedByDescending { it.rssi } }
    }

    /** Connects, finds the serial characteristics and enables notifications. Blocking. */
    fun connect(address: String, onData: (ByteArray) -> Unit, onLog: (String) -> Unit) {
        val ready = CountDownLatch(1)
        var failure: String? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onLog("GATT connected, discovering services...")
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnected = true
                    if (ready.count > 0) failure = "Disconnected (GATT status $status)"
                    ready.countDown()
                    writeDone.release()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                g.services.forEach { s ->
                    onLog("service ${s.uuid}")
                    s.characteristics.forEach { c ->
                        onLog("   char ${c.uuid} props=0x${Integer.toHexString(c.properties)}")
                    }
                }
                val (notify, write) = pickCharacteristics(g) ?: run {
                    failure = "No serial characteristic found (see services above)"
                    ready.countDown()
                    return
                }
                onLog("Using notify=${notify.uuid} write=${write.uuid}")
                writeChar = write
                g.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(CCCD)
                if (cccd == null) { ready.countDown(); return }
                val useIndicate = notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
                @Suppress("DEPRECATION")
                cccd.value = if (useIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                             else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                ready.countDown()
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                writeDone.release()
            }

            // API 33+ delivers the value directly; older versions use the deprecated overload.
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                onData(value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < 33) c.value?.let(onData)
            }
        }

        disconnected = false
        val device: BluetoothDevice = adapter!!.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        if (!ready.await(20, TimeUnit.SECONDS)) failure = failure ?: "Timeout connecting to $address"
        failure?.let { close(); throw IOException(it) }
    }

    /** Writes bytes in 20-byte chunks, waiting for each write to be acknowledged. */
    fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        var off = 0
        while (off < data.size) {
            if (disconnected) throw IOException("BLE disconnected")
            val chunk = data.copyOfRange(off, minOf(off + CHUNK, data.size))
            @Suppress("DEPRECATION")
            c.value = chunk
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(c)) throw IOException("writeCharacteristic refused")
            if (!writeDone.tryAcquire(3, TimeUnit.SECONDS)) throw IOException("BLE write timeout")
            off += chunk.size
        }
    }

    val isConnected: Boolean get() = gatt != null && !disconnected

    /** Connects to [address] and exposes the link as an [ElmTransport] for ObdDriver. Blocking. */
    fun openTransport(address: String, onLog: (String) -> Unit = {}): ElmTransport {
        val transport = BleTransport(this)
        connect(address, onData = transport::push, onLog = onLog)
        return transport
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        disconnected = true
        writeDone.release()
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (k in KNOWN) {
            val s = g.getService(k.service) ?: continue
            val n = s.getCharacteristic(k.notify) ?: continue
            val w = s.getCharacteristic(k.write) ?: continue
            return n to w
        }
        val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        for (s in g.services) {
            if (s.uuid.toString().startsWith("00001800") || s.uuid.toString().startsWith("00001801")) continue
            val n = s.characteristics.firstOrNull { it.properties and notifyMask != 0 } ?: continue
            val w = s.characteristics.firstOrNull { it.properties and writeMask != 0 } ?: continue
            return n to w
        }
        return null
    }
}
