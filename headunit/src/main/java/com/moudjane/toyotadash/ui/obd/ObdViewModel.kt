package com.moudjane.toyotadash.ui.obd

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moudjane.toyotadash.car.DashHub
import com.moudjane.toyotadash.core.dash.DashPoller
import com.moudjane.toyotadash.core.dash.DashValues
import com.moudjane.toyotadash.core.dash.DemoDrive
import com.moudjane.toyotadash.core.dash.DriveSelect
import com.moudjane.toyotadash.core.dash.openSession
import com.moudjane.toyotadash.core.elm.ElmTransport
import com.moudjane.toyotadash.core.elm.ObdDriver
import com.moudjane.toyotadash.core.pid.PidDef
import com.moudjane.toyotadash.data.bluetooth.BluetoothRepository
import com.moudjane.toyotadash.data.bluetooth.BtDevice
import com.moudjane.toyotadash.data.canbus.CanBusKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
                val mode = DriveSelect.fromHeadUnit(raw)
                if (!_state.value.isSimulated && mode != _state.value.driveSelect) {
                    _state.update { it.copy(driveSelect = mode) }
                }
                delay(1000)
            }
        }
    }

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

                val activePids = openSession(d) { step -> _state.update { it.copy(connectStep = step) } }

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
        readingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                DashPoller(d, standardPids).run { snap ->
                    val s = _state.updateAndGet {
                        it.copy(
                            standardReadings = snap.standard,
                            toyotaReadings = snap.toyota,
                            adapterVoltage = snap.adapterVoltage ?: it.adapterVoltage,
                            refreshHz = snap.refreshHz ?: it.refreshHz,
                        )
                    }
                    // Share the live frame with the Android Auto cockpit (and any other surface).
                    DashHub.update(DashValues.from(s.standardReadings, s.toyotaReadings, s.adapterVoltage, s.driveSelect))
                }
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
            }
        }
    }

    // ── Simulator (no Bluetooth required) ────────────────────────────────────

    private fun startSimulator() {
        readingJob?.cancel()
        readingJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val frame = DemoDrive.frame(++tick)
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        connectedDevice  = BtDevice("SIMULATEUR", "00:00:00:00:00:00"),
                        standardReadings = frame.standard,
                        toyotaReadings   = frame.toyota,
                        adapterVoltage   = 14.1f,
                        refreshHz        = 10f,
                        driveSelect      = frame.driveSelect,
                    )
                }
                // Mirror the simulated drive onto the Android Auto cockpit too.
                DashHub.update(DashValues.from(frame.standard, frame.toyota, 14.1f, frame.driveSelect))
                delay(100L)
            }
        }
    }
}
