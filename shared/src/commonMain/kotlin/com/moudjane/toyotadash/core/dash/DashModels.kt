package com.moudjane.toyotadash.core.dash

/** One decoded value, as shown on the dashboard and in the raw-data view. */
data class PidReading(
    val id: String,         // e.g. "0105" or "2181@7E2"
    val name: String,
    val unit: String,
    val value: Float,
    val rawHex: String,     // raw hex bytes — useful for verifying PID formulas
    val isOk: Boolean,      // false if NO DATA or communication error
    val notes: String = "", // formula notes from PidConfig
)

/** State of the car's ECO / EV / POWER mode buttons. */
enum class DriveSelect {
    UNKNOWN, NORMAL, ECO, POWER, EV;

    companion object {
        /**
         * Value of the head unit's `car_driving_mode` setting.
         * ⚠ Mapping assumed, still to be confirmed in the car.
         */
        fun fromHeadUnit(raw: Int?): DriveSelect = when (raw) {
            0 -> NORMAL
            1 -> ECO
            2 -> POWER
            3 -> EV
            else -> UNKNOWN
        }
    }
}

/** What the hybrid powertrain is doing right now, as shown under the speed. */
enum class HybridState {
    EV, HYBRID, REGEN, READY;

    companion object {
        fun from(rpm: Float?, speed: Float?, amps: Float?): HybridState = when {
            // Regen = braking energy only: with the engine running, a negative current is the engine charging.
            (amps ?: 0f) < -2f && (speed ?: 0f) > 3f && (rpm ?: 0f) <= 200f -> REGEN
            (rpm ?: 0f) > 200f -> HYBRID
            (speed ?: 0f) > 1f -> EV
            else -> READY
        }
    }
}

/** Where energy is going, derived from live data. Several flows can be on at once. */
data class EnergyFlows(
    val engineToWheels: Boolean = false,   // petrol engine drives the car
    val engineToBattery: Boolean = false,  // engine charges the battery (MG1 as generator)
    val batteryToWheels: Boolean = false,  // electric assist / EV drive
    val wheelsToBattery: Boolean = false,  // regenerative braking
) {
    companion object {
        fun from(rpm: Float?, speed: Float?, amps: Float?): EnergyFlows {
            val engine = (rpm ?: 0f) > 200f
            val moving = (speed ?: 0f) > 2f
            val a = amps ?: 0f
            return EnergyFlows(
                engineToWheels  = engine && moving,
                engineToBattery = engine && a < -1f,
                batteryToWheels = a > 1f && moving,
                wheelsToBattery = !engine && moving && a < -1f,
            )
        }
    }
}
