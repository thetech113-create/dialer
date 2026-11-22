package com.caller

import android.telecom.CallAudioState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit F-CALL-1: turning the speaker off asked Telecom for ROUTE_EARPIECE. With a wired headset plugged in, Telecom
 * removes the earpiece route (CallAudioRouteStateMachine, CONNECT_WIRED_HEADSET) and logs "Ignoring switch to earpiece
 * command. Not available.", so the call stayed on the loudspeaker. ROUTE_WIRED_OR_EARPIECE lets Telecom pick the
 * headset when one is plugged in and the earpiece otherwise.
 */
class SpeakerToggleRouteTest {
    @Test
    fun speakerOnAsksForTheSpeaker() {
        assertEquals(CallAudioState.ROUTE_SPEAKER, speakerToggleRoute(speakerOn = true))
    }

    @Test
    fun speakerOffAsksForHeadsetOrEarpieceNotEarpieceOnly() {
        assertEquals(CallAudioState.ROUTE_WIRED_OR_EARPIECE, speakerToggleRoute(speakerOn = false))
    }
}
