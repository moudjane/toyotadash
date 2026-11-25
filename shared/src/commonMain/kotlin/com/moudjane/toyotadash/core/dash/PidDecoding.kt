package com.moudjane.toyotadash.core.dash

import com.moudjane.toyotadash.core.elm.ElmResponse
import com.moudjane.toyotadash.core.pid.PidDef
import com.moudjane.toyotadash.core.pid.ToyotaPidDef

/** Decodes the raw answer to a standard Mode 01 PID ([raw] null = no answer). */
fun decode(pid: PidDef, raw: String?): PidReading {
    val data  = ElmResponse.extractData(raw, 0x41, pid.command.substring(2).toInt(16))
    val value = data?.let { runCatching { pid.parse(it) }.getOrNull() }
    return PidReading(
        id = pid.command, name = pid.name, unit = pid.unit,
        value = value ?: 0f, rawHex = raw ?: "NO DATA", isOk = value != null,
    )
}

/** Decodes the raw answer to a Toyota enhanced PID (e.g. 2181 → 6181…). */
fun decode(pid: ToyotaPidDef, raw: String?): PidReading {
    val mode  = pid.command.substring(0, 2).toInt(16)
    val data  = ElmResponse.extractData(raw, 0x40 + mode, pid.command.substring(2).toInt(16))
    val value = data?.let { runCatching { pid.parse(it) }.getOrNull() }
    return PidReading(
        id = "${pid.command}@${pid.ecuAddress}", name = pid.name, unit = pid.unit,
        value = value ?: 0f, rawHex = raw ?: "NO DATA", isOk = value != null, notes = pid.notes,
    )
}
