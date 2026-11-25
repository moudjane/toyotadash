package com.moudjane.toyotadash.core.elm

/** Uppercase hex, zero-padded to [width] digits: 0x7E2.hex(3) = "7E2", 5.hex(2) = "05". */
internal fun Int.hex(width: Int): String = toString(16).uppercase().padStart(width, '0')
