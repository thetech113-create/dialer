package com.caller

/** The keypad character in a PC "dtmf" message, or null when it is not a single character a keypad can send. */
internal fun linkDtmfDigit(raw: String?): Char? = raw?.singleOrNull()?.takeIf { it in "0123456789*#ABCD" }
