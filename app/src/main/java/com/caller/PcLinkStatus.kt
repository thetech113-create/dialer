package com.caller

/** True when the PC the link is connected to right now ([connectedPcId]) is the paired PC [pcId]. */
internal fun pcRowIsConnected(pcId: String, connectedPcId: String?): Boolean =
    pcId.isNotBlank() && connectedPcId == pcId

/** The line under a paired PC: a pairing is remembered, but only a live link is "Connected". */
internal fun pcRowStatusText(connected: Boolean): String = if (connected) "Connected" else "Not connected"
