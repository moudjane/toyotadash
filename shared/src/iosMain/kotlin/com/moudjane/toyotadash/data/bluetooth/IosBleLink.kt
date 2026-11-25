@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.moudjane.toyotadash.data.bluetooth

import com.moudjane.toyotadash.core.elm.ElmException
import com.moudjane.toyotadash.core.elm.ElmTransport
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import platform.posix.memcpy

/** A BLE device seen during a scan. iOS hides MAC addresses: [id] is a per-phone UUID. */
data class BleDongle(val id: String, val name: String, val rssi: Int) {
    val looksLikeObd: Boolean get() = OBD_NAME_HINTS.any { it in name.uppercase() }

    private companion object {
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "NORAUTO", "VLINK", "V-LINK", "VGATE", "VEEPEAK", "ICAR", "KONNWEI")
    }
}

/**
 * CoreBluetooth link to an ELM327 BLE dongle: the iOS twin of the Android BleElmLink.
 *
 * Same "serial over GATT" idea: known service layouts are tried first (FFF0, FFE0, 18F0,
 * OBDLink), then any service having a notify and a write characteristic. Callbacks come
 * on the main queue; use it from the main thread only.
 */
class IosBleLink : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    private val central = CBCentralManager(delegate = this, queue = null)
    private val poweredOn = CompletableDeferred<Unit>()

    private val seen = LinkedHashMap<String, BleDongle>()
    private val peripherals = HashMap<String, CBPeripheral>()

    private var peripheral: CBPeripheral? = null
    private var writeChar: CBCharacteristic? = null
    private var ready = CompletableDeferred<Unit>()
    private var writeAck: CompletableDeferred<Unit>? = null
    private var servicesLeft = 0
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    var isConnected: Boolean = false
        private set

    // ── Scan ────────────────────────────────────────────────────────────────

    /** Scans for [durationMs]; named devices only, likely OBD dongles first, then by signal. */
    suspend fun scan(durationMs: Long = 6000): List<BleDongle> {
        awaitPoweredOn()
        seen.clear()
        central.scanForPeripheralsWithServices(null, null)
        delay(durationMs)
        central.stopScan()
        return seen.values.sortedWith(
            compareByDescending<BleDongle> { it.looksLikeObd }.thenByDescending { it.rssi },
        )
    }

    // ── Connect ─────────────────────────────────────────────────────────────

    /** Connects to a dongle found by [scan] (or remembered by id) and returns its transport. */
    suspend fun connect(id: String): ElmTransport {
        awaitPoweredOn()
        val p = peripherals[id]
            ?: central.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = id))).firstOrNull() as? CBPeripheral
            ?: throw ElmException("Dongle introuvable, relance un scan")
        ready = CompletableDeferred()
        peripheral = p
        p.delegate = this
        central.connectPeripheral(p, null)
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw ElmException("Le dongle ne répond pas")
        } catch (e: Exception) {
            close()
            throw e
        }
        return Transport()
    }

    fun close() {
        peripheral?.let { central.cancelPeripheralConnection(it) }
        peripheral = null
        writeChar = null
        isConnected = false
        writeAck?.completeExceptionally(ElmException("BLE fermé"))
    }

    private suspend fun awaitPoweredOn() {
        withTimeoutOrNull(5000) { poweredOn.await() }
            ?: throw ElmException("Bluetooth désactivé ou non autorisé")
    }

    // ── Transport ───────────────────────────────────────────────────────────

    private inner class Transport : ElmTransport {
        override suspend fun write(bytes: ByteArray) = this@IosBleLink.write(bytes)

        override suspend fun read(timeoutMs: Long): ByteArray? {
            var waited = 0L
            while (true) {
                incoming.tryReceive().getOrNull()?.let { return it }
                if (!isConnected) throw ElmException("Liaison BLE perdue")
                if (waited >= timeoutMs) return null
                withTimeoutOrNull(SLICE_MS) { incoming.receive() }?.let { return it }
                waited += SLICE_MS
            }
        }

        override fun clearInput() { while (incoming.tryReceive().isSuccess) { /* drop */ } }
        override fun close() = this@IosBleLink.close()
    }

    /** Writes in 20-byte chunks; waits for each acknowledgement when the dongle asks for one. */
    private suspend fun write(bytes: ByteArray) {
        val p = peripheral ?: throw ElmException("Non connecté")
        val c = writeChar ?: throw ElmException("Non connecté")
        val withResponse = c.properties and CBCharacteristicPropertyWrite != 0uL
        var offset = 0
        while (offset < bytes.size) {
            if (!isConnected) throw ElmException("Liaison BLE perdue")
            val chunk = bytes.copyOfRange(offset, minOf(offset + CHUNK, bytes.size))
            if (withResponse) {
                val ack = CompletableDeferred<Unit>().also { writeAck = it }
                p.writeValue(chunk.toNSData(), c, CBCharacteristicWriteWithResponse)
                withTimeoutOrNull(3000) { ack.await() } ?: throw ElmException("Écriture BLE sans réponse")
            } else {
                p.writeValue(chunk.toNSData(), c, CBCharacteristicWriteWithoutResponse)
            }
            offset += chunk.size
        }
    }

    // ── CBCentralManagerDelegate ────────────────────────────────────────────

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        if (central.state == CBManagerStatePoweredOn) poweredOn.complete(Unit)
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val name = didDiscoverPeripheral.name
            ?: advertisementData[CBAdvertisementDataLocalNameKey] as? String
            ?: return
        val id = didDiscoverPeripheral.identifier.UUIDString
        peripherals[id] = didDiscoverPeripheral
        seen[id] = BleDongle(id, name, RSSI.intValue)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        didConnectPeripheral.discoverServices(null)
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
        ready.completeExceptionally(ElmException("Connexion impossible : ${error?.localizedDescription}"))
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
        isConnected = false
        ready.completeExceptionally(ElmException("Dongle déconnecté"))
        writeAck?.completeExceptionally(ElmException("Dongle déconnecté"))
    }

    // ── CBPeripheralDelegate ────────────────────────────────────────────────

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        val services = peripheral.services.orEmpty().filterIsInstance<CBService>()
        servicesLeft = services.size
        if (services.isEmpty()) ready.completeExceptionally(ElmException("Aucun service BLE"))
        services.forEach { peripheral.discoverCharacteristics(null, it) }
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
        if (--servicesLeft > 0) return
        val (notify, write) = pickCharacteristics(peripheral)
            ?: return run { ready.completeExceptionally(ElmException("Pas de liaison série BLE sur ce dongle")) }
        writeChar = write
        peripheral.setNotifyValue(true, notify)
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
        if (error != null) {
            ready.completeExceptionally(ElmException("Notifications refusées : ${error.localizedDescription}"))
        } else {
            isConnected = true
            ready.complete(Unit)
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
        val bytes = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
        if (bytes.isNotEmpty()) incoming.trySend(bytes)
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
        writeAck?.complete(Unit)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun pickCharacteristics(p: CBPeripheral): Pair<CBCharacteristic, CBCharacteristic>? {
        val services = p.services.orEmpty().filterIsInstance<CBService>()
        fun CBService.char(uuid: String) =
            characteristics.orEmpty().filterIsInstance<CBCharacteristic>().firstOrNull { it.UUID.UUIDString.equals(uuid, true) }

        for ((service, notify, write) in KNOWN_LAYOUTS) {
            val s = services.firstOrNull { it.UUID.UUIDString.equals(service, true) } ?: continue
            val n = s.char(notify) ?: continue
            val w = s.char(write) ?: continue
            return n to w
        }
        val notifyMask = CBCharacteristicPropertyNotify or CBCharacteristicPropertyIndicate
        val writeMask = CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse
        for (s in services) {
            val chars = s.characteristics.orEmpty().filterIsInstance<CBCharacteristic>()
            val n = chars.firstOrNull { it.properties and notifyMask != 0uL } ?: continue
            val w = chars.firstOrNull { it.properties and writeMask != 0uL } ?: continue
            return n to w
        }
        return null
    }
}

private const val CHUNK = 20
private const val SLICE_MS = 250L
private const val CONNECT_TIMEOUT_MS = 20_000L

/** (service, notify, write). CoreBluetooth shortens 16-bit UUIDs to 4 hex digits. */
private val KNOWN_LAYOUTS = listOf(
    Triple("FFF0", "FFF1", "FFF2"), // Most Chinese ELM327 BLE clones (NORAUTO included)
    Triple("FFE0", "FFE1", "FFE1"), // HM-10 style modules
    Triple("18F0", "2AF0", "2AF1"), // Vgate iCar Pro BLE, Veepeak
    Triple(                         // OBDLink CX / MX+
        "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2",
        "BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F",
        "BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F",
    ),
)

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
