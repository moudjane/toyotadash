package com.moudjane.toyotadash.ui.obd

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moudjane.toyotadash.data.bluetooth.BluetoothRepository
import com.moudjane.toyotadash.data.bluetooth.BtDevice
import com.moudjane.toyotadash.core.elm.ElmResponse
import com.moudjane.toyotadash.core.elm.ElmTransport
import com.moudjane.toyotadash.core.elm.ObdDriver
import com.moudjane.toyotadash.data.canbus.CanBusKeys
import com.moudjane.toyotadash.data.obd2.PidDef
import com.moudjane.toyotadash.data.obd2.StandardPids
import com.moudjane.toyotadash.data.obd2.ToyotaHybridPids
import com.moudjane.toyotadash.data.obd2.ToyotaPidDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/** Single ViewModel for the OBD2 side: BT/BLE connection, OBD2 reading, simulator. */
class ObdViewModel(application: Application) : AndroidViewModel(application) {

    private val btRepo = BluetoothRepository(application)

    private val _state = MutableStateFlow(ObdUiState(lastDevice = btRepo.lastDevice()))
    val state: StateFlow<ObdUiState> = _state.asStateFlow()

    private var transport: ElmTransport? = null
    private var driver: ObdDriver? = null
    private var readingJob: Job? = null
    private var scanJob: Job? = null
    private var autoConnectTried = false

    /** ECO / EV / POWER buttons, read from the head unit's own CAN data (Settings.System). */
    init {
        // Light poll (1 s): Settings.System reads are cheap and the mode changes rarely.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val raw = runCatching {
                    Settings.System.getString(application.contentResolver, CanBusKeys.DRIVING_MODE)
                }.getOrNull()?.trim()?.toIntOrNull()
                val mode = when (raw) {
                    null -> DriveSelect.UNKNOWN
                    0 -> DriveSelect.NORMAL
                    1 -> DriveSelect.ECO
                    2 -> DriveSelect.POWER
                    3 -> DriveSelect.EV
                    else -> DriveSelect.UNKNOWN
                }
                if (!_state.value.isSimulated && mode != _state.value.driveSelect) {
                    _state.update { it.copy(driveSelect = mode) }
                }
                delay(1000)
            }
        }
    }

    /** PIDs read every cycle: they drive the needles. Everything else is read every [SLOW_EVERY] cycles. */
    private val fastCommands = setOf("010C", "010D", "0111", "0104", "0149")

    // ── Public API ────────────────────────────────────────────────────────────

    fun loadPairedDevices() {
        _state.update { it.copy(pairedDevices = btRepo.getPairedDevices()) }
    }

    /** Scans for BLE ELM327 dongles (NORAUTO, Vgate, generic "OBD BLE"…) for a few seconds. */
    fun scanBle() {
        if (_state.value.isScanning) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isScanning = true, scanError = null) }
            try {
                if (!btRepo.isBluetoothOn) throw IllegalStateException("Bluetooth désactivé")
                val found = btRepo.scanBle(6000)
                _state.update { it.copy(bleDevices = found) }
            } catch (e: Exception) {
                _state.update { it.copy(scanError = e.message ?: "Scan impossible") }
            } finally {
                _state.update { it.copy(isScanning = false) }
            }
        }
    }

    /** Debug only (screenshots on an emulator without Bluetooth): fills the lists with sample dongles. */
    fun debugFakeDevices() {
        autoConnectTried = true
        _state.update {
            it.copy(
                bleDevices = listOf(
                    BtDevice("NORAUTOBT0000", "00:11:22:33:44:55", isBle = true, rssi = -74),
                    BtDevice("OBD BLE", "00:11:22:33:44:66", isBle = true, rssi = -96),
                    BtDevice("FSC-BP104E", "00:11:22:33:44:77", isBle = true, rssi = -93),
                ),
                pairedDevices = listOf(BtDevice("Téléphone", "00:11:22:33:44:88")),
                lastDevice = BtDevice("NORAUTOBT0000", "00:11:22:33:44:55", isBle = true),
            )
        }
    }

    /** Connects once per app launch to the last dongle used, so the dash comes up by itself. */
    fun autoConnectIfPossible() {
        if (autoConnectTried) return
        autoConnectTried = true
        val last = _state.value.lastDevice ?: return
        if (_state.value.connectionStatus == ConnectionStatus.DISCONNECTED) connect(last)
    }

    /** Connects to an ELM327 dongle: opens the link, initialises ELM327, starts the reading loop. */
    fun connect(device: BtDevice) {
        if (_state.value.connectionStatus == ConnectionStatus.CONNECTING) return
        readingJob?.cancel()
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.CONNECTING,
                errorMessage = null,
                isSimulated = false,
                connectStep = if (device.isBle) "Connexion BLE à ${device.name}…" else "Connexion à ${device.name}…",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val t = btRepo.openTransport(device)
                transport = t
                val d = ObdDriver(t)
                driver = d

                _state.update { it.copy(connectStep = "Initialisation ELM327…") }
                if (!d.initialize()) throw Exception("Le dongle ne répond pas (ELM327)")

                _state.update { it.copy(connectStep = "Recherche du protocole véhicule…") }
                val supportedPidNums = d.discoverSupportedPids()
                if (supportedPidNums.isEmpty()) {
                    throw Exception("La voiture ne répond pas — moteur allumé / READY ?")
                }
                val activePids = StandardPids.ALL.filter { pid ->
                    supportedPidNums.contains(pid.command.substring(2).uppercase())
                }

                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        connectedDevice = device,
                        lastDevice = device,
                        connectStep = null,
                    )
                }
                startReadingLoop(d, activePids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeLink()
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        connectStep = null,
                        errorMessage = e.message ?: "Erreur inconnue",
                    )
                }
            }
        }
    }

    fun disconnect() {
        readingJob?.cancel()
        closeLink()
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                connectedDevice = null,
                connectStep = null,
                standardReadings = emptyList(),
                toyotaReadings = emptyList(),
                adapterVoltage = null,
                refreshHz = 0f,
                isSimulated = false,
            )
        }
    }

    fun toggleSimulator() {
        if (_state.value.isSimulated) {
            disconnect()
        } else {
            disconnect()
            _state.update { it.copy(isSimulated = true) }
            startSimulator()
        }
    }

    override fun onCleared() {
        super.onCleared()
        readingJob?.cancel()
        scanJob?.cancel()
        closeLink()
    }

    private fun closeLink() {
        driver?.close() ?: transport?.close()
        transport = null
        driver = null
    }

    // ── Real OBD2 reading loop ────────────────────────────────────────────────

    private fun startReadingLoop(d: ObdDriver, standardPids: List<PidDef>) {
        readingJob?.cancel()
        val fast = standardPids.filter { it.command in fastCommands }
        val slow = standardPids.filterNot { it.command in fastCommands }
        val toyotaByEcu = ToyotaHybridPids.ALL.groupBy { it.ecuAddress }

        readingJob = viewModelScope.launch(Dispatchers.IO) {
            val standard = LinkedHashMap<String, PidReading>()
            val toyota = LinkedHashMap<String, PidReading>()
            val ecuMisses = HashMap<String, Int>()
            var cycle = 0
            var lastHzStamp = System.currentTimeMillis()
            var cyclesSinceStamp = 0

            while (isActive) {
                try {
                    d.resetHeader()
                    val batch = if (cycle % SLOW_EVERY == 0) fast + slow else fast
                    for (pid in batch) standard[pid.command] = readStandardPid(d, pid)

                    // Toyota PIDs: one request per (ECU, command), parsed into every PID sharing it.
                    run {
                        for ((ecu, allPids) in toyotaByEcu) {
                            val pids = allPids.filter { cycle % it.everyCycles == 0 }
                            if (pids.isEmpty()) continue
                            // An ECU that never answers is only retried now and then (each miss costs a timeout).
                            if ((ecuMisses[ecu] ?: 0) >= 3 && cycle % 40 != 0) continue
                            d.setHeader(ecu)
                            var anyOk = false
                            for ((command, sameCmd) in pids.groupBy { it.command }) {
                                val raw = runCatching { d.readPid(command) }.getOrNull()
                                sameCmd.forEach { pid ->
                                    val r = parseToyotaPid(pid, raw)
                                    anyOk = anyOk || r.isOk
                                    toyota[pid.name] = r
                                }
                            }
                            ecuMisses[ecu] = if (anyOk) 0 else (ecuMisses[ecu] ?: 0) + 1
                        }
                    }

                    val volts = if (cycle % SLOW_EVERY == 0) d.readAdapterVoltage() else null

                    cycle++
                    cyclesSinceStamp++
                    val now = System.currentTimeMillis()
                    val hz = if (now - lastHzStamp >= 2000) {
                        (cyclesSinceStamp * 1000f / (now - lastHzStamp)).also {
                            lastHzStamp = now; cyclesSinceStamp = 0
                        }
                    } else null

                    _state.update {
                        it.copy(
                            standardReadings = standard.values.toList(),
                            toyotaReadings = toyota.values.toList(),
                            adapterVoltage = volts ?: it.adapterVoltage,
                            refreshHz = hz ?: it.refreshHz,
                        )
                    }
                    delay(20)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closeLink()
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.ERROR,
                            errorMessage = "Connexion perdue : ${e.message}",
                        )
                    }
                    break
                }
            }
        }
    }

    /** Reads one standard PID. A lost link (IOException) propagates to end the loop. */
    private suspend fun readStandardPid(d: ObdDriver, pid: PidDef): PidReading {
        val raw    = d.readPid(pid.command)
        val pidNum = pid.command.substring(2).toInt(16)
        val data   = ElmResponse.extractData(raw, 0x41, pidNum)
        val value  = data?.let { runCatching { pid.parse(it) }.getOrNull() }
        return PidReading(
            id = pid.command, name = pid.name, unit = pid.unit,
            value = value ?: 0f, rawHex = raw ?: "NO DATA", isOk = value != null,
        )
    }

    private fun parseToyotaPid(pid: ToyotaPidDef, raw: String?): PidReading {
        val cmdByte = pid.command.substring(2).toInt(16)
        val modeNum = pid.command.substring(0, 2).toInt(16)
        val data    = ElmResponse.extractData(raw, 0x40 + modeNum, cmdByte)
        val value   = data?.let { runCatching { pid.parse(it) }.getOrNull() }
        return PidReading(
            id = "${pid.command}@${pid.ecuAddress}", name = pid.name, unit = pid.unit,
            value = value ?: 0f, rawHex = raw ?: "NO DATA", isOk = value != null, notes = pid.notes,
        )
    }

    // ── Simulator (no Bluetooth required) ────────────────────────────────────

    private fun startSimulator() {
        readingJob?.cancel()
        readingJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                tick++
                val t = tick.toFloat()
                // A drive cycle: accelerate, cruise, brake (regen), EV crawl.
                val phase = (sin(t / 40) + 1f) / 2f
                val speed = 90f * phase
                val engineOn = phase > 0.35f
                val rpm = if (engineOn) 1100f + 3200f * phase + 300f * sin(t / 3) else 0f
                val current = 60f * sin(t / 25) // + discharge (assist), − charge (regen)

                val simStandard = listOf(
                    sim("010C", "Engine RPM",        "rpm",  rpm.coerceAtLeast(0f)),
                    sim("010D", "Vehicle Speed",     "km/h", speed),
                    sim("0105", "Coolant Temp",      "°C",   78f + 8f * sin(t / 200)),
                    sim("0104", "Engine Load",       "%",    if (engineOn) 20f + 60f * phase else 0f),
                    sim("010F", "Intake Air Temp",   "°C",   24f),
                    sim("0111", "Throttle Position", "%",    12f + 70f * phase),
                    sim("0149", "Accel Pedal D",     "%",    10f + 75f * phase),
                    sim("0142", "Module Voltage",    "V",    14.1f),
                    sim("0146", "Ambient Temp",      "°C",   17f),
                    sim("013C", "Catalyst Temp B1S1","°C",   520f + 80f * phase),
                    sim("012F", "Fuel Level",        "%",    63f),
                )
                val packV = 165f - current * 0.12f
                val simToyota = listOf(
                    sim("2181@7E2", "HV Voltage",      "V",   packV),
                    sim("2198@7E2", "HV Current",      "A",   current),
                    sim("2187@7E2", "HV Battery Temp", "°C",  34f + 2f * sin(t / 300)),
                    sim("2187@7E2", "HV Intake Temp",  "°C",  21.5f),
                    sim("2161@7E2", "MG1 Speed",       "rpm", if (engineOn) 2500f * phase else 0f),
                    sim("2162@7E2", "MG2 Speed",       "rpm", speed * 70f),
                ) + (1..3).map { sim("2187@7E2", "HV Temp TB$it", "°C", 32f + it + sin(t / 300)) } +
                    (1..10).map { sim("2181@7E2", "HV Block $it", "V", packV / 10f + 0.04f * sin(t / 7 + it)) }
                val withSoc = simStandard + sim("015B", "HV Battery SoC", "%", 62f + 6f * sin(t / 90))

                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        connectedDevice  = BtDevice("SIMULATEUR", "00:00:00:00:00:00"),
                        standardReadings = withSoc,
                        toyotaReadings   = simToyota,
                        adapterVoltage   = 14.1f,
                        refreshHz        = 10f,
                        driveSelect      = if (!engineOn && speed > 1f) DriveSelect.EV
                                           else if (tick / 150 % 2 == 0) DriveSelect.ECO else DriveSelect.NORMAL,
                    )
                }
                delay(100L)
            }
        }
    }

    private fun sim(id: String, name: String, unit: String, value: Float) =
        PidReading(id = id, name = name, unit = unit, value = value, rawHex = "SIM", isOk = true)

    private companion object {
        const val SLOW_EVERY = 8
    }
}
