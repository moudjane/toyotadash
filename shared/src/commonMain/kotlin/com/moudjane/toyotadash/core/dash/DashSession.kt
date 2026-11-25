package com.moudjane.toyotadash.core.dash

import com.moudjane.toyotadash.core.elm.ElmException
import com.moudjane.toyotadash.core.elm.ObdDriver
import com.moudjane.toyotadash.core.pid.PidDef
import com.moudjane.toyotadash.core.pid.StandardPids

/**
 * Brings the ELM327 up and finds which standard PIDs the car answers, ready for a
 * [DashPoller]. [onStep] receives user-facing progress messages. Throws [ElmException]
 * with a user-facing message when the dongle or the car does not answer.
 */
suspend fun openSession(driver: ObdDriver, onStep: (String) -> Unit = {}): List<PidDef> {
    onStep("Initialisation ELM327…")
    if (!driver.initialize()) throw ElmException("Le dongle ne répond pas (ELM327)")

    onStep("Recherche du protocole véhicule…")
    val supported = driver.discoverSupportedPids()
    if (supported.isEmpty()) throw ElmException("La voiture ne répond pas — moteur allumé / READY ?")
    return StandardPids.ALL.filter { it.command.substring(2).uppercase() in supported }
}
