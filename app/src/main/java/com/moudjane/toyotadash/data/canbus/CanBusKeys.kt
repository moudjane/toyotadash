package com.moudjane.toyotadash.data.canbus

/**
 * Catalog of the Settings.System keys that the head unit's CAN app (com.hcn.autocan,
 * a system app) writes with live vehicle data. Any app can READ Settings.System without
 * a permission, and observe changes with a ContentObserver — this is how ToyotaDash gets
 * the data the Junsun already decodes from the CAN bus, with no OBD dongle.
 *
 * Keys were reverse-engineered from autocan v41 (bytecode scan of Settings.System.putInt
 * calls). Value scaling/encoding is not fully confirmed (the car was off during analysis),
 * so the UI also shows the raw value to allow calibration while driving.
 */
object CanBusKeys {

    // ── Primary driving values ────────────────────────────────────────────────
    const val SPEED           = "car_speed_value"
    const val AVG_SPEED       = "key_can_average_speed"
    const val SPEED_UNIT      = "key_can_speed_unit"   // 0 = km/h, 1 = mph (tentative)
    const val GEAR            = "key_can_gear"
    const val DRIVING_MODE    = "car_driving_mode"
    const val BATTERY_VOLUME  = "car_battery_volume"
    const val READY           = "car_run_preparation"  // hybrid "READY" state
    const val TEMP_UNIT       = "can_temp_unit"

    /**
     * Indicator / lamp / warning keys. Values are typically 0 (off) or 1 (on).
     * Order here is the display order. Only the ones actually present on the unit are shown.
     */
    val INDICATORS: Map<String, String> = linkedMapOf(
        "car_left_signal"                         to "Left turn",
        "car_right_signal"                        to "Right turn",
        "car_double_flashing"                     to "Hazards",
        "car_head_light"                          to "Head light",
        "car_low_beam"                            to "Low beam",
        "car_high_beam"                           to "High beam",
        "car_front_fog_light"                     to "Front fog",
        "car_rear_fog_light"                      to "Rear fog",
        "car_side_marker_light"                   to "Side marker",
        "car_parking_brake"                       to "Parking brake",
        "car_seat_belt"                           to "Seat belt",
        "car_door_reminder"                       to "Door open",
        "car_brake_failure"                       to "Brake fault",
        "car_eps_alarm"                           to "EPS alarm",
        "car_abs_indication"                      to "ABS",
        "car_low_battery_alarm"                   to "12V low",
        "car_system_fault"                        to "System fault",
        // Hybrid / EV specific
        "car_braking_energy_recovery_indication"  to "Regen",
        "car_charging_connection"                 to "Charge plug",
        "car_charging_status_indication"          to "Charging",
        "car_battery_disconnect"                  to "HV disconnect",
        "car_battery_insulation_fault_indication" to "HV insulation",
        "car_battery_temp_abnormal_indication"    to "HV batt temp",
        "car_motor_fault_indication"              to "Motor fault",
        "car_motor_overheat_indication"           to "Motor overheat",
        "car_dcdc_fault_indication"               to "DC-DC fault",
        "car_power_battery_fault"                 to "HV batt fault",
        "car_power_derate"                        to "Power derate",
    )

    /** A Settings.System key that likely carries vehicle data (for the raw/debug list). */
    val VEHICLE_KEY_REGEX = Regex("^(car_|key_can_|can_)")

    /** Keys that are config/display toggles, not live vehicle data — hidden from the raw list. */
    private val NOISE_KEYS = setOf(
        "car_speed_show", "car_battery_show", "car_lamp_show",
        "car_serial_baud", "can_temp_unit",
    )

    fun isVehicleKey(name: String): Boolean =
        VEHICLE_KEY_REGEX.containsMatchIn(name) && name !in NOISE_KEYS

    /** Tentative gear decoding — confirm by watching the raw value while shifting P/R/N/D. */
    fun gearLabel(raw: Int?): String = when (raw) {
        null -> "—"
        0    -> "P"
        1    -> "R"
        2    -> "N"
        3    -> "D"
        else -> raw.toString()
    }

    fun speedUnitLabel(raw: Int?): String = if (raw == 1) "mph" else "km/h"

    /** Tentative driving-mode decoding for Toyota hybrids. */
    fun drivingModeLabel(raw: Int?): String = when (raw) {
        null -> "—"
        0    -> "NORMAL"
        1    -> "ECO"
        2    -> "POWER"
        3    -> "EV"
        else -> "MODE $raw"
    }
}
