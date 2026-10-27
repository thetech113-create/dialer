@file:Suppress(
    "all",
    "WARNINGS",
    "unused",
    "UNUSED_VARIABLE",
    "UnusedImport",
    "RemoveRedundantQualifierName",
    "NAME_SHADOWING",
    "LiftReturnOrAssignment",
    "RegExpSimplifiable",
    "UNNECESSARY_SAFE_CALL",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "UselessCallOnNotNull",
    "RedundantSafeCall",
    "DEPRECATION",
    "IfThenToIfEmpty",
    "ifEmpty {...}",
    "SpellCheckingInspection",
    "Typo"
)

package com.caller

import android.graphics.BitmapFactory
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf 
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.caller.logging.UltraForensicLogger
import com.caller.ui.theme.CallerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val CALL_UI_FORENSIC_TAG = "P2P_FORENSIC"
private const val TAG_CALL_FORENSIC = "CallForensic"
private const val CALL_UI_PROXIMITY_FAR_CONFIRMATION_MS = 450L
private const val CALL_UI_PROXIMITY_TOUCH_GUARD_MS = 650L

data class CallParticipant(
    val id: String,
    val name: String,
    val number: String,
    val connectTimeMillis: Long = 0L,
    val state: String = "IDLE",
    val talkDurationMillis: Long = 0L,
    val talkTimerRunning: Boolean = false
)

data class CallState(
    val isInCall: Boolean = false,
    val showUI: Boolean = true,
    val primaryState: String = "IDLE",
    val isRinging: Boolean = false,
    val isDialing: Boolean = false,
    val isHolding: Boolean = false,
    val phoneNumber: String = "",
    val contactName: String = "",
    val contactPhotoUri: String? = null,
    val primaryCallId: String = "",
    val secondaryPhoneNumber: String? = null,
    val secondaryContactName: String? = null,
    val secondaryCallId: String? = null,
    val secondaryState: String? = null,
    val secondaryCallStartTime: Long = 0L,
    val isConference: Boolean = false,
    val callStartTime: Long = 0,
    val isTerminal: Boolean = false,
    val disconnectTimeMillis: Long = 0L,
    val finalDurationMillis: Long = 0L,
    val disconnectCauseCode: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isSpam: Boolean = false,
    val conferenceParticipants: List<CallParticipant> = emptyList(),
    val unmergedCallCount: Int = 0,
    val totalTopLevelCalls: Int = 0,
    val canMergeCalls: Boolean = false,
    val currentAudioRoute: Int = CallAudioState.ROUTE_EARPIECE,
    val supportedAudioRouteMask: Int = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER,
    val bluetoothDeviceName: String? = null,
    val simLabel: String = "",
    val isVideoCall: Boolean = false,
    val isWifiCall: Boolean = false,
    val stateSnapshotTimeMillis: Long = 0L,
    val primaryTalkDurationMillis: Long = 0L,
    val primaryTalkTimerRunning: Boolean = false,
    val secondaryTalkDurationMillis: Long = 0L,
    val secondaryTalkTimerRunning: Boolean = false
)

private fun normalizeCallUiPrimaryState(primaryState: String, connectTimeMillis: Long, isTerminal: Boolean): String {
    if (isTerminal || connectTimeMillis <= 0L) return primaryState
    return when (primaryState) {
        "DIALING", "CONNECTING", "TRANSITIONING", "IDLE" -> "ACTIVE"
        else -> primaryState
    }
}

private fun supportsAudioRoute(routeMask: Int, route: Int): Boolean {
    return routeMask and route != 0
}

private fun secondaryCallBannerTitle(callState: CallState): String {
    return when {
        callState.secondaryState == "RINGING" -> "Call waiting"
        callState.secondaryState == "DIALING" -> "Dialing call"
        callState.secondaryState == "ACTIVE" || callState.secondaryState == "CONFERENCE" -> "Current call"
        callState.secondaryState == "HOLDING" -> if (callState.totalTopLevelCalls > 2) {
            "${callState.totalTopLevelCalls - 1} calls on hold"
        } else {
            "Call on hold"
        }
        callState.totalTopLevelCalls > 2 -> "${callState.totalTopLevelCalls - 1} other calls"
        else -> "Other call"
    }
}

private fun secondaryCallActionText(secondaryState: String?): String {
    return if (secondaryState == "RINGING") "Swap & answer" else "Swap"
}

private fun areEquivalentPhoneDisplays(first: String, second: String): Boolean {
    if (first.isBlank() || second.isBlank()) return false
    val firstSnapshot = buildP2PNumberSnapshot(first)
    val secondSnapshot = buildP2PNumberSnapshot(second)
    val firstKey = firstSnapshot.normalized.ifBlank {
        firstSnapshot.last10.ifBlank { firstSnapshot.digits }
    }
    val secondKey = secondSnapshot.normalized.ifBlank {
        secondSnapshot.last10.ifBlank { secondSnapshot.digits }
    }
    return firstKey.isNotBlank() && secondKey.isNotBlank() && firstKey == secondKey
}

private fun resolveCallHeroFontSize(
    text: String,
    isCompactCallSurface: Boolean,
    showKeypad: Boolean,
    maxWidthDp: Float,
    maxHeightDp: Float
): TextUnit {
    val baseSize = when {
        showKeypad && text.length > 16 -> 34.sp
        showKeypad -> 40.sp
        isCompactCallSurface && text.length > 16 -> 34.sp
        isCompactCallSurface && text.length > 12 -> 40.sp
        isCompactCallSurface -> 44.sp
        text.length > 16 -> 44.sp
        text.length > 12 -> 52.sp
        else -> 60.sp
    }
    val surfaceDelta = when {
        maxWidthDp < 350f || maxHeightDp < 760f -> -2f
        maxWidthDp > 430f || maxHeightDp > 920f -> 2f
        else -> 0f
    }
    val minSize = if (showKeypad) 34f else 36f
    return (baseSize.value + surfaceDelta).coerceAtLeast(minSize).sp
}

private fun appendSimLabel(baseText: String, simLabel: String): String {
    return when {
        simLabel.isBlank() -> baseText
        baseText.isBlank() -> simLabel
        else -> "$baseText | $simLabel"
    }
}

private fun formatElapsedDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun resolveLiveTalkDurationMillis(
    snapshotDurationMillis: Long,
    timerRunning: Boolean,
    snapshotTimeMillis: Long,
    currentTimeMillis: Long
): Long {
    val baseDuration = snapshotDurationMillis.coerceAtLeast(0L)
    if (!timerRunning || snapshotTimeMillis <= 0L) return baseDuration
    return (baseDuration + (currentTimeMillis - snapshotTimeMillis).coerceAtLeast(0L)).coerceAtLeast(0L)
}

private fun elapsedDurationFrom(
    snapshotDurationMillis: Long,
    timerRunning: Boolean,
    snapshotTimeMillis: Long,
    currentTimeMillis: Long
): String {
    val liveDuration = resolveLiveTalkDurationMillis(snapshotDurationMillis, timerRunning, snapshotTimeMillis, currentTimeMillis)
    if (liveDuration <= 0L) return ""
    return formatElapsedDuration(liveDuration)
}

private fun participantTimerLabel(participant: CallParticipant, currentTimeMillis: Long, snapshotTimeMillis: Long): String {
    val elapsed = elapsedDurationFrom(participant.talkDurationMillis, participant.talkTimerRunning, snapshotTimeMillis, currentTimeMillis)
    if (participant.state == "HOLDING") {
        return if (elapsed.isNotBlank()) "On hold ($elapsed)" else "On hold"
    }
    if (elapsed.isNotBlank()) return "Talk time: $elapsed"
    return when (participant.state) {
        "RINGING" -> "Ringing"
        "DIALING" -> "Dialing"
        "ACTIVE" -> "Active"
        else -> ""
    }
}

private fun resolveCallSupportingFontSize(
    isCompactCallSurface: Boolean,
    showKeypad: Boolean,
    maxWidthDp: Float,
    maxHeightDp: Float
): TextUnit {
    val baseSize = when {
        showKeypad -> 15.sp
        isCompactCallSurface -> 16.sp
        else -> 17.sp
    }
    val surfaceDelta = when {
        maxWidthDp < 350f || maxHeightDp < 760f -> -1f
        maxWidthDp > 430f || maxHeightDp > 920f -> 1f
        else -> 0f
    }
    val minSize = if (showKeypad) 14f else 15f
    return (baseSize.value + surfaceDelta).coerceAtLeast(minSize).sp
}

private data class AudioRouteOption(
    val route: Int,
    val label: String
)

private data class CallActionTileSpec(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val accentColor: Color? = null
)

@Composable
private fun CallStatusChip(
    text: String,
    isWifiCall: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val chipBg = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFFE2E8F0)
    val textColor = if (isDark) Color.White else Color(0xFF0F172A)
    val hdTagBg = if (isDark) Color.White else Color(0xFF0F172A)
    val hdTagText = if (isDark) Color(0xFF11152A) else Color.White

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(chipBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(hdTagBg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isWifiCall) "VoWiFi" else "HD",
                color = hdTagText,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Text(
            text = text,
            color = textColor,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CallActionTile(
    spec: CallActionTileSpec,
    modifier: Modifier = Modifier
) {
    val palette = UnifiedUiSystem.rememberPalette()
    val accentColor = spec.accentColor

    val iconBubbleColor = when {
        !spec.enabled -> palette.tileDisabledBg
        spec.active && accentColor != null -> accentColor.copy(alpha = 0.24f)
        spec.active -> palette.tileActiveBg
        accentColor != null -> accentColor.copy(alpha = 0.14f)
        else -> palette.tileIdleBg
    }
    val contentColor = when {
        !spec.enabled -> palette.tileDisabledText
        accentColor != null -> accentColor
        else -> palette.textPrimary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(iconBubbleColor)
                .clickable(enabled = spec.enabled, onClick = spec.onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = spec.label,
                tint = contentColor,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = spec.label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 13.sp,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CallActionGrid(
    actions: List<CallActionTileSpec>,
    modifier: Modifier = Modifier
) {
    val rows = when (actions.size) {
        4 -> listOf(actions.take(2), actions.drop(2))
        5 -> listOf(actions.take(3), actions.drop(3))
        else -> actions.chunked(3)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEachIndexed { rowIndex, rowActions ->
            val rowWidthFraction = when (rowActions.size) {
                1 -> 0.34f
                2 -> 0.72f
                else -> 1f
            }
            Row(
                modifier = Modifier.fillMaxWidth(rowWidthFraction),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowActions.forEach { spec ->
                    CallActionTile(spec = spec, modifier = Modifier.weight(1f))
                }
            }
            if (rowIndex != rows.lastIndex) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun TelecomVideoCallView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoVersion by MyInCallService.videoCallVersion.collectAsState()
    var remoteSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var localSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var remoteTextureView by remember { mutableStateOf<TextureView?>(null) }
    var peerSize by remember { mutableStateOf(0 to 0) }

    val applyAspectRatioMatrix = remember(remoteTextureView, peerSize) {
        {
            val tv = remoteTextureView
            val (pw, ph) = peerSize
            if (tv != null && pw > 0 && ph > 0 && tv.width > 0 && tv.height > 0) {
                val vw = tv.width.toFloat()
                val vh = tv.height.toFloat()
                val scale = maxOf(vw / pw.toFloat(), vh / ph.toFloat())
                val matrix = Matrix()
                matrix.setScale(pw.toFloat() * scale / vw, ph.toFloat() * scale / vh, vw / 2f, vh / 2f)
                tv.setTransform(matrix)
                Log.w(CALL_UI_FORENSIC_TAG, "telecom_video_aspect_matrix applied pw=$pw ph=$ph vw=$vw vh=$vh scale=$scale")
            }
        }
    }

    DisposableEffect(remoteSurfaceTexture, localSurfaceTexture, videoVersion) {
        val videoCall = MyInCallService.getInstance()?.getPrimaryVideoCall()
        val callback = object : InCallService.VideoCall.Callback() {
            override fun onPeerDimensionsChanged(width: Int, height: Int) {
                peerSize = width to height
                Log.w(CALL_UI_FORENSIC_TAG, "telecom_video_peer_dimensions w=$width h=$height")
            }
            override fun onCameraCapabilitiesChanged(cameraCapabilities: VideoProfile.CameraCapabilities?) {}
            override fun onSessionModifyRequestReceived(videoProfile: VideoProfile?) {
                if (videoProfile != null && VideoProfile.isVideo(videoProfile.videoState)) {
                    val responseProfile = VideoProfile(
                        VideoProfile.STATE_BIDIRECTIONAL,
                        videoProfile.quality
                    )
                    videoCall?.sendSessionModifyResponse(responseProfile)
                    Log.w(CALL_UI_FORENSIC_TAG, "telecom_video_session_modify_accepted state=${videoProfile.videoState}")
                }
            }
            override fun onSessionModifyResponseReceived(status: Int, requestedProfile: VideoProfile?, responseProfile: VideoProfile?) {}
            override fun onCallSessionEvent(event: Int) {}
            override fun onCallDataUsageChanged(dataUsage: Long) {}
            override fun onVideoQualityChanged(videoQuality: Int) {}
        }

        videoCall?.registerCallback(callback)

        remoteSurfaceTexture?.let { st ->
            val displaySurface = Surface(st)
            videoCall?.setDisplaySurface(displaySurface)
            Log.w(CALL_UI_FORENSIC_TAG, "telecom_video_display_surface_attached surface=$displaySurface videoCall=$videoCall")
        }

        localSurfaceTexture?.let { st ->
            val previewSurface = Surface(st)
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                val frontCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } ?: cameraManager?.cameraIdList?.firstOrNull().orEmpty()

                if (frontCameraId.isNotEmpty()) {
                    videoCall?.setCamera(frontCameraId)
                }
                videoCall?.setPreviewSurface(previewSurface)
                videoCall?.requestCameraCapabilities()
                val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: 0
                videoCall?.setDeviceOrientation(rotation * 90)
                Log.w(CALL_UI_FORENSIC_TAG, "telecom_video_local_camera_configured cameraId=$frontCameraId previewSurface=$previewSurface")
            } catch (e: Exception) {
                Log.e(CALL_UI_FORENSIC_TAG, "telecom_video_local_camera_error err=${e.message}", e)
            }
        }

        applyAspectRatioMatrix()

        onDispose {
            videoCall?.unregisterCallback(callback)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    remoteTextureView = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            remoteSurfaceTexture = st
                            applyAspectRatioMatrix()
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            applyAspectRatioMatrix()
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            remoteSurfaceTexture = null
                            remoteTextureView = null
                            MyInCallService.getInstance()?.getPrimaryVideoCall()?.setDisplaySurface(null)
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        androidx.compose.material3.Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .size(width = 100.dp, height = 145.dp)
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Color.Black
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                localSurfaceTexture = st
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                localSurfaceTexture = null
                                MyInCallService.getInstance()?.getPrimaryVideoCall()?.setPreviewSurface(null)
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun ConferenceManagerDialog(
    participants: List<CallParticipant>,
    currentTimeMillis: Long,
    snapshotTimeMillis: Long,
    onDisconnectParticipant: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Conference") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(participants) { participant ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(participant.name, fontWeight = FontWeight.Bold)
                            Text(participant.number, style = MaterialTheme.typography.bodySmall)
                            val participantTimerText = participantTimerLabel(participant, currentTimeMillis, snapshotTimeMillis)
                            if (participantTimerText.isNotBlank()) {
                                Text(participantTimerText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        IconButton(
                            onClick = { onDisconnectParticipant(participant.id) },
                            modifier = Modifier.background(Color(0xFFFF3B30), CircleShape).size(36.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Drop", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AudioRouteDialog(
    options: List<AudioRouteOption>,
    currentRoute: Int,
    onSelectRoute: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio Output") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.distinctBy { it.route }.forEach { option ->
                    TextButton(
                        onClick = { onSelectRoute(option.route) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = if (option.route == currentRoute) "${option.label} (Current)" else option.label
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun IncomingCallSwipeControl(
    onAnswer: () -> Unit,
    onReject: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val thumbSize = 72.dp
        val trackHeight = 88.dp
        val horizontalInset = 18.dp
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val insetPx = with(density) { horizontalInset.toPx() }
        val maxDrag = (((constraints.maxWidth.toFloat() - thumbSizePx) / 2f) - insetPx).coerceAtLeast(0f)
        val triggerDistance = maxDrag * 0.72f
        val thumbOffset = remember { Animatable(0f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(44.dp))
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = "Decline",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = "Answer",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(thumbOffset.value.roundToInt(), 0) }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(Color.White)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                thumbOffset.snapTo((thumbOffset.value + delta).coerceIn(-maxDrag, maxDrag))
                            }
                        },
                        onDragStopped = {
                            scope.launch {
                                when {
                                    thumbOffset.value >= triggerDistance -> {
                                        Log.w(CALL_UI_FORENSIC_TAG, "incoming_swipe action=answer")
                                        onAnswer()
                                    }
                                    thumbOffset.value <= -triggerDistance -> {
                                        Log.w(CALL_UI_FORENSIC_TAG, "incoming_swipe action=reject")
                                        onReject()
                                    }
                                    else -> thumbOffset.animateTo(0f)
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Phone,
                    contentDescription = "Swipe incoming action",
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun ModernCallingScreenLayout(
    callState: CallState,
    isEnding: Boolean,
    displayedDurationMs: Long,
    currentTimeMillis: Long,
    isProximityNear: Boolean,
    suppressTouches: Boolean,
    localAnswerRequested: Boolean,
    onLocalAnswerRequestedChange: (Boolean) -> Unit,
    showKeypad: Boolean,
    onShowKeypadChange: (Boolean) -> Unit,
    dtmfInput: String,
    onDtmfInputChange: (String) -> Unit,
    showConferenceManager: Boolean,
    onShowConferenceManagerChange: (Boolean) -> Unit,
    showAudioRouteDialog: Boolean,
    onShowAudioRouteDialogChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAnswerCall: () -> Unit,
    onRejectCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSelectAudioRoute: (Int) -> Unit,
    onAddCall: () -> Unit,
    onKeypadPress: (Char) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isConnectedState = callState.primaryState == "ACTIVE" ||
        callState.primaryState == "HOLDING" ||
        callState.primaryState == "CONFERENCE" ||
        (callState.callStartTime > 0L && !callState.isTerminal)

    // Latch that keeps video UI alive across the DIALING -> ACTIVE modem handshake gap
    var wasVideoCallDuringDialing by remember(callState.primaryCallId) { mutableStateOf(false) }
    var keepVideoAliveOnConnect by remember(callState.primaryCallId) { mutableStateOf(false) }

    LaunchedEffect(callState.isVideoCall, callState.isDialing, callState.isRinging) {
        if (callState.isVideoCall && (callState.isDialing || callState.isRinging)) {
            wasVideoCallDuringDialing = true
            keepVideoAliveOnConnect = true
        }
    }

    LaunchedEffect(isConnectedState, wasVideoCallDuringDialing, callState.isVideoCall) {
        if (isConnectedState && wasVideoCallDuringDialing && !callState.isVideoCall) {
            runCatching {
                MyInCallService.getInstance()?.getPrimaryVideoCall()?.sendSessionModifyRequest(
                    VideoProfile(VideoProfile.STATE_BIDIRECTIONAL)
                )
            }
            kotlinx.coroutines.delay(4000L)
            if (!callState.isVideoCall) {
                keepVideoAliveOnConnect = false
            }
        }
    }

    val effectiveIsVideoCall = callState.isVideoCall || (keepVideoAliveOnConnect && !callState.isTerminal)
    val callState = remember(callState, effectiveIsVideoCall) {
        if (callState.isVideoCall == effectiveIsVideoCall) callState else callState.copy(isVideoCall = effectiveIsVideoCall)
    }

    val durationText = formatElapsedDuration(displayedDurationMs)
    val secondaryDurationText = elapsedDurationFrom(
        snapshotDurationMillis = callState.secondaryTalkDurationMillis,
        timerRunning = callState.secondaryTalkTimerRunning,
        snapshotTimeMillis = callState.stateSnapshotTimeMillis,
        currentTimeMillis = currentTimeMillis
    )
    val contactNameMatchesNumber = areEquivalentPhoneDisplays(callState.contactName, callState.phoneNumber)
    val isUnknown = callState.contactName == "Unknown" || callState.contactName.isEmpty() || contactNameMatchesNumber
    val mainText = when {
        callState.isSpam -> "${callState.contactName} (Spam)"
        callState.phoneNumber == "Conference" -> "Conference Call"
        // Telecom gives an outgoing call the number as typed (no +91) and an incoming one as +91...;
        // format it like Keypad and Recents so both look the same.
        isUnknown && callState.phoneNumber.isNotEmpty() -> formatPhoneNumberForDisplay(callState.phoneNumber)
        else -> callState.contactName
    }
    val baseSupportingText = when {
        callState.isSpam -> "Potential spam"
        !callState.isConference &&
            !isUnknown &&
            callState.phoneNumber.isNotEmpty() &&
            !areEquivalentPhoneDisplays(callState.phoneNumber, mainText) -> formatPhoneNumberForDisplay(callState.phoneNumber)
        callState.isConference -> if (callState.totalTopLevelCalls > 1) "${callState.totalTopLevelCalls} linked calls" else "Conference call"
        callState.isHolding -> "Call is currently on hold"
        callState.isRinging && !localAnswerRequested -> "Swipe below to answer"
        isConnectedState -> "Call in progress"
        isEnding || callState.isTerminal -> "Wrapping up call"
        else -> "Connecting"
    }
    val supportingText = appendSimLabel(baseSupportingText, callState.simLabel)
    val statusChipText = when {
        callState.isVideoCall && displayedDurationMs > 0L -> "Video Call • $durationText"
        ((isConnectedState && !isEnding && !callState.isTerminal) || isEnding || callState.isTerminal) && displayedDurationMs > 0L -> durationText
        callState.isHolding -> "On hold"
        callState.isRinging && !localAnswerRequested && !callState.isTerminal -> if (callState.isVideoCall) "Incoming Video Call" else "Incoming"
        !callState.isTerminal && (callState.isDialing || (callState.isRinging && localAnswerRequested)) -> if (callState.isVideoCall) "Video Calling" else "Dialling"
        isEnding || callState.isTerminal -> "Ending"
        else -> if (callState.isVideoCall) "Video Calling" else "Calling"
    }
    val hasBluetoothRoute = supportsAudioRoute(
        callState.supportedAudioRouteMask,
        android.telecom.CallAudioState.ROUTE_BLUETOOTH
    )
    val hasWiredHeadsetRoute = supportsAudioRoute(
        callState.supportedAudioRouteMask,
        android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
    )
    val bluetoothRouteLabel = callState.bluetoothDeviceName?.trim().orEmpty().ifBlank { "Bluetooth" }
    val audioRouteOptions = mutableListOf<AudioRouteOption>().apply {
        if (hasBluetoothRoute) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_BLUETOOTH, bluetoothRouteLabel))
        }
        if (supportsAudioRoute(callState.supportedAudioRouteMask, android.telecom.CallAudioState.ROUTE_SPEAKER)) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_SPEAKER, "Speaker"))
        }
        if (hasWiredHeadsetRoute) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_WIRED_HEADSET, "Headset"))
        } else if (supportsAudioRoute(callState.supportedAudioRouteMask, android.telecom.CallAudioState.ROUTE_EARPIECE)) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_EARPIECE, "Earpiece"))
        }
    }
    val audioButtonIcon = when (callState.currentAudioRoute) {
        android.telecom.CallAudioState.ROUTE_BLUETOOTH -> Icons.Outlined.Bluetooth
        android.telecom.CallAudioState.ROUTE_SPEAKER -> Icons.Outlined.VolumeUp
        else -> Icons.Outlined.VolumeDown
    }
    val totalPeople = callState.conferenceParticipants.size + callState.unmergedCallCount
    val canMerge = callState.canMergeCalls
    val canShowAudioRouteDialog = hasBluetoothRoute && audioRouteOptions.size > 1

    val primaryPanelAction = when {
        callState.isConference -> CallActionTileSpec(
            icon = Icons.Outlined.CallSplit,
            label = "Split",
            onClick = { MyInCallService.getInstance()?.splitConference() }
        )
        canMerge -> CallActionTileSpec(
            icon = Icons.Outlined.CallMerge,
            label = "Merge",
            onClick = { MyInCallService.getInstance()?.mergeCalls() },
            enabled = !callState.isDialing
        )
        totalPeople < 5 -> CallActionTileSpec(
            icon = Icons.Outlined.Add,
            label = "Add call",
            onClick = onAddCall,
            enabled = !callState.isDialing
        )
        else -> CallActionTileSpec(
            icon = Icons.Outlined.Add,
            label = "Add call",
            onClick = onAddCall,
            enabled = false
        )
    }
    val secondaryTopAction = if (callState.isConference) {
        CallActionTileSpec(
            icon = Icons.Outlined.Groups,
            label = "Manage",
            onClick = { onShowConferenceManagerChange(true) }
        )
    } else {
        CallActionTileSpec(
            icon = if (callState.isHolding) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
            label = if (callState.isHolding) "Resume" else "Hold",
            onClick = {
                if (callState.isHolding) {
                    MyInCallService.getInstance()?.unholdCall()
                } else {
                    MyInCallService.getInstance()?.holdCall()
                }
            },
            active = callState.isHolding,
            enabled = !callState.isDialing
        )
    }
    val speakerAction = CallActionTileSpec(
        icon = if (callState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_BLUETOOTH) {
            Icons.Outlined.Bluetooth
        } else {
            Icons.Outlined.VolumeUp
        },
        label = if (callState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_BLUETOOTH) {
            bluetoothRouteLabel
        } else {
            "Speaker"
        },
        onClick = {
            if (canShowAudioRouteDialog) {
                onShowAudioRouteDialogChange(true)
            } else {
                onToggleSpeaker()
            }
        },
        active = callState.isSpeakerOn ||
            callState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER ||
            callState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_BLUETOOTH ||
            callState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_WIRED_HEADSET,
        enabled = canShowAudioRouteDialog ||
            supportsAudioRoute(callState.supportedAudioRouteMask, android.telecom.CallAudioState.ROUTE_SPEAKER)
    )
    val muteAction = CallActionTileSpec(
        icon = if (callState.isMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
        label = if (callState.isMuted) "Unmute" else "Mute",
        onClick = onToggleMute,
        active = callState.isMuted,
        accentColor = if (callState.isMuted) Color(0xFF89C7FF) else null
    )
    val keypadAction = CallActionTileSpec(
        icon = Icons.Outlined.Apps,
        label = if (showKeypad) "Hide pad" else "Keypad",
        onClick = { onShowKeypadChange(!showKeypad) },
        active = showKeypad
    )
    val isPrimaryNumberVisible =
        !callState.isConference &&
            !isUnknown &&
            callState.phoneNumber.isNotEmpty() &&
            !areEquivalentPhoneDisplays(callState.phoneNumber, mainText)
    val supportingTextAlpha = when {
        callState.isSpam -> 0.82f
        isPrimaryNumberVisible -> 0.86f
        else -> 0.74f
    }
    val supportingTextWeight = if (isPrimaryNumberVisible) FontWeight.SemiBold else FontWeight.Medium

    var isVideoControlsCollapsed by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                enabled = callState.isVideoCall,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                isVideoControlsCollapsed = !isVideoControlsCollapsed
            }
    ) {
        val isCompactCallSurface = maxWidth < 390.dp || maxHeight < 820.dp
        val heroFontSize = resolveCallHeroFontSize(
            text = mainText,
            isCompactCallSurface = isCompactCallSurface,
            showKeypad = showKeypad,
            maxWidthDp = maxWidth.value,
            maxHeightDp = maxHeight.value
        )
        val supportingFontSize = resolveCallSupportingFontSize(
            isCompactCallSurface = isCompactCallSurface,
            showKeypad = showKeypad,
            maxWidthDp = maxWidth.value,
            maxHeightDp = maxHeight.value
        )
        val outerHorizontalPadding = if (isCompactCallSurface) 18.dp else 20.dp
        val outerVerticalPadding = if (isCompactCallSurface) 14.dp else 16.dp
        val sectionSpacing = if (isCompactCallSurface) 12.dp else 14.dp
        val identityTopPadding = if (callState.totalTopLevelCalls > 1) 8.dp else 12.dp
        val bottomCardHorizontalPadding = if (isCompactCallSurface) 16.dp else 18.dp
        val bottomCardVerticalPadding = if (showKeypad) 14.dp else 16.dp

        LaunchedEffect(
            callState.primaryCallId,
            callState.primaryState,
            showKeypad,
            isCompactCallSurface,
            heroFontSize,
            supportingFontSize
        ) {
            Log.w(
                CALL_UI_FORENSIC_TAG,
                "call_ui_title_scale source=ModernCallingScreenLayout state=${callState.primaryState} compact=$isCompactCallSurface showKeypad=$showKeypad widthDp=${maxWidth.value} heightDp=${maxHeight.value} heroSp=${heroFontSize.value} supportingSp=${supportingFontSize.value}"
            )
        }

        val palette = UnifiedUiSystem.rememberPalette()

        LaunchedEffect(
            callState.isInCall,
            callState.isRinging,
            callState.isTerminal,
            isEnding,
            localAnswerRequested,
            callState.phoneNumber,
            callState.contactName
        ) {
            if (callState.isInCall && callState.isRinging && !callState.isTerminal && !isEnding && !localAnswerRequested) {
                CallerAnnouncementManager.announceIncomingCaller(
                    context = context,
                    phoneNumber = callState.phoneNumber,
                    contactName = callState.contactName
                )
            } else {
                CallerAnnouncementManager.stop()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                if (!callState.isRinging) {
                    CallerAnnouncementManager.stop()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.screenBackgroundBrush)
                .then(with(UnifiedUiSystem) { Modifier.safeNotificationShadePadding() })
        ) {
            if (callState.isVideoCall) {
                TelecomVideoCallView(modifier = Modifier.fillMaxSize())
            }

            if (!callState.isVideoCall) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    palette.radialGlowColor,
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(30.dp))
                        .background(palette.tileIdleBg)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CallStatusChip(text = statusChipText, isWifiCall = callState.isWifiCall)
                    }
                }

                if (callState.totalTopLevelCalls > 1) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = sectionSpacing),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = palette.cardSurface)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = secondaryCallBannerTitle(callState),
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (callState.totalTopLevelCalls > 2) {
                                        "Multiple participants"
                                    } else {
                                        callState.secondaryContactName ?: callState.secondaryPhoneNumber ?: ""
                                    },
                                    color = palette.textSecondary,
                                    fontSize = 13.sp
                                )
                                if (secondaryDurationText.isNotBlank() && callState.totalTopLevelCalls <= 2) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Talk time: $secondaryDurationText",
                                        color = palette.textSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    if (callState.secondaryState == "RINGING") {
                                        if (!isProximityNear) {
                                            onAnswerCall()
                                        } else {
                                            Toast.makeText(context, "Blocked: Phone at ear", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        MyInCallService.getInstance()?.swapCalls()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (callState.secondaryState == "RINGING") Color(0xFF47B96C) else palette.tileIdleBg,
                                    contentColor = palette.textPrimary
                                )
                            ) {
                                Text(secondaryCallActionText(callState.secondaryState))
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = identityTopPadding, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (callState.isVideoCall) Arrangement.Top else Arrangement.Center
                ) {
                    Text(
                        text = mainText,
                        color = if (callState.isSpam) Color(0xFFFF897D) else palette.textPrimary,
                        fontSize = heroFontSize,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        lineHeight = (heroFontSize.value + 4).sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (supportingText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = supportingText,
                            color = palette.textSecondary,
                            fontSize = supportingFontSize,
                            fontWeight = supportingTextWeight,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (callState.isSpam) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "SPAM RISK",
                            color = Color(0xFFFF897D),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (callState.isVideoCall) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        IconButton(
                            onClick = { isVideoControlsCollapsed = !isVideoControlsCollapsed },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp)
                                .size(44.dp)
                        ) {
                            Icon(
                                imageVector = if (isVideoControlsCollapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isVideoControlsCollapsed) "Expand controls" else "Collapse controls",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

            AnimatedVisibility(
                visible = !callState.isVideoCall || !isVideoControlsCollapsed,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (callState.isRinging && !localAnswerRequested) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IncomingCallSwipeControl(
                                onAnswer = {
                                    if (!isProximityNear) {
                                        onLocalAnswerRequestedChange(true)
                                        onAnswerCall()
                                    } else {
                                        Toast.makeText(context, "Move phone from ear to answer", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onReject = onRejectCall
                            )
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(32.dp),
                            colors = CardDefaults.cardColors(containerColor = palette.cardSurface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = bottomCardHorizontalPadding, vertical = bottomCardVerticalPadding),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (showKeypad) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (dtmfInput.isBlank()) "Keypad" else dtmfInput,
                                            color = palette.textPrimary,
                                            style = MaterialTheme.typography.headlineSmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { if (dtmfInput.isNotEmpty()) onDtmfInputChange(dtmfInput.dropLast(1)) },
                                            enabled = dtmfInput.isNotEmpty()
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Backspace,
                                                contentDescription = "Delete digit",
                                                tint = if (dtmfInput.isNotEmpty()) palette.textPrimary else palette.textPrimary.copy(alpha = 0.28f)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    val keys = listOf(
                                        listOf('1', '2', '3'),
                                        listOf('4', '5', '6'),
                                        listOf('7', '8', '9'),
                                        listOf('*', '0', '#')
                                    )
                                    keys.forEachIndexed { rowIndex, row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            row.forEach { key ->
                                                Button(
                                                    onClick = {
                                                        onDtmfInputChange(dtmfInput + key)
                                                        onKeypadPress(key)
                                                    },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(60.dp),
                                                    shape = RoundedCornerShape(20.dp),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = palette.tileIdleBg,
                                                        contentColor = palette.textPrimary
                                                    )
                                                ) {
                                                    Text(
                                                        text = key.toString(),
                                                        fontSize = 26.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                        if (rowIndex != keys.lastIndex) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CallActionTile(spec = speakerAction, modifier = Modifier.weight(1f))
                                        CallActionTile(spec = muteAction, modifier = Modifier.weight(1f))
                                        CallActionTile(spec = keypadAction, modifier = Modifier.weight(1f))
                                    }
                                } else {
                                    val actionGridItems = listOf(
                                        primaryPanelAction,
                                        secondaryTopAction,
                                        speakerAction,
                                        muteAction,
                                        keypadAction
                                    )
                                    CallActionGrid(
                                        actions = actionGridItems,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))
                                FloatingActionButton(
                                    onClick = onEndCall,
                                    containerColor = Color(0xFFE4453B),
                                    contentColor = Color.White,
                                    shape = CircleShape,
                                    modifier = Modifier.size(74.dp),
                                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CallEnd,
                                        contentDescription = "End call",
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (suppressTouches) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
    }
}
}

private fun UnifiedCallState.toCallUiState(): CallState {
    val normalizedPrimaryState = normalizeCallUiPrimaryState(primaryState, connectTimeMillis, isTerminal)
    return CallState(
        isInCall = isInCall,
        showUI = true,
        primaryState = normalizedPrimaryState,
        isRinging = normalizedPrimaryState == "RINGING" || normalizedPrimaryState == "NEW",
        isDialing = normalizedPrimaryState == "DIALING" || normalizedPrimaryState == "CONNECTING",
        isHolding = isHolding,
        phoneNumber = primaryNumber,
        contactName = primaryName,
        contactPhotoUri = primaryPhotoUri,
        primaryCallId = primaryCallId,
        secondaryPhoneNumber = secondaryNumber,
        secondaryContactName = secondaryName,
        secondaryCallId = secondaryCallId,
        secondaryState = secondaryState,
        secondaryCallStartTime = secondaryConnectTimeMillis,
        isConference = isConference,
        callStartTime = connectTimeMillis,
        isTerminal = isTerminal,
        disconnectTimeMillis = disconnectTimeMillis,
        finalDurationMillis = finalDurationMillis,
        disconnectCauseCode = disconnectCauseCode,
        isMuted = isMuted,
        isSpeakerOn = isSpeakerOn,
        isSpam = isSpam,
        conferenceParticipants = participants,
        unmergedCallCount = unmergedCallCount,
        totalTopLevelCalls = totalTopLevelCalls,
        canMergeCalls = canMergeCalls,
        currentAudioRoute = currentAudioRoute,
        supportedAudioRouteMask = supportedAudioRouteMask,
        bluetoothDeviceName = bluetoothDeviceName,
        simLabel = simLabel,
        isVideoCall = isVideoCall,
        isWifiCall = isWifiCall,
        stateSnapshotTimeMillis = stateSnapshotTimeMillis,
        primaryTalkDurationMillis = primaryTalkDurationMillis,
        primaryTalkTimerRunning = primaryTalkTimerRunning,
        secondaryTalkDurationMillis = secondaryTalkDurationMillis,
        secondaryTalkTimerRunning = secondaryTalkTimerRunning
    )
}

private fun Intent?.toSeedCallUiState(): CallState {
    val stateHint = this?.getStringExtra(CALL_UI_EXTRA_STATE_HINT).orEmpty()
    val numberHint = this?.getStringExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT).orEmpty()
    val contactNameHint = this?.getStringExtra(CALL_UI_EXTRA_CONTACT_NAME_HINT).orEmpty()
    val isConferenceHint = this?.getBooleanExtra(CALL_UI_EXTRA_IS_CONFERENCE_HINT, false) == true
    val simLabelHint = this?.getStringExtra(CALL_UI_EXTRA_SIM_LABEL).orEmpty()
    val isVideoCallHint = this?.getBooleanExtra("EXTRA_IS_VIDEO_CALL", false) == true
    val hasSeed = isConferenceHint || numberHint.isNotBlank() || stateHint.isNotBlank()
        Log.w(
            CALL_UI_FORENSIC_TAG,
            "call_ui_seed stateHint=$stateHint rawNumber=${maskP2PNumberForLog(numberHint)} contactNameHint=${maskP2PDisplayValueForLog(contactNameHint)} isConference=$isConferenceHint isVideoCall=$isVideoCallHint hasSeed=$hasSeed"
        )
    if (!hasSeed) {
        return CallState()
    }

    val resolvedNumber = if (isConferenceHint) "Conference" else numberHint
    val resolvedName = when {
        isConferenceHint -> "Conference Call"
        contactNameHint.isNotBlank() -> contactNameHint
        numberHint.isNotBlank() -> numberHint
        else -> "Unknown"
    }

    return CallState(
        isInCall = true,
        showUI = true,
        primaryState = stateHint.ifBlank { "IDLE" },
        isRinging = stateHint == "RINGING" || stateHint == "NEW",
        isDialing = stateHint == "DIALING" || stateHint == "CONNECTING",
        isHolding = stateHint == "HOLDING",
        phoneNumber = resolvedNumber,
        contactName = resolvedName,
        isConference = isConferenceHint,
        simLabel = simLabelHint,
        isVideoCall = isVideoCallHint
    )
}

private fun CallState.toTerminalUiState(
    disconnectAtMillis: Long = System.currentTimeMillis()
): CallState {
    return copy(
        isInCall = false,
        primaryState = "DISCONNECTED",
        isRinging = false,
        isDialing = false,
        isHolding = false,
        secondaryPhoneNumber = null,
        secondaryContactName = null,
        secondaryCallId = null,
        secondaryState = null,
        secondaryCallStartTime = 0L,
        isConference = false,
        isTerminal = true,
        disconnectTimeMillis = disconnectAtMillis,
        conferenceParticipants = emptyList(),
        unmergedCallCount = 0,
        totalTopLevelCalls = 0
    )
}

@Composable
private fun CallLaunchPlaceholder(seedState: CallState) {
    val displayName = when {
        seedState.isConference -> "Conference Call"
        seedState.contactName.isNotBlank() && seedState.contactName != "Unknown" -> seedState.contactName
        seedState.phoneNumber.isNotBlank() -> formatPhoneNumberForDisplay(seedState.phoneNumber)
        else -> "Call in progress"
    }
    val statusText = appendSimLabel(
        when {
            seedState.isRinging -> "Incoming Call..."
            seedState.isDialing -> "Dialling..."
            seedState.isInCall -> "Opening call screen..."
            else -> "Opening call screen..."
        },
        seedState.simLabel
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF080B11), Color(0xFF10141E), Color(0xFF0A0D14)))),
        contentAlignment = Alignment.Center
    ) {
        val isCompactCallSurface = maxWidth < 390.dp || maxHeight < 820.dp
        val heroFontSize = resolveCallHeroFontSize(
            text = displayName,
            isCompactCallSurface = isCompactCallSurface,
            showKeypad = false,
            maxWidthDp = maxWidth.value,
            maxHeightDp = maxHeight.value
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = displayName,
                fontSize = heroFontSize,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            if (statusText.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF4CAF50),
                textAlign = TextAlign.Center
            )
        }
    }
}

// --- CALL ACTIVITY FOR GOOGLE PHONE BEHAVIOR ---
class CallActivity : ComponentActivity() {
    private val seededCallState = mutableStateOf(CallState())
    private var finishReceiver: BroadcastReceiver? = null
    private var isFinishingCallUi = false
    @Volatile private var isEndCallRequested = false

    private fun logCall(msg: String) {
        Log.e(
            TAG_CALL_FORENSIC,
            "[t=${android.os.SystemClock.elapsedRealtime()}][thread=${Thread.currentThread().name}] $msg"
        )
    }

    private fun hasLiveCall(): Boolean {
        if (MyInCallService.getInstance()?.hasActiveCall() == true) {
            return true
        }

        val telecomInCall = try {
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).isInCall
        } catch (_: Exception) {
            false
        }
        val telephonyState = try {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState
        } catch (_: Exception) {
            TelephonyManager.CALL_STATE_IDLE
        }

        return telecomInCall ||
            telephonyState == TelephonyManager.CALL_STATE_RINGING ||
            telephonyState == TelephonyManager.CALL_STATE_OFFHOOK
    }

    private fun logCallActivityLifecycle(event: String, extra: String = "") {
        val sticky = MyInCallService.peekLastCallUiSnapshot()
        Log.w(
            CALL_UI_FORENSIC_TAG,
            "call_activity_lifecycle event=$event thread=${Thread.currentThread().name} isChangingConfigurations=$isChangingConfigurations stickyInCall=${sticky?.isInCall == true} stickyCallId=${sticky?.primaryCallId.orEmpty()} stickyState=${sticky?.primaryState.orEmpty()} extra=$extra"
        )
    }

    /**
     * Call is over: close the call screen's own task. That task sits on top of whatever was on screen
     * before the call, so on its own the phone falls back to the home screen if the app wasn't directly
     * under it. If the app was on screen when the call started, reopen it (Recents) instead; otherwise
     * go back to wherever the user was. Never opens the app over the lock screen.
     */
    private fun finishCallUiAfterCall(reason: String) {
        if (isFinishingCallUi || isFinishing || isDestroyed) {
            Log.w(CALL_UI_FORENSIC_TAG, "call_ui_finish skipped reason=$reason (already finishing)")
            return
        }
        isFinishingCallUi = true
        MyInCallService.appWasOnScreenAtCallStart = false
        CallBubbleOverlay.isCallActivityVisible = false
        CallBubbleOverlay.hide(this)
        runCatching { AccountDeviceHistoryManager.syncAccountNow(this, showToast = false) }
        Log.w(CALL_UI_FORENSIC_TAG, "call_ui_finish reason=$reason finishAndRemoveTask")
        if (!isFinishing && !isDestroyed) {
            finishAndRemoveTask()
        }
    }

    private fun openInAppMainUi(reason: String) {
        // Only open MainActivity when the user explicitly taps "Add call" during an active call
        if (reason != "add_call") {
            finishCallUiAfterCall("blocked_open_main_ui:$reason")
            return
        }
        Log.w(CALL_UI_FORENSIC_TAG, "call_ui_navigation action=open_main_ui reason=$reason")
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "ADD_CALL_FROM_INCALL"
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun updateSeededCallState(intent: Intent?) {
        val intentSeed = intent.toSeedCallUiState()
        val stickySeed = MyInCallService.peekLastCallUiSnapshot()?.toCallUiState() ?: CallState()
        val liveCallSeed = when {
            intentSeed.isInCall || stickySeed.isInCall -> CallState()
            hasLiveCall() -> {
                val stateHint = intent?.getStringExtra(CALL_UI_EXTRA_STATE_HINT).orEmpty().ifBlank { "CONNECTING" }
                val numberHint = intent?.getStringExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT).orEmpty()
                val contactNameHint = intent?.getStringExtra(CALL_UI_EXTRA_CONTACT_NAME_HINT).orEmpty()
                val isConferenceHint = intent?.getBooleanExtra(CALL_UI_EXTRA_IS_CONFERENCE_HINT, false) == true
                val simLabelHint = intent?.getStringExtra(CALL_UI_EXTRA_SIM_LABEL).orEmpty()
                val resolvedNumber = if (isConferenceHint) "Conference" else numberHint
                val resolvedName = when {
                    isConferenceHint -> "Conference Call"
                    contactNameHint.isNotBlank() && contactNameHint != "Unknown" -> contactNameHint
                    numberHint.isNotBlank() -> numberHint
                    else -> "Call in progress"
                }
                CallState(
                    isInCall = true,
                    showUI = true,
                    primaryState = stateHint,
                    isRinging = stateHint == "RINGING" || stateHint == "NEW",
                    isDialing = stateHint == "DIALING" || stateHint == "CONNECTING" || stateHint == "TRANSITIONING",
                    isHolding = stateHint == "HOLDING",
                    phoneNumber = resolvedNumber,
                    contactName = resolvedName,
                    isConference = isConferenceHint,
                    simLabel = simLabelHint
                )
            }
            else -> CallState()
        }
        val resolvedSeed = when {
            stickySeed.isInCall -> stickySeed
            intentSeed.isInCall -> intentSeed
            liveCallSeed.isInCall -> liveCallSeed
            else -> CallState()
        }
        val resolvedStateLabel = when {
            resolvedSeed.isRinging -> "RINGING"
            resolvedSeed.isDialing -> "DIALING"
            resolvedSeed.isHolding -> "HOLDING"
            resolvedSeed.isConference -> "CONFERENCE"
            resolvedSeed.isInCall -> "ACTIVE"
            else -> "IDLE"
        }
        val source = when {
            stickySeed.isInCall -> "sticky"
            intentSeed.isInCall -> "intent"
            liveCallSeed.isInCall -> "live_call"
            else -> "empty"
        }
        Log.w(
            CALL_UI_FORENSIC_TAG,
            "call_ui_seed_resolved source=$source state=$resolvedStateLabel number=${maskP2PNumberForLog(resolvedSeed.phoneNumber)} name=${maskP2PDisplayValueForLog(resolvedSeed.contactName)}"
        )
        seededCallState.value = resolvedSeed
    }
    
    private fun isTerminalHint(intent: Intent?): Boolean {
        val hint = intent?.getStringExtra(CALL_UI_EXTRA_STATE_HINT).orEmpty()
        return hint.equals("DISCONNECTED", ignoreCase = true) ||
            hint.equals("DISCONNECTING", ignoreCase = true) ||
            hint.equals("IDLE", ignoreCase = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        logCallActivityLifecycle("onNewIntent", "action=${intent?.action.orEmpty()}")
        if (isTerminalHint(intent) || !hasLiveCall()) {
            Log.w(CALL_UI_FORENSIC_TAG, "onNewIntent blocked: terminal hint or no live call")
            finishCallUiAfterCall("new_intent_terminal_or_no_live_call")
            return
        }
        isFinishingCallUi = false
        // Update activity's intent so action checks in CallingScreen work
        setIntent(intent)
        updateSeededCallState(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        // Keep the UI state fresh, but do not forcibly steal foreground focus on
        // every wake intent. That lets users switch to other apps during dialing.
        if (intent?.action == CALL_UI_ACTION_WAKE || intent?.action == Intent.ACTION_MAIN) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            MyInCallService.getInstance()?.forceUpdateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. CRITICAL: Must be called BEFORE super.onCreate
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        super.onCreate(savedInstanceState)
        logCall("InCallActivity.onCreate hasActiveCall=${hasLiveCall()}")
        logCallActivityLifecycle("onCreate", "action=${intent?.action.orEmpty()}")
        if (isTerminalHint(intent) && !hasLiveCall()) {
            Log.w(CALL_UI_FORENSIC_TAG, "onCreate blocked: terminal hint and no live call")
            finishCallUiAfterCall("onCreate_terminal_no_live_call")
            return
        }
        isFinishingCallUi = false
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val isUserUnlocked = try {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } catch (_: Exception) {
            true
        }
        Log.w(
            CALL_UI_FORENSIC_TAG,
            "call_activity_onCreate directBootPhase=${!isUserUnlocked} action=${intent?.action} seedNumber=${maskP2PNumberForLog(intent?.getStringExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT).orEmpty())}"
        )
        if (isTerminalHint(intent) && !hasLiveCall()) {
            finishCallUiAfterCall("create_terminal_intent")
            return
        }
        updateSeededCallState(intent)

        try {
            val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext() ?: this
            } else {
                this
            }
            UltraForensicLogger.init(safeContext)
        } catch (e: Exception) {
            Log.e(CALL_UI_FORENSIC_TAG, "call_activity_logger_init_failed error=${e.message}", e)
        }
        
        // 2. FORCED SYSTEM BAR REMOVAL
        actionBar?.hide()
        enableEdgeToEdge()
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // CRITICAL FIX: Allow call screen on lock screen properly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // WE COMPLETELY REMOVED `requestDismissKeyguard` HERE. 
            // It was forcing the user to enter a PIN/Password while the phone was ringing!
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (hasLiveCall() && !isFinishingCallUi && !isEndCallRequested) {
                        CallBubbleOverlay.showFromCallActivityExit(this@CallActivity)
                        MyInCallService.getInstance()?.showCallBubbleIfNeeded()
                        if (!isFinishing && !isDestroyed) {
                            finish()
                        }
                    } else {
                        finishCallUiAfterCall("dispatcher_back")
                    }
                }
            }
        )

        finishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "FINISH_CALL_ACTIVITY") {
                    val hasLiveCall = hasLiveCall()
                    logCall("call_ui_finish_signal action=received hasLiveCall=$hasLiveCall")
                    Log.w(CALL_UI_FORENSIC_TAG, "call_ui_finish_signal action=received hasLiveCall=$hasLiveCall")
                    if (hasLiveCall) {
                        logCall("cleanup blocked because live call still exists in InCallService")
                        return
                    }
                    finishCallUiAfterCall("finish_signal")
                }
            }
        }
        val finishFilter = IntentFilter("FINISH_CALL_ACTIVITY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, finishFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(finishReceiver, finishFilter)
        }
        
        // Set up call UI
        setContent {
            CallerTheme {
                val seededState = seededCallState.value
                var callState by remember { mutableStateOf(seededState) }
                var stableInCallState by remember {
                    mutableStateOf(
                        if (seededState.isInCall) seededState else CallState()
                    )
                }
                var terminalState by remember {
                    mutableStateOf(
                        if (seededState.isTerminal) seededState else CallState()
                    )
                }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var endCallJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                var localEndRequested by remember { mutableStateOf(false) }
                val scheduleFinish: (String, Long) -> Unit = { reason, delayMs ->
                    if (endCallJob == null) {
                        logCall("call_ui_finish_debounce action=schedule delayMs=$delayMs reason=$reason localEndRequested=$localEndRequested")
                        Log.w(
                            CALL_UI_FORENSIC_TAG,
                            "call_ui_finish_debounce action=schedule delayMs=$delayMs reason=$reason localEndRequested=$localEndRequested"
                        )
                        endCallJob = scope.launch {
                            kotlinx.coroutines.delay(delayMs)
                            val hasLiveCall = hasLiveCall()
                            if (hasLiveCall) {
                                logCall("cleanup blocked inside Compose debounce because live call still exists")
                                endCallJob = null
                            } else {
                                finishCallUiAfterCall("call_ended")
                            }
                        }
                    }
                }
                val renderState = when {
                    callState.isInCall -> callState
                    callState.isTerminal -> callState
                    terminalState.isTerminal -> terminalState
                    localEndRequested -> {
                        val baseState = when {
                            stableInCallState.isInCall -> stableInCallState
                            seededState.isInCall -> seededState
                            else -> callState
                        }
                        baseState.toTerminalUiState()
                    }
                    seededState.isInCall -> seededState
                    else -> callState
                }

                LaunchedEffect(seededState) {
                    if (!callState.isInCall && !callState.isTerminal && (seededState.isInCall || seededState.isTerminal)) {
                        Log.w(
                            CALL_UI_FORENSIC_TAG,
                            "call_ui_seed_promote state=${if (seededState.isDialing) "DIALING" else if (seededState.isRinging) "RINGING" else if (seededState.isTerminal) "TERMINAL" else "SEEDED"} target=${maskP2PDisplayValueForLog(seededState.contactName.ifBlank { seededState.phoneNumber })}"
                        )
                        callState = seededState
                        if (seededState.isInCall) {
                            stableInCallState = seededState
                        }
                        if (seededState.isTerminal) {
                            terminalState = seededState
                        }
                    }
                }

                LaunchedEffect(seededState, callState.isInCall, terminalState.isTerminal) {
                    if (!seededState.isInCall || callState.isInCall || terminalState.isTerminal) {
                        return@LaunchedEffect
                    }

                    delay(if (seededState.isDialing) 2500L else 1200L)

                    val stickyLiveState = MyInCallService.peekLastCallUiSnapshot()?.toCallUiState()
                    val stillStale =
                        !callState.isInCall &&
                            !terminalState.isTerminal &&
                            stickyLiveState?.isInCall != true &&
                            !hasLiveCall()

                    if (stillStale) {
                        Log.w(
                            CALL_UI_FORENSIC_TAG,
                            "call_ui_seed_drop reason=stale_after_restart state=${seededState.primaryState} number=${maskP2PNumberForLog(seededState.phoneNumber)}"
                        )
                        seededCallState.value = CallState()
                        callState = CallState()
                        stableInCallState = CallState()
                        terminalState = CallState()
                        if (!isFinishing && !isDestroyed) {
                            finishAndRemoveTask()
                        }
                    }
                }

                DisposableEffect(Unit) {
                    MyInCallService.setCallUIListener { stateObj ->
                        runOnUiThread {
                            val newState = stateObj.toCallUiState()
                            val stickyLiveState = MyInCallService.peekLastCallUiSnapshot()?.toCallUiState()
                            val shouldHoldForRecovery =
                                !newState.isInCall &&
                                    !newState.isTerminal &&
                                    !localEndRequested &&
                                    stableInCallState.isInCall &&
                                    stickyLiveState?.isInCall == true
                            if (shouldHoldForRecovery) {
                                Log.w(
                                    CALL_UI_FORENSIC_TAG,
                                    "call_ui_recovery_hold ts=${System.currentTimeMillis()} thread=${Thread.currentThread().name} stickyPrimaryId=${stickyLiveState?.primaryCallId.orEmpty()} stickyState=${stickyLiveState?.primaryState} currentState=${stateObj.primaryState} oem_fallback_path_reached=no"
                                )
                                callState = stickyLiveState ?: newState
                                stickyLiveState?.let { stableInCallState = it }
                                if (endCallJob != null) {
                                    Log.w(CALL_UI_FORENSIC_TAG, "call_ui_finish_request action=cancel reason=recovery_hold currentState=${stateObj.primaryState}")
                                }
                                endCallJob?.cancel()
                                endCallJob = null
                                return@runOnUiThread
                            }
                            when {
                                newState.isInCall -> {
                                    callState = newState
                                    stableInCallState = newState
                                    terminalState = CallState()
                                    if (localEndRequested) {
                                        Log.w(CALL_UI_FORENSIC_TAG, "call_ui_local_end action=clear reason=live_state_restored")
                                    }
                                    localEndRequested = false
                                    if (endCallJob != null) {
                                        Log.w(
                                            CALL_UI_FORENSIC_TAG,
                                            "call_ui_finish_debounce action=cancel state=${stateObj.primaryState}"
                                        )
                                    }
                                    endCallJob?.cancel()
                                    endCallJob = null
                                }
                                newState.isTerminal -> {
                                    callState = newState
                                    terminalState = newState
                                    scheduleFinish(
                                        "terminal_snapshot:${stateObj.primaryState}",
                                        if (localEndRequested) 180L else 450L
                                    )
                                }
                                stableInCallState.isInCall || terminalState.isTerminal -> {
                                    callState = newState
                                    terminalState = if (terminalState.isTerminal) {
                                        terminalState
                                    } else {
                                        stableInCallState.toTerminalUiState()
                                    }
                                    scheduleFinish(
                                        "empty_after_valid_state:${stateObj.primaryState}",
                                        if (localEndRequested) 180L else 450L
                                    )
                                }
                                else -> {
                                    callState = newState
                                    scheduleFinish(
                                        "empty_without_snapshot:${stateObj.primaryState}",
                                        if (localEndRequested) 180L else 450L
                                    )
                                }
                            }
                        }
                    }
                    MyInCallService.getInstance()?.forceUpdateUI()
                    onDispose {
                        endCallJob?.cancel()
                        endCallJob = null
                        MyInCallService.setCallUIListener(null)
                    }
                }

                if (renderState.isInCall || renderState.isTerminal) {
                    CallingScreen(
                        callState = renderState,
                        isEnding = localEndRequested || renderState.isTerminal,
                        onAnswerCall = { answerCall(this) },
                        onRejectCall = {
                            localEndRequested = true
                            isEndCallRequested = true
                            CallBubbleOverlay.suppressForTermination(this)
                            Log.w(CALL_UI_FORENSIC_TAG, "call_ui_local_end action=reject")
                            endCall(this)
                        },
                        onEndCall = {
                            localEndRequested = true
                            isEndCallRequested = true
                            CallBubbleOverlay.suppressForTermination(this)
                            Log.w(CALL_UI_FORENSIC_TAG, "call_ui_local_end action=end")
                            endCall(this)
                        },
                        onToggleMute = { toggleMute(this, renderState.isMuted) { newMuteState ->
                            MyInCallService.getInstance()?.setMuted(newMuteState)
                        }},
                        onToggleSpeaker = { toggleSpeaker(this, renderState.isSpeakerOn) { newSpeakerState ->
                            val route = if (newSpeakerState) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
                            MyInCallService.getInstance()?.setCallAudioRoute(route)
                        }},
                        onSelectAudioRoute = { route ->
                            selectAudioRoute(route)
                        },
                        onAddCall = {
                            openInAppMainUi("add_call")
                        },
                        onKeypadPress = { digit ->
                            MyInCallService.getInstance()?.playDtmfTone(digit)
                        }
                    )
                } else if (seededState.isInCall && hasLiveCall()) {
                    Log.w(
                        CALL_UI_FORENSIC_TAG,
                        "call_ui_placeholder activeSeed=${maskP2PDisplayValueForLog(seededState.phoneNumber.ifBlank { seededState.contactName })} activeStateInCall=${callState.isInCall}"
                    )
                    CallLaunchPlaceholder(seededState)
                } else {
                    LaunchedEffect(Unit) {
                        Log.w(CALL_UI_FORENSIC_TAG, "call_ui_no_active_call_close reason=idle_render_state")
                        finishCallUiAfterCall("no_active_call")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        logCall("InCallActivity.onDestroy isFinishing=$isFinishing hasActiveCall=${hasLiveCall()}")
        logCallActivityLifecycle("onDestroy")
        try {
            finishReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        } finally {
            finishReceiver = null
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        logCall("InCallActivity.onStart hasActiveCall=${hasLiveCall()}")
        logCallActivityLifecycle("onStart")
    }

    override fun onResume() {
        super.onResume()
        CallBubbleOverlay.isCallActivityVisible = true
        CallBubbleOverlay.hide(this)
        logCall("InCallActivity.onResume hasActiveCall=${hasLiveCall()}")
        logCallActivityLifecycle("onResume")
    }

    override fun onPause() {
        logCall("InCallActivity.onPause hasActiveCall=${hasLiveCall()}")
        logCallActivityLifecycle("onPause")
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        logCall("InCallActivity.onUserLeaveHint hasActiveCall=${hasLiveCall()}")
        logCallActivityLifecycle("onUserLeaveHint")
        if (!isFinishing && !isFinishingCallUi && !isEndCallRequested && hasLiveCall()) {
            CallBubbleOverlay.showFromCallActivityExit(this)
            MyInCallService.getInstance()?.showCallBubbleIfNeeded()
            if (CallBubbleOverlay.canShow(this)) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onStop() {
        logCall("InCallActivity.onStop hasActiveCall=${hasLiveCall()}")
        logCallActivityLifecycle("onStop")
        CallBubbleOverlay.isCallActivityVisible = false
        super.onStop()
        if (!isFinishing && !isFinishingCallUi && !isEndCallRequested && hasLiveCall()) {
            CallBubbleOverlay.showFromCallActivityExit(this)
            MyInCallService.getInstance()?.showCallBubbleIfNeeded()
        }
    }

    override fun onBackPressed() {
        if (hasLiveCall() && !isFinishingCallUi && !isEndCallRequested) {
            CallBubbleOverlay.showFromCallActivityExit(this)
            MyInCallService.getInstance()?.showCallBubbleIfNeeded()
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        } else {
            finishCallUiAfterCall("back_press")
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logCallActivityLifecycle("onConfigurationChanged", "uiMode=${newConfig.uiMode} orientation=${newConfig.orientation}")
        MyInCallService.getInstance()?.forceUpdateUI()
    }
}

// --- CALL CONTROL FUNCTIONS ---

fun endCall(context: Context) {
    try {
        CallBubbleOverlay.suppressForTermination(context)
        val inCallService = MyInCallService.getInstance()
        if (inCallService != null) {
            Log.w(CALL_UI_FORENSIC_TAG, "call_disconnect_owner source=CallScreen.endCall owner=MyInCallService")
            inCallService.disconnectCall()
            return
        }
        Log.w(CALL_UI_FORENSIC_TAG, "call_disconnect_owner source=CallScreen.endCall owner=blocked reason=service_unavailable")
    } catch (e: Exception) {
        android.util.Log.e("ContactApp", "System endCall failed: ${e.message}")
    }
}

fun toggleMute(context: Context, currentMuteState: Boolean, onMuteChanged: (Boolean) -> Unit) {
    val newMuteState = !currentMuteState
    onMuteChanged(newMuteState)
    try {
        MyInCallService.getInstance()?.setMuted(newMuteState)
    } catch (e: Exception) {
        onMuteChanged(currentMuteState)
    }
}

fun toggleSpeaker(context: Context, currentSpeakerState: Boolean, onSpeakerChanged: (Boolean) -> Unit) {
    val newSpeakerState = !currentSpeakerState
    onSpeakerChanged(newSpeakerState)
    try {
        val route = if (newSpeakerState) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
        MyInCallService.getInstance()?.setCallAudioRoute(route)
    } catch (e: Exception) {
        onSpeakerChanged(currentSpeakerState)
    }
}

fun selectAudioRoute(route: Int) {
    try {
        MyInCallService.getInstance()?.setCallAudioRoute(route)
    } catch (_: Exception) {
    }
}

fun answerCall(context: Context) {
    try {
        MyInCallService.getInstance()?.answerCall()
    } catch (e: Exception) {}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallingScreen(
    callState: CallState,
    isEnding: Boolean = false,
    onAnswerCall: () -> Unit = {},
    onRejectCall: () -> Unit = {},
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSelectAudioRoute: (Int) -> Unit = {},
    onAddCall: () -> Unit = {},
    onKeypadPress: (Char) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isProximityNear by remember { mutableStateOf(false) }
    var suppressTouchesUntilMs by remember { mutableLongStateOf(0L) }

    val isConnectedState = callState.primaryState == "ACTIVE" ||
        callState.primaryState == "HOLDING" ||
        callState.primaryState == "CONFERENCE" ||
        (callState.callStartTime > 0L && !callState.isTerminal)
    val hasBluetoothRoute = supportsAudioRoute(
        callState.supportedAudioRouteMask,
        android.telecom.CallAudioState.ROUTE_BLUETOOTH
    )
    val hasWiredHeadsetRoute = supportsAudioRoute(
        callState.supportedAudioRouteMask,
        android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
    )
    val bluetoothRouteLabel = callState.bluetoothDeviceName?.trim().orEmpty().ifBlank { "Bluetooth" }
    val showAudioRouteButton = hasBluetoothRoute
    val audioRouteOptions = mutableListOf<AudioRouteOption>().apply {
        if (hasBluetoothRoute) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_BLUETOOTH, bluetoothRouteLabel))
        }
        if (supportsAudioRoute(callState.supportedAudioRouteMask, android.telecom.CallAudioState.ROUTE_SPEAKER)) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_SPEAKER, "Speaker"))
        }
        if (hasWiredHeadsetRoute) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_WIRED_HEADSET, "Headset"))
        } else if (supportsAudioRoute(callState.supportedAudioRouteMask, android.telecom.CallAudioState.ROUTE_EARPIECE)) {
            add(AudioRouteOption(android.telecom.CallAudioState.ROUTE_EARPIECE, "Earpiece"))
        }
    }
    val audioButtonIcon = when (callState.currentAudioRoute) {
        android.telecom.CallAudioState.ROUTE_BLUETOOTH -> Icons.Outlined.Bluetooth
        android.telecom.CallAudioState.ROUTE_SPEAKER -> Icons.Outlined.VolumeUp
        else -> Icons.Outlined.VolumeDown
    }
    val audioButtonLabel = when (callState.currentAudioRoute) {
        android.telecom.CallAudioState.ROUTE_BLUETOOTH -> bluetoothRouteLabel
        android.telecom.CallAudioState.ROUTE_SPEAKER -> "Speaker"
        android.telecom.CallAudioState.ROUTE_WIRED_HEADSET -> "Headset"
        else -> "Earpiece"
    }
    val audioButtonContentDescription = when (callState.currentAudioRoute) {
        android.telecom.CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth audio"
        android.telecom.CallAudioState.ROUTE_SPEAKER -> "Speaker"
        android.telecom.CallAudioState.ROUTE_WIRED_HEADSET -> "Headset"
        else -> "Earpiece"
    }
    var contactPhotoBitmap by remember(callState.contactPhotoUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showKeypad by remember { mutableStateOf(false) }
    var dtmfInput by remember { mutableStateOf("") }
    var showConferenceManager by remember { mutableStateOf(false) }
    var showAudioRouteDialog by remember { mutableStateOf(false) }
    var localActiveStartTime by remember { mutableLongStateOf(0L) }
    var frozenDurationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(hasBluetoothRoute) {
        if (!hasBluetoothRoute) {
            showAudioRouteDialog = false
        }
    }

    LaunchedEffect(callState.contactPhotoUri) {
        val photoUri = callState.contactPhotoUri
        if (photoUri.isNullOrBlank()) {
            contactPhotoBitmap = null
            return@LaunchedEffect
        }
        contactPhotoBitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    
    // CRITICAL FIX: Local Answer State
    // Telecom can take 1.5 seconds to acknowledge an answered call. 
    // This boolean immediately skips the ringing UI so the swipe slider doesn't get stuck!
    var localAnswerRequested by remember { mutableStateOf(false) }
    LaunchedEffect(callState.isRinging) {
        if (!callState.isRinging) {
            localAnswerRequested = false
        }
    }
    SideEffect {
        when {
            callState.isInCall && !callState.isTerminal && !isEnding && frozenDurationMs != 0L -> {
                frozenDurationMs = 0L
            }
            (!callState.isInCall || !isConnectedState) && !callState.isTerminal && !isEnding -> {
                if (localActiveStartTime != 0L) {
                    localActiveStartTime = 0L
                }
            }
            callState.callStartTime > 0L -> {
                if (localActiveStartTime != 0L) {
                    localActiveStartTime = 0L
                }
            }
            isConnectedState && localActiveStartTime == 0L -> {
                localActiveStartTime = System.currentTimeMillis()
                Log.w(CALL_UI_FORENSIC_TAG, "call_ui_timer_fallback action=start")
            }
        }
    }

    val liveDurationMs = when {
        callState.callStartTime > 0L -> (currentTime - callState.callStartTime).coerceAtLeast(0L)
        localActiveStartTime > 0L -> (currentTime - localActiveStartTime).coerceAtLeast(0L)
        else -> 0L
    }

    LaunchedEffect(isEnding, callState.isTerminal) {
        if ((isEnding || callState.isTerminal) && frozenDurationMs == 0L) {
            val durationToFreeze = if (callState.isTerminal && callState.finalDurationMillis > 0L) {
                callState.finalDurationMillis
            } else {
                liveDurationMs
            }
            if (durationToFreeze > 0L) {
                frozenDurationMs = durationToFreeze
                Log.w(
                    CALL_UI_FORENSIC_TAG,
                    "call_ui_timer_frozen durationMs=$durationToFreeze callId=${callState.primaryCallId}"
                )
            }
        }
    }

    // Keep a single proximity controller alive for both answer blocking and
    // stable near/far wake decisions so no parallel sensor path can still fire.
    val shouldEnableProximity = callState.isInCall &&
        !callState.isTerminal &&
        !isEnding &&
        isConnectedState &&
        !callState.isSpeakerOn

    LaunchedEffect(suppressTouchesUntilMs) {
        if (suppressTouchesUntilMs <= 0L) {
            return@LaunchedEffect
        }
        val remainingMs = (suppressTouchesUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
        if (suppressTouchesUntilMs <= SystemClock.elapsedRealtime()) {
            suppressTouchesUntilMs = 0L
            Log.w(CALL_UI_FORENSIC_TAG, "proximity_touch_guard action=release")
        }
    }

    DisposableEffect(context, shouldEnableProximity) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val proximitySensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val handler = Handler(Looper.getMainLooper())
        var wakeLock: android.os.PowerManager.WakeLock? = null
        var pendingFarConfirmation: Runnable? = null

        fun cancelFarConfirmation(reason: String) {
            pendingFarConfirmation?.let {
                handler.removeCallbacks(it)
                pendingFarConfirmation = null
                Log.w(CALL_UI_FORENSIC_TAG, "proximity_far_confirmation action=cancel reason=$reason")
            }
        }

        fun releaseWakeLock(reason: String, armTouchGuard: Boolean) {
            cancelFarConfirmation("release:$reason")
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    Log.w(CALL_UI_FORENSIC_TAG, "proximity_wake_lock action=release reason=$reason")
                    if (armTouchGuard) {
                        suppressTouchesUntilMs = SystemClock.elapsedRealtime() + CALL_UI_PROXIMITY_TOUCH_GUARD_MS
                        Log.w(
                            CALL_UI_FORENSIC_TAG,
                            "proximity_touch_guard action=arm durationMs=$CALL_UI_PROXIMITY_TOUCH_GUARD_MS reason=$reason"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(CALL_UI_FORENSIC_TAG, "proximity_wake_lock action=release_failed reason=$reason error=${e.message}", e)
            }
        }

        fun acquireWakeLock(reason: String) {
            if (!shouldEnableProximity) {
                return
            }
            cancelFarConfirmation("acquire:$reason")
            try {
                if (!powerManager.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    Log.w(CALL_UI_FORENSIC_TAG, "proximity_wake_lock action=unsupported reason=$reason")
                    return
                }
                if (wakeLock == null) {
                    wakeLock = powerManager.newWakeLock(
                        android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "CallerApp::ProximityEarLock"
                    )
                }
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(2 * 60 * 60 * 1000L)
                    Log.w(CALL_UI_FORENSIC_TAG, "proximity_wake_lock action=acquire reason=$reason")
                }
            } catch (e: Exception) {
                Log.e(CALL_UI_FORENSIC_TAG, "proximity_wake_lock action=acquire_failed reason=$reason error=${e.message}", e)
            }
        }

        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (event.sensor.type != android.hardware.Sensor.TYPE_PROXIMITY) {
                    return
                }
                val maxRange = proximitySensor?.maximumRange ?: 5f
                val distance = event.values.firstOrNull() ?: maxRange
                val isRawNear = distance < maxRange && distance < 5f
                isProximityNear = isRawNear

                if (!shouldEnableProximity) {
                    releaseWakeLock("controller_disabled", armTouchGuard = false)
                    return
                }

                if (isRawNear) {
                    acquireWakeLock("near_raw")
                    return
                }

                if (wakeLock?.isHeld == true && pendingFarConfirmation == null) {
                    pendingFarConfirmation = Runnable {
                        pendingFarConfirmation = null
                        releaseWakeLock("far_confirmed", armTouchGuard = true)
                    }
                    handler.postDelayed(pendingFarConfirmation!!, CALL_UI_PROXIMITY_FAR_CONFIRMATION_MS)
                    Log.w(
                        CALL_UI_FORENSIC_TAG,
                        "proximity_far_confirmation action=schedule durationMs=$CALL_UI_PROXIMITY_FAR_CONFIRMATION_MS"
                    )
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }

        Log.w(
            CALL_UI_FORENSIC_TAG,
            "proximity_runtime_path source=CallingScreen shouldEnable=$shouldEnableProximity sensorPresent=${proximitySensor != null}"
        )

        if (proximitySensor != null) {
            sensorManager.registerListener(listener, proximitySensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            cancelFarConfirmation("dispose")
            try {
                sensorManager.unregisterListener(listener)
            } catch (_: Exception) {
            }
            isProximityNear = false
            releaseWakeLock("dispose", armTouchGuard = false)
        }
    }

    LaunchedEffect(callState.isInCall, callState.primaryCallId, callState.isTerminal, isEnding) {
        if (callState.isInCall && !callState.isTerminal && !isEnding) {
            while (true) {
                currentTime = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    val primaryTalkDurationMs = resolveLiveTalkDurationMillis(
        snapshotDurationMillis = callState.primaryTalkDurationMillis,
        timerRunning = callState.primaryTalkTimerRunning,
        snapshotTimeMillis = callState.stateSnapshotTimeMillis,
        currentTimeMillis = currentTime
    )
    val displayedDurationMs = when {
        frozenDurationMs > 0L -> frozenDurationMs
        callState.isTerminal && callState.finalDurationMillis > 0L -> callState.finalDurationMillis
        callState.primaryTalkDurationMillis > 0L || callState.primaryTalkTimerRunning -> primaryTalkDurationMs
        else -> liveDurationMs
    }

    ModernCallingScreenLayout(
        callState = callState,
        isEnding = isEnding,
        displayedDurationMs = displayedDurationMs,
        currentTimeMillis = currentTime,
        isProximityNear = isProximityNear,
        suppressTouches = suppressTouchesUntilMs > 0L,
        localAnswerRequested = localAnswerRequested,
        onLocalAnswerRequestedChange = { localAnswerRequested = it },
        showKeypad = showKeypad,
        onShowKeypadChange = { showKeypad = it },
        dtmfInput = dtmfInput,
        onDtmfInputChange = { dtmfInput = it },
        showConferenceManager = showConferenceManager,
        onShowConferenceManagerChange = { showConferenceManager = it },
        showAudioRouteDialog = showAudioRouteDialog,
        onShowAudioRouteDialogChange = { showAudioRouteDialog = it },
        modifier = modifier,
        onAnswerCall = onAnswerCall,
        onRejectCall = onRejectCall,
        onEndCall = onEndCall,
        onToggleMute = onToggleMute,
        onToggleSpeaker = onToggleSpeaker,
        onSelectAudioRoute = onSelectAudioRoute,
        onAddCall = onAddCall,
        onKeypadPress = onKeypadPress
    )

    if (showConferenceManager) {
        ConferenceManagerDialog(
            participants = callState.conferenceParticipants,
            currentTimeMillis = currentTime,
            snapshotTimeMillis = callState.stateSnapshotTimeMillis,
            onDisconnectParticipant = { id ->
                MyInCallService.getInstance()?.disconnectSpecificParticipant(id)
            },
            onDismiss = { showConferenceManager = false }
        )
    }

    if (showAudioRouteDialog && hasBluetoothRoute) {
        AudioRouteDialog(
            options = audioRouteOptions,
            currentRoute = callState.currentAudioRoute,
            onSelectRoute = { route ->
                onSelectAudioRoute(route)
                showAudioRouteDialog = false
            },
            onDismiss = { showAudioRouteDialog = false }
        )
    }
}
