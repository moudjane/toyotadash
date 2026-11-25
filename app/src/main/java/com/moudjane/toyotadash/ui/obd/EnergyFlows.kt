package com.moudjane.toyotadash.ui.obd

/** What the hybrid system is doing, derived from live data. Several can be on at once. */
internal data class EnergyFlows(
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
