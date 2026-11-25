package com.moudjane.toyotadash.ui.obd

import com.moudjane.toyotadash.data.bluetooth.BtDevice

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class PidReading(
    val id: String,         // e.g. "0105" or "2101@7C0"
    val name: String,
    val unit: String,
    val value: Float,
    val rawHex: String,     // raw hex bytes — useful for verifying PID formulas
    val isOk: Boolean,      // false if NO DATA or communication error
    val notes: String = ""  // ⚠ formula notes from PidConfig
)

data class ObdUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val pairedDevices: List<BtDevice> = emptyList(),
    val bleDevices: List<BtDevice> = emptyList(),
    val isScanning: Boolean = false,
    val scanError: String? = null,
    val lastDevice: BtDevice? = null,
    val connectedDevice: BtDevice? = null,
    val connectStep: String? = null,       // "Connexion BLE…", "Initialisation ELM327…"
    val standardReadings: List<PidReading> = emptyList(),
    val toyotaReadings: List<PidReading> = emptyList(),
    val adapterVoltage: Float? = null,     // ATRV, measured by the dongle
    val refreshHz: Float = 0f,             // fast-PID refresh rate, for the status bar
    val errorMessage: String? = null,
    val isSimulated: Boolean = false,
    /** Driving mode published by the head unit's CAN app (ECO / EV / POWER buttons). */
    val driveSelect: DriveSelect = DriveSelect.UNKNOWN,
) {
    /** Live value of a standard Mode 01 PID (e.g. "010C"), null when absent or unreadable. */
    fun std(command: String): Float? =
        standardReadings.firstOrNull { it.id == command && it.isOk }?.value

    /** Live value of a Toyota PID by name (several share one command, e.g. 2101). */
    fun toyota(name: String): Float? =
        toyotaReadings.firstOrNull { it.name == name && it.isOk }?.value
}

/** State of the car's ECO / EV / POWER mode buttons. */
enum class DriveSelect { UNKNOWN, NORMAL, ECO, POWER, EV }
