package com.caller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.caller.link.LinkServer
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

class PcPairRequest(val pcName: String, val sas: String) {
    val answer = CompletableFuture<Boolean>()
}

/** Process-wide link status, written by the services and observed by the UI. */
object LinkState {
    var running by mutableStateOf(false)
    var connectedPc by mutableStateOf<String?>(null)

    /** The id of the PC connected right now (null when none), so the paired-PC list can show which one is live. */
    var connectedPcId by mutableStateOf<String?>(null)
    var pairRequest by mutableStateOf<PcPairRequest?>(null)
    var error by mutableStateOf<String?>(null)

    /** Bumped whenever the set of paired PCs changes so the UI re-reads it. */
    var pairingsVersion by mutableIntStateOf(0)

    @Volatile
    var server: LinkServer? = null

    fun send(msg: JSONObject): Boolean = server?.send(msg) ?: false

    /** For live audio: dropped rather than queued when the link is behind. */
    fun sendDroppable(msg: JSONObject): Boolean = server?.send(msg, droppable = true) ?: false
}
