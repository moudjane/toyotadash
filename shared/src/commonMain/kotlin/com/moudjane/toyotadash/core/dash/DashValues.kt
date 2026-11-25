package com.moudjane.toyotadash.core.dash

/**
 * Everything the dashboard shows, computed from the raw readings. Null = not available
 * (PID not supported, not read yet, or unreadable).
 */
data class DashValues(
    val speed: Float?,      // km/h
    val rpm: Float?,        // engine, tr/min
    val pedal: Float?,      // %
    val coolant: Float?,    // °C
    val fuel: Float?,       // %
    val ambient: Float?,    // °C
    val volts12: Float?,    // 12 V battery, V
    val soc: Float?,        // hybrid battery state of charge, %
    val hvVolt: Float?,     // hybrid battery voltage, V
    val hvAmp: Float?,      // hybrid battery current, A (positive = discharge)
    val hvTemp: Float?,     // hottest hybrid battery sensor, °C
    val blocks: List<Float?>, // 10 block voltages, V
    val kw: Float?,         // hybrid battery power, kW (positive = drawn, negative = regen)
    val state: HybridState,
    val flows: EnergyFlows,
    val driveSelect: DriveSelect,
) {
    companion object {
        fun from(
            standard: List<PidReading>,
            toyota: List<PidReading>,
            adapterVoltage: Float? = null,
            driveSelect: DriveSelect = DriveSelect.UNKNOWN,
        ): DashValues {
            fun std(cmd: String) = standard.firstOrNull { it.id == cmd && it.isOk }?.value
            fun hv(name: String) = toyota.firstOrNull { it.name == name && it.isOk }?.value

            val rpm = std("010C")
            val speed = std("010D")
            val hvVolt = hv("HV Voltage")
            val hvAmp = hv("HV Current")
            return DashValues(
                speed = speed,
                rpm = rpm,
                pedal = std("0149") ?: std("0111"),
                coolant = std("0105"),
                fuel = std("012F"),
                ambient = std("0146"),
                volts12 = std("0142") ?: adapterVoltage,
                soc = std("015B"),
                hvVolt = hvVolt,
                hvAmp = hvAmp,
                hvTemp = hv("HV Battery Temp"),
                blocks = (1..10).map { hv("HV Block $it") },
                kw = if (hvVolt != null && hvAmp != null) hvVolt * hvAmp / 1000f else null,
                state = HybridState.from(rpm, speed, hvAmp),
                flows = EnergyFlows.from(rpm, speed, hvAmp),
                driveSelect = driveSelect,
            )
        }
    }
}
