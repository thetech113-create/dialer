package com.caller

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plays a voicemail through the loudspeaker as normal media (not the earpiece call stream). */
private class VoicemailPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    var onFinished: (() -> Unit)? = null

    fun play(uri: Uri): Boolean {
        stop()
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, uri)
                setOnCompletionListener { stop(); onFinished?.invoke() }
                setOnErrorListener { _, _, _ -> stop(); onFinished?.invoke(); true }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.e("VoicemailPlayer", "play failed: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }
}

@Composable
fun VoicemailScreen(refreshTrigger: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voicemails by remember { mutableStateOf<List<VoicemailItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    val player = remember { VoicemailPlayer(context) }
    player.onFinished = { playingId = null }

    var reloadKey by remember { mutableStateOf(0) }
    var forwardingOn by remember { mutableStateOf(VoicemailForwarding.isEnabled(context)) }
    LaunchedEffect(refreshTrigger, reloadKey) {
        val server = runCatching { VoicemailServerClient.list(context) }.getOrDefault(emptyList())
        voicemails = (VoicemailManager.getLocalVoicemails(context) + VoicemailManager.getVoicemails(context) + server)
            .sortedByDescending { it.date }
        loaded = true
    }
    val sendForwardingCode: (String, Boolean?) -> Unit = { code, enabled ->
        try {
            VoicemailForwarding.sendCode(context, code)
            if (enabled != null) {
                VoicemailForwarding.setEnabledFlag(context, enabled)
                forwardingOn = enabled
            }
            Toast.makeText(context, "Sent to carrier. Check the message on screen to confirm.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't send code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    val callVoicemail = {
        if (!VoicemailManager.callCarrierVoicemail(context)) {
            Toast.makeText(context, "Couldn't call voicemail. Check the voicemail number in SIM settings.", Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Voicemail",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = callVoicemail) {
                Icon(Icons.Default.Call, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Call voicemail")
            }
        }

        if (VOICEMAIL_SERVER_URL.isNotBlank()) {
            VoicemailForwardingCard(
                forwardingOn = forwardingOn,
                signedIn = VoicemailServerClient.isAvailable(context),
                onTurnOn = { sendForwardingCode(VoicemailForwarding.enableAllCode(), true) },
                onTurnOff = { sendForwardingCode(VoicemailForwarding.DISABLE_CODE, false) },
                onNoAnswer = { sendForwardingCode(VoicemailForwarding.noAnswerCode(), true) },
                onBusy = { sendForwardingCode(VoicemailForwarding.busyCode(), true) },
                onUnreachable = { sendForwardingCode(VoicemailForwarding.unreachableCode(), true) },
                onRefresh = { reloadKey++ }
            )
        }

        if (loaded && voicemails.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "No voicemails yet.\n\nCarrier voicemail: tap \"Call voicemail\".",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            VoicemailList(
                voicemails = voicemails,
                playingId = playingId,
                onToggle = { vm ->
                    fun markRead() {
                        if (vm.isRead) return
                        voicemails = voicemails.map { if (it.id == vm.id) it.copy(isRead = true) else it }
                        scope.launch {
                            when {
                                vm.remoteId != null -> VoicemailServerClient.markHeard(context, vm.remoteId)
                                vm.isLocal -> VoicemailManager.markLocalAsRead(context, vm.id)
                                else -> VoicemailManager.markAsRead(context, vm.id)
                            }
                        }
                    }
                    if (playingId == vm.id) {
                        player.stop()
                        playingId = null
                    } else if (vm.remoteId != null) {
                        // Twilio voicemail: download through the Jio-safe client, then play the local copy.
                        scope.launch {
                            val uri = VoicemailServerClient.downloadAudio(context, vm.remoteId)
                            if (uri != null && player.play(uri)) {
                                playingId = vm.id
                                markRead()
                            } else {
                                Toast.makeText(context, "Couldn't play this voicemail", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (player.play(vm.audioUri)) {
                        playingId = vm.id
                        markRead()
                    } else {
                        Toast.makeText(context, "Couldn't play this voicemail", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { vm ->
                    val remoteId = vm.remoteId ?: return@VoicemailList
                    scope.launch {
                        if (playingId == vm.id) { player.stop(); playingId = null }
                        if (VoicemailServerClient.delete(context, remoteId)) {
                            voicemails = voicemails.filterNot { it.remoteId == remoteId }
                        } else {
                            Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
}

/** Twilio voicemail: carrier forwarding on/off, per-condition fallback codes and a refresh. */
@Composable
private fun VoicemailForwardingCard(
    forwardingOn: Boolean,
    signedIn: Boolean,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onNoAnswer: () -> Unit,
    onBusy: () -> Unit,
    onUnreachable: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (forwardingOn) "Voicemail: ON — missed calls go to $TWILIO_VOICEMAIL_NUMBER" else "Voicemail: OFF",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (!signedIn) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Verify your number in Profile to see your messages here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTurnOn, modifier = Modifier.weight(1f)) { Text("Turn on") }
                OutlinedButton(onClick = onTurnOff, modifier = Modifier.weight(1f)) { Text("Turn off") }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("Refresh") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "If \"Turn on\" shows a carrier error, set each one separately:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNoAnswer, modifier = Modifier.weight(1f)) { Text("No answer") }
                TextButton(onClick = onBusy, modifier = Modifier.weight(1f)) { Text("Busy") }
                TextButton(onClick = onUnreachable, modifier = Modifier.weight(1f)) { Text("Unreachable") }
            }
        }
    }
}

@Composable
private fun VoicemailList(
    voicemails: List<VoicemailItem>,
    playingId: Long?,
    onToggle: (VoicemailItem) -> Unit,
    onDelete: (VoicemailItem) -> Unit
) {
    run {
        val dateFormat = remember { SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()) }
        var confirmDelete by remember { mutableStateOf<VoicemailItem?>(null) }
        confirmDelete?.let { vm ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Delete voicemail?") },
                text = { Text("From ${vm.number}. This can't be undone.") },
                confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(vm) }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(voicemails, key = { "${it.isLocal}_${it.remoteId ?: it.id}" }) { vm ->
                val isPlaying = playingId == vm.id
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (vm.isRead) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = vm.number,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (vm.isRead) FontWeight.Normal else FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${dateFormat.format(Date(vm.date))} • ${vm.durationSec / 60}:${"%02d".format(vm.durationSec % 60)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (vm.remoteId != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = vm.transcript ?: "(Transcript not available yet)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { onToggle(vm) }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Stop" else "Play"
                            )
                        }
                        if (vm.remoteId != null) {
                            IconButton(onClick = { confirmDelete = vm }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
