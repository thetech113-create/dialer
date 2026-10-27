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
    "Typo",
    "UseFullScreenIntent",
    "MissingPermission",
    "HardwareIds",
    "RedundantSuspendModifier"
)

package com.caller

import com.caller.logging.UltraForensicLogger
import android.telecom.InCallService
        import android.telecom.Call
        import android.telecom.CallAudioState
        import android.telecom.DisconnectCause
        import android.content.Intent
        import android.content.Context
        import android.content.BroadcastReceiver
        import android.content.IntentFilter
        import android.util.Log
        import android.Manifest
        import android.content.pm.PackageManager
        import androidx.core.content.ContextCompat
        import android.app.Notification
        import android.app.NotificationChannel
        import android.app.NotificationManager
        import android.app.PendingIntent
        import android.media.AudioAttributes
        import android.media.AudioManager
        import android.media.RingtoneManager
        import android.content.ContentUris
        import android.net.Uri
        import android.provider.MediaStore
        import android.os.Build
        import android.os.Handler
        import android.os.Looper
        import android.os.PowerManager
        import android.os.SystemClock
        import androidx.core.app.NotificationCompat
        import androidx.core.app.Person
        import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
        import android.annotation.SuppressLint
        import android.telecom.VideoProfile
        import android.telecom.TelecomManager
        import android.telephony.TelephonyManager
        import android.telephony.PhoneNumberUtils
        import android.content.pm.ServiceInfo
        import android.util.Base64
        import android.provider.ContactsContract
        import android.os.UserManager
        import android.app.ActivityOptions

// Import CallParticipant from CallScreen.kt
import com.caller.CallParticipant
import kotlinx.coroutines.flow.MutableStateFlow

        data class UnifiedCallState(
            val isInCall: Boolean,
            val primaryState: String,
            val primaryNumber: String,
            val primaryName: String,
            val primaryPhotoUri: String? = null,
            val primaryCallId: String = "",
            val isHolding: Boolean,
            val secondaryNumber: String?,
            val secondaryName: String?,
            val secondaryCallId: String? = null,
            val secondaryState: String?,
            val secondaryConnectTimeMillis: Long = 0L,
            val isConference: Boolean,
            val isMuted: Boolean = false,
            val isSpeakerOn: Boolean = false,
            val isSpam: Boolean = false,
            val participants: List<CallParticipant> = emptyList(),
            val connectTimeMillis: Long = 0L,
            val isTerminal: Boolean = false,
            val disconnectTimeMillis: Long = 0L,
            val finalDurationMillis: Long = 0L,
            val disconnectCauseCode: Int = DisconnectCause.UNKNOWN,
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

        private data class CallerDisplayResolution(
            val name: String,
            val source: String,
            val photoUri: String? = null
        )

        private data class IncomingCallNotificationDecision(
            val policy: IncomingCallUiDecision,
            val foregroundPackage: String,
            val defaultDialerPackage: String,
            val launcherPackage: String
        )

private const val OPEN_UI_AFTER_ANSWER_EXTRA = "OPEN_UI_AFTER_ANSWER_EXTRA"

@SuppressLint("MissingPermission", "UseFullScreenIntent")
class MyInCallService : InCallService() {

            private val incomingCallChannelId = "call_channel_v4"
            private val ongoingCallChannelId = "ongoing_call_channel_v3"
            private val answeredBannerChannelId = "answered_call_banner_v2"
            // Set when the call was answered from the banner in another app: the next ongoing notification pops
            // as a banner once (option A) and the floating call pill is shown (option B).
            @Volatile private var answeredFromBannerPopPending = false
            private var transitionWakeLock: PowerManager.WakeLock? = null
            // Incoming-call ringtone, played via the system ringtone player (android.media.Ringtone).
            private var incomingRingtone: android.media.Ringtone? = null
            private val SYSTEM_RINGTONE_VERIFY_DELAY_MS = 700L
            private var activeIncomingRingtoneKey: String? = null
            private var incomingRingtoneSilencedForCurrentCall = false
            private var screenStateReceiver: BroadcastReceiver? = null
            private var lastScreenOffAtElapsedMs: Long = 0L
            private var lastAppInitiatedDisconnectAtElapsedMs: Long = 0L
            private var lastPowerEndRecoveryCallId: String = ""

            fun logCall(msg: String) {
                Log.e(
                    TAG_CALL_FORENSIC,
                    "[t=${android.os.SystemClock.elapsedRealtime()}][thread=${Thread.currentThread().name}] $msg"
                )
            }

            fun hasActiveCall(): Boolean {
                return getLiveCalls().isNotEmpty()
            }

    override fun onBringToForeground(showDialpad: Boolean) {
        val activeCalls = getLiveCalls().filter { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
        if (activeCalls.isEmpty()) {
            Log.w(P2P_FORENSIC_TAG, "onBringToForeground blocked: zero active calls")
            return
        }
        super.onBringToForeground(showDialpad)
        logOemDialerFallbackServiceEvent("onBringToForeground", showDialpad)
        val currentState = runCatching { getCurrentCallStateFromSync() }.getOrNull()
        Log.w(
            P2P_FORENSIC_TAG,
            "ui_launch_owner source=MyInCallService.onBringToForeground owner=launchCallUI showDialpad=$showDialpad"
        )
        launchCallUI(
            reason = "onBringToForeground",
            showDialpad = showDialpad,
            rawNumber = currentState?.primaryNumber.orEmpty(),
            initialStateHint = currentState?.primaryState.orEmpty(),
            initialNameHint = currentState?.primaryName.orEmpty(),
            isConferenceHint = currentState?.isConference == true
        )
    }

    companion object {
                val videoCallVersion = MutableStateFlow(0)
                private var instance: MyInCallService? = null
                private var callStateListener: ((UnifiedCallState) -> Unit)? = null
                private var callUIListener: ((UnifiedCallState) -> Unit)? = null
                @Volatile private var lastCallUiSnapshot: UnifiedCallState? = null
                @Volatile private var lastTerminalUiSnapshot: UnifiedCallState? = null
                private const val TRANSIENT_CALL_GAP_MS: Long = 5000L
                private const val TAG_CALL_FORENSIC = "CallForensic"
                private const val ACTION_DISCONNECT_CALL = "DISCONNECT_CALL"
                // Call notification was removed (swipe/Clear all on some phones): put it straight back.
                private const val ACTION_REPOST_CALL_NOTIFICATION = "REPOST_CALL_NOTIFICATION"
                private const val ACTION_START_INCALL_KEEPALIVE = "com.caller.ACTION_START_INCALL_KEEPALIVE"
                const val VOICEMAIL_FILE_PREFIX = "Voicemail"

                /** Whether the app's main screen was visible when the current call session began. */
                @Volatile var appWasOnScreenAtCallStart = false

                fun getInstance(): MyInCallService? = instance
                fun peekLastCallUiSnapshot(): UnifiedCallState? = lastCallUiSnapshot

                fun setCallStateListener(listener: (UnifiedCallState) -> Unit) {
                    callStateListener = listener
                }
                
                fun setCallUIListener(listener: ((UnifiedCallState) -> Unit)?) {
                    callUIListener = listener
                    // CRITICAL FIX: Instantly push live state to CallScreen to destroy the blank Ghost Screen
                    if (listener != null) {
                        instance?.forceUpdateUI()
                    }
                }
            }

            private fun cacheLastCallUiSnapshot(state: UnifiedCallState, source: String) {
                lastCallUiSnapshot = state
                if (state.isInCall) {
                    lastLiveCallSeenAtMs = System.currentTimeMillis()
                }
                Log.w(
                    P2P_FORENSIC_TAG,
                    "call_ui_snapshot action=cache source=$source state=${state.primaryState} number=${maskP2PNumberForLog(state.primaryNumber)} name=${maskP2PDisplayValueForLog(state.primaryName)} sim=${state.simLabel}"
                )
                if (state.isInCall && state.primaryCallId.isNotBlank()) {
                    logCallerDisplayResolution(
                        event = "ui_publish",
                        callKey = state.primaryCallId,
                        rawNumber = state.primaryNumber,
                        extra = "source=$source state=${state.primaryState}",
                        windowMs = 1000L
                    )
                }
            }

            private fun resolveSimLabel(call: Call?): String {
                val handle = call?.details?.accountHandle ?: return ""
                return DualSimFixes.resolveCompactSimLabel(this, handle)
            }
            private fun clearLastCallUiSnapshot(source: String) {
                lastCallUiSnapshot = null
                Log.w(P2P_FORENSIC_TAG, "call_ui_snapshot action=clear source=$source")
            }

            private fun clearTerminalUiSnapshot(source: String) {
                if (lastTerminalUiSnapshot != null) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_ui_terminal action=clear source=$source callId=${lastTerminalUiSnapshot?.primaryCallId.orEmpty()}"
                    )
                }
                lastTerminalUiSnapshot = null
            }

            private lateinit var bgHandler: Handler
            private var notificationReposterActive: AtomicBoolean = AtomicBoolean(false)
            @Volatile private var lastNotificationStateKey: String? = null
            private var pendingDisconnect = false
            private var pendingDisconnectCleanup: Runnable? = null
            private var transientEmptyRecoveryRunnable: Runnable? = null
            private var lastLiveCallSeenAtMs: Long = 0L
            @Volatile private var bubbleSuppressedForTermination = false
            @Volatile private var isServiceExplicitlyStarted = false

            /**
             * Hides MainActivity/MessagingActivity tasks from Recent Apps while a call is active
             * so "Clear All" in Recents skips our process, and restores them when the call ends.
             * CallActivity and WidgetCallActivity remain permanently excluded.
             */
            private fun setAllAppTasksExcludedFromRecents(excluded: Boolean) {
                runCatching {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
                    am.appTasks.forEach { task ->
                        val baseClass = runCatching {
                            task.taskInfo.baseIntent.component?.className.orEmpty()
                        }.getOrDefault("")
                        val alwaysExcluded = baseClass.endsWith("CallActivity") ||
                            baseClass.endsWith("WidgetCallActivity")
                        task.setExcludeFromRecents(if (alwaysExcluded) true else excluded)
                    }
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "app_tasks_exclude_from_recents excluded=$excluded taskCount=${am.appTasks.size}"
                    )
                }
            }

            /**
             * Explicitly calls startForegroundService() on MyInCallService so Android sets
             * ServiceRecord.startRequested = true. Without this, onTaskRemoved() never fires
             * and OEM Recents cleaners kill the bound-only InCallService process on swipe.
             */
            private fun ensureServiceExplicitlyStarted(call: Call? = null) {
                val targetCall = call ?: getLiveCalls().firstOrNull()
                if (targetCall != null) {
                    val isConf = targetCall.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
                    val rawNum = if (isConf) "Conference" else targetCall.details?.handle?.schemeSpecificPart.orEmpty()
                    val stateHint = stateToUiStateHint(targetCall.state)
                    val (_, nameHint) = resolveCallIdentity(targetCall, "ensureServiceExplicitlyStarted")
                    if (stateHint != "RINGING") {
                        startImmediateCallForeground(
                            phoneNumber = rawNum,
                            contactName = nameHint.ifBlank {
                                if (isConf) "Conference Call" else rawNum.ifBlank { "Ongoing Call" }
                            },
                            isConf = isConf,
                            callState = stateHint
                        )
                    }
                }
                if (!isServiceExplicitlyStarted) {
                    runCatching {
                        val keepAliveIntent = Intent(applicationContext, MyInCallService::class.java).apply {
                            action = ACTION_START_INCALL_KEEPALIVE
                        }
                        ContextCompat.startForegroundService(applicationContext, keepAliveIntent)
                        isServiceExplicitlyStarted = true
                        Log.w(P2P_FORENSIC_TAG, "incall_service_explicit_start startRequested=true")
                    }.onFailure { e ->
                        Log.e(P2P_FORENSIC_TAG, "incall_service_explicit_start failed: ${e.message}", e)
                    }
                }
                setAllAppTasksExcludedFromRecents(true)
            }

            // --- CALL STATE MACHINE TRACKERS ---
            private val callDirectionTracker = java.util.concurrent.ConcurrentHashMap<String, String>()
            private val callAnswerTracker = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            private val callMachineCallbackTracker = java.util.concurrent.ConcurrentHashMap<String, Call.Callback>()
            private data class CallTimerState(
                val callId: String,
                var accumulatedActiveMillis: Long = 0L,
                var lastActiveStartElapsedRealtime: Long? = null,  // PATCHED: Changed from 0L to nullable
                var currentCallState: Int = Call.STATE_NEW,
                var isConference: Boolean = false,
                var parentConferenceId: String? = null,
                var disconnected: Boolean = false
            )
            private val callTimerStates = java.util.concurrent.ConcurrentHashMap<String, CallTimerState>()
            private val callTimerKeyAliases = java.util.concurrent.ConcurrentHashMap<String, String>()
            private val callDisplayResolutionCache = java.util.concurrent.ConcurrentHashMap<String, CallerDisplayResolution>()
            private val missedNotificationTracker = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            private val p2pCallSendTracker = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            private var lastUiLaunchAt = 0L
            private var lastUiLaunchKey = ""

            private fun getSafeCalls(): List<Call> {
                return try {
                    calls ?: emptyList()
                } catch (e: Exception) {
                    Log.w("MyInCallService", "Telecom not attached yet: ${e.message}")
                    emptyList()
                }
            }

            private fun isDirectBootPhase(): Boolean {
                return try {
                    val userManager = getSystemService(Context.USER_SERVICE) as UserManager
                    userManager.isUserUnlocked.not()
                } catch (_: Exception) {
                    false
                }
            }

            private fun isPowerSaveModeEnabledForDiagnostics(): Boolean {
                return try {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    powerManager.isPowerSaveMode
                } catch (_: Exception) {
                    false
                }
            }

            private fun isIgnoringBatteryOptimizationsForDiagnostics(): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
                return try {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    powerManager.isIgnoringBatteryOptimizations(packageName)
                } catch (_: Exception) {
                    false
                }
            }

            private fun logOemDialerFallbackServiceEvent(event: String, showDialpad: Boolean? = null) {
                val liveCalls = getLiveCalls()
                val stickySnapshot = lastCallUiSnapshot
                val powerSaveMode = isPowerSaveModeEnabledForDiagnostics()
                val batteryExempt = isIgnoringBatteryOptimizationsForDiagnostics()
                val defaultDialerPackage = try {
                    resolveDefaultDialerPackage(this@MyInCallService)
                } catch (_: Exception) {
                    ""
                }
                val isDefaultDialer = defaultDialerPackage == packageName
                val evidence = mutableListOf<String>()
                if (powerSaveMode) evidence += "power_save_active"
                if (liveCalls.isNotEmpty()) evidence += "framework_live_calls=${liveCalls.size}"
                if (stickySnapshot?.isInCall == true) evidence += "sticky_call_snapshot"
                if (!isDefaultDialer) evidence += "default_dialer_role_not_ours"
                if (!batteryExempt) evidence += "battery_exemption_missing"

                val oemFallbackSuspected =
                    powerSaveMode &&
                        (liveCalls.isNotEmpty() || stickySnapshot?.isInCall == true)

                val message =
                    "service_oem_fallback_probe event=$event showDialpad=${showDialpad ?: false} powerSaveMode=$powerSaveMode " +
                        "batteryExempt=$batteryExempt liveCalls=${liveCalls.size} " +
                        "stickyInCall=${stickySnapshot?.isInCall == true} stickyState=${stickySnapshot?.primaryState.orEmpty()} " +
                        "stickyCallId=${stickySnapshot?.primaryCallId.orEmpty()} isDefaultDialer=$isDefaultDialer " +
                        "defaultDialerPackage=${if (defaultDialerPackage.isBlank()) "unknown" else defaultDialerPackage} " +
                        "evidence=${if (evidence.isEmpty()) "none" else evidence.joinToString("+")} " +
                        "confidence=${if (event == "onDestroy" && oemFallbackSuspected) "high" else if (oemFallbackSuspected) "medium" else "low"} " +
                        "oem_fallback_path_reached=${if (oemFallbackSuspected) "yes" else "no"}"

                if (event == "onDestroy" && oemFallbackSuspected) {
                    Log.wtf(P2P_FORENSIC_TAG, message)
                    UltraForensicLogger.ultra("MyInCallService", "OEM_DIALER_FALLBACK_SERVICE_DESTROY", message)
                } else {
                    Log.w(P2P_FORENSIC_TAG, message)
                    UltraForensicLogger.forensic("MyInCallService", "OEM_DIALER_FALLBACK_SERVICE_EVENT", message)
                }
            }

            private fun getLiveCalls(): List<Call> {
                return getSafeCalls().filter {
                    it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING
                }
            }

            /**
             * The overlay must not rely on a sticky UI snapshot while a user-initiated
             * disconnect is still being processed by Telecom.
             */
            fun isOngoingCallForBubble(): Boolean =
                !bubbleSuppressedForTermination && getLiveCalls().isNotEmpty()

            private fun getLiveTopLevelCalls(allCalls: List<Call> = getSafeCalls()): List<Call> {
                return allCalls.filter {
                    it.parent == null &&
                        it.state != Call.STATE_DISCONNECTED &&
                        it.state != Call.STATE_DISCONNECTING
                }
            }

            private fun stableCallId(call: Call?): String = call?.hashCode()?.toString().orEmpty()

            // Top-level conference detection for TIMER OWNERSHIP purposes only.
            // Critical: child calls (call.parent != null) must NEVER be treated as "conference keys".
            private fun isTopLevelConferenceForTimer(call: Call?): Boolean {
                if (call == null) return false
                if (call.parent != null) return false
                return (call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) ||
                    (call.children?.isNotEmpty() == true)
            }

            private fun resolveCallTimerStateKey(call: Call?): String {
                if (call == null) return ""
                val objectCallId = stableCallId(call)
                if (objectCallId.isBlank()) return ""

                val isConferenceTopLevel = isTopLevelConferenceForTimer(call)

                // PINPOINT FIX: Use unique per-call-leg keys to prevent timer state collision
                // Child calls: child:<stableCallId> (unique per call object)
                // Conference calls: conference:<stableCallId> (unique per conference object)
                val expectedKey = when {
                    isConferenceTopLevel -> "conference:$objectCallId"
                    else -> "child:$objectCallId"
                }

                val existingKey = callTimerKeyAliases[objectCallId]
                if (existingKey == null) {
                    callTimerKeyAliases[objectCallId] = expectedKey
                    UltraForensicLogger.info(
                        "CallTimerStrict",
                        "KEY_ALIAS_SET",
                        "telecomId=$objectCallId stableCallKey=$expectedKey isConferenceTopLevel=$isConferenceTopLevel " +
                            "timestamp=${SystemClock.elapsedRealtime()} reason=initial_assignment"
                    )
                    return expectedKey
                }

                // Pinpoint fix: allow one-way rotation ONLY when the call becomes a TOP-LEVEL conference.
                // This is the exact merge-time failure mode: conference call must never keep a child key.
                if (isConferenceTopLevel && !existingKey.startsWith("conference:")) {
                    callTimerKeyAliases[objectCallId] = expectedKey
                    UltraForensicLogger.info(
                        "CallTimerStrict",
                        "KEY_ALIAS_ROTATE_TO_CONFERENCE",
                        "telecomId=$objectCallId oldStableCallKey=$existingKey newStableCallKey=$expectedKey " +
                            "timestamp=${SystemClock.elapsedRealtime()} reason=conference_promotion"
                    )
                    return expectedKey
                }

                // Keep existing mapping to prevent timer resets due to transient details updates
                return existingKey
            }

            private fun hasLiveTimerStateAlias(timerKey: String, excludingCallId: String): Boolean {
                if (timerKey.isBlank()) return false
                return getSafeCalls().any { candidate ->
                    val candidateCallId = stableCallId(candidate)
                    candidateCallId != excludingCallId &&
                        candidate.state != Call.STATE_DISCONNECTED &&
                        candidate.state != Call.STATE_DISCONNECTING &&
                        resolveCallTimerStateKey(candidate) == timerKey
                }
            }

            private fun updateCallTimerStateStrict(call: Call?, newState: Int) {
                if (call == null) return
                val objectCallId = stableCallId(call)
                if (objectCallId.isBlank()) return
                val timerKey = resolveCallTimerStateKey(call)
                if (timerKey.isBlank()) return

                val isConf = isTopLevelConferenceForTimer(call)
                val parentId = call.parent?.let { stableCallId(it) }

                val timerState = callTimerStates.getOrPut(timerKey) {
                    CallTimerState(
                        callId = timerKey,
                        isConference = isConf,
                        parentConferenceId = parentId,
                        currentCallState = Call.STATE_NEW
                    )
                }

                val mergeJustHappened = timerState.parentConferenceId != parentId
                
                // Capture previous parent ID before updating state
                val prevParentId = timerState.parentConferenceId
                
                timerState.isConference = isConf
                timerState.parentConferenceId = parentId

                val previousState = timerState.currentCallState
                if (previousState == newState && !mergeJustHappened) return

                val nowElapsed = SystemClock.elapsedRealtime()
                
                // <<< PATCH START: ELIMINATE THE OVERCOUNT BUG >>>
                // Child calls continue counting even when merged into conference
                // All ACTIVE calls accumulate time regardless of conference status
                val wasActive = previousState == Call.STATE_ACTIVE
                val isNowActive = newState == Call.STATE_ACTIVE
                // <<< PATCH END >>>
                val holdJustHappened = wasActive && newState == Call.STATE_HOLDING
                val resumeJustHappened = previousState == Call.STATE_HOLDING && isNowActive

                val prevAccumulated = timerState.accumulatedActiveMillis
                val prevLastStart = timerState.lastActiveStartElapsedRealtime

                if (wasActive && !isNowActive) {
                    if (timerState.lastActiveStartElapsedRealtime != null) {  // PATCHED: Check for null instead of > 0L
                        timerState.accumulatedActiveMillis += (nowElapsed - timerState.lastActiveStartElapsedRealtime!!).coerceAtLeast(0L)
                    }
                    timerState.lastActiveStartElapsedRealtime = null  // PATCHED: Set to null instead of 0L
                } else if (!wasActive && isNowActive) {
                    timerState.lastActiveStartElapsedRealtime = nowElapsed
                }

                timerState.currentCallState = newState
                if (newState == Call.STATE_DISCONNECTED || newState == Call.STATE_DISCONNECTING) {
                    timerState.disconnected = true
                    if (newState == Call.STATE_DISCONNECTED) {
                        runCatching { AccountDeviceHistoryManager.syncAccountNow(this@MyInCallService, showToast = false) }
                    }
                }

                // <<< PATCH START: MANDATORY FORENSIC LOGGING >>>
                val displayedElapsed = resolveCallTalkDurationStrictMillis(call, nowElapsed)
                UltraForensicLogger.info(
                    "CallTimerStrict",
                    "STATE_TRANSITION",
                    "stableCallKey=$timerKey " +
                    "telecomId=$objectCallId " +
                    "prevState=$previousState " +
                    "newState=$newState " +
                    "accumulatedActiveMillisBefore=$prevAccumulated " +
                    "accumulatedActiveMillisAfter=${timerState.accumulatedActiveMillis} " +
                    "activeStartElapsedRealtimeBefore=$prevLastStart " +
                    "activeStartElapsedRealtimeAfter=${timerState.lastActiveStartElapsedRealtime} " +
                    "isConference=$isConf " +
                    "parentConferenceKey=${parentId ?: "null"} " +
                    "mergeEvent=$mergeJustHappened " +
                    "displayedElapsed=$displayedElapsed " +
                    "timestamp=$nowElapsed " +
                    "reason=updateCallTimerStateStrict"
                )
                // <<< PATCH END >>>

                UltraForensicLogger.info(
                    "CallTimerStrict",
                    "STATE_TRANSITION",
                    "callObjectId=$objectCallId timerKey=$timerKey parentId=${parentId.orEmpty()} isConf=$isConf prevState=$previousState newState=$newState prevAccum=$prevAccumulated newAccum=${timerState.accumulatedActiveMillis} prevStart=$prevLastStart newStart=${timerState.lastActiveStartElapsedRealtime} merge=$mergeJustHappened hold=$holdJustHappened resume=$resumeJustHappened timestamp=$nowElapsed"
                )
            }

            private fun resolveCallTalkDurationStrictMillis(call: Call?, nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
                if (call == null) return 0L
                val timerKey = resolveCallTimerStateKey(call)
                if (timerKey.isBlank()) return 0L
                val timerState = callTimerStates[timerKey] ?: return 0L

                var displayed = timerState.accumulatedActiveMillis
                // <<< PATCH START: FIX DISPLAY LOGIC - CHILD TIMERS CONTINUE IN CONFERENCE >>>
                // Show running timer for all ACTIVE calls (including conference children)
                if (timerState.currentCallState == Call.STATE_ACTIVE && 
                    timerState.lastActiveStartElapsedRealtime != null) {
                    displayed += (nowElapsed - timerState.lastActiveStartElapsedRealtime!!).coerceAtLeast(0L)
                }
                // <<< PATCH END >>>
                return displayed
            }

            private fun isCallTalkTimerRunningStrict(call: Call?): Boolean {
                if (call == null) return false
                val timerKey = resolveCallTimerStateKey(call)
                if (timerKey.isBlank()) return false
                val timerState = callTimerStates[timerKey] ?: return false
                // <<< PATCH START: FIX RUNNING CHECK - CHILD TIMERS CONTINUE IN CONFERENCE >>>
                // Report running for all ACTIVE calls (including conference children)
                return timerState.currentCallState == Call.STATE_ACTIVE && 
                       timerState.lastActiveStartElapsedRealtime != null
                // <<< PATCH END >>>
            }

            private fun isConferenceCall(call: Call?): Boolean {
                return call?.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
            }

            private fun canUseCapability(call: Call?, capability: Int): Boolean {
                return try {
                    call?.details?.can(capability) == true
                } catch (_: Exception) {
                    false
                }
            }

            private fun isMergeReadyTopLevelCall(call: Call?): Boolean {
                val state = call?.state ?: return false
                return when (state) {
                    Call.STATE_ACTIVE, Call.STATE_HOLDING -> true
                    else -> false
                }
            }

            private fun findConferenceableMergePair(liveTopLevelCalls: List<Call>): Pair<Call, Call>? {
                for (host in liveTopLevelCalls) {
                    val peer = try {
                        host.conferenceableCalls.firstOrNull { candidate ->
                            candidate != host && liveTopLevelCalls.any { it == candidate }
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (peer != null) {
                        return host to peer
                    }
                }
                return null
            }

            private fun canMergeCalls(liveTopLevelCalls: List<Call>): Boolean {
                if (liveTopLevelCalls.size < 2) return false

                val mergeReadyCalls = liveTopLevelCalls.filter(::isMergeReadyTopLevelCall)
                if (mergeReadyCalls.size < 2) return false

                if (mergeReadyCalls.any { canUseCapability(it, Call.Details.CAPABILITY_MERGE_CONFERENCE) }) {
                    return true
                }

                if (findConferenceableMergePair(mergeReadyCalls) != null) {
                    return true
                }

                val standaloneCount = mergeReadyCalls.count { !isConferenceCall(it) }
                val hasConference = mergeReadyCalls.any(::isConferenceCall)
                return standaloneCount >= 2 || (hasConference && standaloneCount >= 1)
            }

            private fun buildCallResolutionKey(call: Call?, rawNumber: String): String {
                val callId = stableCallId(call)
                if (callId.isNotBlank()) {
                    return callId
                }
                val snapshot = buildP2PNumberSnapshot(rawNumber)
                return snapshot.normalized.ifBlank {
                    snapshot.last10.ifBlank {
                        snapshot.digits.ifBlank {
                            rawNumber.ifBlank { "unknown_call" }
                        }
                    }
                }
            }

            private fun isUsableResolvedName(name: String, rawNumber: String): Boolean {
                return name.isNotBlank() && name != "Unknown" && name != rawNumber
            }

            private fun logCallerDisplayResolution(
                event: String,
                callKey: String,
                rawNumber: String,
                extra: String,
                windowMs: Long = 5000L
            ) {
                val snapshot = buildP2PNumberSnapshot(rawNumber)
                logP2PThrottled(
                    level = Log.WARN,
                    dedupeKey = "caller_display:$event:$callKey:$extra",
                    message = "caller_display_$event callId=$callKey hasNumber=${snapshot.lookupKeys.isNotEmpty()} $extra",
                    windowMs = windowMs
                )
            }

            private fun clearCallDisplayResolution(call: Call?, rawNumber: String, reason: String) {
                val callKey = buildCallResolutionKey(call, rawNumber)
                if (callDisplayResolutionCache.remove(callKey) != null) {
                    logCallerDisplayResolution(
                        event = "clear",
                        callKey = callKey,
                        rawNumber = rawNumber,
                        extra = "reason=$reason",
                        windowMs = 1000L
                    )
                }
            }

            private fun getEffectiveCallState(call: Call?): Int {
                if (call == null) return Call.STATE_NEW
                val rawState = call.state
                if (rawState == Call.STATE_DISCONNECTED || rawState == Call.STATE_DISCONNECTING) {
                    return rawState
                }

                val parent = call.parent ?: return rawState
                val parentState = getEffectiveCallState(parent)
                val parentIsConference = parent.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true

                return when {
                    parentState == Call.STATE_HOLDING -> Call.STATE_HOLDING
                    parentState == Call.STATE_DISCONNECTED || parentState == Call.STATE_DISCONNECTING -> Call.STATE_DISCONNECTED
                    parentIsConference && parentState == Call.STATE_ACTIVE &&
                        rawState != Call.STATE_RINGING &&
                        rawState != Call.STATE_DIALING &&
                        rawState != Call.STATE_CONNECTING &&
                        rawState != Call.STATE_SELECT_PHONE_ACCOUNT -> Call.STATE_ACTIVE
                    else -> rawState
                }
            }

            private fun uiStateLabelForCall(call: Call?): String {
                val effectiveState = getEffectiveCallState(call)
                return when (effectiveState) {
                    Call.STATE_ACTIVE -> "ACTIVE"
                    Call.STATE_HOLDING -> "HOLDING"
                    Call.STATE_RINGING -> "RINGING"
                    Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> "DIALING"
                    Call.STATE_DISCONNECTING -> "DISCONNECTING"
                    Call.STATE_DISCONNECTED -> "DISCONNECTED"
                    else -> if (call != null) "TRANSITIONING" else "IDLE"
                }
            }

            private fun normalizeUiStateLabel(rawState: String, connectTimeMillis: Long): String {
                if (connectTimeMillis <= 0L) return rawState
                return when (rawState) {
                    "DIALING", "CONNECTING", "TRANSITIONING", "IDLE" -> "ACTIVE"
                    else -> rawState
                }
            }

            private fun resolvePrimaryCall(
                active: Call?,
                holding: Call?,
                ringing: Call?,
                dialing: Call?,
                conference: Call?,
                fallback: Call? = null
            ): Call? {
                // Foreground ownership must stay with the transitioning call first.
                return ringing ?: dialing ?: active ?: holding ?: conference ?: fallback
            }

            private fun resolveSecondaryCall(
                primaryCall: Call?,
                active: Call?,
                holding: Call?,
                ringing: Call?,
                dialing: Call?,
                conference: Call?,
                fallbackCalls: List<Call> = emptyList()
            ): Call? {
                return listOfNotNull(holding, active, ringing, dialing, conference)
                    .distinct()
                    .firstOrNull { it != primaryCall }
                    ?: fallbackCalls.firstOrNull { it != primaryCall }
            }

            private fun logCallOwnershipSnapshot(
                source: String,
                liveTopLevelCalls: List<Call>,
                primaryCall: Call?,
                secondaryCall: Call?
            ) {
                val holdOwnerId = liveTopLevelCalls.firstOrNull { it.state == Call.STATE_HOLDING }?.let(::stableCallId).orEmpty()
                val timerOwnerId = primaryCall?.takeIf { resolveConnectTime(it) > 0L }?.let(::stableCallId).orEmpty()
                liveTopLevelCalls.forEachIndexed { index, call ->
                    val callId = stableCallId(call)
                    val parentId = stableCallId(call.parent)
                    val childIds = call.children?.joinToString(",") { stableCallId(it) }.orEmpty()
                    val uiSlot = when (call) {
                        primaryCall -> "PRIMARY"
                        secondaryCall -> "SECONDARY"
                        else -> "BACKGROUND"
                    }
                    val displayedLabel = when (call.state) {
                        Call.STATE_RINGING -> "RINGING"
                        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> "DIALING"
                        Call.STATE_ACTIVE -> "ACTIVE"
                        Call.STATE_HOLDING -> "HOLDING"
                        else -> uiStateLabelForCall(call)
                    }
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_identity_matrix source=$source ts=${System.currentTimeMillis()} eventSource=sync callId=$callId state=${call.state} uiState=$displayedLabel parentId=$parentId childIds=$childIds uiSlot=$uiSlot displayedLabel=$displayedLabel holdOwner=$holdOwnerId timerOwner=$timerOwnerId rawNumber=${maskP2PNumberForLog(call.details?.handle?.schemeSpecificPart.orEmpty())} hash=${System.identityHashCode(call)} index=$index"
                    )
                }
            }

            private fun resolvePrimaryTopLevelCall(liveTopLevelCalls: List<Call>): Call? {
                var active: Call? = null
                var holding: Call? = null
                var ringing: Call? = null
                var dialing: Call? = null
                var conference: Call? = null

                for (c in liveTopLevelCalls) {
                    if (c.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) {
                        conference = c
                    } else {
                        when (c.state) {
                            Call.STATE_ACTIVE -> active = c
                            Call.STATE_HOLDING -> holding = c
                            Call.STATE_RINGING -> ringing = c
                            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> dialing = c
                        }
                    }
                }

                return resolvePrimaryCall(
                    active = active,
                    holding = holding,
                    ringing = ringing,
                    dialing = dialing,
                    conference = conference,
                    fallback = liveTopLevelCalls.firstOrNull()
                )
            }

            private fun resolveDisconnectTarget(allCalls: List<Call>): Call? {
                val liveTopLevelCalls = getLiveTopLevelCalls(allCalls)
                return resolvePrimaryTopLevelCall(liveTopLevelCalls)
                    ?: liveTopLevelCalls.firstOrNull()
                    ?: allCalls.firstOrNull {
                        it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING
                    }
            }

            private fun resolveConnectTime(primaryCall: Call?): Long {
                return primaryCall?.details?.connectTimeMillis ?: 0L
            }

            private fun isBluetoothCommunicationDevice(device: android.media.AudioDeviceInfo): Boolean {
                return when (device.type) {
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_HEARING_AID -> true
                    else -> false
                }
            }

            private fun resolveBluetoothDeviceName(
                currentAudioRoute: Int,
                supportedAudioRouteMask: Int,
                fallbackName: String? = null
            ): String? {
                val bluetoothSupported = supportedAudioRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0
                if (!bluetoothSupported && currentAudioRoute != CallAudioState.ROUTE_BLUETOOTH) {
                    return fallbackName?.takeIf { it.isNotBlank() }
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    return fallbackName?.takeIf { it.isNotBlank() }
                }
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    ?: return fallbackName?.takeIf { it.isNotBlank() }
                val currentDeviceName = runCatching {
                    audioManager.communicationDevice
                        ?.takeIf(::isBluetoothCommunicationDevice)
                        ?.productName
                        ?.toString()
                        ?.trim()
                }.getOrNull()
                if (!currentDeviceName.isNullOrBlank()) {
                    return currentDeviceName
                }
                val availableDeviceName = runCatching {
                    audioManager.availableCommunicationDevices
                        .firstOrNull(::isBluetoothCommunicationDevice)
                        ?.productName
                        ?.toString()
                        ?.trim()
                }.getOrNull()
                if (!availableDeviceName.isNullOrBlank()) {
                    return availableDeviceName
                }
                return fallbackName?.takeIf { it.isNotBlank() }
            }

            private fun resolveCallPhotoUri(
                call: Call?,
                rawNumberHint: String = "",
                source: String = "resolveCallPhotoUri"
            ): String? {
                if (call == null) return null
                if (call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) return null
                val rawNumber = rawNumberHint.ifBlank { call.details?.handle?.schemeSpecificPart.orEmpty() }
                if (rawNumber.isBlank()) return null
                val callKey = buildCallResolutionKey(call, rawNumber)
                callDisplayResolutionCache[callKey]?.photoUri?.let { cachedUri ->
                    if (cachedUri.isNotBlank()) return cachedUri
                }
                val resolvedPhotoUri = getContactPhotoUri(rawNumber)
                if (!resolvedPhotoUri.isNullOrBlank()) {
                    val existing = callDisplayResolutionCache[callKey]
                    if (existing != null && existing.photoUri != resolvedPhotoUri) {
                        callDisplayResolutionCache[callKey] = existing.copy(photoUri = resolvedPhotoUri)
                    }
                    logCallerDisplayResolution(
                        event = "photo",
                        callKey = callKey,
                        rawNumber = rawNumber,
                        extra = "source=$source result=hit",
                        windowMs = 1500L
                    )
                }
                return resolvedPhotoUri
            }

            private fun resolveCallIdentity(call: Call?, source: String = "resolveCallIdentity"): Pair<String, String> {
                if (call == null) return "" to "Unknown"
                if (call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) {
                    return "Conference" to "Conference Call"
                }

                val rawNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
                val callKey = buildCallResolutionKey(call, rawNumber)
                callDisplayResolutionCache[callKey]?.let { cached ->
                    if (cached.source != "saved_contact" && rawNumber.isNotEmpty()) {
                        val fetchedName = getContactName(rawNumber)
                        if (isUsableResolvedName(fetchedName, rawNumber)) {
                            val fetchedPhotoUri = getContactPhotoUri(rawNumber)
                            val upgraded = CallerDisplayResolution(fetchedName, "saved_contact", fetchedPhotoUri)
                            callDisplayResolutionCache[callKey] = upgraded
                            logCallerDisplayResolution(
                                event = "upgrade_saved_contact",
                                callKey = callKey,
                                rawNumber = rawNumber,
                                extra = "source=$source winner=saved_contact name=${maskP2PDisplayValueForLog(fetchedName)}"
                            )
                            return rawNumber to fetchedName
                        }
                    }
                    logCallerDisplayResolution(
                        event = "reuse",
                        callKey = callKey,
                        rawNumber = rawNumber,
                        extra = "source=$source winner=${cached.source} multiplePublishPrevented=true",
                        windowMs = 1500L
                    )
                    return rawNumber to cached.name
                }

                logCallerDisplayResolution(
                    event = "start",
                    callKey = callKey,
                    rawNumber = rawNumber,
                    extra = "source=$source"
                )

                val fetchedName = if (rawNumber.isNotEmpty()) getContactName(rawNumber) else "Unknown"
                val fetchedPhotoUri = if (rawNumber.isNotEmpty()) getContactPhotoUri(rawNumber) else null
                val savedHit = isUsableResolvedName(fetchedName, rawNumber)
                logCallerDisplayResolution(
                    event = "saved_lookup",
                    callKey = callKey,
                    rawNumber = rawNumber,
                    extra = "source=$source result=${if (savedHit) "hit" else "empty"}"
                )

                val telecomName = call.details?.callerDisplayName.orEmpty().trim()
                val cnamHit = isUsableResolvedName(telecomName, rawNumber)
                logCallerDisplayResolution(
                    event = "cnam_lookup",
                    callKey = callKey,
                    rawNumber = rawNumber,
                    extra = "source=$source result=${if (cnamHit) "hit" else "empty"}"
                )

                val resolution = when {
                    savedHit -> CallerDisplayResolution(fetchedName, "saved_contact", fetchedPhotoUri)
                    cnamHit -> CallerDisplayResolution(telecomName, "telecom_cnam", fetchedPhotoUri)
                    rawNumber.isNotBlank() -> CallerDisplayResolution(rawNumber, "raw_number", fetchedPhotoUri)
                    else -> CallerDisplayResolution("Unknown", "unknown_caller", null)
                }
                callDisplayResolutionCache[callKey] = resolution
                logCallerDisplayResolution(
                    event = "final",
                    callKey = callKey,
                    rawNumber = rawNumber,
                    extra = "source=$source winner=${resolution.source}"
                )
                return rawNumber to resolution.name
            }

            private fun buildUnifiedCallState(allCalls: List<Call>, source: String): UnifiedCallState {
                var active: Call? = null
                var holding: Call? = null
                var ringing: Call? = null
                var dialing: Call? = null
                var conference: Call? = null

                val liveTopLevelCalls = getLiveTopLevelCalls(allCalls)

                for (c in liveTopLevelCalls) {
                    if (c.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) {
                        conference = c
                    } else {
                        when (c.state) {
                            Call.STATE_ACTIVE -> active = c
                            Call.STATE_HOLDING -> holding = c
                            Call.STATE_RINGING -> ringing = c
                            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> dialing = c
                            else -> {}
                        }
                    }
                }

                val fallbackPrimaryCall = liveTopLevelCalls.firstOrNull()
                val primaryCall = resolvePrimaryCall(
                    active = active,
                    holding = holding,
                    ringing = ringing,
                    dialing = dialing,
                    conference = conference,
                    fallback = fallbackPrimaryCall
                )
                val secondaryCall = resolveSecondaryCall(
                    primaryCall = primaryCall,
                    active = active,
                    holding = holding,
                    ringing = ringing,
                    dialing = dialing,
                    conference = conference,
                    fallbackCalls = liveTopLevelCalls
                )
                val snapshotTimeMillis = System.currentTimeMillis()
                val snapshotElapsed = SystemClock.elapsedRealtime()
                val connectTimeMillis = resolveConnectTime(primaryCall)
                val secondaryConnectTimeMillis = resolveConnectTime(secondaryCall)
                val primaryTalkDurationMillis = resolveCallTalkDurationStrictMillis(primaryCall, snapshotElapsed)
                val secondaryTalkDurationMillis = resolveCallTalkDurationStrictMillis(secondaryCall, snapshotElapsed)
                val rawPrimaryState = uiStateLabelForCall(primaryCall)
                val primaryState = normalizeUiStateLabel(rawPrimaryState, connectTimeMillis)
                val secondaryState = uiStateLabelForCall(secondaryCall).takeIf { secondaryCall != null }
                val (primaryNumber, primaryName) = resolveCallIdentity(primaryCall, "$source:primary")
                val (secondaryNumber, secondaryName) = resolveCallIdentity(secondaryCall, "$source:secondary")
                val primaryPhotoUri = resolveCallPhotoUri(primaryCall, primaryNumber, "$source:primary")
                val primarySimLabel = resolveSimLabel(primaryCall)
                val participants = mutableListOf<CallParticipant>()
                if (primaryCall?.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true) {
                    primaryCall.children?.forEach { child ->
                        val (participantNumber, participantName) = resolveCallIdentity(child, "$source:participant")
                        participants.add(
                            CallParticipant(
                                id = stableCallId(child),
                                name = participantName,
                                number = participantNumber,
                                connectTimeMillis = child.details?.connectTimeMillis ?: 0L,
                                state = uiStateLabelForCall(child),
                                talkDurationMillis = resolveCallTalkDurationStrictMillis(child, snapshotElapsed),
                                talkTimerRunning = isCallTalkTimerRunningStrict(child)
                            )
                        )
                    }
                }

                val usedFallbackPrimary =
                    primaryCall != null &&
                        primaryCall != active &&
                        primaryCall != holding &&
                        primaryCall != ringing &&
                        primaryCall != dialing &&
                        primaryCall != conference
                if (usedFallbackPrimary) {
                    val fallbackRawState = primaryCall?.state ?: Call.STATE_NEW
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_state_primary_fallback source=$source callId=${stableCallId(primaryCall)} rawState=$fallbackRawState uiState=$primaryState liveTopLevel=${liveTopLevelCalls.size}"
                    )
                }
                if (primaryState != rawPrimaryState) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_state_promoted source=$source callId=${stableCallId(primaryCall)} rawState=$rawPrimaryState promotedState=$primaryState connectTime=$connectTimeMillis"
                    )
                }

                val currentAudioRoute = callAudioState?.route ?: CallAudioState.ROUTE_EARPIECE
                val supportedAudioRouteMask = callAudioState?.supportedRouteMask
                    ?: (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER)
                val bluetoothDeviceName = resolveBluetoothDeviceName(currentAudioRoute, supportedAudioRouteMask)
                val currentState = UnifiedCallState(
                    isInCall = primaryCall != null,
                    primaryState = primaryState,
                    primaryNumber = primaryNumber,
                    primaryName = primaryName,
                    primaryPhotoUri = primaryPhotoUri,
                    primaryCallId = stableCallId(primaryCall),
                    isHolding = primaryCall?.state == Call.STATE_HOLDING,
                    secondaryNumber = secondaryNumber.takeIf { it.isNotBlank() },
                    secondaryName = secondaryName.takeIf { it.isNotBlank() },
                    secondaryCallId = stableCallId(secondaryCall).ifBlank { null },
                    secondaryState = secondaryState,
                    secondaryConnectTimeMillis = secondaryConnectTimeMillis,
                    isConference = primaryCall?.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true,
                    isMuted = callAudioState?.isMuted == true,
                    isSpeakerOn = callAudioState?.route == CallAudioState.ROUTE_SPEAKER,
                    isSpam = checkIsSpam(primaryNumber),
                    participants = participants,
                    connectTimeMillis = connectTimeMillis,
                    unmergedCallCount = liveTopLevelCalls.count {
                        !isConferenceCall(it)
                    },
                    totalTopLevelCalls = liveTopLevelCalls.size,
                    canMergeCalls = canMergeCalls(liveTopLevelCalls),
                    currentAudioRoute = currentAudioRoute,
                    supportedAudioRouteMask = supportedAudioRouteMask,
                    bluetoothDeviceName = bluetoothDeviceName,
                    simLabel = primarySimLabel,
                    isVideoCall = primaryCall?.details?.videoState?.let { VideoProfile.isVideo(it) } == true,
                    isWifiCall = primaryCall?.details?.hasProperty(Call.Details.PROPERTY_WIFI) == true,
                    stateSnapshotTimeMillis = snapshotTimeMillis,
                    primaryTalkDurationMillis = primaryTalkDurationMillis,
                    primaryTalkTimerRunning = isCallTalkTimerRunningStrict(primaryCall),
                    secondaryTalkDurationMillis = secondaryTalkDurationMillis,
                    secondaryTalkTimerRunning = isCallTalkTimerRunningStrict(secondaryCall)
                )

                Log.w(
                    P2P_FORENSIC_TAG,
                    "call_sync_selection source=$source primaryId=${currentState.primaryCallId} primaryState=${currentState.primaryState} secondaryId=${currentState.secondaryCallId.orEmpty()} secondaryState=${currentState.secondaryState.orEmpty()} totalTopLevel=${currentState.totalTopLevelCalls} connectTime=${currentState.connectTimeMillis}"
                )
                logCallOwnershipSnapshot(
                    source = source,
                    liveTopLevelCalls = liveTopLevelCalls,
                    primaryCall = primaryCall,
                    secondaryCall = secondaryCall
                )

                if (!currentState.isInCall && liveTopLevelCalls.isNotEmpty()) {
                    Log.e(
                        P2P_FORENSIC_TAG,
                        "call_state_invariant_failure source=$source reason=no_primary_with_live_calls liveTopLevel=${liveTopLevelCalls.size}"
                    )
                }

                return currentState
            }

            private fun buildTerminalUiSnapshot(call: Call, source: String): UnifiedCallState {
                val callId = stableCallId(call)
                val previousSnapshot = lastCallUiSnapshot
                val wasPrimary = previousSnapshot?.primaryCallId == callId
                var (resolvedNumber, resolvedName) = resolveCallIdentity(call, source)
                var resolvedPhotoUri = resolveCallPhotoUri(call, resolvedNumber, "$source:photo")
                if (resolvedNumber.isBlank()) {
                    resolvedNumber = previousSnapshot?.primaryNumber.orEmpty()
                }
                if (resolvedName.isBlank() || resolvedName == "Unknown") {
                    resolvedName = previousSnapshot?.primaryName ?: resolvedNumber.ifBlank { "Unknown" }
                }
                if (resolvedPhotoUri.isNullOrBlank()) {
                    resolvedPhotoUri = previousSnapshot?.takeIf { wasPrimary }?.primaryPhotoUri
                }
                val connectTime = (call.details?.connectTimeMillis ?: 0L)
                    .takeIf { it > 0L }
                    ?: previousSnapshot?.takeIf { wasPrimary }?.connectTimeMillis
                    ?: 0L
                val disconnectTime = System.currentTimeMillis()
                val talkDurationSnapshot = resolveCallTalkDurationStrictMillis(call, SystemClock.elapsedRealtime())
                val frozenDuration = when {
                    talkDurationSnapshot > 0L -> talkDurationSnapshot
                    previousSnapshot?.takeIf { wasPrimary }?.finalDurationMillis ?: 0L > 0L -> previousSnapshot?.finalDurationMillis ?: 0L
                    connectTime > 0L -> (disconnectTime - connectTime).coerceAtLeast(0L)
                    else -> 0L
                }
                val currentAudioRoute = callAudioState?.route ?: CallAudioState.ROUTE_EARPIECE
                val supportedAudioRouteMask = callAudioState?.supportedRouteMask
                    ?: (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER)
                val bluetoothDeviceName = resolveBluetoothDeviceName(
                    currentAudioRoute = currentAudioRoute,
                    supportedAudioRouteMask = supportedAudioRouteMask,
                    fallbackName = previousSnapshot?.takeIf { wasPrimary }?.bluetoothDeviceName
                )
                val primarySimLabel = previousSnapshot?.takeIf { wasPrimary }?.simLabel.orEmpty().ifBlank {
                    resolveSimLabel(call)
                }
                val snapshot = UnifiedCallState(
                    isInCall = false,
                    primaryState = "DISCONNECTED",
                    primaryNumber = resolvedNumber,
                    primaryName = resolvedName,
                    primaryPhotoUri = resolvedPhotoUri,
                    primaryCallId = callId,
                    isHolding = false,
                    secondaryNumber = null,
                    secondaryName = null,
                    secondaryCallId = null,
                    secondaryState = null,
                    isConference = call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true,
                    isMuted = callAudioState?.isMuted == true,
                    isSpeakerOn = callAudioState?.route == CallAudioState.ROUTE_SPEAKER,
                    isSpam = checkIsSpam(resolvedNumber),
                    participants = emptyList(),
                    connectTimeMillis = connectTime,
                    isTerminal = true,
                    disconnectTimeMillis = disconnectTime,
                    finalDurationMillis = frozenDuration,
                    disconnectCauseCode = call.details?.disconnectCause?.code ?: DisconnectCause.UNKNOWN,
                    unmergedCallCount = 0,
                    totalTopLevelCalls = 0,
                    currentAudioRoute = currentAudioRoute,
                    supportedAudioRouteMask = supportedAudioRouteMask,
                    bluetoothDeviceName = bluetoothDeviceName,
                    simLabel = primarySimLabel,
                    stateSnapshotTimeMillis = disconnectTime,
                    primaryTalkDurationMillis = frozenDuration,
                    primaryTalkTimerRunning = false,
                    secondaryTalkDurationMillis = 0L,
                    secondaryTalkTimerRunning = false,
                )
                Log.w(
                    P2P_FORENSIC_TAG,
                    "call_ui_terminal action=capture source=$source callId=${snapshot.primaryCallId} disconnectCause=${snapshot.disconnectCauseCode} connectTime=${snapshot.connectTimeMillis} disconnectTime=${snapshot.disconnectTimeMillis} frozenDuration=${snapshot.finalDurationMillis}"
                )
                return snapshot
            }

            private fun captureTerminalUiSnapshot(call: Call, source: String) {
                lastTerminalUiSnapshot = buildTerminalUiSnapshot(call, source)
            }

            private fun cancelPendingDisconnectCleanup(source: String) {
                pendingDisconnectCleanup?.let {
                    bgHandler.removeCallbacks(it)
                    pendingDisconnectCleanup = null
                    pendingDisconnect = false
                    Log.w(P2P_FORENSIC_TAG, "call_cleanup action=cancel source=$source")
                }
            }

            private fun cancelTransientCallGapRecovery(source: String) {
                transientEmptyRecoveryRunnable?.let {
                    bgHandler.removeCallbacks(it)
                    transientEmptyRecoveryRunnable = null
                    Log.w(P2P_FORENSIC_TAG, "call_sync_transient_empty_recovery action=cancel source=$source")
                }
            }

            private fun scheduleTransientCallGapRecovery(source: String) {
                if (transientEmptyRecoveryRunnable != null) {
                    Log.w(P2P_FORENSIC_TAG, "call_sync_transient_empty_recovery action=dedupe source=$source")
                    return
                }
                val runnable = Runnable {
                    transientEmptyRecoveryRunnable = null
                    Log.w(P2P_FORENSIC_TAG, "call_sync_transient_empty_recovery action=execute source=$source")
                    syncCalls()
                }
                transientEmptyRecoveryRunnable = runnable
                bgHandler.postDelayed(runnable, 300L)
                Log.w(P2P_FORENSIC_TAG, "call_sync_transient_empty_recovery action=schedule source=$source delayMs=300")
            }

            private fun scheduleDisconnectCleanup(source: String) {
                if (pendingDisconnectCleanup != null) {
                    Log.w(P2P_FORENSIC_TAG, "call_cleanup action=dedupe source=$source")
                    return
                }
                pendingDisconnect = true
                val cleanupRunnable = Runnable {
                    pendingDisconnect = false
                    pendingDisconnectCleanup = null
                    val liveCalls = getLiveCalls()
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_cleanup action=execute source=$source liveCalls=${liveCalls.size} terminalCallId=${lastTerminalUiSnapshot?.primaryCallId.orEmpty()}"
                    )
                    if (liveCalls.isNotEmpty()) {
                        syncCalls()
                        return@Runnable
                    }
                    notificationReposterActive.set(false)
                    bgHandler.removeCallbacks(notificationReposter)
                    DirectBootCallManager.removeCallStateFromDirectBootStorage(this)
                    PendingCallStateStore.clear(this)
                    clearAllNotifications()
                    val intent = Intent("FINISH_CALL_ACTIVITY").apply { setPackage(packageName) }
                    sendBroadcast(intent)
                    clearLastCallUiSnapshot("disconnect_cleanup")
                    clearTerminalUiSnapshot("disconnect_cleanup")
                    callDisplayResolutionCache.clear()
                    
                    // Release WakeLock to allow normal power management
                    if (transitionWakeLock?.isHeld == true) {
                        transitionWakeLock?.release()
                        android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: PARTIAL_WAKE_LOCK released.")
                    }

                    isServiceExplicitlyStarted = false
                    setAllAppTasksExcludedFromRecents(false)

                    // Drop foreground status to allow normal process priority
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: Foreground service released. Process priority normalized.")
                    
                    stopSelf()
                }
                pendingDisconnectCleanup = cleanupRunnable
                bgHandler.postDelayed(cleanupRunnable, 350L)
                Log.w(P2P_FORENSIC_TAG, "call_cleanup action=schedule source=$source delayMs=350")
            }

            private val notificationReposter: Runnable = object : Runnable {
                override fun run() {
                    if (!notificationReposterActive.get()) return

                    // THE GHOST KILLER: Check the physical reality of the Telecom framework
                    if (getLiveCalls().isEmpty()) {
                        UltraForensicLogger.ultra("MyInCallService", "GHOST_KILLER", "Reposter detected zero framework calls.")
                        notificationReposterActive.set(false)
                        lastNotificationStateKey = null
                        syncCalls()
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "call_cleanup_deferred source=notificationReposter deferring teardown to scheduleDisconnectCleanup"
                        )
                        scheduleDisconnectCleanup("notificationReposter")
                        return
                    }

                    try {
                        val currentState = getCurrentCallStateFromSync()
                        if (currentState.isInCall && currentState.primaryNumber.isNotEmpty()) {
                            val notificationStateKey = buildNotificationStateKey(currentState)
                            val notificationManager = getSystemService(NotificationManager::class.java)
                            val isVisible = notificationManager?.activeNotifications
                                ?.any { it.id == Constants.NOTIFICATION_ID_ONGOING_CALL } == true

                            if (notificationStateKey == lastNotificationStateKey && isVisible) {
                                UltraForensicLogger.debug(
                                    "Skipped notification refresh for unchanged state=${currentState.primaryState} number=${maskPhoneNumber(currentState.primaryNumber)}"
                                )
                            } else {
                                var notificationPublished = true
                                when (currentState.primaryState) {
                                    "RINGING" -> showIncomingCallNotification(currentState.primaryNumber, currentState.primaryName)
                                    "ACTIVE", "DIALING", "HOLDING", "CONFERENCE" -> showOngoingCallNotification(currentState.primaryNumber, currentState.primaryName, currentState.isConference, currentState.primaryState)
                                    else -> {
                                        notificationPublished = false
                                        UltraForensicLogger.debug("Skipping notification refresh for unsupported state=${currentState.primaryState}")
                                    }
                                }
                                if (notificationPublished) {
                                    lastNotificationStateKey = notificationStateKey
                                    if (!isVisible) {
                                        UltraForensicLogger.ultra(
                                            "MyInCallService",
                                            "REPOSTER",
                                            "Notification was hidden. Reposter forced a resurrection."
                                        )
                                    }
                                    UltraForensicLogger.debug(
                                        "Notification refreshed for ${maskPhoneNumber(currentState.primaryNumber)} state=${currentState.primaryState}"
                                    )
                                }
                            }
                        } else {
                            notificationReposterActive.set(false)
                            lastNotificationStateKey = null
                        }
                    } catch (e: Exception) {
                        UltraForensicLogger.deepest("Notification repost failed: ${e.message}")
                        notificationReposterActive.set(false)
                    }

                    if (notificationReposterActive.get()) {
                        bgHandler.postDelayed(this, 2000L)
                    }
                }
            }

            override fun onCreate() {
                super.onCreate()
                instance = this
                logCall("InCallService.onCreate initialized")
                bgHandler = Handler(Looper.getMainLooper())

                val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext() ?: this
                } else {
                    this
                }
                val directBootPhase = isDirectBootPhase()
                Log.w(
                    P2P_FORENSIC_TAG,
                    "service_onCreate directBootPhase=$directBootPhase safeContextClass=${safeContext::class.java.simpleName}"
                )

                try {
                    UltraForensicLogger.init(safeContext)
                } catch (e: Exception) {
                    Log.e("MyInCallService", "Logger failed to init in DPS: ${e.message}", e)
                }

                try {
                    createNotificationChannels()
                    registerScreenStateReceiver()

                    // Defer foreground notification until we have real call details.
                    // This avoids briefly showing a generic placeholder before the
                    // actual outgoing contact notification is available.
                } catch (e: Exception) {
                    Log.e("MyInCallService", "Foreground notification bootstrap failed in DPS: ${e.message}", e)
                }
            }

            private fun createNotificationChannels() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val notificationManager = getSystemService(NotificationManager::class.java)

                    // Delete old unused channels
                    notificationManager?.deleteNotificationChannel("incoming_calls_quiet")
                    notificationManager?.deleteNotificationChannel("incoming_calls")
                    notificationManager?.deleteNotificationChannel("missed_calls")
                    notificationManager?.deleteNotificationChannel("call_channel")
                    notificationManager?.deleteNotificationChannel("call_channel_v2")
                    notificationManager?.deleteNotificationChannel("call_channel_v3")
                    notificationManager?.deleteNotificationChannel("call_service")
                    notificationManager?.deleteNotificationChannel("call_resume_channel")
                    notificationManager?.deleteNotificationChannel("ongoing_call_channel")
                    notificationManager?.deleteNotificationChannel("ongoing_call_channel_v2")
                    notificationManager?.deleteNotificationChannel("answered_call_banner")

                    // Incoming calls need a fresh high-importance channel so Android can
                    // choose heads-up vs full-screen presentation natively.
                    val callChannel = NotificationChannel(
                        incomingCallChannelId,
                        "Incoming Calls",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Incoming call status"
                        // NO CUSTOM SOUND OR VIBRATION overrides here.
                        // We do not want to force sound/vibration at the channel level.
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setShowBadge(false) // Prevents notification dot on app icon
                        enableVibration(false) // Prevents unwanted vibration
                        enableLights(false) // Prevents unwanted notification light
                        setBypassDnd(true) // Bypass Do Not Disturb
                        
                        // CRITICAL FIX: Removed invalid setOngoing and setDeleteChannel calls.
                        // 'Ongoing' is already correctly set in showOngoingCallNotification on Builder itself!
                    }
                    notificationManager?.createNotificationChannel(callChannel)

                    val ongoingChannel = NotificationChannel(
                        ongoingCallChannelId,
                        "Ongoing Calls",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Passive ongoing call status"
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setShowBadge(false)
                        enableVibration(false)
                        enableLights(false)
                        setSound(null, null)
                    }
                    notificationManager?.createNotificationChannel(ongoingChannel)

                    // Used once right after answering from the banner in another app: the same banner turns
                    // into the call (name, timer, Hang up) for a few seconds. Silent, no vibration.
                    val answeredBannerChannel = NotificationChannel(
                        answeredBannerChannelId,
                        "Answered call banner",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Shows the call timer as a banner right after answering"
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setShowBadge(false)
                        enableVibration(false)
                        enableLights(false)
                        setSound(null, null)
                    }
                    notificationManager?.createNotificationChannel(answeredBannerChannel)
                }
            }

            /** startForeground for the ongoing-call notification (phone-call type). */
            private fun startCallForeground(notification: android.app.Notification) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    startForeground(Constants.NOTIFICATION_ID_ONGOING_CALL, notification)
                    return
                }
                startForeground(Constants.NOTIFICATION_ID_ONGOING_CALL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            }

            private fun startImmediateCallForeground(phoneNumber: String, contactName: String, isConf: Boolean, callState: String = "ACTIVE") {
                val notification = buildImmediateCallNotification(phoneNumber, contactName, isConf, callState)
                try {
                    startCallForeground(notification)
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to start immediate foreground service: ${e.message}")
                    getSystemService(NotificationManager::class.java).notify(Constants.NOTIFICATION_ID_ONGOING_CALL, notification)
                }
            }

            private fun registerScreenStateReceiver() {
                if (screenStateReceiver != null) return
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.action) {
                            Intent.ACTION_SCREEN_OFF -> {
                                lastScreenOffAtElapsedMs = SystemClock.elapsedRealtime()
                                Log.w(
                                    P2P_FORENSIC_TAG,
                                    "power_end_probe action=screen_off activeCalls=${getLiveCalls().size} lastScreenOffAtElapsedMs=$lastScreenOffAtElapsedMs"
                                )
                            }
                            Intent.ACTION_SCREEN_ON -> {
                                Log.w(
                                    P2P_FORENSIC_TAG,
                                    "power_end_probe action=screen_on activeCalls=${getLiveCalls().size} sinceScreenOffMs=${if (lastScreenOffAtElapsedMs > 0L) SystemClock.elapsedRealtime() - lastScreenOffAtElapsedMs else -1L}"
                                )
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                registerReceiver(receiver, filter)
                screenStateReceiver = receiver
            }

            private fun unregisterScreenStateReceiver() {
                val receiver = screenStateReceiver ?: return
                try {
                    unregisterReceiver(receiver)
                } catch (_: Exception) {
                } finally {
                    screenStateReceiver = null
                }
            }

            private fun maybeShowPowerEndRecoveryNotification(
                call: Call,
                callId: String,
                direction: String,
                wasAnswered: Boolean
            ) {
                val phoneNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
                val nowElapsed = SystemClock.elapsedRealtime()
                val sinceScreenOffMs = if (lastScreenOffAtElapsedMs > 0L) nowElapsed - lastScreenOffAtElapsedMs else Long.MAX_VALUE
                val sinceAppDisconnectMs =
                    if (lastAppInitiatedDisconnectAtElapsedMs > 0L) {
                        nowElapsed - lastAppInitiatedDisconnectAtElapsedMs
                    } else {
                        Long.MAX_VALUE
                    }
                val eligibleCall = phoneNumber.isNotBlank() && (wasAnswered || direction == "OUTGOING")
                val screenOffRecently = sinceScreenOffMs in 0L..1800L
                val appInitiatedDisconnectRecently = sinceAppDisconnectMs in 0L..2500L
                val alreadyPrompted = lastPowerEndRecoveryCallId == callId

                Log.w(
                    P2P_FORENSIC_TAG,
                    "power_end_recovery_decision source=MyInCallService.onStateChanged callId=$callId direction=$direction wasAnswered=$wasAnswered eligibleCall=$eligibleCall screenOffRecently=$screenOffRecently sinceScreenOffMs=${if (sinceScreenOffMs == Long.MAX_VALUE) -1L else sinceScreenOffMs} appInitiatedDisconnectRecently=$appInitiatedDisconnectRecently sinceAppDisconnectMs=${if (sinceAppDisconnectMs == Long.MAX_VALUE) -1L else sinceAppDisconnectMs} alreadyPrompted=$alreadyPrompted"
                )

                if (!eligibleCall || !screenOffRecently || appInitiatedDisconnectRecently || alreadyPrompted) {
                    return
                }

                lastPowerEndRecoveryCallId = callId
                val (_, resolvedName) = resolveCallIdentity(call, "power_end_recovery")
                val displayName = resolvedName.ifBlank { phoneNumber }
                val recoveryIntent = Intent(this, MainActivity::class.java).apply {
                    action = POWER_END_RECOVERY_ACTION
                    putExtra(POWER_END_RECOVERY_PHONE_EXTRA, phoneNumber)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val recoveryPendingIntent = PendingIntent.getActivity(
                    this,
                    4104,
                    recoveryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(this, ongoingCallChannelId)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle("Call ended unexpectedly")
                    .setContentText("Tap to call $displayName again")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(recoveryPendingIntent)
                    .addAction(android.R.drawable.ic_menu_call, "Redial", recoveryPendingIntent)
                    .build()
                getSystemService(NotificationManager::class.java)
                    ?.notify(Constants.NOTIFICATION_ID_POWER_END_RECOVERY, notification)
                Log.w(
                    P2P_FORENSIC_TAG,
                    "power_end_recovery_notification action=show callId=$callId number=${maskP2PNumberForLog(phoneNumber)} display=${maskP2PDisplayValueForLog(displayName)}"
                )
            }

            private fun silenceMainActivityRingtone() {
                sendBroadcast(
                    Intent("SILENCE_RINGTONE").apply {
                        setPackage(packageName)
                    }
                )
            }

            private fun getCustomRingtoneUriForNumber(phoneNumber: String): Uri? {
                if (phoneNumber.isBlank()) return null
                return try {
                    val lookupUri = Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(phoneNumber)
                    )
                    contentResolver.query(
                        lookupUri,
                        arrayOf(ContactsContract.PhoneLookup.CUSTOM_RINGTONE),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val customRingtoneStr = cursor.getString(0)
                            if (!customRingtoneStr.isNullOrBlank()) {
                                Uri.parse(customRingtoneStr)
                            } else null
                        } else null
                    }
                } catch (e: Exception) {
                    Log.e("CMF_RINGTONE_DEBUG", "Failed to query custom contact ringtone: ${e.message}")
                    null
                }
            }

            private fun playIncomingRingtoneIfNeeded(
                callKey: String,
                phoneNumber: String = "",
                surface: String = ""
            ) {
                val normalizedNumber = buildP2PNumberSnapshot(phoneNumber).normalized.ifBlank { phoneNumber }
                val customAlias = CallerAnnouncementManager.getAlias(this, normalizedNumber)
                if (customAlias.isNotBlank()) {
                    CallerAnnouncementManager.announce(this, customAlias)
                }

                Log.e("CMF_RINGTONE_DEBUG", "=== RINGTONE PLAYBACK START ===")
                Log.e("CMF_RINGTONE_DEBUG", "Device: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}")
                Log.e("CMF_RINGTONE_DEBUG", "OS: ${Build.VERSION.RELEASE} SDK: ${Build.VERSION.SDK_INT}")
                Log.e("CMF_RINGTONE_DEBUG", "callKey=$callKey phoneNumber=${maskP2PNumberForLog(phoneNumber)} surface=$surface")

                if (activeIncomingRingtoneKey != callKey) {
                    stopIncomingRingtone("incoming_key_changed", resetCurrentCall = false)
                    activeIncomingRingtoneKey = callKey
                    incomingRingtoneSilencedForCurrentCall = false
                }

                if (incomingRingtoneSilencedForCurrentCall) {
                    Log.e("CMF_RINGTONE_DEBUG", "BLOCKED: user_silenced")
                    return
                }
                // incomingRingtone counts while non-null (even if not yet reported playing),
                // so the notification reposter doesn't start a second ringtone during verification.
                if (incomingRingtone != null) {
                    Log.e("CMF_RINGTONE_DEBUG", "BLOCKED: already_playing")
                    return
                }

                val notificationManager = getSystemService(NotificationManager::class.java)
                val interruptionFilter =
                    notificationManager?.currentInterruptionFilter
                        ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                val notificationPolicyAccessGranted =
                    notificationManager?.isNotificationPolicyAccessGranted == true
                val callsAllowedInPriorityMode =
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        notificationPolicyAccessGranted &&
                        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    ) {
                        val priorityCategories = notificationManager.notificationPolicy?.priorityCategories ?: 0
                        (priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) != 0
                    } else {
                        null
                    }
                val shouldBlockForInterruptionFilter = when (interruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_NONE,
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                        notificationPolicyAccessGranted && callsAllowedInPriorityMode == false
                    else -> false
                }
                Log.e("CMF_RINGTONE_DEBUG", "Interruption filter: $interruptionFilter (block=$shouldBlockForInterruptionFilter)")

                if (shouldBlockForInterruptionFilter) {
                    Log.e("CMF_RINGTONE_DEBUG", "BLOCKED: interruption_filter mode")
                    return
                }

                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val ringerMode = audioManager?.ringerMode
                Log.e("CMF_RINGTONE_DEBUG", "Ringer mode: $ringerMode (NORMAL=${AudioManager.RINGER_MODE_NORMAL})")

                if (ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    Log.e("CMF_RINGTONE_DEBUG", "BLOCKED: ringer_mode not NORMAL ($ringerMode)")
                    return
                }

                val customRingtoneUri = getCustomRingtoneUriForNumber(phoneNumber)
                val defaultRingtoneUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)

                if (customRingtoneUri == null && defaultRingtoneUri == null) {
                    Log.e("CMF_RINGTONE_DEBUG", "BLOCKED: no ringtone set (silent / none set in settings)")
                    return
                }

                // Ringtone usage → plays on the ring stream, so it follows the phone's ring volume.
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val candidates = LinkedHashSet<Uri>()
                if (customRingtoneUri != null) {
                    candidates.add(customRingtoneUri)
                    Log.e("CMF_RINGTONE_DEBUG", "Primary candidate: custom contact ringtone $customRingtoneUri")
                } else if (defaultRingtoneUri != null) {
                    candidates.add(defaultRingtoneUri)
                    Log.e("CMF_RINGTONE_DEBUG", "Primary candidate: actual default system ringtone $defaultRingtoneUri")
                }

                // Append fallback URIs only for backup in case primary cannot be played
                candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                findInternalRingtoneUri()?.let(candidates::add)

                // Playback goes through android.media.Ringtone, which hands off to the system's ringtone
                // player when the app can't open the file itself (e.g. a user ringtone on external storage,
                // since the app holds no READ_MEDIA_AUDIO). If the user's tone isn't playing shortly after,
                // the next candidate (ending with a built-in internal tone) is tried.
                if (!startSystemRingtone(candidates.toList(), attributes)) {
                    Log.e("CMF_RINGTONE_DEBUG", "EXCEPTION: no playable ringtone URI among $candidates")
                }
            }

            private fun startSystemRingtone(uris: List<Uri>, attributes: AudioAttributes): Boolean {
                val uri = uris.firstOrNull() ?: return false
                val backupUris = uris.drop(1)
                val ringtone = try {
                    RingtoneManager.getRingtone(this, uri)
                } catch (e: Exception) {
                    Log.e("CMF_RINGTONE_DEBUG", "System ringtone unavailable: $uri (${e.message})")
                    null
                } ?: return startSystemRingtone(backupUris, attributes)
                return try {
                    ringtone.audioAttributes = attributes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = true
                    }
                    ringtone.play()
                    incomingRingtone = ringtone
                    Log.e("CMF_RINGTONE_DEBUG", "System Ringtone.play() requested uri=$uri — verifying")
                    // Remote playback can fail silently; if it isn't playing shortly after, use the backup.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (incomingRingtone !== ringtone) return@postDelayed
                        if (ringtone.isPlaying) {
                            Log.e("CMF_RINGTONE_DEBUG", "System Ringtone verified playing uri=$uri")
                            return@postDelayed
                        }
                        Log.e("CMF_RINGTONE_DEBUG", "System Ringtone not playing uri=$uri — trying next")
                        try { ringtone.stop() } catch (_: Exception) {}
                        incomingRingtone = null
                        if (!startSystemRingtone(backupUris, attributes)) {
                            Log.e("CMF_RINGTONE_DEBUG", "EXCEPTION: backup ringtone also failed")
                        }
                    }, SYSTEM_RINGTONE_VERIFY_DELAY_MS)
                    true
                } catch (e: Exception) {
                    Log.e("CMF_RINGTONE_DEBUG", "System Ringtone failed: $uri (${e.message})")
                    try { ringtone.stop() } catch (_: Exception) {}
                    startSystemRingtone(backupUris, attributes)
                }
            }

            private fun findInternalRingtoneUri(): Uri? {
                return try {
                    contentResolver.query(
                        MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Audio.Media._ID),
                        "${MediaStore.Audio.Media.IS_RINGTONE} = 1",
                        null,
                        MediaStore.Audio.Media.TITLE
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            ContentUris.withAppendedId(MediaStore.Audio.Media.INTERNAL_CONTENT_URI, cursor.getLong(0))
                        } else {
                            null
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CMF_RINGTONE_DEBUG", "Internal ringtone lookup failed: ${e.message}")
                    null
                }
            }

            private fun stopIncomingRingtone(
                reason: String,
                resetCurrentCall: Boolean = false,
                markSilenced: Boolean = false
            ) {
                CallerAnnouncementManager.stop()
                if (markSilenced && activeIncomingRingtoneKey != null) {
                    incomingRingtoneSilencedForCurrentCall = true
                }
                try {
                    incomingRingtone?.stop()
                } catch (_: Exception) {
                } finally {
                    incomingRingtone = null
                }
                if (resetCurrentCall) {
                    activeIncomingRingtoneKey = null
                    incomingRingtoneSilencedForCurrentCall = false
                }
                Log.w(
                    P2P_FORENSIC_TAG,
                    "incoming_ringtone action=stop reason=$reason resetCurrentCall=$resetCurrentCall silenced=$incomingRingtoneSilencedForCurrentCall"
                )
            }

            override fun onUnbind(intent: Intent?): Boolean {
                android.util.Log.e("OEM_FALLBACK_TRACE", "=====================================================================")
        android.util.Log.e("OEM_FALLBACK_TRACE", "ULTRA SUPREME FORENSIC INFLOP PROFESSION LOGGING")
                android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: onUnbind TRIGGERED!")
                android.util.Log.e("OEM_FALLBACK_TRACE", "TelecomManager is forcibly detaching our Call Service.")
                android.util.Log.e("OEM_FALLBACK_TRACE", "If a call is still active, THIS IS THE EXACT MOMENT THE OEM DIALER STRIKES.")
                android.util.Log.e("OEM_FALLBACK_TRACE", "Intent Action: ${intent?.action}")
                android.util.Log.e("OEM_FALLBACK_TRACE", "Stack Trace of Unbind:", Throwable("UNBIND TRACE"))
                android.util.Log.e("OEM_FALLBACK_TRACE", "=====================================================================")
                return super.onUnbind(intent)
            }

            override fun onTaskRemoved(rootIntent: Intent?) {
                super.onTaskRemoved(rootIntent)
                val liveCalls = getLiveCalls()
                Log.w(
                    P2P_FORENSIC_TAG,
                    "call_task_removed liveCalls=${liveCalls.size} terminationSuppressed=$bubbleSuppressedForTermination rootIntent=${rootIntent?.component}"
                )
                if (liveCalls.isEmpty()) return

                // Re-assert WakeLock, Recents exclusion, and started Foreground Service state immediately
                if (transitionWakeLock?.isHeld == false) {
                    transitionWakeLock?.acquire(4 * 60 * 60 * 1000L)
                }
                setAllAppTasksExcludedFromRecents(true)
                isServiceExplicitlyStarted = false
                ensureServiceExplicitlyStarted(liveCalls.firstOrNull())

                val currentState = runCatching { getCurrentCallStateFromSync() }.getOrNull()
                if (currentState != null && currentState.isInCall) {
                    if (currentState.primaryState == "RINGING") {
                        showIncomingCallNotification(currentState.primaryNumber, currentState.primaryName)
                    } else {
                        showOngoingCallNotification(
                            currentState.primaryNumber,
                            currentState.primaryName,
                            currentState.isConference,
                            currentState.primaryState.ifBlank { "ACTIVE" }
                        )
                    }
                }

                // Keep our floating call pill attached (a visible TYPE_APPLICATION_OVERLAY window
                // forces VISIBLE_APP_ADJ priority on MIUI/HyperOS and ColorOS).
                CallBubbleOverlay.isCallActivityVisible = false
                startNotificationRepost(forceRefresh = true)
                syncCalls()
                showCallBubbleIfNeeded(launchFullScreenWhenOverlayUnavailable = false)
            }

            override fun onDestroy() {
                logCall("InCallService.onDestroy called. Service is dying.")
                android.util.Log.e("OEM_FALLBACK_TRACE", "=====================================================================")
        android.util.Log.e("OEM_FALLBACK_TRACE", "ULTRA SUPREME FORENSIC INFLOP PROFESSION LOGGING")
                android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: onDestroy TRIGGERED!")
                android.util.Log.e("OEM_FALLBACK_TRACE", "Our Service process is being MURDERED by Ultra Battery Saver mode.")
                android.util.Log.e("OEM_FALLBACK_TRACE", "Because we died, TelecomManager will revive the OEM Dialer as a fail-safe.")
                android.util.Log.e("OEM_FALLBACK_TRACE", "=====================================================================")
                
                // COMPREHENSIVE CLEANUP AND OEM FALLBACK DETECTION
                logOemDialerFallbackServiceEvent("onDestroy")

                
                // ROOT CAUSE CLEANUP: Release all wake locks and resources
                InCallServiceEnhancements.releaseProcessWakeLock()
                transitionWakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                        android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: Transition wake lock released")
                    }
                }
                transitionWakeLock = null
                
                // Stop ringtone and cleanup audio resources
                try {
                    incomingRingtone?.stop()
                } catch (_: Exception) {}
                incomingRingtone = null

                // Clear all cached state
                clearLastCallUiSnapshot("onDestroy")
                clearTerminalUiSnapshot("onDestroy")
                callDirectionTracker.clear()
                callAnswerTracker.clear()
                callMachineCallbackTracker.clear()
                callTimerStates.clear()
                callTimerKeyAliases.clear()
                callDisplayResolutionCache.clear()
                missedNotificationTracker.clear()
                p2pCallSendTracker.clear()
                
                // Cancel all pending operations
                bgHandler.removeCallbacksAndMessages(null)
                notificationReposterActive.set(false)
                lastNotificationStateKey = null
                
                // Clear listeners
                callStateListener = null
                callUIListener = null
                
                isServiceExplicitlyStarted = false
                setAllAppTasksExcludedFromRecents(false)
                super.onDestroy()
                instance = null
                unregisterScreenStateReceiver()
                bgHandler.removeCallbacks(notificationReposter)
                transientEmptyRecoveryRunnable?.let { bgHandler.removeCallbacks(it) }
                transientEmptyRecoveryRunnable = null
                callDisplayResolutionCache.clear()
            }

            fun setCallAudioRoute(route: Int) {
                // This calls the native InCallService.setAudioRoute() method safely
                super.setAudioRoute(route)
            }

            fun boostCallClarity(): String {
                try {
                    val audioState = callAudioState
                    val supportedRouteMask = audioState?.supportedRouteMask
                        ?: (CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER)
                    val currentRoute = audioState?.route ?: CallAudioState.ROUTE_EARPIECE
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

                    val actions = mutableListOf<String>()

                    if (audioState?.isMuted == true) {
                        runCatching { setMuted(false) }
                            .onSuccess { actions += "mic unmuted" }
                    }

                    val targetRoute = when {
                        currentRoute == CallAudioState.ROUTE_BLUETOOTH -> currentRoute
                        supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
                        supportedRouteMask and CallAudioState.ROUTE_EARPIECE != 0 -> CallAudioState.ROUTE_EARPIECE
                        else -> currentRoute
                    }
                    if (targetRoute != currentRoute) {
                        super.setAudioRoute(targetRoute)
                        actions += when (targetRoute) {
                            CallAudioState.ROUTE_WIRED_HEADSET -> "headset route"
                            CallAudioState.ROUTE_EARPIECE -> "earpiece route"
                            else -> "audio route"
                        }
                    }

                    audioManager?.let { manager ->
                        val maxVoice = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                        val currentVoice = manager.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                        val targetVoice = when {
                            maxVoice <= 1 -> maxVoice
                            maxVoice >= 6 -> maxVoice - 1
                            else -> maxVoice
                        }
                        if (targetVoice > currentVoice) {
                            manager.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, targetVoice, 0)
                            actions += "voice volume boosted"
                        }
                    }

                    syncCalls()
                    val message = if (actions.isEmpty()) {
                        "Audio already optimized"
                    } else {
                        "Clarity boosted: ${actions.joinToString(", ")}"
                    }
                    UltraForensicLogger.info("CallAudio", "CLARITY_BOOST", message)
                    return message
                } catch (e: Exception) {
                    UltraForensicLogger.debug("CallAudio", "CLARITY_BOOST_FAILED", e.message ?: "unknown")
                    return "Unable to boost call audio"
                }
            }

            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                when (intent?.action) {
                    ACTION_START_INCALL_KEEPALIVE, null -> {
                        isServiceExplicitlyStarted = true
                        val liveCalls = getLiveCalls()
                        if (liveCalls.isNotEmpty()) {
                            setAllAppTasksExcludedFromRecents(true)
                            val currentState = getCurrentCallStateFromSync()
                            if (currentState.primaryState == "RINGING") {
                                showIncomingCallNotification(currentState.primaryNumber, currentState.primaryName)
                            } else {
                                showOngoingCallNotification(
                                    currentState.primaryNumber,
                                    currentState.primaryName,
                                    currentState.isConference,
                                    currentState.primaryState.ifBlank { "ACTIVE" }
                                )
                            }
                            startNotificationRepost(forceRefresh = true)
                            return START_STICKY
                        } else {
                            runCatching {
                                startCallForeground(
                                    buildImmediateCallNotification("", "Call ended", false, "DISCONNECTED")
                                )
                                stopForeground(STOP_FOREGROUND_REMOVE)
                            }
                            isServiceExplicitlyStarted = false
                            setAllAppTasksExcludedFromRecents(false)
                            stopSelfResult(startId)
                            return START_NOT_STICKY
                        }
                    }
                    Constants.ACTION_ANSWER_CALL -> {
                        val shouldOpenUiAfterAnswer = intent?.getBooleanExtra(OPEN_UI_AFTER_ANSWER_EXTRA, false) == true
                        val rawNumberHint = intent?.getStringExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT).orEmpty()
                        val initialNameHint = intent?.getStringExtra(CALL_UI_EXTRA_CONTACT_NAME_HINT).orEmpty()
                        val isConferenceHint = intent?.getBooleanExtra(CALL_UI_EXTRA_IS_CONFERENCE_HINT, false) == true
                        stopIncomingRingtone("answerCall", resetCurrentCall = true)
                        if (!shouldOpenUiAfterAnswer) {
                            answeredFromBannerPopPending = true
                            val title = initialNameHint.takeIf { it.isNotBlank() && it != "Unknown" } ?: rawNumberHint
                            CallBubbleOverlay.show(
                                context = this,
                                title = title,
                                connectedAtMillis = 0L,
                                onOpen = {
                                    launchCallUI(
                                        reason = "call_bubble_tap",
                                        rawNumber = rawNumberHint,
                                        initialStateHint = "ACTIVE",
                                        initialNameHint = initialNameHint,
                                        isConferenceHint = isConferenceHint
                                    )
                                },
                                onHangUp = { disconnectCall() }
                            )
                        }
                        answerCall()
                        if (shouldOpenUiAfterAnswer) {
                            Log.w(
                                P2P_FORENSIC_TAG,
                                "incoming_notification_answer action=launch_ui raw=${maskP2PNumberForLog(rawNumberHint)}"
                            )
                            launchCallUI(
                                reason = "notification_answer",
                                rawNumber = rawNumberHint,
                                initialStateHint = "ACTIVE",
                                initialNameHint = initialNameHint,
                                isConferenceHint = isConferenceHint
                            )
                        }
                    }
                    Constants.ACTION_REJECT_CALL -> {
                        stopIncomingRingtone("rejectCall", resetCurrentCall = true)
                        rejectCall()
                    }
                    ACTION_DISCONNECT_CALL -> disconnectCall()
                    ACTION_REPOST_CALL_NOTIFICATION -> if (getLiveCalls().isNotEmpty()) startNotificationRepost(forceRefresh = true)
                    "RESTORE_CALL_STATE" -> {
                        val contactName = intent?.getStringExtra("CONTACT_NAME") ?: "Unknown"
                        val phoneNumber = intent?.getStringExtra("PHONE_NUMBER") ?: ""
                        if (phoneNumber.isNotEmpty()) {
                            showOngoingCallNotification(phoneNumber, contactName, false, "ACTIVE")
                        }
                        return START_STICKY
                    }
                    Constants.ACTION_REPOST_NOTIFICATION -> {
                        val phoneNumber = intent?.getStringExtra("PHONE_NUMBER") ?: ""
                        if (phoneNumber.isNotEmpty()) {
                            UltraForensicLogger.debug("Handling repost notification request for phone number: ${maskPhoneNumber(phoneNumber)}")
                            startNotificationRepost(forceRefresh = true)
                            syncCalls()
                        }
                        return START_STICKY
                    }
                    Constants.ACTION_STOP_FOREGROUND_SERVICE -> {
                        com.caller.logging.UltraForensicLogger.forensic(
                            "MyInCallService",
                            "NOTIFICATION_DISMISS_IGNORED",
                            "Notification action received, but live call must not be ended from notification dismissal"
                        )
                        val currentState = runCatching { getCurrentCallStateFromSync() }.getOrNull()
                        if (currentState?.isInCall == true && currentState.primaryNumber.isNotEmpty()) {
                            showOngoingCallNotification(
                                currentState.primaryNumber,
                                currentState.primaryName,
                                currentState.isConference,
                                currentState.primaryState
                            )
                            return START_STICKY
                        }
                        stopNotificationRepost()
                        clearAllNotifications()
                        stopForeground(true)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    else -> return START_STICKY
                }
                return START_STICKY
            }

            fun forceUpdateUI() {
                Log.w("P2P_FORENSIC", "=== forceUpdateUI CALLED ===")
                Log.w("P2P_FORENSIC", "MyInCallService instance: ${this@MyInCallService}")
                syncCalls()
                Log.w("P2P_FORENSIC", "=== forceUpdateUI COMPLETED ===")
            }

            /**
             * A P2P name was just saved. resolveCallIdentity() keeps each call's result until the call ends, so
             * a call resolved before the name arrived would keep showing the bare number. Drop those results
             * (contact-name results stay) so the next sync reads the new name.
             */
            fun refreshCallerIdentityAfterP2P() {
                val dropped = callDisplayResolutionCache.entries.removeIf { it.value.source != "saved_contact" }
                Log.w(P2P_FORENSIC_TAG, "caller_display_p2p_refresh droppedStaleResults=$dropped")
                forceUpdateUI()
            }

            fun getPrimaryVideoCall(): VideoCall? {
                val liveCalls = getLiveCalls()
                val primaryCall = liveCalls.firstOrNull() ?: return null
                return primaryCall.videoCall
            }

            fun toggleVideoCall(enableVideo: Boolean) {
                val liveCalls = getLiveCalls()
                val primaryCall = liveCalls.firstOrNull() ?: return
                val videoCall = primaryCall.videoCall
                if (videoCall != null) {
                    val targetState = if (enableVideo) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
                    videoCall.sendSessionModifyRequest(VideoProfile(targetState))
                    Log.w(P2P_FORENSIC_TAG, "video_call_toggle enable=$enableVideo targetState=$targetState")
                } else {
                    Log.w(P2P_FORENSIC_TAG, "video_call_toggle failed reason=no_video_call_object")
                }
            }

            /**
             * Shows the compact in-call surface after the full call UI has been dismissed, or while an
             * incoming call is presented over another app.  The latter must never promote itself to a
             * full-screen activity when Xiaomi/Redmi denies an overlay: HyperOS may otherwise surface
             * the activity above the app the user is using.
             */
            fun showCallBubbleIfNeeded(launchFullScreenWhenOverlayUnavailable: Boolean = true) {
                if (!isOngoingCallForBubble()) {
                    Log.d(P2P_FORENSIC_TAG, "call_bubble_skip reason=no_ongoing_call_or_termination")
                    return
                }
                val liveCalls = getLiveCalls()
                val snapshot = lastCallUiSnapshot ?: getCurrentCallStateFromSync()
                val hasCall = snapshot.isInCall || liveCalls.isNotEmpty() || hasActiveCall()
                if (!hasCall) return
                val primaryCall = liveCalls.firstOrNull()
                val number = snapshot.primaryNumber.ifBlank {
                    primaryCall?.details?.handle?.schemeSpecificPart.orEmpty()
                }
                if (number.isBlank() && primaryCall == null) return
                val (_, resolvedName) = resolveCallIdentity(primaryCall, "bubble_show")
                val title = resolvedName.takeIf { it.isNotBlank() && it != "Unknown" && it != number }
                    ?: snapshot.primaryName.takeIf { it.isNotBlank() && it != "Unknown" && it != number }
                    ?: number.ifBlank { "Ongoing call" }
                if (CallBubbleOverlay.canShow(this)) {
                    CallBubbleOverlay.show(
                        context = this,
                        title = title,
                        connectedAtMillis = snapshot.connectTimeMillis,
                        statusText = snapshot.primaryState.ifBlank { "ACTIVE" },
                        onOpen = {
                            launchCallUI(
                                reason = "call_bubble_tap",
                                rawNumber = number,
                                initialStateHint = snapshot.primaryState.ifBlank { "ACTIVE" },
                                initialNameHint = title,
                                isConferenceHint = snapshot.isConference
                            )
                        },
                        onHangUp = { disconnectCall() }
                    )
                } else {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_bubble_skip reason=overlay_permission_missing fallbackFullScreen=$launchFullScreenWhenOverlayUnavailable"
                    )
                    if (launchFullScreenWhenOverlayUnavailable) {
                        launchCallUI(
                            reason = "call_bubble_fallback",
                            rawNumber = number,
                            initialStateHint = snapshot.primaryState.ifBlank { "ACTIVE" },
                            initialNameHint = title,
                            isConferenceHint = snapshot.isConference
                        )
                    }
                }
            }

            fun getActivePrimaryNumberForP2P(): String {
                return try {
                    getCurrentCallStateFromSync().primaryNumber
                } catch (_: Exception) {
                    ""
                }
            }

            private fun maybeTriggerP2PExchange(call: Call, callId: String, trigger: String) {
                val direction = callDirectionTracker[callId] ?: "UNKNOWN"
                val remoteNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
                val numberSnapshot = buildP2PNumberSnapshot(remoteNumber)
                logP2PNumberTrace("MyInCallService.$trigger.$direction", numberSnapshot)

                val messageType = when (direction) {
                    "OUTGOING" -> P2PMessageType.CALLER
                    "INCOMING" -> P2PMessageType.REPLY
                    else -> null
                }

                if (messageType == null) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "exchange_skip trigger=$trigger callId=$callId reason=unknown_direction direction=$direction"
                    )
                    return
                }

                if (numberSnapshot.lookupKeys.isEmpty()) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "exchange_skip trigger=$trigger callId=$callId direction=$direction reason=blank_number raw=${maskP2PNumberForLog(remoteNumber)}"
                    )
                    return
                }

                val trackerKey = "$callId:${messageType.name}"
                if (!p2pCallSendTracker.add(trackerKey)) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "exchange_dedupe_blocked trigger=$trigger callId=$callId direction=$direction trackerKey=$trackerKey"
                    )
                    return
                }

                // Server first (once per call); the silent SMS below stays as the backup.
                requestP2PServerCallerName(this, remoteNumber)

                val reverseTargetKey = if (messageType == P2PMessageType.REPLY) {
                    "replyTarget:${numberSnapshot.normalized.ifBlank { numberSnapshot.last10 }}"
                } else {
                    null
                }

                if (reverseTargetKey != null) {
                    val suppressed = P2PSendDedupeRegistry.shouldSuppress(reverseTargetKey, 30_000L)
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "reverse_exchange_decision trigger=$trigger callId=$callId targetRaw=${maskP2PNumberForLog(numberSnapshot.raw)} targetNormalized=${maskP2PNumberForLog(numberSnapshot.normalized)} targetLast10=${maskP2PNumberForLog(numberSnapshot.last10)} dedupeKey=${maskP2PDedupeKeyForLog(reverseTargetKey)} suppressed=$suppressed"
                    )
                    if (suppressed) {
                        return
                    }
                }

                val sendResult = sendP2PProfileMessage(
                    context = this,
                    destinationNumberRaw = remoteNumber,
                    messageType = messageType,
                    trigger = "MyInCallService.$trigger.$direction"
                )
                Log.w(
                    P2P_FORENSIC_TAG,
                    "exchange_send_result trigger=$trigger callId=$callId direction=$direction type=${messageType.name} requested=${sendResult.requested} blockedReason=${sendResult.blockedReason} retryable=${sendResult.retryable} payload=${sendResult.payload?.let(::maskP2PPayloadForLog)}"
                )
            }

            private fun clearP2PExchangeState(callId: String, phoneNumber: String) {
                p2pCallSendTracker.remove("$callId:${P2PMessageType.CALLER.name}")
                p2pCallSendTracker.remove("$callId:${P2PMessageType.REPLY.name}")

                val numberSnapshot = buildP2PNumberSnapshot(phoneNumber)
                val reverseTargetKey = "replyTarget:${numberSnapshot.normalized.ifBlank { numberSnapshot.last10 }}"
                P2PSendDedupeRegistry.clear(reverseTargetKey)
                Log.w(
                    P2P_FORENSIC_TAG,
                    "exchange_clear callId=$callId phoneRaw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)} reverseKey=${maskP2PDedupeKeyForLog(reverseTargetKey)}"
                )
            }

            private fun checkIsSpam(phoneNumber: String): Boolean {
                val cleanNum = phoneNumber.filter { it.isDigit() }
                if (cleanNum == String(Base64.decode("MTk4", 0))) return false
                if (cleanNum == String(Base64.decode("MTkxNQ==", 0))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDExNDAwMA==", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMzYyODY=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDEyMzQ=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDIxMDA=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDExMjIxMQ==", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTM4MDA=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDExMTEwOQ==", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("OTQ0OTExMjIxMQ==", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDE2MDA=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2MDA=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwODA=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDIwOTU1Nzc=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMzU1Nzc=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTg2MDQxOTU1NTU=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTg2MDUwMDU1NTU=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDU3MDA=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMjc3ODg=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDIyMjI0NA==", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDIwODIyNDQ=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTE1MTU=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTM1NTU=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTI0MDc=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTAwMDA=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTg2MDI2Nzc3Nzc=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2NjA0NDc=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2NjA2MTY=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMjMzMzM=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2NzM0NTY=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDEyMTIyMDg=", 0)))) return false
                if (cleanNum.contains(String(Base64.decode("MTgwMDIyMTkxMQ==", 0)))) return false

                try {
                    val safeContext = createDeviceProtectedStorageContext()
                    val blocklistPrefs = safeContext.getSharedPreferences("local_spam_blocklist", Context.MODE_PRIVATE)
                    return blocklistPrefs.getBoolean(cleanNum, false) ||
                            (cleanNum.length >= 10 && blocklistPrefs.getBoolean(cleanNum.takeLast(10), false))
                } catch (_: Exception) {
                    return false
                }
            }

            private fun startNotificationRepost() {
                notificationReposterActive.set(true)
                bgHandler.removeCallbacks(notificationReposter)
                bgHandler.post(notificationReposter)
                UltraForensicLogger.debug("Scheduled notification refresh")
            }

            private fun startNotificationRepost(forceRefresh: Boolean) {
                if (forceRefresh) {
                    lastNotificationStateKey = null
                }
                startNotificationRepost()
            }

            private fun stopNotificationRepost() {
                notificationReposterActive.set(false)
                lastNotificationStateKey = null
                bgHandler.removeCallbacks(notificationReposter)
                UltraForensicLogger.debug("Stopped notification reposting")
            }

            private fun buildNotificationStateKey(state: UnifiedCallState): String {
                return listOf(
                    state.primaryCallId,
                    state.primaryState,
                    state.primaryNumber,
                    state.primaryName,
                    state.isConference.toString()
                ).joinToString("|")
            }

            private fun getCurrentCallStateFromSync(): UnifiedCallState {
                return buildUnifiedCallState(getSafeCalls(), "getCurrentCallStateFromSync")
            }

            private fun syncCalls() {
                val allCalls = getSafeCalls()
                val currentState = buildUnifiedCallState(allCalls, "syncCalls")
                val now = System.currentTimeMillis()
                val liveCallCount = allCalls.count {
                    it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING
                }
                val stickyLiveSnapshot = lastCallUiSnapshot?.takeIf { it.isInCall }
                val stickyAgeMs = if (lastLiveCallSeenAtMs > 0L) now - lastLiveCallSeenAtMs else Long.MAX_VALUE
                val transientEmptyGap =
                    !currentState.isInCall &&
                        lastTerminalUiSnapshot == null &&
                        !pendingDisconnect &&
                        stickyLiveSnapshot != null &&
                        stickyAgeMs <= TRANSIENT_CALL_GAP_MS

                Log.w(
                    P2P_FORENSIC_TAG,
                    "call_sync_snapshot ts=$now thread=${Thread.currentThread().name} safeCalls=${allCalls.size} liveCalls=$liveCallCount currentInCall=${currentState.isInCall} stickyInCall=${stickyLiveSnapshot != null} pendingDisconnect=$pendingDisconnect stickyAgeMs=${if (stickyAgeMs == Long.MAX_VALUE) -1 else stickyAgeMs}"
                )

                if (currentState.isInCall) {
                    cancelTransientCallGapRecovery("syncCalls_live_state")
                    cancelPendingDisconnectCleanup("syncCalls_live_state")
                    clearTerminalUiSnapshot("syncCalls_live_state")
                    cacheLastCallUiSnapshot(currentState, "syncCalls")
                } else {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_ui_empty_emission source=syncCalls terminalSnapshot=${lastTerminalUiSnapshot?.primaryCallId.orEmpty()}"
                    )
                }

                if (transientEmptyGap) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_sync_transient_empty_hold ts=$now thread=${Thread.currentThread().name} primaryId=${stickyLiveSnapshot?.primaryCallId.orEmpty()} primaryState=${stickyLiveSnapshot?.primaryState.orEmpty()} stickyAgeMs=$stickyAgeMs oem_fallback_path_reached=no"
                    )
                    scheduleTransientCallGapRecovery("syncCalls_transient_gap")
                    stickyLiveSnapshot?.let {
                        callStateListener?.invoke(it)
                        callUIListener?.invoke(it)
                    }
                    return
                } else if (!currentState.isInCall) {
                    cancelTransientCallGapRecovery("syncCalls_empty_release")
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_sync_transient_empty_release ts=$now thread=${Thread.currentThread().name} reason=timeout_or_real_disconnect pendingDisconnect=$pendingDisconnect terminalSnapshot=${lastTerminalUiSnapshot?.primaryCallId.orEmpty()}"
                    )
                }

                if (currentState.isInCall && currentState.primaryNumber.isNotEmpty()) {
                    startNotificationRepost()
                } else {
                    stopNotificationRepost()
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_cleanup_deferred source=syncCalls deferring teardown to scheduleDisconnectCleanup"
                    )
                    scheduleDisconnectCleanup("syncCalls")
                }

                callStateListener?.invoke(currentState)
                callUIListener?.invoke(lastTerminalUiSnapshot ?: currentState)
                if (CallBubbleOverlay.isShowing()) {
                    showCallBubbleIfNeeded()
                }
            }

            @SuppressLint("FullScreenIntentPolicy")
            @Suppress("UseFullScreenIntent")
            override fun onCallAdded(call: Call) {
                // A newly added Telecom call begins a new session and may use the bubble again.
                bubbleSuppressedForTermination = false
                CallBubbleOverlay.allowForNewOngoingCall()
                // First call of a session: remember whether the app was on screen, so the call screen can
                // return to the app (not the home screen) when the call ends.
                if (getLiveCalls().count { it !== call } == 0) {
                    appWasOnScreenAtCallStart = MainActivity.isIncomingUiGateMainVisible()
                }
                Log.e("CMF_RINGTONE_DEBUG", "=== onCallAdded CALLED ===")
                Log.e("CMF_RINGTONE_DEBUG", "Call state: ${call.state} (RINGING=${Call.STATE_RINGING})")
                Log.e("CMF_RINGTONE_DEBUG", "Call direction: ${call.details?.callDirection}")

                // 1. ABSOLUTELY CRITICAL: Always call super first so OS state machine doesn't corrupt!
                super.onCallAdded(call)
                updateCallTimerStateStrict(call, getEffectiveCallState(call))
                val details = call.details
                val seedRawNumber = details?.handle?.schemeSpecificPart.orEmpty()


                // -----------------------------
                // MISSING VARIABLES PATCHED HERE
                val callId = stableCallId(call)
                val immediateState = stateToUiStateHint(call.state)
                val (_, immediateName) = resolveCallIdentity(call, "onCallAdded_initial")
                // -----------------------------

                // Promote MyInCallService to a started ForegroundService (startRequested = true)
                // and hide app tasks from Recents immediately.
                ensureServiceExplicitlyStarted(call)

                logCall("onCallAdded call=${System.identityHashCode(call)} state=${call.state} handle=${details?.handle}")
                android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: onCallAdded - System recognized our ownership.")
                
                // COMPREHENSIVE OEM FALLBACK PREVENTION
                // ROOT CAUSE #2: Process warming to prevent cold start timeout
                InCallServiceEnhancements.warmUpProcess(this)
                
                // ROOT CAUSE #10: Battery optimization whitelist verification
                val batteryOptimized = InCallServiceEnhancements.ensureBatteryOptimizationWhitelist(this)
                android.util.Log.e("OEM_FALLBACK_TRACE", "Battery optimization status: whitelisted=$batteryOptimized")
                
                // ROOT CAUSE #5: Enhanced default dialer role verification with race condition prevention
                val isDefaultDialer = InCallServiceEnhancements.verifyDefaultDialerRole(this)
                android.util.Log.e("OEM_FALLBACK_TRACE", "Default dialer role verified: isDefault=$isDefaultDialer")
                
                // Additional race condition detection
                val roleConflict = RoleManagerEnhancements.detectRoleConflicts(this)
                if (roleConflict.hasConflict) {
                    android.util.Log.e("OEM_FALLBACK_TRACE", "ROLE CONFLICT DETECTED: ${roleConflict.conflictResolution}")
                    val recovered = RoleManagerEnhancements.recheckDialerRole(this)
                    android.util.Log.e("OEM_FALLBACK_TRACE", "Dialer role recheck result: $recovered")
                }
                
                // Refresh role cache to prevent stale data
                RoleManagerEnhancements.refreshRoleCache(this)
                
                // STRICTNESS GATE: Acquire WakeLock immediately to survive Ultra Battery Saver freeze
                if (transitionWakeLock == null) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    transitionWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallerApp::InCallSurvivalLock")
                    transitionWakeLock?.setReferenceCounted(false)
                }
                if (transitionWakeLock?.isHeld == false) {
                    transitionWakeLock?.acquire(4 * 60 * 60 * 1000L) // 4-hour timeout for long calls
                    android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: PARTIAL_WAKE_LOCK acquired. Process freeze blocked.")
                }

                // AUDIO FOCUS: Request for call to get maximum volume treatment
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.let { manager ->
                        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                                .setAudioAttributes(audioAttributes)
                                .setAcceptsDelayedFocusGain(false)
                                .build()
                            manager.requestAudioFocus(focusRequest)
                        } else {
                            manager.requestAudioFocus(
                                null,
                                android.media.AudioManager.STREAM_VOICE_CALL,
                                android.media.AudioManager.AUDIOFOCUS_GAIN
                            )
                        }
                        if (result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            // Set VOICE_CALL stream to maximum volume for loud calls
                            val maxVolume = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                            manager.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
                            // Also boost MUSIC stream for speakerphone clarity
                            val maxMusic = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxMusic, 0)
                            Log.w("CallAudio", "Audio focus granted & volumes set to maximum for loud calls")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CallAudio", "Error requesting audio focus: ${e.message}")
                }

                val isConference = call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
                val immediatePhotoUri = if (isConference) null else resolveCallPhotoUri(call, seedRawNumber, "onCallAdded_initial")
                val immediateSimLabel = resolveSimLabel(call)
                cacheLastCallUiSnapshot(
                    UnifiedCallState(
                        isInCall = true,
                        primaryState = immediateState,
                        primaryNumber = if (isConference) "Conference" else seedRawNumber,
                        primaryName = immediateName,
                        primaryPhotoUri = immediatePhotoUri,
                        primaryCallId = callId,
                        isHolding = false,
                        secondaryNumber = null,
                        secondaryName = null,
                        secondaryState = null,
                        isConference = isConference,
                        simLabel = immediateSimLabel
                    ),
                    "onCallAdded"
                )

                // 1. DETERMINISTIC DIRECTION LOCKING
                // A call is ONLY incoming if it starts its life in the RINGING state.
                if (call.state == Call.STATE_RINGING) {
                    callDirectionTracker[callId] = "INCOMING"
                    UltraForensicLogger.forensicTransition(
                        "CallStateMachine", callId, "NONE", "RINGING", "Registered INCOMING call"
                    )
                } else {
                    // DIALING, CONNECTING, SELECT_PHONE_ACCOUNT are all OUTGOING
                    callDirectionTracker[callId] = "OUTGOING"
                    UltraForensicLogger.forensicTransition(
                        "CallStateMachine", callId, "NONE", "OUTGOING", "Registered OUTGOING call. Missed Call logic locked out."
                    )
                }

                if (callDirectionTracker[callId] == "OUTGOING" && (isConference || seedRawNumber.isNotBlank() || immediateName.isNotBlank())) {
                    startImmediateCallForeground(
                        phoneNumber = seedRawNumber,
                        contactName = immediateName.ifBlank { if (isConference) "Conference Call" else seedRawNumber },
                        isConf = isConference,
                        callState = immediateState
                    )
                }

                // 2. DEFAULT ANSWER STATE
                callAnswerTracker[callId] = false
                maybeTriggerP2PExchange(call, callId, "onCallAdded")

                // 3. ATTACH THE STATE MACHINE OBSERVER
                val machineCallback = object : Call.Callback() {
                    override fun onVideoCallChanged(call: Call, videoCall: VideoCall?) {
                        super.onVideoCallChanged(call, videoCall)
                        videoCallVersion.value++
                        Log.w(P2P_FORENSIC_TAG, "onVideoCallChanged callId=$callId videoCall=${videoCall != null}")
                        syncCalls()
                    }

                    override fun onStateChanged(call: Call, state: Int) {
                        val effectiveState = getEffectiveCallState(call)
                        updateCallTimerStateStrict(call, effectiveState)
                        call.children?.forEach { child ->
                            updateCallTimerStateStrict(child, getEffectiveCallState(child))
                        }
                        val direction = callDirectionTracker[callId] ?: "UNKNOWN"
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "call_state_change callId=$callId direction=$direction state=$state number=${maskP2PNumberForLog(call.details?.handle?.schemeSpecificPart.orEmpty())}"
                        )

                        if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                            maybeTriggerP2PExchange(call, callId, "onStateChanged_$state")
                        }
                        if (
                            direction == "OUTGOING" &&
                            (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_ACTIVE)
                        ) {
                            Log.w(
                                P2P_FORENSIC_TAG,
                                "ui_launch_skip reason=outgoing_state_update_respects_user_background callId=$callId state=$state number=${maskP2PNumberForLog(call.details?.handle?.schemeSpecificPart.orEmpty())}"
                            )
                        }
                         
                        // Track if the call was ever answered
                        if (state == Call.STATE_ACTIVE) {
                            callAnswerTracker[callId] = true
                            UltraForensicLogger.forensicTransition(
                                "CallStateMachine", callId, "UNKNOWN", "ACTIVE", "Call answered. Missed Call logic bypassed."
                            )
                        }

                        // Evaluate on death
                        if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
                            captureTerminalUiSnapshot(call, "onStateChanged_$state")
                        }
                        if (state == Call.STATE_DISCONNECTED) {
                            val direction = callDirectionTracker[callId] ?: "UNKNOWN"
                            val wasAnswered = callAnswerTracker[callId] ?: false
                            val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
                            val disconnectCause = call.details?.disconnectCause?.code

                            if (direction == "INCOMING" && !wasAnswered) {
                                // CRITICAL FIX: Only trigger if framework explicitly marks it as MISSED (automatic timeout).
                                // If you manually rejected it, cause will be REJECTED, and this skips notification.
                                if (disconnectCause == DisconnectCause.MISSED) {
                                    if (missedNotificationTracker.add(callId)) {
                                        UltraForensicLogger.ultra(
                                            "CallStateMachine", "MISSED_CALL_VERIFIED", "Call $callId ($number) automatically timed out. Generating Missed Call UI."
                                        )
                                        showCustomMissedCallNotification(number)
                                    } else {
                                        UltraForensicLogger.debug(
                                            "CallStateMachine", "MISSED_DUPLICATE_BLOCKED", "Missed UI already emitted for call $callId"
                                        )
                                    }
                                } else {
                                    UltraForensicLogger.forensic(
                                        "CallStateMachine", "CALL_REJECTED", "User manually rejected call. Suppressing missed notification."
                                    )
                                }
                            } else if (direction == "OUTGOING") {
                                UltraForensicLogger.forensic(
                                    "CallStateMachine", "GHOST_BLOCKED", "Call $callId was OUTGOING. Suppressing false missed call notification."
                                )
                            }


                            maybeShowPowerEndRecoveryNotification(
                                call = call,
                                callId = callId,
                                direction = direction,
                                wasAnswered = wasAnswered
                            )
                        }
                        
                        // Keep your standard UI sync running
                        syncCalls()
                    }

                    override fun onDetailsChanged(call: Call, details: Call.Details) {
                        updateCallTimerStateStrict(call, getEffectiveCallState(call))
                        call.children?.forEach { child ->
                            updateCallTimerStateStrict(child, getEffectiveCallState(child))
                        }
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "call_details_change callId=$callId state=${call.state} effectiveState=${getEffectiveCallState(call)} parentId=${stableCallId(call.parent)} childIds=${call.children?.joinToString(",") { stableCallId(it) }.orEmpty()}"
                        )
                        syncCalls()
                    }

                    override fun onParentChanged(call: Call, parent: Call?) {
                        updateCallTimerStateStrict(call, getEffectiveCallState(call))
                        parent?.children?.forEach { child ->
                            updateCallTimerStateStrict(child, getEffectiveCallState(child))
                        }
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "call_parent_change callId=$callId effectiveState=${getEffectiveCallState(call)} parentId=${stableCallId(parent)} childIds=${call.children?.joinToString(",") { stableCallId(it) }.orEmpty()}"
                        )
                        syncCalls()
                    }

                    override fun onChildrenChanged(call: Call, children: MutableList<Call>) {
                        updateCallTimerStateStrict(call, getEffectiveCallState(call))
                        children.forEach { child ->
                            updateCallTimerStateStrict(child, getEffectiveCallState(child))
                        }
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "call_children_change callId=$callId state=${call.state} effectiveState=${getEffectiveCallState(call)} parentId=${stableCallId(call.parent)} childIds=${children.joinToString(",") { stableCallId(it) }}"
                        )
                        syncCalls()
                    }

                    override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) {
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "call_conferenceable_change callId=$callId state=${call.state} conferenceableIds=${conferenceableCalls.joinToString(",") { stableCallId(it) }}"
                        )
                        syncCalls()
                    }
                }
                 
                call.registerCallback(machineCallback)
                callMachineCallbackTracker[callId] = machineCallback
                val direction = callDirectionTracker[callId].orEmpty()
                val rawNumber = call.details?.handle?.schemeSpecificPart.orEmpty()
                val initialStateHint = stateToUiStateHint(call.state)
                val (_, initialNameHint) = resolveCallIdentity(call, "onCallAdded_launch")
                val isConferenceHint = call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
                if (direction == "INCOMING") {
                    val directBootPhase = isDirectBootPhase()
                    val shouldShowIncomingNotification =
                        call.state == Call.STATE_RINGING ||
                            call.state == Call.STATE_NEW ||
                            initialStateHint == "RINGING" ||
                            initialStateHint == "NEW"
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "incoming_launch_fastpath callId=$callId raw=${maskP2PNumberForLog(rawNumber)} stateHint=$initialStateHint nameHint=${maskP2PDisplayValueForLog(initialNameHint)} directBootPhase=$directBootPhase shouldShowIncomingNotification=$shouldShowIncomingNotification"
                    )
                    if (shouldShowIncomingNotification) {
                        val notificationReason = if (directBootPhase) "direct_boot" else "normal"
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "incoming_fullscreen_notification callId=$callId raw=${maskP2PNumberForLog(rawNumber)} action=showIncomingCallNotification reason=$notificationReason"
                        )
                        showIncomingCallNotification(rawNumber, initialNameHint.ifBlank { rawNumber })
                    }
                    syncCalls()
                } else {
                    syncCalls()
                    launchCallUI(
                        reason = "onCallAdded:$direction",
                        showDialpad = direction == "OUTGOING",
                        rawNumber = rawNumber,
                        initialStateHint = initialStateHint,
                        initialNameHint = initialNameHint,
                        isConferenceHint = isConferenceHint
                    )
                }
            }

            override fun onCallRemoved(call: Call) {
                logCall("onCallRemoved call=${System.identityHashCode(call)} state=${call.state}")
                android.util.Log.e("OEM_FALLBACK_TRACE", "=====================================================================")
        android.util.Log.e("OEM_FALLBACK_TRACE", "ULTRA SUPREME FORENSIC INFLOP PROFESSION LOGGING")
                android.util.Log.e("OEM_FALLBACK_TRACE", "MyInCallService: onCallRemoved TRIGGERED!")
                android.util.Log.e("OEM_FALLBACK_TRACE", "Call State at Removal: ${call.state}")
                android.util.Log.e("OEM_FALLBACK_TRACE", "If state is NOT Disconnected, the OS is stripping the call from us!")
                android.util.Log.e("OEM_FALLBACK_TRACE", "=====================================================================")
                super.onCallRemoved(call)

                // Floating call pill goes away with the last call.
                if (getLiveCalls().none { it !== call }) {
                    answeredFromBannerPopPending = false
                    CallBubbleOverlay.hide(this)
                }

                // AUDIO FOCUS: Abandon focus when call ends
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.let { manager ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            // For Android O+, we need to have the AudioFocusRequest
                            manager.abandonAudioFocus(null)
                        } else {
                            manager.abandonAudioFocus(null)
                        }
                        Log.w("CallAudio", "Audio focus abandoned - call ended")
                    }
                } catch (e: Exception) {
                    Log.e("CallAudio", "Error abandoning audio focus: ${e.message}")
                }

                
                val callId = call.hashCode().toString()
                
                // STRICTNESS GATE: Verify if the call actually ended
                val isReallyDisconnected = call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING

                // 1. Unregister callbacks to prevent late-arriving state changes
                callMachineCallbackTracker.remove(callId)?.let { cb ->
                    try { call.unregisterCallback(cb) } catch (_: Exception) {}
                }
                try { call.unregisterCallback(callCallback) } catch (_: Exception) {}

                val phoneNumber = call.details?.handle?.schemeSpecificPart ?: ""
                clearCallDisplayResolution(call, phoneNumber, "onCallRemoved")
                val direction = callDirectionTracker[callId] ?: "UNKNOWN"
                val wasAnswered = callAnswerTracker[callId] ?: false
                
                // Missed-call fallback in case OEM skips DISCONNECTED callback delivery
                if (direction == "INCOMING" && !wasAnswered && missedNotificationTracker.add(callId)) {
                    UltraForensicLogger.ultra("CallStateMachine", "MISSED_CALL_FALLBACK", "onCallRemoved emitted missed UI for call $callId")
                    showCustomMissedCallNotification(phoneNumber)
                }

                // EXACT FIX: Guard terminal snapshot against transient unbinds
                if (isReallyDisconnected) {
                    runCatching { AccountDeviceHistoryManager.syncAccountNow(this, showToast = false) }
                    if (lastTerminalUiSnapshot?.primaryCallId != callId) {
                        captureTerminalUiSnapshot(call, "onCallRemoved_fallback")
                    }
                } else {
                    UltraForensicLogger.ultra(
                        "MyInCallService", 
                        "OEM_FALLBACK_PREVENTION", 
                        "ULTRA SUPREME FORENSIC INFLOP PROFESSION LOGGING: Call removed but state is ${call.state}. Transient unbind detected (Ultra Battery Saver). Blocking OEM fallback!"
                    )
                }
                val timerKey = callTimerKeyAliases.remove(callId).orEmpty().ifBlank { resolveCallTimerStateKey(call) }
                
                // <<< PATCH START: ELIMINATE THE WIPEOUT BUG >>>
                // DELIBERATELY RETAIN state in callTimerStates. 
                // Do NOT call callTimerStates.remove(timerKey).
                // Transient unbinds during IMS merge will no longer wipe the durable tracker.
                // The map will safely GC when the service is destroyed.
                // <<< PATCH END >>>
                // Cleanup trackers
                callDirectionTracker.remove(callId)
                callAnswerTracker.remove(callId)
                clearP2PExchangeState(callId, phoneNumber)
                  
                // Keep UI state deterministic before deciding service teardown
                syncCalls()
                val liveCalls = getLiveCalls()

                // EXACT FIX: Guard teardown against transient unbinds
                if (liveCalls.isEmpty()) {
                    if (isReallyDisconnected) {
                        UltraForensicLogger.forensic("MyInCallService", "ABSOLUTE_EVICTION_PENDING", "No active calls remain. Scheduling guarded teardown.")
                        scheduleDisconnectCleanup("onCallRemoved")
                    } else {
                        UltraForensicLogger.ultra(
                            "MyInCallService", 
                            "OEM_FALLBACK_PREVENTION", 
                            "ULTRA SUPREME FORENSIC INFLOP PROFESSION LOGGING: liveCalls empty but not disconnected. Bypassing teardown to preserve ownership during Ultra Battery Saver mode switch."
                        )
                    }
                } else {
                    cancelPendingDisconnectCleanup("onCallRemoved_live_calls_remaining")
                }
                missedNotificationTracker.remove(callId)
            }

            @Deprecated("Deprecated in Java")
            override fun onCallAudioStateChanged(audioState: CallAudioState?) {
                super.onCallAudioStateChanged(audioState)
                syncCalls()
            }

            override fun onSilenceRinger() {
                super.onSilenceRinger()
                UltraForensicLogger.debug("System requested ringer silence (Power/Volume button pressed)")
                stopIncomingRingtone("onSilenceRinger", resetCurrentCall = false, markSilenced = true)

                val intent = Intent("SILENCE_RINGTONE")
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }

            private val callCallback = object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    updateCallTimerStateStrict(call, getEffectiveCallState(call))
                    call.children?.forEach { child ->
                        updateCallTimerStateStrict(child, getEffectiveCallState(child))
                    }
                    syncCalls()
                }
            }

            fun answerCall() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                        UltraForensicLogger.deepest("ANSWER_PHONE_CALLS permission not granted")
                        return
                    }

                    val ringingCall = getSafeCalls().firstOrNull { it.state == Call.STATE_RINGING }
                    if (ringingCall != null) {
                        val videoState = ringingCall.details?.videoState ?: VideoProfile.STATE_AUDIO_ONLY
                        val answerVideoState = if (VideoProfile.isVideo(videoState)) {
                            VideoProfile.STATE_BIDIRECTIONAL
                        } else {
                            VideoProfile.STATE_AUDIO_ONLY
                        }
                        ringingCall.answer(answerVideoState)
                    }
                    bgHandler.postDelayed({ syncCalls() }, 300)
                } catch (e: SecurityException) {
                    UltraForensicLogger.deepest("SecurityException when answering call: ${e.message}")
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to answer call: ${e.message}")
                }
            }

            fun rejectCall() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                        UltraForensicLogger.deepest("ANSWER_PHONE_CALLS permission not granted")
                        return
                    }

                    getSafeCalls().firstOrNull { it.state == Call.STATE_RINGING }?.reject(false, null)
                    syncCalls()
                } catch (e: SecurityException) {
                    UltraForensicLogger.deepest("SecurityException when rejecting call: ${e.message}")
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to reject call: ${e.message}")
                }
            }

            fun disconnectCall() {
                try {
                    // Set this before asking Telecom to disconnect. Its in-call signals can linger long
                    // enough for CallActivity.onStop to otherwise recreate the overlay.
                    bubbleSuppressedForTermination = true
                    CallBubbleOverlay.suppressForTermination(this)
                    stopIncomingRingtone("disconnectCall", resetCurrentCall = true)
                    lastAppInitiatedDisconnectAtElapsedMs = SystemClock.elapsedRealtime()
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                        UltraForensicLogger.deepest("ANSWER_PHONE_CALLS permission not granted")
                        return
                    }

                    val safeCalls = getSafeCalls()
                    val targetCall = resolveDisconnectTarget(safeCalls)

                    if (targetCall == null) {
                        pendingDisconnect = true
                        bgHandler.postDelayed({
                            pendingDisconnect = false
                        }, 3000)
                        return
                    }

                    Log.w(
                        P2P_FORENSIC_TAG,
                        "call_disconnect_target callId=${stableCallId(targetCall)} state=${uiStateLabelForCall(targetCall)} totalLive=${getLiveTopLevelCalls(safeCalls).size} isConference=${targetCall.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true}"
                    )

                    if (targetCall.state == Call.STATE_RINGING) {
                        targetCall.reject(false, null)
                    } else {
                        targetCall.disconnect()
                    }

                    bgHandler.postDelayed({ syncCalls() }, 150)

                } catch (e: SecurityException) {
                    UltraForensicLogger.deepest("SecurityException when disconnecting call: ${e.message}")
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Error disconnecting: ${e.message}")
                }
            }

            fun holdCall() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                        UltraForensicLogger.deepest("ANSWER_PHONE_CALLS permission not granted")
                        return
                    }

                    val safeCalls = getSafeCalls()

                    // Separate multi-hold support - hold each active call individually
                    val activeCalls = safeCalls.filter {
                        it.state == Call.STATE_ACTIVE &&
                        it.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) != true
                    }

                    // Hold each active call separately for true multi-hold
                    activeCalls.forEach { call ->
                        try {
                            call.hold()
                            UltraForensicLogger.debug("Holding call: ${maskPhoneNumber(call.details?.handle?.schemeSpecificPart.orEmpty())}")
                        } catch (e: Exception) {
                            UltraForensicLogger.deepest("Failed to hold call: ${e.message}")
                        }
                    }
                } catch (e: SecurityException) {
                    UltraForensicLogger.deepest("SecurityException when holding call: ${e.message}")
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to hold call: ${e.message}")
                }
            }

            fun unholdCall() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                        UltraForensicLogger.deepest("ANSWER_PHONE_CALLS permission not granted")
                        return
                    }

                    // Separate multi-unhold support - unhold each held call individually
                    val heldCalls = getSafeCalls().filter { it.state == Call.STATE_HOLDING }

                    heldCalls.forEach { call ->
                        try {
                            call.unhold()
                            UltraForensicLogger.debug("Unholding call: ${maskPhoneNumber(call.details?.handle?.schemeSpecificPart.orEmpty())}")
                        } catch (e: Exception) {
                            UltraForensicLogger.deepest("Failed to unhold call: ${e.message}")
                        }
                    }
                } catch (e: SecurityException) {
                    UltraForensicLogger.deepest("SecurityException when unholding call: ${e.message}")
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to unhold call: ${e.message}")
                }
            }

            fun swapCalls() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                        UltraForensicLogger.deepest("ANSWER_PHONE_CALLS permission not granted")
                        return
                    }

                    val safeCalls = getSafeCalls()
                    val active = safeCalls.firstOrNull { it.state == Call.STATE_ACTIVE }
                    val held = safeCalls.firstOrNull { it.state == Call.STATE_HOLDING }

                    if (active != null && held != null) {
                        held.unhold()
                        bgHandler.postDelayed({
                            active.hold()
                        }, 150)
                    }
                } catch (e: SecurityException) {
                    UltraForensicLogger.deepest("SecurityException when swapping calls: ${e.message}")
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to swap calls: ${e.message}")
                }
            }

            fun mergeCalls() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return

                    val liveTopLevelCalls = getLiveTopLevelCalls(getSafeCalls())
                    if (!canMergeCalls(liveTopLevelCalls)) {
                        UltraForensicLogger.debug("Merge skipped because calls are not merge-ready yet")
                        syncCalls()
                        return
                    }

                    val mergeReadyCalls = liveTopLevelCalls.filter(::isMergeReadyTopLevelCall)
                    val mergeCapableCall = mergeReadyCalls.firstOrNull {
                        canUseCapability(it, Call.Details.CAPABILITY_MERGE_CONFERENCE)
                    }

                    if (mergeCapableCall != null) {
                        mergeCapableCall.mergeConference()
                        UltraForensicLogger.debug("Merged calls using framework mergeConference()")
                    } else {
                        val conferenceablePair = findConferenceableMergePair(mergeReadyCalls)
                        if (conferenceablePair != null) {
                            val (hostCall, peerCall) = conferenceablePair
                            hostCall.conference(peerCall)
                            UltraForensicLogger.debug("Merged calls using conferenceable pair")
                        } else {
                            val conference = mergeReadyCalls.firstOrNull(::isConferenceCall)
                            val standaloneCalls = mergeReadyCalls.filter { !isConferenceCall(it) }

                            if (conference != null && standaloneCalls.isNotEmpty()) {
                                conference.conference(standaloneCalls.first())
                                UltraForensicLogger.debug("Absorbed standalone call into existing conference")
                            } else if (standaloneCalls.size >= 2) {
                                val hostCall = standaloneCalls.first()
                                for (call in standaloneCalls.drop(1)) {
                                    hostCall.conference(call)
                                }
                                UltraForensicLogger.debug("Merged standalone calls using fallback conference()")
                            }
                        }
                    }

                    bgHandler.postDelayed({ syncCalls() }, 200)
                } catch (e: SecurityException) {
                    UltraForensicLogger.deepest("SecurityException when merging calls: ${e.message}")
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to merge calls: ${e.message}")
                }
            }

            fun splitConference() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return

                    val safeCalls = getSafeCalls()
                    val conferenceCall = safeCalls.firstOrNull { it.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true }

                    val childToSplit = conferenceCall?.children?.lastOrNull()
                    try {
                        childToSplit?.splitFromConference()
                    } catch (e: SecurityException) {
                        UltraForensicLogger.deepest("SecurityException when splitting conference: ${e.message}")
                    } catch (e: Exception) {
                        UltraForensicLogger.deepest("Failed to split conference: ${e.message}")
                    }

                    UltraForensicLogger.debug("Split command sent to child call")
                } catch (e: SecurityException) {
                    Log.e("MyInCallService", "SecurityException when splitting conference: ${e.message}")
                } catch (e: Exception) {
                    Log.e("MyInCallService", "Failed to split conference: ${e.message}")
                }
            }

            fun dropLastConferenceParticipant() {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return

                    val safeCalls = getSafeCalls()
                    val conferenceCall = safeCalls.firstOrNull { it.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true }

                    val childToDrop = conferenceCall?.children?.lastOrNull()
                    try {
                        childToDrop?.disconnect()
                    } catch (e: SecurityException) {
                        UltraForensicLogger.deepest("SecurityException when dropping participant: ${e.message}")
                    } catch (e: Exception) {
                        UltraForensicLogger.deepest("Failed to drop participant: ${e.message}")
                    }

                    UltraForensicLogger.debug("Dropped last participant from conference")
                } catch (e: SecurityException) {
                    Log.e("MyInCallService", "SecurityException when dropping participant: ${e.message}")
                } catch (e: Exception) {
                    Log.e("MyInCallService", "Failed to drop participant: ${e.message}")
                }
            }

            fun disconnectSpecificParticipant(callId: String) {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return

                    val safeCalls = getSafeCalls()
                    val conferenceCall = safeCalls.firstOrNull { it.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true }

                    val participant = conferenceCall?.children?.firstOrNull { stableCallId(it) == callId }
                    try {
                        participant?.disconnect()
                    } catch (e: SecurityException) {
                        UltraForensicLogger.deepest("SecurityException when disconnecting specific participant: ${e.message}")
                    } catch (e: Exception) {
                        UltraForensicLogger.deepest("Failed to disconnect specific participant: ${e.message}")
                    }

                    UltraForensicLogger.debug("Disconnected specific participant: $callId")
                } catch (e: SecurityException) {
                    Log.e("MyInCallService", "SecurityException when disconnecting specific participant: ${e.message}")
                } catch (e: Exception) {
                    Log.e("MyInCallService", "Failed to disconnect specific participant: ${e.message}")
                }
            }

            fun playDtmfTone(digit: Char) {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return

                    val active = getSafeCalls().firstOrNull { it.state == Call.STATE_ACTIVE }
                    try {
                        active?.playDtmfTone(digit)
                    } catch (e: SecurityException) {
                        Log.e("MyInCallService", "SecurityException playing DTMF: ${e.message}")
                    } catch (e: Exception) {
                        Log.e("MyInCallService", "Failed to play DTMF: ${e.message}")
                    }
                    bgHandler.postDelayed({
                        try { active?.stopDtmfTone() } catch (ex: SecurityException) {
                            Log.e("MyInCallService", "SecurityException stopping DTMF: ${ex.message}")
                        } catch (_: Exception) {}
                    }, 200)
                } catch (e: SecurityException) {
                    Log.e("MyInCallService", "SecurityException playing DTMF: ${e.message}")
                } catch (_: Exception) {}
            }

            private fun buildCallStylePerson(displayName: String): Person {
                return Person.Builder()
                    .setName(displayName)
                    .setImportant(true)
                    .build()
            }

            private fun buildDisconnectPendingIntent(): PendingIntent {
                val hangupIntent = Intent(this, MyInCallService::class.java).apply {
                    action = ACTION_DISCONNECT_CALL
                }
                return PendingIntent.getService(
                    this,
                    101,
                    hangupIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            /**
             * Shared extras for the ongoing call-style notification (both builders use this):
             * - call timer (also shown in the status-bar call chip), always on as in the CallStyle guide;
             * - if the notification is removed anyway, the service puts it straight back.
             */
            private fun NotificationCompat.Builder.applyOngoingCallExtras(
                phoneNumber: String,
                callState: String
            ): NotificationCompat.Builder {
                val repostIntent = PendingIntent.getService(
                    this@MyInCallService,
                    102,
                    Intent(this@MyInCallService, MyInCallService::class.java).setAction(ACTION_REPOST_CALL_NOTIFICATION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setDeleteIntent(repostIntent)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

                // As in the CallStyle guide (SIRN): the timer is always on. It counts from the moment the
                // call connected, or from when the call started while it is still dialing/on hold.
                val liveCalls = getLiveCalls()
                val call = liveCalls.firstOrNull { it.details?.handle?.schemeSpecificPart == phoneNumber }
                    ?: liveCalls.firstOrNull { it.state == Call.STATE_ACTIVE }
                    ?: liveCalls.firstOrNull()
                val connectedAt = call?.details?.connectTimeMillis?.takeIf { it > 0L }
                val startedAt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    call?.details?.creationTimeMillis?.takeIf { it > 0L }
                } else null
                setUsesChronometer(true)
                setShowWhen(true)
                setWhen(connectedAt ?: startedAt ?: System.currentTimeMillis())
                return this
            }

            private fun getDefaultLauncherPackage(): String {
                return try {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                    }
                    val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    resolveInfo?.activityInfo?.packageName.orEmpty()
                } catch (_: Exception) {
                    ""
                }
            }

            private fun getForegroundPackageName(): String {
                if (MainActivity.isIncomingUiGateMainVisible()) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "incoming_foreground_source source=MainActivity.visibility package=$packageName"
                    )
                    return packageName
                }
                var currentApp = ""
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        // The app on screen = the last app whose activity came to the front, read from the activity
                        // event log. (Daily usage totals' "last used" time is not "in front now": background apps,
                        // the dialer or the launcher can be newer than the app the user is actually in.)
                        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                        val time = System.currentTimeMillis()
                        val events = usageStatsManager.queryEvents(time - 6 * 60 * 60 * 1000L, time)
                        val event = android.app.usage.UsageEvents.Event()
                        @Suppress("DEPRECATION")
                        val resumedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                        } else {
                            android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                        }
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event)
                            if (event.eventType == resumedType) currentApp = event.packageName.orEmpty()
                        }
                    } else {
                        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                        @Suppress("DEPRECATION")
                        val tasks = activityManager.getRunningTasks(1)
                        if (tasks.isNotEmpty()) {
                            currentApp = tasks[0].topActivity?.packageName.orEmpty()
                        }
                    }
                } catch (e: Exception) {
                    UltraForensicLogger.debug(
                        "FullScreenIntent",
                        "ERROR",
                        "Failed to query foreground package: ${e.message}"
                    )
                }
                return currentApp.takeUnless { it.equals("UNKNOWN", ignoreCase = true) }.orEmpty()
            }

            private fun resolveIncomingCallNotificationDecision(): IncomingCallNotificationDecision {
                // Our own main screen on top counts as "in the dialer" even without Usage access.
                val foregroundPackage = if (MainActivity.isIncomingUiGateMainVisible()) packageName else getForegroundPackageName()
                val launcherPackage = getDefaultLauncherPackage()
                val defaultDialerPackage = try {
                    resolveDefaultDialerPackage(this@MyInCallService)
                } catch (_: Exception) {
                    ""
                }
                val isDeviceLocked = try {
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                    keyguardManager?.isKeyguardLocked == true
                } catch (_: Exception) {
                    false
                }
                val isScreenInteractive = try {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    powerManager?.isInteractive != false
                } catch (_: Exception) {
                    true
                }
                val policy = resolveIncomingCallUiDecision(
                    foregroundPackage = foregroundPackage,
                    myPackage = packageName,
                    defaultDialerPackage = defaultDialerPackage,
                    launcherPackage = launcherPackage,
                    // Full screen only in the dialer / on the home screen / locked / screen off.
                    assumeDialerOwnership = false,
                    isDeviceLocked = isDeviceLocked,
                    isScreenInteractive = isScreenInteractive
                )
                Log.w(
                    P2P_FORENSIC_TAG,
                    "incoming_fullscreen_policy source=MyInCallService.showIncomingCallNotification foreground=$foregroundPackage launcher=$launcherPackage defaultDialer=$defaultDialerPackage deviceLocked=$isDeviceLocked screenInteractive=$isScreenInteractive allow=${policy.allowFullScreen} reason=${policy.reason}"
                )
                return IncomingCallNotificationDecision(
                    policy = policy,
                    foregroundPackage = foregroundPackage,
                    defaultDialerPackage = defaultDialerPackage,
                    launcherPackage = launcherPackage
                )
            }

            private fun showIncomingCallNotification(phoneNumber: String, contactName: String) {
                Log.e("CMF_RINGTONE_DEBUG", "=== showIncomingCallNotification CALLED ===")
                Log.e("CMF_RINGTONE_DEBUG", "Phone: $phoneNumber, Name: $contactName")

                // ROOT CAUSE #3, #4, #15 FIX: Enhanced FullScreenIntent with OEM bypass
                val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
                    action = CALL_UI_ACTION_WAKE
                    attachCallUiHint(phoneNumber, "RINGING", contactName, false)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_FROM_BACKGROUND
                    )
                }
                val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                
                // ROOT CAUSE #15: Dual-path UI guarantee - notification + direct launch
                val callActivityIntent = Intent(this, CallActivity::class.java).apply {
                    action = CALL_UI_ACTION_WAKE
                    attachCallUiHint(phoneNumber, "RINGING", contactName, false)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
                // The full-screen call page is opened only below, when the policy allows it (dialer, home
                // screen, locked or screen off). In any other app the user gets the Answer/Decline banner.

                // 2. Button Intents
                val answerIntent = Intent(this, MyInCallService::class.java).apply {
                    action = Constants.ACTION_ANSWER_CALL
                    // This Answer button is on the banner shown while the user is in another app (WhatsApp etc.):
                    // answer and stay there. The ongoing call notification (with the timer) opens the full call
                    // screen only if the user taps it.
                    putExtra(OPEN_UI_AFTER_ANSWER_EXTRA, false)
                    attachCallUiHint(phoneNumber, "ACTIVE", contactName, false)
                }
                val answerPendingIntent = PendingIntent.getService(this, 10, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val rejectIntent = Intent(this, MyInCallService::class.java).apply { action = Constants.ACTION_REJECT_CALL }
                val rejectPendingIntent = PendingIntent.getService(this, 11, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val displayText = if (contactName == "Unknown") phoneNumber else contactName
                val incomingNotificationCallKey = lastCallUiSnapshot
                    ?.takeIf { it.isInCall && it.primaryNumber == phoneNumber }
                    ?.primaryCallId
                    .orEmpty()
                    .ifBlank { buildCallResolutionKey(null, phoneNumber) }
                logCallerDisplayResolution(
                    event = "notification_publish",
                    callKey = incomingNotificationCallKey,
                    rawNumber = phoneNumber,
                    extra = "surface=incoming state=RINGING",
                    windowMs = 1000L
                )

                val person = buildCallStylePerson(displayText)
                val style = NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    rejectPendingIntent,
                    answerPendingIntent
                )

                val incomingDecision = resolveIncomingCallNotificationDecision()
                val allowFullScreen = incomingDecision.policy.allowFullScreen
                silenceMainActivityRingtone()
                playIncomingRingtoneIfNeeded(
                    callKey = incomingNotificationCallKey,
                    phoneNumber = phoneNumber,
                    surface = if (allowFullScreen) "fullscreen_allowed" else "notification_only"
                )

                val builder = NotificationCompat.Builder(this, incomingCallChannelId)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle("Incoming Call")
                    .setContentText(displayText)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(fullScreenPendingIntent)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true) // CRITICAL FIX: Stops repeated floating every 3 seconds

                if (allowFullScreen) {
                    builder.setFullScreenIntent(fullScreenPendingIntent, true)
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "incoming_fullscreen_execution source=MyInCallService.showIncomingCallNotification action=attach reason=${incomingDecision.policy.reason} notificationSurface=fullscreen_without_actions"
                    )
                    launchCallUI(
                        reason = "incoming_allowed:${incomingDecision.policy.reason}",
                        showDialpad = false,
                        rawNumber = phoneNumber,
                        initialStateHint = "RINGING",
                        initialNameHint = contactName,
                        isConferenceHint = false
                    )
                } else {
                    builder.setStyle(style)
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "incoming_fullscreen_execution source=MyInCallService.showIncomingCallNotification action=withhold reason=${incomingDecision.policy.reason} notificationSurface=notification_with_answer_decline ringtoneOwner=MyInCallService"
                    )
                    // When another app is in front, keep it in front.  In particular, Redmi 13C
                    // (MIUI/HyperOS) can turn a normal incoming-call surface into a full-screen
                    // activity.  Give the user the compact overlay instead, while retaining the
                    // CallStyle notification as the permission-denied fallback.
                    showCallBubbleIfNeeded(launchFullScreenWhenOverlayUnavailable = false)
                }

                builder.setPublicVersion(builder.build())
                val notification = builder.build().applyLockedForegroundFlags()

                try {
                    startCallForeground(notification)
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Failed to start foreground for incoming call: ${e.message}")
                    getSystemService(NotificationManager::class.java).notify(Constants.NOTIFICATION_ID_ONGOING_CALL, notification)
                }
            }

            private fun buildImmediateCallNotification(phoneNumber: String, contactName: String, isConf: Boolean, callState: String = "ACTIVE"): android.app.Notification {
                // Quick notification builder for immediate foreground service elevation
                val intent = Intent(this, CallActivity::class.java).apply {
                    action = CALL_UI_ACTION_WAKE
                    putExtra("WAKE_REASON", "immediate_foreground_$callState")
                    attachCallUiHint(phoneNumber, callState, contactName, isConf)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                
                val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    2003,
                    intent,
                    flags
                )

                val title = when {
                    isConf -> "Conference Call"
                    callState == "HOLDING" -> "On Hold"
                    contactName == "Unknown" -> "Ongoing Call"
                    else -> contactName
                }
                val text = when {
                    isConf -> "Tap to return to conference"
                    callState == "HOLDING" -> if (contactName == "Unknown") "On hold: $phoneNumber" else "On hold: $contactName"
                    contactName == "Unknown" -> "Tap to return: $phoneNumber"
                    else -> phoneNumber
                }
                val immediateNotificationCallKey = lastCallUiSnapshot
                    ?.takeIf { it.isInCall && it.primaryNumber == phoneNumber }
                    ?.primaryCallId
                    .orEmpty()
                    .ifBlank { buildCallResolutionKey(null, phoneNumber) }
                logCallerDisplayResolution(
                    event = "notification_publish",
                    callKey = immediateNotificationCallKey,
                    rawNumber = phoneNumber,
                    extra = "surface=immediate state=$callState",
                    windowMs = 1000L
                )

                val displayName = when {
                    isConf -> "Conference Call"
                    contactName == "Unknown" -> phoneNumber
                    else -> contactName
                }
                val person = buildCallStylePerson(displayName)
                val style = NotificationCompat.CallStyle.forOngoingCall(
                    person,
                    buildDisconnectPendingIntent()
                )

                val builder = NotificationCompat.Builder(this, ongoingCallChannelId)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(pendingIntent)
                    .setStyle(style)
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false)
                    .applyOngoingCallExtras(phoneNumber, callState)

                builder.setPublicVersion(builder.build())
                return builder.build().applyLockedForegroundFlags()
            }

            private fun showOngoingCallNotification(phoneNumber: String, contactName: String, isConf: Boolean, callState: String = "ACTIVE") {
                stopIncomingRingtone("showOngoingCallNotification", resetCurrentCall = true)
                val intent = Intent(this, CallActivity::class.java).apply {
                    action = CALL_UI_ACTION_WAKE
                    putExtra("WAKE_REASON", "ongoing_notification_$callState")
                    attachCallUiHint(phoneNumber, callState, contactName, isConf)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                
                val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    2002,
                    intent,
                    flags
                )

                val shouldWakeOutgoingUi = false
                Log.w(
                    P2P_FORENSIC_TAG,
                    "ongoing_notification_wake raw=${maskP2PNumberForLog(phoneNumber)} state=$callState shouldWakeOutgoingUi=$shouldWakeOutgoingUi"
                )

                val title = when {
                    isConf -> "Conference Call"
                    callState == "HOLDING" -> "On Hold"
                    contactName == "Unknown" -> "Ongoing Call"
                    else -> contactName
                }
                val text = when {
                    isConf -> "Tap to return to conference"
                    callState == "HOLDING" -> if (contactName == "Unknown") "On hold: $phoneNumber" else "On hold: $contactName"
                    contactName == "Unknown" -> "Tap to return: $phoneNumber"
                    else -> phoneNumber
                }
                val ongoingNotificationCallKey = lastCallUiSnapshot
                    ?.takeIf { it.isInCall && it.primaryNumber == phoneNumber }
                    ?.primaryCallId
                    .orEmpty()
                    .ifBlank { buildCallResolutionKey(null, phoneNumber) }
                logCallerDisplayResolution(
                    event = "notification_publish",
                    callKey = ongoingNotificationCallKey,
                    rawNumber = phoneNumber,
                    extra = "surface=ongoing state=$callState",
                    windowMs = 1000L
                )

                val displayName = when {
                    isConf -> "Conference Call"
                    contactName == "Unknown" -> phoneNumber
                    else -> contactName
                }
                val person = buildCallStylePerson(displayName)
                val style = NotificationCompat.CallStyle.forOngoingCall(
                    person,
                    buildDisconnectPendingIntent()
                )

                // Answered from the banner in another app: this one post alerts on the silent high-priority channel,
                // so the banner on screen turns into the call (name, timer, Hang up) for a few seconds.
                val popAsBanner = answeredFromBannerPopPending && callState == "ACTIVE"
                if (popAsBanner) answeredFromBannerPopPending = false
                val builder = NotificationCompat.Builder(this, if (popAsBanner) answeredBannerChannelId else ongoingCallChannelId)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(pendingIntent)
                    .setStyle(style)
                    .setOngoing(true)
                    .setSilent(!popAsBanner)
                    .setOnlyAlertOnce(!popAsBanner)
                    .setAutoCancel(false)
                    .applyOngoingCallExtras(phoneNumber, callState)

                builder.setPublicVersion(builder.build())
                val notification = builder
                    .build()
                    .applyLockedForegroundFlags()

                try {
                    startCallForeground(notification)
                } catch (e: Exception) {
                    com.caller.logging.UltraForensicLogger.deepest("Failed to start foreground service: ${e.message}")
                    getSystemService(NotificationManager::class.java).notify(Constants.NOTIFICATION_ID_ONGOING_CALL, notification)
                }
            }

            private fun showMissedCallNotification(phoneNumber: String, contactName: String) {
                stopIncomingRingtone("showMissedCallNotification", resetCurrentCall = true)
                // CRITICAL FIX: Target MainActivity instead of CallActivity
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = "OPEN_RECENTS"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val title = "Missed Call"
                val text = if (contactName == "Unknown") phoneNumber else contactName

                val notification = NotificationCompat.Builder(this, incomingCallChannelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

                try {
                    getSystemService(NotificationManager::class.java).notify(Constants.NOTIFICATION_ID_MISSED_CALL, notification)
                } catch (e: Exception) {
                    Log.e("MyInCallService", "Failed to show missed call notification: ${e.message}")
                }
            }

            private fun clearAllNotifications() {
                lastNotificationStateKey = null
                stopIncomingRingtone("clearAllNotifications", resetCurrentCall = true)
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.cancel(Constants.NOTIFICATION_ID_INCOMING_CALL)
                    nm.cancel(Constants.NOTIFICATION_ID_ONGOING_CALL)
                    nm.cancel(Constants.NOTIFICATION_ID_MISSED_CALL)
                } catch (_: Exception) {}
            }

            private fun Notification.applyLockedForegroundFlags(): Notification {
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
                return this
            }

            private fun Intent.attachCallUiHint(rawNumber: String, stateHint: String, contactNameHint: String, isConferenceHint: Boolean): Intent {
                putExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT, rawNumber)
                putExtra(CALL_UI_EXTRA_STATE_HINT, stateHint)
                putExtra(CALL_UI_EXTRA_CONTACT_NAME_HINT, contactNameHint)
                putExtra(CALL_UI_EXTRA_SIM_LABEL, lastCallUiSnapshot?.simLabel.orEmpty())
                putExtra(CALL_UI_EXTRA_IS_CONFERENCE_HINT, isConferenceHint)
                val isVideo = lastCallUiSnapshot?.isVideoCall == true ||
                    (getLiveCalls().firstOrNull()?.details?.videoState?.let { VideoProfile.isVideo(it) } == true)
                putExtra("EXTRA_IS_VIDEO_CALL", isVideo)
                return this
            }

            private fun stateToUiStateHint(state: Int): String {
                return when (state) {
                    Call.STATE_RINGING -> "RINGING"
                    Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> "DIALING"
                    Call.STATE_ACTIVE -> "ACTIVE"
                    Call.STATE_HOLDING -> "HOLDING"
                    else -> "IDLE"
                }
            }

            private fun launchCallUI(
                reason: String,
                showDialpad: Boolean = false,
                rawNumber: String = "",
                initialStateHint: String = "",
                initialNameHint: String = "",
                isConferenceHint: Boolean = false
            ) {
                val activeCalls = getLiveCalls().filter { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
                if (activeCalls.isEmpty()) {
                    Log.w(P2P_FORENSIC_TAG, "ui_launch_request blocked reason=$reason (zero active connected calls)")
                    return
                }
                val numberSnapshot = buildP2PNumberSnapshot(rawNumber)
                val now = System.currentTimeMillis()
                val launchKey = "${numberSnapshot.normalized.ifBlank { rawNumber }}|$showDialpad"
                val isExplicitAction = reason.contains("tap") || reason.contains("answer") || reason.contains("wake") || reason.contains("user") || reason.contains("incoming") || reason.contains("bubble")
                val suppressed = !isExplicitAction && launchKey.isNotBlank() && launchKey == lastUiLaunchKey && (now - lastUiLaunchAt) < 1500L
                val directBootPhase = isDirectBootPhase()
                Log.w(
                    P2P_FORENSIC_TAG,
                    "ui_launch_request reason=$reason raw=${maskP2PNumberForLog(rawNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)} stateHint=$initialStateHint nameHint=${maskP2PDisplayValueForLog(initialNameHint)} showDialpad=$showDialpad isConference=$isConferenceHint directBootPhase=$directBootPhase suppressed=$suppressed"
                )
                if (suppressed) {
                    return
                }
                lastUiLaunchAt = now
                lastUiLaunchKey = launchKey

                // ROOT CAUSE #3, #7, #15: Enhanced UI launch with OEM bypass
                val intent = Intent(this, CallActivity::class.java).apply {
                    action = CALL_UI_ACTION_WAKE
                    putExtra("WAKE_REASON", reason)
                    attachCallUiHint(rawNumber, initialStateHint, initialNameHint, isConferenceHint)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                }

                // ROOT CAUSE #15: Use comprehensive UI guarantee
                InCallServiceEnhancements.guaranteeUiDisplay(this, intent)
                
                Log.w(P2P_FORENSIC_TAG, "ui_launch_activity reason=$reason result=comprehensive_guarantee_applied")
            }

            private fun getContactName(phoneNumber: String): String {
                val numberSnapshot = buildP2PNumberSnapshot(phoneNumber)
                val lookupSources = mutableListOf<String>()
                Log.w(
                    P2P_FORENSIC_TAG,
                    "lookup_started flow=MyInCallService raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)}"
                )
                val cleanNum = phoneNumber.filter { it.isDigit() }
                if (cleanNum == String(Base64.decode("MTk4", 0))) {
                    try {
                        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val rawName = telephonyManager.networkOperatorName ?: ""
                        val cleanName = when {
                            rawName.contains("jio", ignoreCase = true) -> "Jio"
                            rawName.contains("airtel", ignoreCase = true) -> "Airtel"
                            rawName.contains("vi", ignoreCase = true) || rawName.contains("vodafone", ignoreCase = true) || rawName.contains("idea", ignoreCase = true) -> "Vi"
                            rawName.contains("bsnl", ignoreCase = true) -> "BSNL"
                            else -> rawName.replace(Regex("(?i)4g|5g|lte|ind|\\s+"), "").trim()
                        }
                        return if (cleanName.isNotEmpty()) "$cleanName Customer Care" else "Customer Care"
                    } catch (_: Exception) {
                        return "Customer Care"
                    }
                }
                if (cleanNum == String(Base64.decode("MTkxNQ==", 0))) {
                    return "National Consumer Helpline"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDExNDAwMA==", 0)))) {
                    return "National Consumer Helpline"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMzYyODY=", 0)))) {
                    return "Xiaomi Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDEyMzQ=", 0)))) {
                    return "SBI Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDIxMDA=", 0)))) {
                    return "SBI Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDExMjIxMQ==", 0)))) {
                    return "SBI Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTM4MDA=", 0)))) {
                    return "SBI Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDExMTEwOQ==", 0)))) {
                    return "SBI Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("OTQ0OTExMjIxMQ==", 0)))) {
                    return "SBI Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDE2MDA=", 0)))) {
                    return "HDFC Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2MDA=", 0)))) {
                    return "HDFC Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwODA=", 0)))) {
                    return "ICICI Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDIwOTU1Nzc=", 0)))) {
                    return "Axis Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMzU1Nzc=", 0)))) {
                    return "Axis Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTg2MDQxOTU1NTU=", 0)))) {
                    return "Axis Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTg2MDUwMDU1NTU=", 0)))) {
                    return "Axis Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDU3MDA=", 0)))) {
                    return "Bank of Baroda Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMjc3ODg=", 0)))) {
                    return "Bank of Baroda Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDIyMjI0NA==", 0)))) {
                    return "Union Bank Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDIwODIyNDQ=", 0)))) {
                    return "Union Bank Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTE1MTU=", 0)))) {
                    return "Union Bank Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTM1NTU=", 0)))) {
                    return "Union Bank Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTI0MDc=", 0)))) {
                    return "Union Bank Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDQyNTAwMDA=", 0)))) {
                    return "Indian Bank Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTg2MDI2Nzc3Nzc=", 0)))) {
                    return "IndusInd Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2NjA0NDc=", 0)))) {
                    return "IndusInd Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2NjA2MTY=", 0)))) {
                    return "IndusInd Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDEwMjMzMzM=", 0)))) {
                    return "IndusInd Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDI2NzM0NTY=", 0)))) {
                    return "HSBC Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDEyMTIyMDg=", 0)))) {
                    return "HSBC Customer Care"
                }
                if (cleanNum.contains(String(Base64.decode("MTgwMDIyMTkxMQ==", 0)))) {
                    return "Central Bank Customer Care"
                }

                var isUnlocked = true
                try {
                    val userManager = getSystemService(Context.USER_SERVICE) as UserManager
                    isUnlocked = userManager.isUserUnlocked
                } catch (_: Exception) {}

                val hasReadContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                if (isUnlocked && hasReadContacts) {
                    var foundName: String? = null
                    lookupSources.add("contacts_provider")
                    try {
                        val uri = Uri.withAppendedPath(
                            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                            Uri.encode(phoneNumber)
                        )
                        contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) foundName = cursor.getString(0)
                        }
                    } catch (_: Exception) {}
                    if (!foundName.isNullOrBlank()) {
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "lookup_final flow=MyInCallService raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} sources=$lookupSources finalSource=contacts_provider finalName=${maskP2PDisplayValueForLog(foundName.orEmpty())}"
                        )
                        return foundName!!
                    }
                } else if (!hasReadContacts) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "lookup_contacts_skip flow=MyInCallService raw=${maskP2PNumberForLog(phoneNumber)} reason=missing_READ_CONTACTS"
                    )
                }

                lookupSources.add("p2p_cache")
                Log.w("P2P_FORENSIC", "Checking unverified remote caller ID for ${maskPhoneNumber(phoneNumber)}")
                val p2pName = DirectBootCallManager.getDpsContactName(this, phoneNumber)
                val finalName = if (p2pName != "Unknown" && p2pName.isNotEmpty()) {
                    p2pName
                } else {
                    "Unknown"
                }
                Log.w(
                    P2P_FORENSIC_TAG,
                    "lookup_final flow=MyInCallService raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} sources=$lookupSources finalSource=${if (finalName != "Unknown") "p2p_cache" else "unknown"} finalName=${maskP2PDisplayValueForLog(finalName)}"
                )
                return finalName
            }

            private fun getContactPhotoUri(phoneNumber: String): String? {
                var isUnlocked = true
                try {
                    val userManager = getSystemService(Context.USER_SERVICE) as UserManager
                    isUnlocked = userManager.isUserUnlocked
                } catch (_: Exception) {}
                val hasReadContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                if (!isUnlocked || !hasReadContacts) return null
                return try {
                    val uri = Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(phoneNumber)
                    )
                    contentResolver.query(
                        uri,
                        arrayOf(
                            ContactsContract.PhoneLookup.PHOTO_URI,
                            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
                        ),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val fullPhotoUri = cursor.getString(0)
                            val thumbnailPhotoUri = cursor.getString(1)
                            fullPhotoUri?.takeIf { it.isNotBlank() }
                                ?: thumbnailPhotoUri?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }

            private fun showCustomMissedCallNotification(phoneNumber: String) {
                stopIncomingRingtone("showCustomMissedCallNotification", resetCurrentCall = true)
                val nm = getSystemService(NotificationManager::class.java)
                
                // CRITICAL FIX: Target MainActivity instead of CallActivity
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = "OPEN_RECENTS"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(this, phoneNumber.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val contactName = getContactName(phoneNumber) // Uses your existing getContactName function
                val displayText = if (contactName == "Unknown" || contactName == phoneNumber) phoneNumber else "$contactName ($phoneNumber)"

                val builder = NotificationCompat.Builder(this, incomingCallChannelId)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle("Missed Call")
                    .setContentText(displayText)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)

                nm.notify("missed_call_${phoneNumber.hashCode()}", phoneNumber.hashCode(), builder.build())
            }

            }












