package com.moudjane.toyotadash.core

/** Raw ELM327 answers (headers off, spaces off) used across the tests. */
object Frames {
    /**
     * 7E2 2181, ISO-TP multi-frame: 10 blocks at 0x34CE (≈ 16.50 V) and a pack voltage of
     * 1650 (165.0 V), the values read on the car.
     */
    const val HV_2181 = "01A\n0:618134CE34CE\n1:34CE34CE34CE34\n2:CE34CE34CE34CE\n3:34CE00000672"

    /** 7E2 2198, as received from the car: 0x806E → +1.10 A (light discharge). */
    const val HV_2198 = "6198806E5D9E"

    /** 7E2 2187: intake 0x471C (≈ 21.1 °C), TB1..3 0x538C / 0x53A8 / 0x5335 (≈ 33-34 °C), seen on the car. */
    const val HV_2187 = "6187471C538C53A85335"
}
