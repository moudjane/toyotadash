package com.moudjane.toyotadash.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.IOException
import java.util.UUID

/** Standard SPP (Serial Port Profile) UUID — used by all Classic ELM327 dongles. */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val PREF_LAST_DEVICE = "last_bt_device_address"
private const val PREF_LAST_NAME   = "last_bt_device_name"
private const val PREF_LAST_BLE    = "last_bt_device_ble"

/** A dongle: Bluetooth Classic (paired, SPP) or Bluetooth Low Energy (scanned, GATT). */
data class BtDevice(
    val name: String,
    val address: String,
    val isBle: Boolean = false,
    val rssi: Int? = null,
) {
    /** Heuristic: names used by ELM327 dongles (NORAUTO, Vgate, OBDLink, generic clones…). */
    val looksLikeObd: Boolean
        get() = OBD_NAME_HINTS.any { it in name.uppercase() }

    private companion object {
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "NORAUTO", "VLINK", "V-LINK", "VGATE", "VEEPEAK", "ICAR", "KONNWEI")
    }
}

/** Handles the Bluetooth layer (Classic + BLE) and hands out ElmTransports; knows nothing about OBD2. */
@SuppressLint("MissingPermission")
class BluetoothRepository(private val context: Context) {

    private val adapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val prefs: SharedPreferences =
        context.getSharedPreferences("toyotadash", Context.MODE_PRIVATE)

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Returns already-paired Classic devices — no Bluetooth discovery needed. */
    fun getPairedDevices(): List<BtDevice> =
        adapter?.bondedDevices?.map { BtDevice(it.name ?: "Unknown", it.address) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Blocking BLE scan; named devices only, likely OBD dongles first, then by signal strength. */
    fun scanBle(durationMs: Long = 6000): List<BtDevice> =
        BleElmLink(context).scan(durationMs)
            .filter { it.name != "?" }
            .map { BtDevice(it.name, it.address, isBle = true, rssi = it.rssi) }
            .sortedWith(compareByDescending<BtDevice> { it.looksLikeObd }.thenByDescending { it.rssi ?: -200 })

    /** Opens an SPP socket to the given MAC address. Throws on failure. */
    fun connect(address: String): BluetoothSocket {
        adapter?.cancelDiscovery() // Discovery slows down SPP connections
        val device: BluetoothDevice = adapter!!.getRemoteDevice(address)
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket.connect() // Blocking — must be called from Dispatchers.IO
        prefs.edit().putString(PREF_LAST_DEVICE, address).apply()
        return socket
    }

    /**
     * Opens a transport to [device], Classic or BLE. BLE connections often fail the first
     * time with GATT error 133, so they are retried a few times. Blocking.
     */
    fun openTransport(device: BtDevice, attempts: Int = 4): ElmTransport {
        var lastError: Exception? = null
        repeat(if (device.isBle) attempts else 1) { attempt ->
            try {
                val transport = if (device.isBle) {
                    BleElmLink(context).openTransport(device.address) { Log.d("BleElmLink", it) }
                } else {
                    ClassicTransport(connect(device.address))
                }
                rememberDevice(device)
                return transport
            } catch (e: Exception) {
                lastError = e
                Log.w("BluetoothRepository", "Connect attempt ${attempt + 1} failed: ${e.message}")
                Thread.sleep(600L * (attempt + 1))
            }
        }
        throw IOException(lastError?.message ?: "Connection failed", lastError)
    }

    private fun rememberDevice(device: BtDevice) {
        prefs.edit()
            .putString(PREF_LAST_DEVICE, device.address)
            .putString(PREF_LAST_NAME, device.name)
            .putBoolean(PREF_LAST_BLE, device.isBle)
            .apply()
    }

    /** Address of the last successfully connected device, null if none. */
    fun lastDeviceAddress(): String? = prefs.getString(PREF_LAST_DEVICE, null)

    /** Last successfully connected dongle (Classic or BLE), null if none. */
    fun lastDevice(): BtDevice? {
        val address = prefs.getString(PREF_LAST_DEVICE, null) ?: return null
        return BtDevice(
            name    = prefs.getString(PREF_LAST_NAME, null) ?: address,
            address = address,
            isBle   = prefs.getBoolean(PREF_LAST_BLE, false),
        )
    }
}
