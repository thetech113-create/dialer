@file:Suppress( "unused")

package com.caller

// ADDED IMPORTS FOR NATIVE IMAGE LOADING
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.caller.logging.UltraForensicLogger
import com.caller.ui.theme.CallerTheme
import com.caller.ui.theme.OneUi
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.graphics.vector.ImageVector
import com.caller.ui.theme.OneUiBottomTabs
import com.caller.ui.theme.OneUiCollapsingPage
import com.caller.ui.theme.oneUiColors
import com.caller.ui.theme.oneUiGroupRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("EXPERIMENTAL_API_USAGE")
data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null, // CRITICAL ADDITION: Store Google Sync Photo
    val rawContactId: String? = null
)

data class ContactDisplayEntry(
    val displayKey: String,
    val name: String,
    val phoneNumbers: List<String>,
    val photoUri: String? = null,
    val sourceContacts: List<Contact> = emptyList(),
    val isMerged: Boolean = false
)

enum class DuplicateReason { SAME_NAME, SIMILAR_NAME, SAME_NUMBER }

data class DuplicateContactCandidate(
    val mergeKey: String,
    val name: String,
    val contacts: List<Contact>,
    val reason: DuplicateReason = DuplicateReason.SAME_NAME
)

data class RecentCall(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val timestamp: Long,
    val callType: CallType,
    val duration: Long = 0L,
    val simLabel: String = "",
    val isVideo: Boolean = false,
    val isTollFree: Boolean = false
)

data class GroupedRecentCall(
    val phoneNumber: String,
    val name: String,
    val callHistory: List<CallHistoryItem>,
    val groupKey: String = phoneNumber,
    val providerRowIds: List<String> = emptyList(),
    val isVideo: Boolean = callHistory.firstOrNull()?.isVideo == true,
    val isTollFree: Boolean = callHistory.firstOrNull()?.isTollFree == true || !isNumberVideoCallCapable(phoneNumber)
)

data class CallHistoryItem(
    val timestamp: Long,
    val callType: CallType,
    val duration: Long = 0L,
    val formattedTime: String = "",
    val formattedDuration: String = "",
    val typeString: String = "",
    val isBlocked: Boolean = false,
    val simLabel: String = "",
    val isVideo: Boolean = false,
    val isTollFree: Boolean = false
)

private fun appendRecentSimLabel(baseText: String, simLabel: String): String {
    return when {
        simLabel.isBlank() -> baseText
        baseText.isBlank() -> simLabel
        else -> "$baseText • $simLabel"
    }
}

private fun buildRecentCallerGroupKey(phoneNumber: String): String {
    val snapshot = buildP2PNumberSnapshot(phoneNumber)
    // The shared snapshot drops the leading 1 of every 11-digit number (a +1 country-code rule), which
    // made 18004190157 and 8004190157 one Recents row. Only strip it when the number really carries a
    // country code ("+1..."); a bare 11-digit number starting with 1 is its own number.
    val hasExplicitCountryCode = snapshot.raw.startsWith("+")
    val normalized = if (!hasExplicitCountryCode && snapshot.digits.length == 11 && snapshot.digits.startsWith("1")) {
        snapshot.digits
    } else {
        snapshot.normalized
    }
    return normalized.ifBlank {
        phoneNumber.trim().lowercase(Locale.ROOT)
    }
}

private fun mergeLiveRecentCallGroup(
    groupedRecentCalls: List<GroupedRecentCall>,
    isInCall: Boolean,
    primaryState: String,
    primaryNumber: String,
    primaryName: String,
    simLabel: String
): List<GroupedRecentCall> {
    val persistedGroups = groupedRecentCalls.filterNot { groupedCall ->
        groupedCall.providerRowIds.isEmpty() &&
            groupedCall.callHistory.size == 1 &&
            groupedCall.callHistory.firstOrNull()?.formattedTime == "Now"
    }
    if (!isInCall || primaryNumber.isBlank()) {
        return persistedGroups
    }

    val liveTypeText = when (primaryState) {
        "RINGING" -> "Incoming..."
        "DIALING", "CONNECTING" -> "Dialing..."
        "HOLDING" -> "On Hold"
        else -> "Ongoing call"
    }
    val liveCallType = if (primaryState == "RINGING") CallType.INCOMING else CallType.OUTGOING
    val groupKey = buildRecentCallerGroupKey(primaryNumber)
    val liveGroupedCall = GroupedRecentCall(
        phoneNumber = primaryNumber,
        name = primaryName.ifBlank { primaryNumber.ifBlank { "Unknown" } },
        callHistory = listOf(
            CallHistoryItem(
                timestamp = System.currentTimeMillis(),
                callType = liveCallType,
                duration = 0L,
                formattedTime = "Now",
                formattedDuration = liveTypeText,
                typeString = liveTypeText,
                isBlocked = false,
                simLabel = simLabel
            )
        ),
        groupKey = groupKey,
        providerRowIds = emptyList()
    )

    return buildList {
        add(liveGroupedCall)
        addAll(persistedGroups.filterNot { it.groupKey == groupKey })
    }
}

@Composable
private fun RecentSubtitleLine(
    baseText: String,
    simLabel: String,
    textColor: Color,
    simBadgeColor: Color,
    simTextColor: Color
) {
    if (simLabel.isBlank()) {
        Text(
            text = baseText,
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = baseText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = simBadgeColor
        ) {
            Text(
                text = simLabel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = simTextColor,
                maxLines = 1
            )
        }
    }
}

private data class RecentCallRow(
    val id: String,
    val cachedName: String,
    val phoneNumber: String,
    val timestamp: Long,
    val callType: CallType,
    val duration: Long,
    val phoneAccountId: String? = null,
    val phoneAccountComponentName: String? = null,
    val isVideo: Boolean = false,
    val isTollFree: Boolean = false
)

private data class PendingRecentDeleteRequest(
    val phoneNumber: String,
    val displayName: String,
    val groupKey: String,
    val targetRowId: String = "",
    val providerRowIds: List<String>,
    val bucketSize: Int
)

private data class RecentDeleteResult(
    val requestedRows: Int,
    val deletedRows: Int,
    val resolvedRowIds: List<String>,
    val remainingRowIds: List<String>
)

data class AppGlobalState(
    val contacts: List<Contact> = emptyList(),
    val recentCalls: List<RecentCall> = emptyList(),
    val groupedRecentCalls: List<GroupedRecentCall> = emptyList(),
    val callState: CallState = CallState(),
    val profileName: String = "",
    val isLoading: Boolean = false,
    val selectedTab: Int = TAB_CONTACTS,
    val prefilledNumber: String = "",
    val recentSearchQuery: String = "",
    val recentDialpadVisible: Boolean = false,
    val recentDialpadDigits: String = "",
    val refreshTrigger: Int = 0,
    val isDefaultDialer: Boolean = false,
    val isPowerSaveMode: Boolean = false
)

private const val CONTACT_MERGE_DECISIONS_KEY = "contact_merge_decisions"
private const val CONTACT_MERGE_DECISION_MERGED = "merged"
private const val CONTACT_MERGE_DECISION_SEPARATE = "separate"
private const val BATTERY_OPTIMIZATION_PROMPT_DISMISSED_KEY = "battery_optimization_prompt_dismissed"
private const val XIAOMI_OTHER_PERMISSIONS_PROMPT_DISMISSED_KEY = "xiaomi_other_permissions_prompt_dismissed"
private const val TAB_CONTACTS = 0
private const val TAB_RECENTS = 1
private const val TAB_PROFILE = 2
private const val TAB_VOICEMAIL = 3
private const val TAB_KEYPAD = 4
private const val TAB_SETTINGS = 5
private const val STATE_SELECTED_TAB_KEY = "main_selected_tab"
private const val STATE_RECENT_SEARCH_QUERY_KEY = "main_recent_search_query"
private const val STATE_RECENT_DIALPAD_VISIBLE_KEY = "main_recent_dialpad_visible"
private const val STATE_RECENT_DIALPAD_DIGITS_KEY = "main_recent_dialpad_digits"

enum class CallType {
    OUTGOING,
    INCOMING,
    MISSED
}

private enum class RecentFilter(val label: String) {
    ALL("All"),
    MISSED("Missed"),
    INCOMING("Incoming"),
    OUTGOING("Outgoing"),
    BLOCKED("Blocked")
}

// Also used by CallScreen so the call screen shows a number the same way as Keypad and Recents.
internal fun formatPhoneNumberForDisplay(phoneNumber: String): String {
    if (phoneNumber.contains("*") || phoneNumber.contains("#")) return phoneNumber
    val digitsOnly = phoneNumber.filter { it.isDigit() }
    // One look for every Indian mobile, however it was saved ("+919900184987", "+91 99001 84987",
    // "9900184987"): "+91 99001 84987".
    val indianMobile = when {
        digitsOnly.length == 10 && !phoneNumber.trim().startsWith("+") -> digitsOnly
        digitsOnly.length == 12 && digitsOnly.startsWith("91") -> digitsOnly.drop(2)
        else -> null
    }?.takeIf { it[0] in '6'..'9' }
    if (indianMobile != null) return "+91 ${indianMobile.take(5)} ${indianMobile.takeLast(5)}"
    if (phoneNumber.startsWith("+")) return phoneNumber
    if (digitsOnly.startsWith("1800") || digitsOnly.startsWith("1860") || digitsOnly.length <= 5) return phoneNumber
    if (digitsOnly.startsWith("0")) return phoneNumber
    // Only Indian mobile numbers (10 digits starting 6-9) are shown as +91. Length alone isn't enough:
    // 10-digit service numbers such as 1600303088 are not mobiles and +911600303088 is not a real number.
    if (digitsOnly.length == 10 && digitsOnly[0] in '6'..'9') return "+91$digitsOnly"
    if (digitsOnly.length == 12 && digitsOnly.startsWith("91")) return "+$digitsOnly"
    return phoneNumber
}

private fun sanitizeDisplayNameForUi(name: String): String {
    val cleaned = name
        .replace(Regex("[\\p{Cntrl}\\p{Cf}]"), "")
        .replace(Regex("[\\p{So}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.ifBlank { name.trim() }
}

private fun mapContactNameToT9Digits(name: String): String {
    return buildString(name.length) {
        name.uppercase().forEach { c ->
            val mapped = when (c) {
                in 'A'..'C' -> '2'
                in 'D'..'F' -> '3'
                in 'G'..'I' -> '4'
                in 'J'..'L' -> '5'
                in 'M'..'O' -> '6'
                in 'P'..'S' -> '7'
                in 'T'..'V' -> '8'
                in 'W'..'Z' -> '9'
                else -> null
            }
            if (mapped != null) append(mapped)
        }
    }
}

private fun sanitizeDialableClipboardText(rawText: String): String {
    if (rawText.isBlank()) return ""
    val trimmed = rawText.trim()
    val builder = StringBuilder()
    trimmed.forEachIndexed { index, char ->
        when {
            char.isDigit() || char == '*' || char == '#' -> builder.append(char)
            char == '+' && index == 0 && builder.isEmpty() -> builder.append(char)
        }
    }
    return builder.toString().take(15)
}

private fun normalizeDuplicateContactName(name: String): String {
    return name.trim().lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Suppress("HandlerLeak")
class MainActivity : ComponentActivity() {
    companion object {
        // Newest call time Recents has already shown. Lives for the whole app process, so reloads, tab switches
        // and the screen being recreated after a call don't count as "a new call" (scroll-to-top only then).
        // Usage access prompt: at most once per app process.
        @Volatile private var usageAccessAskedThisSession = false
        @Volatile private var recentCallsRequestGeneration = 0L
        @Volatile private var recentCallsAppliedGeneration = 0L
        @Volatile private var lastSeenNewestRecentCallTopId = ""
        // Set when a call with a new person lands on top; cleared only after the scroll has actually run.
        @Volatile private var pendingRecentScrollToTop = false
        // How long (in frames, ~1 s) Recents keeps checking that the new call really is the top row.
        private const val RECENTS_SCROLL_TO_NEW_CALL_MAX_FRAMES = 60

        // How long a P2P-shared caller name stays visible in Recents after it was received. Same as the
        // P2P cache validity the call screen uses (was 6h, so a name shown on the call screen vanished here).
        private const val RECENTS_P2P_NAME_WINDOW_MS = P2P_PROFILE_CACHE_TTL_MS

        @Volatile
        private var incomingUiGateMainVisible: Boolean = false

        fun isIncomingUiGateMainVisible(): Boolean = incomingUiGateMainVisible
    }

    private fun isPhoneLauncherIntent(intent: Intent?): Boolean {
        return intent?.component?.className == "$packageName.PhoneLauncherAlias"
    }

    private lateinit var sharedPreferences: SharedPreferences
    private var uiState by mutableStateOf(AppGlobalState())
    
    private var googleSyncObserver: ContentObserver? = null
    private var isGoogleSyncObserverRegistered = false
    private var recentCallsRefreshJob: Job? = null
    private var pendingRecentCallsRefresh = false
    private var pendingRecentCallsRefreshDelay = false
    private var contactMergeDecisions by mutableStateOf<Map<String, String>>(emptyMap())
    private var pendingDuplicateCandidate by mutableStateOf<DuplicateContactCandidate?>(null)
    // The check opens from the Contacts "duplicates" button, which shows only while a duplicate is unanswered.
    private var showDuplicateDialog by mutableStateOf(false)
    // Groups answered "One Person" whose merge hasn't finished/reloaded yet: skip them so the next question
    // is the next real duplicate, not the same pair again.
    private val duplicateKeysBeingMerged = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var duplicatesRemaining by mutableStateOf(0)
    private var activeMergedContactEntry by mutableStateOf<ContactDisplayEntry?>(null)
    private var isMainActivityVisible = false
    private var showBatteryOptimizationPrompt by mutableStateOf(false)
    private var showXiaomiOtherPermissionsPrompt by mutableStateOf(false)
    private val forensicProbeHandler = Handler(Looper.getMainLooper())
    private var pendingOemFallbackProbe: Runnable? = null
    private val powerSaveModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                refreshPowerSaveModeState("broadcast")
                scheduleOemDialerFallbackProbe("power_save_mode_changed")
            }
        }
    }

    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SILENCE_RINGTONE" -> {
                    return
                }
                "FINISH_CALL_ACTIVITY" -> {
                    uiState = uiState.copy(callState = CallState())
                    refreshRecentCalls(withDelay = true)
                    return
                }
                P2P_CACHE_UPDATED_ACTION -> {
                    val transportRaw = intent.getStringExtra("transport_number_raw") ?: ""
                    val transportNormalized = intent.getStringExtra("transport_number_normalized") ?: ""
                    val profileName = intent.getStringExtra("profile_name") ?: ""
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "ui_refresh_trigger action=$P2P_CACHE_UPDATED_ACTION transportRaw=${maskP2PNumberForLog(transportRaw)} transportNormalized=${maskP2PNumberForLog(transportNormalized)} profileName=${maskP2PDisplayValueForLog(profileName)} activeCallNumber=${maskP2PNumberForLog(uiState.callState.phoneNumber)}"
                    )
                    forceUpdateUI()
                    refreshRecentCalls(withDelay = false)
                    MyInCallService.getInstance()?.forceUpdateUI()
                    return
                }
                SMS_THREADS_UPDATED_ACTION -> {
                    forceUpdateUI()
                    return
                }
                "CALL_STATE_UPDATED" -> {
                    val isInCall = intent.getBooleanExtra("IS_IN_CALL", false)
                    val primaryState = intent.getStringExtra("PRIMARY_STATE") ?: "IDLE"
                    val primaryNumber = intent.getStringExtra("PRIMARY_NUMBER") ?: ""
                    val primaryName = intent.getStringExtra("PRIMARY_NAME") ?: "Unknown"
                    val simLabel = intent.getStringExtra("SIM_LABEL") ?: ""

                    uiState = uiState.copy(
                        callState = uiState.callState.copy(
                            isInCall = isInCall,
                            primaryState = primaryState,
                            isRinging = primaryState == "RINGING",
                            isDialing = primaryState == "DIALING",
                            isHolding = primaryState == "HOLDING",
                            phoneNumber = primaryNumber,
                            contactName = primaryName,
                            simLabel = simLabel
                        ),
                        groupedRecentCalls = mergeLiveRecentCallGroup(
                            groupedRecentCalls = uiState.groupedRecentCalls,
                            isInCall = isInCall,
                            primaryState = primaryState,
                            primaryNumber = primaryNumber,
                            primaryName = primaryName,
                            simLabel = simLabel
                        )
                    )

                    if (!isInCall || primaryState == "IDLE") {
                        refreshRecentCalls(withDelay = true)
                    }
                }
            }
        }
    }

    private val restorePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleRestoreUri(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.READ_CONTACTS] == true) {
            syncContactsObserverRegistration()
            loadContacts()
            if (hasReadCallLogPermission()) {
                refreshRecentCalls()
            }
            loadProfileBackup()
        } else {
            if (hasReadContactsPermission()) {
                syncContactsObserverRegistration()
                loadContacts()
                if (hasReadCallLogPermission()) {
                    refreshRecentCalls()
                }
                loadProfileBackup()
            } else {
                syncContactsObserverRegistration()
                showError("Contacts permission is required to load contacts")
            }
        }
    }

    private var dialerRoleRequestLaunchedAtMs = -1L
    private val DIALER_ROLE_SILENT_DENY_THRESHOLD_MS = 700L

    private val dialerRoleRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.e("CMF_DIALER_DEBUG", "=== DIALER ROLE RESULT RECEIVED ===")
        Log.e("CMF_DIALER_DEBUG", "Result code: ${result.resultCode} (RESULT_OK=${Activity.RESULT_OK})")
        Log.e("CMF_DIALER_DEBUG", "Result data: ${result.data}")

        val userGrantedRole = result.resultCode == Activity.RESULT_OK
        val currentDefault = getDefaultDialerPackageSafely()
        Log.e("CMF_DIALER_DEBUG", "User granted via result: $userGrantedRole")
        Log.e("CMF_DIALER_DEBUG", "Current default dialer after activity: $currentDefault")
        Log.e("CMF_DIALER_DEBUG", "App package: $packageName")

        val isNowDefault = userGrantedRole || currentDefault == packageName
        Log.e("CMF_DIALER_DEBUG", "Is now default (final): $isNowDefault")

        // After the user denies the role request twice (or ticks "Don't ask again"), Android stops
        // showing the dialog and returns RESULT_CANCELED almost instantly. Detect that and send the
        // user to the system "Default apps" screen, where the phone app can still be chosen manually.
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - dialerRoleRequestLaunchedAtMs
        if (!isNowDefault && elapsedMs in 0L..DIALER_ROLE_SILENT_DENY_THRESHOLD_MS) {
            Log.e("CMF_DIALER_DEBUG", "Role dialog auto-denied by system (elapsed=${elapsedMs}ms) — opening Default apps settings")
            openDefaultAppsSettings()
            return@registerForActivityResult
        }

        uiState = uiState.copy(isDefaultDialer = isNowDefault)

        if (isNowDefault) {
            Log.e("CMF_DIALER_DEBUG", "SUCCESS: App is now default dialer")
            checkPermissionsAndLoadContacts()
            refreshPowerPermissionPrompts()
        } else {
            Log.e("CMF_DIALER_DEBUG", "FAILED: App is NOT default dialer after request")
            showBatteryOptimizationPrompt = false
            showXiaomiOtherPermissionsPrompt = false
            showError("Please set this app as default dialer to use all features")
        }
    }

    private val contactsRoleRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val userGrantedRole = result.resultCode == Activity.RESULT_OK
        
        if (userGrantedRole) {
            showSuccess("Set as default contacts app")
        } else {
            showError("Failed to set as default contacts app")
        }
    }

    private val callScreeningRoleRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isCallScreeningRoleHeld()) {
            showSuccess("Call blocking enabled")
        } else {
            showError("Allow call screening to block future calls")
        }
    }

    private val batteryOptimizationRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPowerPermissionPrompts()
    }

    private fun forceUpdateUI() {
        runOnUiThread { uiState = uiState.copy(refreshTrigger = uiState.refreshTrigger + 1) }
    }

    private fun restoreSavedMainState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val restoredDigits = sanitizeDialableClipboardText(
            savedInstanceState.getString(STATE_RECENT_DIALPAD_DIGITS_KEY).orEmpty()
        )
        uiState = uiState.copy(
            selectedTab = savedInstanceState.getInt(STATE_SELECTED_TAB_KEY, TAB_CONTACTS).coerceIn(TAB_CONTACTS, TAB_KEYPAD),
            recentSearchQuery = savedInstanceState.getString(STATE_RECENT_SEARCH_QUERY_KEY).orEmpty(),
            recentDialpadVisible = savedInstanceState.getBoolean(STATE_RECENT_DIALPAD_VISIBLE_KEY, false) || restoredDigits.isNotEmpty(),
            recentDialpadDigits = restoredDigits
        )
    }

    private fun isPowerSaveModeEnabled(): Boolean {
        return try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            powerManager.isPowerSaveMode
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "power_save_mode action=query result=failure error=${e.message}", e)
            false
        }
    }

    private fun refreshPowerSaveModeState(trigger: String) {
        val enabled = isPowerSaveModeEnabled()
        uiState = uiState.copy(isPowerSaveMode = enabled)
        Log.w(
            P2P_FORENSIC_TAG,
            "power_save_mode action=refresh trigger=$trigger enabled=$enabled selectedTab=${uiState.selectedTab} dialpadVisible=${uiState.recentDialpadVisible} digits=${maskP2PNumberForLog(uiState.recentDialpadDigits)}"
        )
    }

    private fun logOemDialerFallbackProbe(trigger: String, stage: String) {
        val powerSaveMode = isPowerSaveModeEnabled()
        val stickySnapshot = MyInCallService.peekLastCallUiSnapshot()
        val inCallServiceBound = MyInCallService.getInstance() != null
        val telecomInCall = isTelecomInCallSafely()
        val defaultDialerPackage = getDefaultDialerPackageSafely()
        val isDefaultDialer = defaultDialerPackage == packageName
        val batteryExempt = isIgnoringBatteryOptimizationsForApp()
        val liveFrameworkCall = telecomInCall

        val evidence = mutableListOf<String>()
        if (powerSaveMode) evidence += "power_save_active"
        if (liveFrameworkCall) evidence += "framework_call_alive"
        if (uiState.callState.isInCall) evidence += "main_ui_in_call"
        if (stickySnapshot?.isInCall == true) evidence += "sticky_call_snapshot"
        if (!inCallServiceBound) evidence += "incall_service_missing"
        if (!isDefaultDialer) evidence += "default_dialer_role_not_ours"
        if (!batteryExempt) evidence += "battery_exemption_missing"

        val oemFallbackSuspected =
            powerSaveMode &&
                liveFrameworkCall &&
                (!inCallServiceBound || !isDefaultDialer) &&
                (uiState.callState.isInCall || stickySnapshot?.isInCall == true)

        val message =
            "oem_dialer_fallback_probe trigger=$trigger stage=$stage powerSaveMode=$powerSaveMode " +
                "telecomInCall=$telecomInCall " +
                "uiInCall=${uiState.callState.isInCall} stickyInCall=${stickySnapshot?.isInCall == true} " +
                "stickyState=${stickySnapshot?.primaryState.orEmpty()} stickyCallId=${stickySnapshot?.primaryCallId.orEmpty()} " +
                "inCallServiceBound=$inCallServiceBound isDefaultDialer=$isDefaultDialer " +
                "defaultDialerPackage=${if (defaultDialerPackage.isBlank()) "unknown" else defaultDialerPackage} " +
                "batteryExempt=$batteryExempt evidence=${if (evidence.isEmpty()) "none" else evidence.joinToString("+")} " +
                "oem_fallback_path_reached=${if (oemFallbackSuspected) "yes" else "no"}"

        if (oemFallbackSuspected) {
            Log.wtf(P2P_FORENSIC_TAG, message)
            UltraForensicLogger.ultra("MainActivity", "OEM_DIALER_FALLBACK_SUSPECTED", message)
        } else {
            Log.w(P2P_FORENSIC_TAG, message)
        }
    }

    private fun scheduleOemDialerFallbackProbe(trigger: String) {
        pendingOemFallbackProbe?.let { forensicProbeHandler.removeCallbacks(it) }
        logOemDialerFallbackProbe(trigger, "immediate")

        val shouldScheduleDelayedProbe =
            hasLiveFrameworkCall() ||
                uiState.callState.isInCall ||
                MyInCallService.peekLastCallUiSnapshot()?.isInCall == true

        if (!shouldScheduleDelayedProbe) {
            pendingOemFallbackProbe = null
            return
        }

        val probe = Runnable {
            pendingOemFallbackProbe = null
            logOemDialerFallbackProbe(trigger, "delayed_1500ms")
        }
        pendingOemFallbackProbe = probe
        forensicProbeHandler.postDelayed(probe, 1500L)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
        handleIncomingCallIntent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB_KEY, uiState.selectedTab)
        outState.putString(STATE_RECENT_SEARCH_QUERY_KEY, uiState.recentSearchQuery)
        outState.putBoolean(STATE_RECENT_DIALPAD_VISIBLE_KEY, uiState.recentDialpadVisible)
        outState.putString(STATE_RECENT_DIALPAD_DIGITS_KEY, uiState.recentDialpadDigits)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        isMainActivityVisible = true
        incomingUiGateMainVisible = true

        // Every time the app comes to the front (not only first start): right after Clear data Android can
        // refuse the system block list for a moment, so a later resume must still restore the blocks.
        CoroutineScope(Dispatchers.IO).launch {
            syncSystemBlockedNumbersToLocal()
            // Redraw so restored blocks show as blocked in Recents.
            withContext(Dispatchers.Main) {
                uiState = uiState.copy(groupedRecentCalls = groupRecentCalls(uiState.recentCalls))
            }
        }

        uiState = uiState.copy(
            isDefaultDialer = getDefaultDialerPackageSafely() == packageName
        )
        if (uiState.isDefaultDialer) maybeAskUsageAccess()
        AppUpdateManager.checkAndInstallPending(this)
        syncContactsObserverRegistration()
        refreshPowerSaveModeState("resume")
        refreshPowerPermissionPrompts()
        if (uiState.isPowerSaveMode || hasLiveFrameworkCall() || MyInCallService.peekLastCallUiSnapshot()?.isInCall == true) {
            scheduleOemDialerFallbackProbe("main_onResume")
        }

        if (hasReadCallLogPermission()) {
            refreshRecentCalls(withDelay = false)
        }
        if (hasReadContactsPermission()) {
            CoroutineScope(Dispatchers.IO).launch { loadContacts() }
        }
        forceUpdateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val splashStartMs = android.os.SystemClock.elapsedRealtime()
        splashScreen.setKeepOnScreenCondition {
            android.os.SystemClock.elapsedRealtime() - splashStartMs < 250L
        }
        UltraForensicLogger.init(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        enableEdgeToEdge()

        // A throttled, non-interactive check.  The updater only downloads a release
        // whose manifest versionCode is greater than this installed build.
        AppUpdateManager.checkForUpdate(this)

        val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() ?: this else this
        sharedPreferences = dpsContext.getSharedPreferences("recent_calls", MODE_PRIVATE)
        contactMergeDecisions = loadContactMergeDecisions()
        restoreSavedMainState(savedInstanceState)
        refreshPowerSaveModeState("create")

        handleIncomingCallIntent()

        SmsMonitoringService.startService(this)
        OtpAutoFillService.startOtpAutoFill(this)

        unregisterAppPhoneAccountsSafely()

        val launchAction = intent?.action
        if (
            savedInstanceState == null &&
            !isPhoneLauncherIntent(intent) &&
            (
                launchAction == Intent.ACTION_MAIN ||
                    (launchAction.isNullOrBlank() && intent?.data == null)
            )
        ) {
            launchMessagingActivity()
            finish()
            return
        }

        MyInCallService.setCallStateListener { stateObj ->
            runOnUiThread {
                val mergedRecentCalls = mergeLiveRecentCallGroup(
                    groupedRecentCalls = uiState.groupedRecentCalls,
                    isInCall = stateObj.isInCall,
                    primaryState = stateObj.primaryState,
                    primaryNumber = stateObj.primaryNumber,
                    primaryName = stateObj.primaryName,
                    simLabel = stateObj.simLabel
                )

                if (!stateObj.isInCall) {
                    uiState = uiState.copy(
                        callState = CallState(),
                        groupedRecentCalls = mergedRecentCalls
                    )
                    return@runOnUiThread
                }

                val shouldEvaluateIncomingUi = stateObj.primaryState == "RINGING"

                uiState = uiState.copy(
                    callState = uiState.callState.copy(
                        isInCall = true,
                        primaryState = stateObj.primaryState,
                        isRinging = stateObj.primaryState == "RINGING",
                        isDialing = stateObj.primaryState == "DIALING",
                        isHolding = stateObj.primaryState == "HOLDING",
                        phoneNumber = stateObj.primaryNumber,
                        contactName = stateObj.primaryName,
                        simLabel = stateObj.simLabel
                    ),
                    groupedRecentCalls = mergedRecentCalls
                )

                if (shouldEvaluateIncomingUi) {
                    val incomingDecision = resolveIncomingCallUiDecision(
                        foregroundPackage = if (isMainActivityVisible) packageName else "",
                        myPackage = packageName,
                        defaultDialerPackage = getDefaultDialerPackageSafely(),
                        launcherPackage = getDefaultLauncherPackageSafely()
                    )
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "incoming_ui_dispatch source=MainActivity.callStateListener visible=$isMainActivityVisible allow=${incomingDecision.allowFullScreen} reason=${incomingDecision.reason}"
                    )
                }
            }
        }

        MyInCallService.getInstance()?.forceUpdateUI()

        val filter = IntentFilter().apply {
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction("CALL_STATE_UPDATED")
            addAction("SILENCE_RINGTONE")
            addAction("FINISH_CALL_ACTIVITY")
            addAction(P2P_CACHE_UPDATED_ACTION)
            addAction(SMS_THREADS_UPDATED_ACTION)
        }
        ContextCompat.registerReceiver(this, callStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            powerSaveModeReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        googleSyncObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                UltraForensicLogger.debug("Google Cloud Sync detected! Auto-refreshing contacts...")
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    CoroutineScope(Dispatchers.IO).launch { loadContacts() }
                }
            }
        }
        syncContactsObserverRegistration()

        setContent {
            val isDark = isSystemInDarkTheme()
            val view = LocalView.current

            CallerTheme(darkTheme = isDark) {
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view)
                            .isAppearanceLightStatusBars = !isDark
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!uiState.isDefaultDialer) {
                        WelcomeScreen(onSetDefaultDialer = { requestDefaultDialerRole() })
                    } else {
                        LaunchedEffect(Unit) {
                            if (!hasReadContactsPermission() || !hasReadCallLogPermission()) {
                                Log.w(
                                    P2P_FORENSIC_TAG,
                                    "default_ui_bootstrap action=request_permissions readContacts=${hasReadContactsPermission()} readCallLog=${hasReadCallLogPermission()}"
                                )
                                checkPermissionsAndLoadContacts()
                            }
                            if (hasReadContactsPermission()) {
                                val cached = withContext(Dispatchers.IO) { loadContactsFromPreferences() }
                                if (cached.isNotEmpty()) {
                                    uiState = uiState.copy(contacts = cached)
                                    enqueueDuplicateContactCandidate(cached)
                                }
                            }
                            uiState = uiState.copy(isLoading = true)
                            CoroutineScope(Dispatchers.IO).launch {
                                if (hasReadContactsPermission()) {
                                    loadContacts()
                                } else {
                                    Log.w(P2P_FORENSIC_TAG, "default_ui_bootstrap action=skip_contacts reason=missing_READ_CONTACTS")
                                }
                                if (hasReadCallLogPermission()) {
                                    loadRecentCalls()
                                } else {
                                    Log.w(P2P_FORENSIC_TAG, "default_ui_bootstrap action=skip_calllog reason=missing_READ_CALL_LOG")
                                }
                            }
                        }

                        var showAddContactDialog by remember { mutableStateOf(false) }
                        var addContactPrefillNumber by remember { mutableStateOf("") }
                        var pendingMergedContactDelete by remember { mutableStateOf<Contact?>(null) }
                        val contactDisplayEntries = remember(uiState.contacts, contactMergeDecisions) {
                            buildContactDisplayEntries(uiState.contacts, contactMergeDecisions)
                        }
                        val handleDeleteContact: (Contact) -> Unit = { contact ->
                            val updatedContacts = if (!contact.rawContactId.isNullOrBlank()) {
                                uiState.contacts.filter { it.rawContactId != contact.rawContactId }
                            } else {
                                uiState.contacts.filter { it.id != contact.id }
                            }
                            uiState = uiState.copy(contacts = updatedContacts)
                            pendingDuplicateCandidate = null
                            enqueueDuplicateContactCandidate(updatedContacts)
                            CoroutineScope(Dispatchers.IO).launch {
                                deleteContactFromSystem(contact.id, contact.rawContactId)
                                val cached = if (!contact.rawContactId.isNullOrBlank()) {
                                    loadContactsFromPreferences().filter { it.rawContactId != contact.rawContactId }
                                } else {
                                    loadContactsFromPreferences().filter { it.id != contact.id }
                                }
                                saveContactsToPreferences(cached)
                            }
                        }

                        Scaffold(
                            modifier = Modifier.fillMaxSize()
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                MainScreen(
                                    contacts = uiState.contacts,
                                    contactDisplayEntries = contactDisplayEntries,
                                    recentCalls = uiState.recentCalls,
                                    groupedRecentCalls = uiState.groupedRecentCalls,
                                    isLoading = uiState.isLoading,
                                    selectedTab = uiState.selectedTab,
                                    prefilledNumber = uiState.prefilledNumber,
                                    recentSearchQuery = uiState.recentSearchQuery,
                                    recentDialpadVisible = uiState.recentDialpadVisible,
                                    recentDialpadDigits = uiState.recentDialpadDigits,
                                    refreshTrigger = uiState.refreshTrigger,
                                    isPowerSaveMode = uiState.isPowerSaveMode,
                                    isInCall = uiState.callState.isInCall,
                                    onTabSelected = { tab ->
                                        // The keypad now lives in its own tab; leaving it closes the dialpad view.
                                        uiState = if (tab == TAB_KEYPAD) {
                                            uiState.copy(selectedTab = tab)
                                        } else {
                                            uiState.copy(selectedTab = tab, recentDialpadVisible = false)
                                        }
                                    },
                                    onRecentSearchQueryChange = { query -> uiState = uiState.copy(recentSearchQuery = query) },
                                    onRecentDialpadVisibilityChange = { visible -> uiState = uiState.copy(recentDialpadVisible = visible) },
                                    onRecentDialpadDigitsChange = { digits ->
                                        uiState = uiState.copy(recentDialpadDigits = sanitizeDialableClipboardText(digits))
                                    },
                                    onContactClick = { phoneNumber -> startCall(phoneNumber) },
                                    onContactEntryClick = { entry ->
                                        if (entry.phoneNumbers.size > 1) {
                                            activeMergedContactEntry = entry
                                        } else {
                                            entry.phoneNumbers.firstOrNull()?.let { phoneNumber ->
                                                startCall(phoneNumber)
                                            }
                                        }
                                    },
                                    onRecentCallClick = { phoneNumber -> startCall(phoneNumber) },
                                    onRefresh = {
                                        checkPermissionsAndLoadContacts()
                                        if (hasReadCallLogPermission()) {
                                            refreshRecentCalls(withDelay = false)
                                        }
                                    },
                                    onOpenAddContact = { showAddContactDialog = true },
                                    onOpenAddContactWithNumber = { num ->
                                        addContactPrefillNumber = num
                                        showAddContactDialog = true
                                    },
                                    onRequestDefaultDialer = { requestDefaultDialerRole() },
                                    onPrefilledNumberConsumed = {
                                        if (uiState.prefilledNumber.isNotEmpty()) {
                                            uiState = uiState.copy(prefilledNumber = "")
                                        }
                                    },
                                    onDeleteContact = handleDeleteContact,
                                    onDeleteRecentCall = { request ->
                                        Log.w(
                                            P2P_FORENSIC_TAG,
                                            "recent_delete action=confirm groupKey=${request.groupKey} phone=${maskP2PNumberForLog(request.phoneNumber)} targetId=${request.targetRowId} remainingInBucket=${request.bucketSize - 1}"
                                        )
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val deleteResult = deleteRecentCallFromSystem(request)
                                            if (deleteResult.deletedRows > 0) {
                                                val providerRowIdsSet = deleteResult.resolvedRowIds.toSet()
                                                withContext(Dispatchers.Main) {
                                                    val updatedRecentCalls = uiState.recentCalls.filterNot { providerRowIdsSet.contains(it.id) }
                                                    val updatedGroupedCalls = groupRecentCalls(updatedRecentCalls)
                                                    uiState = uiState.copy(
                                                        recentCalls = updatedRecentCalls,
                                                        groupedRecentCalls = updatedGroupedCalls
                                                    )
                                                    pendingRecentScrollToTop = true
                                                }
                                                if (deleteResult.remainingRowIds.isEmpty()) {
                                                    sharedPreferences.edit().remove("recent_call_${request.phoneNumber}").apply()
                                                }
                                                Log.w(
                                                    P2P_FORENSIC_TAG,
                                                    "recent_delete action=refresh_start groupKey=${request.groupKey} phone=${maskP2PNumberForLog(request.phoneNumber)} remainingRows=${deleteResult.remainingRowIds.size}"
                                                )
                                                recentCallsRefreshJob?.cancel()
                                                pendingRecentCallsRefresh = false
                                                pendingRecentCallsRefreshDelay = false
                                                refreshRecentCalls(withDelay = false)
                                            } else {
                                                Log.w(
                                                    P2P_FORENSIC_TAG,
                                                    "recent_delete action=failed expectedRows=${deleteResult.requestedRows} deletedRows=${deleteResult.deletedRows} groupKey=${request.groupKey} phone=${maskP2PNumberForLog(request.phoneNumber)}"
                                                )
                                                withContext(Dispatchers.Main) {
                                                    showError("Failed to delete recent call from system log")
                                                }
                                            }
                                        }
                                    },
                                    onToggleBlockPhoneNumber = { phoneNumber ->
                                        togglePhoneNumberBlockState(phoneNumber)
                                    }
                                )

                                if (showDuplicateDialog) pendingDuplicateCandidate?.let { candidate ->
                                    // Different names in the group: the user picks which one to keep.
                                    val nameChoices = remember(candidate.mergeKey) {
                                        candidate.contacts.map { it.name.trim() }.filter { it.isNotBlank() }.distinct()
                                    }
                                    var keepName by remember(candidate.mergeKey) {
                                        mutableStateOf(nameChoices.maxByOrNull { it.length } ?: candidate.name)
                                    }
                                    AlertDialog(
                                        onDismissRequest = { showDuplicateDialog = false },
                                        title = {
                                            Text(
                                                when (candidate.reason) {
                                                    DuplicateReason.SAME_NAME -> "Same Name Found"
                                                    DuplicateReason.SIMILAR_NAME -> "Similar Names Found"
                                                    DuplicateReason.SAME_NUMBER -> "Same Number Found"
                                                } + if (duplicatesRemaining > 1) " ($duplicatesRemaining left)" else ""
                                            )
                                        },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text(
                                                    text = when (candidate.reason) {
                                                        DuplicateReason.SAME_NAME ->
                                                            "\"${candidate.name}\" has multiple numbers. Are they the same person or different people?"
                                                        DuplicateReason.SIMILAR_NAME ->
                                                            "These contacts have similar names. Are they the same person or different people?"
                                                        DuplicateReason.SAME_NUMBER ->
                                                            "This number is saved as separate contacts. Are they the same person or different people?"
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                candidate.contacts.forEach { contact ->
                                                    Text(
                                                        text = if (candidate.reason == DuplicateReason.SAME_NAME) {
                                                            formatPhoneNumberForDisplay(contact.phoneNumber)
                                                        } else {
                                                            "${contact.name} · ${formatPhoneNumberForDisplay(contact.phoneNumber)}"
                                                        },
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (nameChoices.size > 1) {
                                                    Text(
                                                        text = "If one person, keep which name?",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    nameChoices.forEach { choice ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(RoundedCornerShape(12.dp))
                                                                .clickable { keepName = choice },
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            androidx.compose.material3.RadioButton(
                                                                selected = keepName == choice,
                                                                onClick = { keepName = choice }
                                                            )
                                                            Text(choice, style = MaterialTheme.typography.bodyMedium)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    applyDuplicateContactDecision(
                                                        mergeKey = candidate.mergeKey,
                                                        decision = CONTACT_MERGE_DECISION_MERGED,
                                                        contacts = uiState.contacts,
                                                        candidateContacts = candidate.contacts,
                                                        keepName = keepName.takeIf { nameChoices.size > 1 }
                                                    )
                                                }
                                            ) {
                                                Text("One Person")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(
                                                onClick = {
                                                    applyDuplicateContactDecision(
                                                        mergeKey = candidate.mergeKey,
                                                        decision = CONTACT_MERGE_DECISION_SEPARATE,
                                                        contacts = uiState.contacts,
                                                        candidateContacts = candidate.contacts
                                                    )
                                                }
                                            ) {
                                                Text("Different")
                                            }
                                        }
                                    )
                                }

                                activeMergedContactEntry?.let { entry ->
                                    val isEntryFavorite = isContactEntryFavorite(entry)
                                    AlertDialog(
                                        onDismissRequest = { activeMergedContactEntry = null },
                                        title = { Text(entry.name) },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    text = "Choose a number and use the contact actions below.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                                    items(
                                                        items = entry.sourceContacts,
                                                        key = { contact -> "${contact.id}_${normalizePhoneNumber(contact.phoneNumber)}" }
                                                    ) { sourceContact ->
                                                        val phoneNumber = sourceContact.phoneNumber
                                                        Surface(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 4.dp)
                                                                .clip(RoundedCornerShape(18.dp)),
                                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                                        ) {
                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                                            ) {
                                                                Text(
                                                                    text = formatPhoneNumberForDisplay(phoneNumber),
                                                                    style = MaterialTheme.typography.bodyLarge,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                                Spacer(modifier = Modifier.height(10.dp))
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                                ) {
                                                                    ActionPill(
                                                                        label = "Call",
                                                                        onClick = {
                                                                            activeMergedContactEntry = null
                                                                            startCall(phoneNumber)
                                                                        },
                                                                        modifier = Modifier.weight(1f),
                                                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                                        contentColor = MaterialTheme.colorScheme.primary
                                                                    )
                                                                    ActionPill(
                                                                        label = "Message",
                                                                        onClick = {
                                                                            activeMergedContactEntry = null
                                                                            openSmsComposer(phoneNumber)
                                                                        },
                                                                        modifier = Modifier.weight(1f)
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                                ) {
                                                                    ActionPill(
                                                                        label = "Copy",
                                                                        onClick = {
                                                                            activeMergedContactEntry = null
                                                                            copyNumberToClipboard(phoneNumber)
                                                                        },
                                                                        modifier = Modifier.weight(1f)
                                                                    )
                                                                    ActionPill(
                                                                        label = "Share",
                                                                        onClick = {
                                                                            activeMergedContactEntry = null
                                                                            sharePhoneNumber(phoneNumber, entry.name)
                                                                        },
                                                                        modifier = Modifier.weight(1f)
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                TextButton(
                                                                    onClick = {
                                                                        activeMergedContactEntry = null
                                                                        pendingMergedContactDelete = sourceContact
                                                                    },
                                                                    modifier = Modifier.align(Alignment.End)
                                                                ) {
                                                                    Text("Delete number", color = MaterialTheme.colorScheme.error)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    val nextFavoriteState = !isEntryFavorite
                                                    if (setContactEntryFavorite(entry, nextFavoriteState)) {
                                                        showSuccess(
                                                            if (nextFavoriteState) {
                                                                "${entry.name} added to favorites"
                                                            } else {
                                                                "${entry.name} removed from favorites"
                                                            }
                                                        )
                                                        forceUpdateUI()
                                                    }
                                                    activeMergedContactEntry = null
                                                }
                                            ) {
                                                Text(if (isEntryFavorite) "Unfavorite" else "Favorite")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { activeMergedContactEntry = null }) {
                                                Text("Close")
                                            }
                                        }
                                    )
                                }

                                pendingMergedContactDelete?.let { contact ->
                                    AlertDialog(
                                        onDismissRequest = { pendingMergedContactDelete = null },
                                        title = { Text("Delete contact?") },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Do you really want to delete this contact?")
                                                Text(contact.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                                Text(
                                                    formatPhoneNumberForDisplay(contact.phoneNumber),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    handleDeleteContact(contact)
                                                    pendingMergedContactDelete = null
                                                }
                                            ) {
                                                Text("Delete", color = MaterialTheme.colorScheme.error)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { pendingMergedContactDelete = null }) {
                                                Text("Cancel")
                                            }
                                        }
                                    )
                                }

                                if (showAddContactDialog) {
                                    var newName by remember { mutableStateOf("") }
                                    var newPhone by remember(addContactPrefillNumber) { mutableStateOf(addContactPrefillNumber) }

                                    val googleAccount = remember { getDefaultGoogleAccount() }
                                    val accountDisplayName = googleAccount.first ?: "Device Storage"

                                    AlertDialog(
                                        onDismissRequest = { showAddContactDialog = false },
                                        title = {
                                            Column {
                                                Text("Create New Contact", fontWeight = FontWeight.Bold)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Saving to: $accountDisplayName", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                OutlinedTextField(
                                                    value = newName,
                                                    onValueChange = { newName = it },
                                                    label = { Text("Full Name") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                OutlinedTextField(
                                                    value = newPhone,
                                                    onValueChange = { newPhone = it },
                                                    label = { Text("Phone Number") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    keyboardOptions = KeyboardOptions(
                                                        keyboardType = KeyboardType.Phone
                                                    )
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    if (newName.isNotBlank() && newPhone.isNotBlank()) {
                                                        CoroutineScope(Dispatchers.IO).launch {
                                                            val success = saveNewContactToSystem(newName, newPhone, googleAccount.first, googleAccount.second)
                                                            withContext(Dispatchers.Main) {
                                                                if (success) {
                                                                    Toast.makeText(this@MainActivity, "Saved to $accountDisplayName", Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    Toast.makeText(this@MainActivity, "Failed to save contact", Toast.LENGTH_SHORT).show()
                                                                }
                                                                showAddContactDialog = false
                                                            }
                                                        }
                                                    } else {
                                                        Toast.makeText(this@MainActivity, "Please enter both name and number", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("Save")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showAddContactDialog = false }) {
                                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    )
                                }

                                if (showBatteryOptimizationPrompt) {
                                    AlertDialog(
                                        onDismissRequest = { dismissBatteryOptimizationPrompt() },
                                        title = { Text("Allow Battery Exemption") },
                                        text = {
                                            Text(
                                                "On some phones, battery saver can block incoming call UI from opening reliably. Allow this app to ignore battery optimization so calls stay in your app."
                                            )
                                        },
                                        confirmButton = {
                                            Button(onClick = { requestIgnoreBatteryOptimizations() }) {
                                                Text("Allow")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { dismissBatteryOptimizationPrompt() }) {
                                                Text("Not now")
                                            }
                                        }
                                    )
                                }

                                if (!showBatteryOptimizationPrompt && showXiaomiOtherPermissionsPrompt) {
                                    AlertDialog(
                                        onDismissRequest = { dismissXiaomiOtherPermissionsPrompt() },
                                        title = { Text("Allow Xiaomi Other Permissions") },
                                        text = {
                                            Text(
                                                "On Xiaomi, Redmi, and POCO phones, open Other permissions and allow lock screen and background pop-up access so incoming call UI can open reliably."
                                            )
                                        },
                                        confirmButton = {
                                            Button(onClick = { openXiaomiOtherPermissionsSettings() }) {
                                                Text("Open")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { dismissXiaomiOtherPermissionsPrompt() }) {
                                                Text("Not now")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        window.decorView.post {
            if (hasReadContactsPermission()) {
                val cachedContacts = loadContactsFromPreferences()
                if (cachedContacts.isNotEmpty()) {
                    uiState = uiState.copy(contacts = cachedContacts)
                    enqueueDuplicateContactCandidate(cachedContacts)
                }
            }
            checkPermissionsAndLoadContacts()
            loadProfileBackup()
            autoRestoreBackupIfCleared()
            if (hasReadCallLogPermission()) {
                refreshRecentCalls()
            } else {
                Log.w(P2P_FORENSIC_TAG, "startup_calllog_load action=skip reason=missing_READ_CALL_LOG")
            }
        }
    }

    /**
     * Usage access lets the call service see which app is in front, so an incoming call opens full screen
     * only in the dialer / on the home screen and as an Answer/Decline banner inside other apps.
     * Asked on each app start until granted (or "Don't ask again"); without it calls outside the dialer
     * show as a banner.
     */
    private fun maybeAskUsageAccess() {
        val granted = try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
        if (granted) return
        // Without Usage access the app can't tell the home screen from other apps, so every call shows as the
        // banner. Ask once per app start until it's on, unless the user chose "Don't ask again".
        val prefs = getSharedPreferences("usage_access_prompt", MODE_PRIVATE)
        if (prefs.getBoolean("never_ask", false) || usageAccessAskedThisSession) return
        usageAccessAskedThisSession = true
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow usage access")
            .setMessage(
                "So incoming calls open full screen on the home screen and in this app, and only as a small " +
                    "Answer / Decline banner while you use other apps.\n\nTurn on \"Usage access\" for this app on the next screen."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }
            }
            .setNegativeButton("Not now", null)
            .setNeutralButton("Don't ask again") { _, _ -> prefs.edit().putBoolean("never_ask", true).apply() }
            .show()
    }

    override fun onDestroy() {
        isMainActivityVisible = false
        incomingUiGateMainVisible = false
        super.onDestroy()
        pendingOemFallbackProbe?.let { forensicProbeHandler.removeCallbacks(it) }
        pendingOemFallbackProbe = null
        recentCallsRefreshJob?.cancel()
        recentCallsRefreshJob = null
        if (isGoogleSyncObserverRegistered) {
            try {
                googleSyncObserver?.let { contentResolver.unregisterContentObserver(it) }
            } catch (_: Exception) {
            }
            isGoogleSyncObserverRegistered = false
        }
        try { unregisterReceiver(powerSaveModeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(callStateReceiver) } catch (e: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        isMainActivityVisible = false
        incomingUiGateMainVisible = false
        Log.w(
            P2P_FORENSIC_TAG,
            "main_lifecycle action=onStop selectedTab=${uiState.selectedTab} powerSaveMode=${uiState.isPowerSaveMode} dialpadVisible=${uiState.recentDialpadVisible} digits=${maskP2PNumberForLog(uiState.recentDialpadDigits)}"
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(
            P2P_FORENSIC_TAG,
            "main_lifecycle action=onTrimMemory level=$level selectedTab=${uiState.selectedTab} powerSaveMode=${uiState.isPowerSaveMode}"
        )
    }

    private fun syncContactsObserverRegistration() {
        if (hasReadContactsPermission()) {
            if (!isGoogleSyncObserverRegistered) {
                try {
                    googleSyncObserver?.let {
                        contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, it)
                        isGoogleSyncObserverRegistered = true
                        Log.w(P2P_FORENSIC_TAG, "contacts_observer action=register result=success")
                    }
                } catch (e: Exception) {
                    Log.e(P2P_FORENSIC_TAG, "contacts_observer action=register result=failure error=${e.message}", e)
                }
            }
        } else if (isGoogleSyncObserverRegistered) {
            try {
                googleSyncObserver?.let { contentResolver.unregisterContentObserver(it) }
            } catch (_: Exception) {
            }
            isGoogleSyncObserverRegistered = false
            Log.w(P2P_FORENSIC_TAG, "contacts_observer action=unregister reason=missing_permission")
        } else {
            Log.w(P2P_FORENSIC_TAG, "contacts_observer action=skip reason=missing_permission")
        }
    }

    private fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWriteContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasReadCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWriteCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCallPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun isTelecomInCallSafely(): Boolean {
        val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        return try {
            telecomManager.isInCall
        } catch (e: SecurityException) {
            Log.w(P2P_FORENSIC_TAG, "telecom_in_call action=query result=security_exception error=${e.message}")
            false
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "telecom_in_call action=query result=failure error=${e.message}", e)
            false
        }
    }

    private fun getDefaultDialerPackageSafely(): String {
        return try {
            resolveDefaultDialerPackage(this)
        } catch (e: SecurityException) {
            Log.w(P2P_FORENSIC_TAG, "default_dialer action=query result=security_exception error=${e.message}")
            ""
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "default_dialer action=query result=failure error=${e.message}", e)
            ""
        }
    }

    private fun getDefaultLauncherPackageSafely(): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                .orEmpty()
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "default_launcher action=query result=failure error=${e.message}", e)
            ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterAppPhoneAccountsSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(P2P_FORENSIC_TAG, "phone_account action=query result=missing_read_phone_state_permission")
            return
        }
        val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            val myAccounts = telecomManager.callCapablePhoneAccounts.filter { it.componentName.packageName == packageName }
            myAccounts.forEach { account ->
                try {
                    telecomManager.unregisterPhoneAccount(account)
                    Log.w(P2P_FORENSIC_TAG, "phone_account action=unregister result=success handle=$account")
                } catch (e: SecurityException) {
                    Log.w(P2P_FORENSIC_TAG, "phone_account action=unregister result=security_exception handle=$account error=${e.message}")
                }
            }
        } catch (e: SecurityException) {
            Log.w(P2P_FORENSIC_TAG, "phone_account action=query result=security_exception error=${e.message}")
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "phone_account action=query result=failure error=${e.message}", e)
        }
    }
    @Deprecated("Deprecated in Java", ReplaceWith("OnBackPressedDispatcher"))
    override fun onBackPressed() {
        if (uiState.callState.isInCall) {
            moveTaskToBack(true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        forceUpdateUI()
        UltraForensicLogger.debug("Theme change detected: Smooth transition applied.")
    }

    private fun requestDefaultDialerRole() {
        try {
            val currentDefault = getDefaultDialerPackageSafely()
            val isNothingOS = Build.MANUFACTURER.equals("Nothing", ignoreCase = true) ||
                             Build.BRAND.equals("nothing", ignoreCase = true) ||
                             Build.MODEL.contains("Phone", ignoreCase = true)
            val isAndroid16Plus = Build.VERSION.SDK_INT >= 36

            Log.e("CMF_DIALER_DEBUG", "=== DIALER ROLE REQUEST START ===")
            Log.e("CMF_DIALER_DEBUG", "Device: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}")
            Log.e("CMF_DIALER_DEBUG", "OS: ${Build.VERSION.RELEASE} SDK: ${Build.VERSION.SDK_INT}")
            Log.e("CMF_DIALER_DEBUG", "Is Nothing OS: $isNothingOS, Android 16+: $isAndroid16Plus")
            Log.e("CMF_DIALER_DEBUG", "Current default dialer: $currentDefault")
            Log.e("CMF_DIALER_DEBUG", "App package: $packageName")

            if (currentDefault == packageName) {
                Log.e("CMF_DIALER_DEBUG", "Already default — returning early")
                showSuccess("Already set as default dialer")
                checkPermissionsAndLoadContacts()
                refreshPowerPermissionPrompts()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
                val isAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)
                Log.e("CMF_DIALER_DEBUG", "isRoleAvailable = $isAvailable")

                if (isAvailable) {
                    try {
                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                        Log.e("CMF_DIALER_DEBUG", "Created intent (no flags): $intent")
                        Log.e("CMF_DIALER_DEBUG", "Launching via dialerRoleRequestLauncher")
                        dialerRoleRequestLaunchedAtMs = android.os.SystemClock.elapsedRealtime()
                        dialerRoleRequestLauncher.launch(intent)
                        return
                    } catch (e: Exception) {
                        Log.e("CMF_DIALER_DEBUG", "Launcher failed: ${e.message}", e)
                    }
                }
            }

            Log.e("CMF_DIALER_DEBUG", "Role dialog unavailable — opening Default apps settings")
            openDefaultAppsSettings()
        } catch (e: Exception) {
            Log.e("CMF_DIALER_DEBUG", "OUTER EXCEPTION: ${e.message}", e)
            openDefaultAppsSettings()
        }
    }

    private fun requestDefaultSmsRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    startActivity(intent)
                    return
                }
            }
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("CMF_DIALER_DEBUG", "Failed to request SMS role: ${e.message}", e)
            openDefaultAppsSettings()
        }
    }

    private fun openDefaultAppsSettings() {
        Toast.makeText(this, "Choose this app under \"Phone app\"", Toast.LENGTH_LONG).show()
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.e("CMF_DIALER_DEBUG", "Could not open ${intent.action}: ${e.message}")
            }
        }
    }

    private fun requestDefaultContactsRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
                // Use actual string value for contacts role
                val contactsRole = "android.app.role.CONTACTS"
                if (roleManager.isRoleAvailable(contactsRole) &&
                    !roleManager.isRoleHeld(contactsRole)) {
                    contactsRoleRequestLauncher.launch(roleManager.createRequestRoleIntent(contactsRole))
                    return
                }
            }
            showSuccess("Already set as default contacts app")
        } catch (e: Exception) {}
    }

    private fun isCallScreeningRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        try {
            val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
            val isRoleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
            val isRoleHeld = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            return !isRoleAvailable || isRoleHeld
        } catch (e: Exception) {
            return false
        }
    }

    private fun requestCallScreeningRoleIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        try {
            val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
            val isRoleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
            val isRoleHeld = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            if (!isRoleAvailable || isRoleHeld) {
                return true
            }
            callScreeningRoleRequestLauncher.launch(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            )
            return false
        } catch (e: Exception) {
            showError("Unable to request call blocking permission")
            return false
        }
    }

    private fun getBlocklistPreferences(): SharedPreferences {
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        return safeContext.getSharedPreferences("local_spam_blocklist", MODE_PRIVATE)
    }

    private fun getBlockedNumberKey(phoneNumber: String): String {
        return phoneNumber.filter { it.isDigit() }
    }

    private fun getBlockedPhoneNumbers(): List<String> {
        val blockedKeys = getBlocklistPreferences().all.entries
            .asSequence()
            .filter { it.value == true }
            .map { it.key.filter(Char::isDigit) }
            .filter { it.isNotBlank() }
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
            .toList()

        val deduped = mutableListOf<String>()
        blockedKeys.forEach { candidate ->
            val candidateLast10 = if (candidate.length >= 10) candidate.takeLast(10) else candidate
            val alreadyListed = deduped.any { existing ->
                existing == candidate ||
                    (existing.length >= 10 && candidate.length >= 10 && existing.takeLast(10) == candidateLast10)
            }
            if (!alreadyListed) {
                deduped += candidate
            }
        }

        return deduped.sortedBy(::formatPhoneNumberForDisplay)
    }

    private fun getFavoritesPreferences(): SharedPreferences {
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext() ?: this
        } else {
            this
        }
        return safeContext.getSharedPreferences("favorite_contacts", MODE_PRIVATE)
    }

    private fun getFavoriteNumberKey(phoneNumber: String): String {
        return normalizePhoneNumber(phoneNumber)
    }

    private fun isPhoneNumberFavorite(phoneNumber: String): Boolean {
        val key = getFavoriteNumberKey(phoneNumber)
        if (key.isBlank()) {
            return false
        }
        return getFavoritesPreferences().getBoolean(key, false)
    }

    private fun isContactEntryFavorite(entry: ContactDisplayEntry): Boolean {
        return entry.phoneNumbers.any(::isPhoneNumberFavorite)
    }

    private fun setPhoneNumberFavorite(phoneNumber: String, favorite: Boolean): Boolean {
        val key = getFavoriteNumberKey(phoneNumber)
        if (key.isBlank()) {
            return false
        }
        getFavoritesPreferences().edit().apply {
            if (favorite) {
                putBoolean(key, true)
            } else {
                remove(key)
            }
            apply()
        }
        return true
    }

    /** Shared favorite toggle for a contact card (Contacts tab and Keypad results). */
    private fun toggleContactEntryFavorite(entry: ContactDisplayEntry, currentlyFavorite: Boolean) {
        if (setContactEntryFavorite(entry, !currentlyFavorite)) {
            showSuccess(
                if (currentlyFavorite) "${entry.name} removed from favorites"
                else "${entry.name} added to favorites"
            )
            forceUpdateUI()
        }
    }

    private fun setContactEntryFavorite(entry: ContactDisplayEntry, favorite: Boolean): Boolean {
        var changed = false
        entry.phoneNumbers.forEach { phoneNumber ->
            changed = setPhoneNumberFavorite(phoneNumber, favorite) || changed
        }
        return changed
    }

    private fun isPhoneNumberBlocked(phoneNumber: String): Boolean {
        val cleanNum = getBlockedNumberKey(phoneNumber)
        if (cleanNum.isBlank()) {
            return false
        }
        val prefs = getBlocklistPreferences()
        return prefs.getBoolean(cleanNum, false) ||
            (cleanNum.length >= 10 && prefs.getBoolean(cleanNum.takeLast(10), false))
    }

    private fun addPhoneNumberToBlocklist(phoneNumber: String): Boolean {
        val cleanNum = getBlockedNumberKey(phoneNumber)
        if (cleanNum.isBlank()) {
            return false
        }
        val prefs = getBlocklistPreferences()
        prefs.edit().apply {
            putBoolean(cleanNum, true)
            if (cleanNum.length >= 10) {
                putBoolean(cleanNum.takeLast(10), true)
            }
            apply()
        }
        writeSystemBlockedNumber(phoneNumber, blocked = true)
        return true
    }

    private fun removePhoneNumberFromBlocklist(phoneNumber: String): Boolean {
        val cleanNum = getBlockedNumberKey(phoneNumber)
        if (cleanNum.isBlank()) {
            return false
        }
        val prefs = getBlocklistPreferences()
        prefs.edit().apply {
            remove(cleanNum)
            if (cleanNum.length >= 10) {
                remove(cleanNum.takeLast(10))
            }
            apply()
        }
        writeSystemBlockedNumber(phoneNumber, blocked = false)
        return true
    }

    // Android's own blocked-numbers database (same one the Google dialer uses). It is kept by the system,
    // so blocks survive Clear data; only the default dialer/SMS app may read or write it.
    private fun canUseSystemBlockedNumbers(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            runCatching { android.provider.BlockedNumberContract.canCurrentUserBlockNumbers(this) }.getOrDefault(false)

    private fun writeSystemBlockedNumber(phoneNumber: String, blocked: Boolean) {
        if (!canUseSystemBlockedNumbers()) return
        runCatching {
            if (blocked) {
                if (!android.provider.BlockedNumberContract.isBlocked(this, phoneNumber)) {
                    contentResolver.insert(
                        android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        android.content.ContentValues().apply {
                            put(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, phoneNumber)
                        }
                    )
                }
            } else {
                android.provider.BlockedNumberContract.unblock(this, phoneNumber)
            }
            Log.w(P2P_FORENSIC_TAG, "system_blocklist_write blocked=$blocked result=ok")
        }.onFailure { e ->
            Log.e(P2P_FORENSIC_TAG, "system_blocklist_write blocked=$blocked result=failed error=${e.message}")
        }
    }

    /** Copies the system block list into the local one (restores blocks after Clear data). */
    private fun syncSystemBlockedNumbersToLocal() {
        if (!canUseSystemBlockedNumbers()) {
            val defaultDialer = runCatching {
                (getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager).defaultDialerPackage
            }.getOrNull()
            val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { android.provider.BlockedNumberContract.canCurrentUserBlockNumbers(this).toString() }
                    .getOrElse { "error:${it.message}" }
            } else "sdk_too_old"
            Log.w(P2P_FORENSIC_TAG, "system_blocklist_sync result=skipped canBlock=$reason defaultDialer=$defaultDialer")
            return
        }
        runCatching {
            val numbers = mutableListOf<String>()
            contentResolver.query(
                android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(numbers::add)
            }
            val prefs = getBlocklistPreferences()
            prefs.edit().apply {
                numbers.forEach { number ->
                    val cleanNum = getBlockedNumberKey(number)
                    if (cleanNum.isNotBlank()) {
                        putBoolean(cleanNum, true)
                        if (cleanNum.length >= 10) putBoolean(cleanNum.takeLast(10), true)
                    }
                }
                apply()
            }
            // Blocks made before this change exist only locally: copy them into the system list once.
            getBlockedPhoneNumbers().forEach { writeSystemBlockedNumber(it, blocked = true) }
            Log.w(P2P_FORENSIC_TAG, "system_blocklist_sync count=${numbers.size}")
        }.onFailure { e ->
            Log.e(P2P_FORENSIC_TAG, "system_blocklist_sync result=failed error=${e.message}")
        }
    }

    private fun togglePhoneNumberBlockState(phoneNumber: String): Boolean {
        val displayNumber = formatPhoneNumberForDisplay(phoneNumber)
        val success = if (isPhoneNumberBlocked(phoneNumber)) {
            if (removePhoneNumberFromBlocklist(phoneNumber)) {
                showSuccess("$displayNumber unblocked")
                true
            } else {
                showError("Unable to unblock this number")
                false
            }
        } else if (addPhoneNumberToBlocklist(phoneNumber)) {
            val blockingReady = requestCallScreeningRoleIfNeeded()
            if (blockingReady) {
                showSuccess("Future calls from $displayNumber will be blocked")
            } else {
                showSuccess("$displayNumber added to blocked numbers")
            }
            true
        } else {
            showError("Unable to block this number")
            false
        }

        if (success) {
            uiState = uiState.copy(groupedRecentCalls = groupRecentCalls(uiState.recentCalls))
            forceUpdateUI()
        }
        return success
    }

    private fun copyNumberToClipboard(phoneNumber: String, label: String = "Phone number") {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            showError("Clipboard unavailable")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, phoneNumber))
        showSuccess("Number copied")
    }

    private fun openSmsComposer(phoneNumber: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phoneNumber)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            showError("No messaging app found")
        }
    }

    private fun sharePhoneNumber(phoneNumber: String, displayName: String = "") {
        val shareText = if (displayName.isNotBlank() && displayName != "Unknown") {
            "$displayName\n${formatPhoneNumberForDisplay(phoneNumber)}"
        } else {
            formatPhoneNumberForDisplay(phoneNumber)
        }
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    },
                    "Share number"
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            showError("Unable to share this number")
        }
    }

    private fun checkPermissionsAndLoadContacts() {
        try {
            if (getDefaultDialerPackageSafely() == packageName) {
                requestPermissions()
                refreshPowerPermissionPrompts()
            }
        } catch (e: Exception) {
            requestPermissions()
            refreshPowerPermissionPrompts()
        }
    }

    private fun isIgnoringBatteryOptimizationsForApp(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "battery_optimization_prompt action=query result=failure error=${e.message}", e)
            false
        }
    }

    private fun refreshBatteryOptimizationPrompt() {
        if (!::sharedPreferences.isInitialized) {
            return
        }
        val ignored = isIgnoringBatteryOptimizationsForApp()
        val dismissed = sharedPreferences.getBoolean(BATTERY_OPTIMIZATION_PROMPT_DISMISSED_KEY, false)
        val shouldShow = uiState.isDefaultDialer && !ignored && !dismissed
        showBatteryOptimizationPrompt = shouldShow
        Log.w(
            P2P_FORENSIC_TAG,
            "battery_optimization_prompt action=refresh defaultDialer=${uiState.isDefaultDialer} ignored=$ignored dismissed=$dismissed show=$shouldShow"
        )
    }

    private fun isXiaomiFamilyDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        val brand = Build.BRAND.lowercase(Locale.getDefault())
        return manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
    }

    private fun refreshXiaomiOtherPermissionsPrompt() {
        if (!::sharedPreferences.isInitialized) {
            return
        }
        val dismissed = sharedPreferences.getBoolean(XIAOMI_OTHER_PERMISSIONS_PROMPT_DISMISSED_KEY, false)
        val shouldShow = uiState.isDefaultDialer && isXiaomiFamilyDevice() && !dismissed
        showXiaomiOtherPermissionsPrompt = shouldShow
        Log.w(
            P2P_FORENSIC_TAG,
            "xiaomi_other_permissions_prompt action=refresh defaultDialer=${uiState.isDefaultDialer} isXiaomi=${isXiaomiFamilyDevice()} dismissed=$dismissed show=$shouldShow"
        )
    }

    private fun refreshPowerPermissionPrompts() {
        refreshBatteryOptimizationPrompt()
        refreshXiaomiOtherPermissionsPrompt()
    }

    private fun dismissBatteryOptimizationPrompt() {
        if (::sharedPreferences.isInitialized) {
            sharedPreferences.edit().putBoolean(BATTERY_OPTIMIZATION_PROMPT_DISMISSED_KEY, true).apply()
        }
        showBatteryOptimizationPrompt = false
        Log.w(
            P2P_FORENSIC_TAG,
            "battery_optimization_prompt action=dismiss rememberChoice=true"
        )
    }

    private fun dismissXiaomiOtherPermissionsPrompt() {
        if (::sharedPreferences.isInitialized) {
            sharedPreferences.edit().putBoolean(XIAOMI_OTHER_PERMISSIONS_PROMPT_DISMISSED_KEY, true).apply()
        }
        showXiaomiOtherPermissionsPrompt = false
        Log.w(
            P2P_FORENSIC_TAG,
            "xiaomi_other_permissions_prompt action=dismiss rememberChoice=true"
        )
    }

    fun openXiaomiOtherPermissionsSettings() {
        val packageUri = Uri.fromParts("package", packageName, null)
        val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorAct.ivity"
            )
            putExtra("extra_pkgname", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val autoStartIntent = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = packageUri
        }
        try {
            startActivity(miuiIntent)
            Toast.makeText(
                this,
                "Enable Other permissions, lock screen, and background pop-up access",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            try {
                startActivity(autoStartIntent)
            } catch (_: Exception) {
                try {
                    startActivity(appDetailsIntent)
                } catch (fallbackError: Exception) {
                    Log.e(
                        P2P_FORENSIC_TAG,
                        "xiaomi_other_permissions_prompt action=open result=failure error=${fallbackError.message}",
                        fallbackError
                    )
                    showError("Unable to open Xiaomi permission settings")
                    return
                }
            }
        }
        dismissXiaomiOtherPermissionsPrompt()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showBatteryOptimizationPrompt = false
            return
        }
        val packageUri = Uri.parse("package:$packageName")
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = packageUri
        }
        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            Log.w(P2P_FORENSIC_TAG, "battery_optimization_prompt action=request mode=package_specific")
            batteryOptimizationRequestLauncher.launch(requestIntent)
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "battery_optimization_prompt action=request mode=package_specific result=failure error=${e.message}", e)
            try {
                Log.w(P2P_FORENSIC_TAG, "battery_optimization_prompt action=request mode=fallback_settings")
                batteryOptimizationRequestLauncher.launch(fallbackIntent)
            } catch (fallbackError: Exception) {
                Log.e(P2P_FORENSIC_TAG, "battery_optimization_prompt action=request mode=fallback_settings result=failure error=${fallbackError.message}", fallbackError)
                showError("Unable to open battery optimization settings")
            }
        }
        showBatteryOptimizationPrompt = false
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun loadContactMergeDecisions(): Map<String, String> {
        val rawJson = sharedPreferences.getString(CONTACT_MERGE_DECISIONS_KEY, null) ?: return emptyMap()
        return try {
            buildMap {
                val json = JSONObject(rawJson)
                json.keys().forEach { key ->
                    put(key, json.optString(key))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistContactMergeDecisions(decisions: Map<String, String>) {
        val json = JSONObject()
        decisions.forEach { (key, value) -> json.put(key, value) }
        sharedPreferences.edit().putString(CONTACT_MERGE_DECISIONS_KEY, json.toString()).apply()
    }

    private fun buildContactMergeKey(name: String, contacts: List<Contact>): String {
        val normalizedNumbers = contacts.map { normalizePhoneNumber(it.phoneNumber) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        return "${normalizeDuplicateContactName(name)}|${normalizedNumbers.joinToString(",")}"
    }

    private fun resolveRawContactIdsForContacts(contacts: List<Contact>): List<Long> {
        val resolved = linkedSetOf<Long>()
        contacts.forEach { contact ->
            contact.rawContactId?.toLongOrNull()?.let { resolved.add(it) }
        }
        if (resolved.size >= 2) return resolved.toList()

        contacts.forEach { contact ->
            try {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Data.RAW_CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contact.id),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val candidateRawId = cursor.getLong(0)
                        val candidateNumber = cursor.getString(1).orEmpty()
                        if (normalizePhoneNumber(candidateNumber) == normalizePhoneNumber(contact.phoneNumber)) {
                            resolved.add(candidateRawId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(P2P_FORENSIC_TAG, "contact_merge action=resolve_raw_ids result=failure error=${e.message}", e)
            }
        }
        return resolved.toList()
    }

    /** Decision key for a duplicate group. For one shared name it equals buildContactMergeKey (old answers still count). */
    private fun buildDuplicateGroupKey(group: List<Contact>): String {
        val names = group.map { normalizeDuplicateContactName(it.name) }.distinct().sorted()
        val numbers = group.map { normalizePhoneNumber(it.phoneNumber) }.filter { it.isNotBlank() }.distinct().sorted()
        return "${names.joinToString("+")}|${numbers.joinToString(",")}"
    }

    private fun findNextDuplicateContactCandidate(
        contacts: List<Contact>,
        decisions: Map<String, String>
    ): DuplicateContactCandidate? {
        val all = findDuplicateContactCandidates(contacts, decisions)
        duplicatesRemaining = all.size
        return all.firstOrNull()
    }

    /** Every unanswered duplicate group, in the order they're asked. */
    private fun findDuplicateContactCandidates(
        contacts: List<Contact>,
        decisions: Map<String, String>
    ): List<DuplicateContactCandidate> {
        val byName = contacts.groupBy { normalizeDuplicateContactName(it.name) }
        val byNameSorted = { groups: List<List<Contact>> ->
            groups.sortedBy { it.firstOrNull()?.name?.lowercase(Locale.getDefault()) ?: "" }
        }
        val isSeparateContacts = { group: List<Contact> -> group.map { it.id }.distinct().size > 1 }

        // 1. Exact same name, different numbers (the original check).
        val sameName = byNameSorted(
            byName.values
                .map { group -> group.distinctBy { normalizePhoneNumber(it.phoneNumber) } }
                .filter { group -> group.size > 1 && isSeparateContacts(group) }
        ).map { it to DuplicateReason.SAME_NAME }

        // 2. Similar names: one name is the other plus more words ("Sanjana" / "Sanjana BC").
        val names = byName.keys.filter { it.isNotBlank() }.sorted()
        val similarName = buildList {
            names.forEachIndexed { index, shortName ->
                var next = index + 1
                // Sorted order keeps every name that starts with shortName right after it.
                while (next < names.size && names[next].startsWith(shortName)) {
                    val longName = names[next]
                    if (longName.startsWith("$shortName ")) {
                        val group = (byName.getValue(shortName) + byName.getValue(longName))
                            .distinctBy { normalizePhoneNumber(it.phoneNumber) }
                        if (isSeparateContacts(group)) add(group to DuplicateReason.SIMILAR_NAME)
                    }
                    next++
                }
            }
        }

        // 3. Same number saved as separate contacts (usually under different names).
        val sameNumber = byNameSorted(
            contacts
                .filter { normalizePhoneNumber(it.phoneNumber).isNotBlank() }
                .groupBy { normalizePhoneNumber(it.phoneNumber) }
                .values
                .map { group -> group.distinctBy { it.id } }
                .filter { group -> group.size > 1 }
        ).map { it to DuplicateReason.SAME_NUMBER }

        return (sameName + similarName + sameNumber)
            .distinctBy { (group, _) -> buildDuplicateGroupKey(group) }
            .filter { (group, reason) ->
                val key = buildDuplicateGroupKey(group)
                if (key in duplicateKeysBeingMerged) return@filter false
                when (decisions[key]) {
                    null -> true
                    CONTACT_MERGE_DECISION_SEPARATE -> false
                    // "One Person" for different names is done by linking them in the phone's contacts. Every
                    // group here is still separate contacts, so the link is gone (re-added, unlinked, or the
                    // link failed): ask again. Same name is merged in the app list, so it stays answered.
                    else -> reason != DuplicateReason.SAME_NAME
                }
            }
            .map { (group, reason) ->
                DuplicateContactCandidate(
                    mergeKey = buildDuplicateGroupKey(group),
                    name = group.first().name,
                    contacts = group,
                    reason = reason
                )
            }
    }

    private fun enqueueDuplicateContactCandidate(contacts: List<Contact>) {
        // Always re-check against the newest list (the saved copy loads first, then the live contacts).
        // Was: skip whenever a candidate existed, so a stale early result blocked every later check.
        // Only keep the current one while its question is open on screen.
        if (showDuplicateDialog && pendingDuplicateCandidate != null) return
        pendingDuplicateCandidate = findNextDuplicateContactCandidate(contacts, contactMergeDecisions)
    }

    /** Gives every contact in the group the chosen name (writes the phone's contacts database). */
    private fun setContactsName(contacts: List<Contact>, name: String) {
        if (!hasWriteContactsPermission()) return
        val nameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        resolveRawContactIdsForContacts(contacts).forEach { rawId ->
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                }
                val updated = contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    values,
                    "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(rawId.toString(), nameMime)
                )
                if (updated == 0) {
                    contentResolver.insert(ContactsContract.Data.CONTENT_URI, values.apply {
                        put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        put(ContactsContract.Data.MIMETYPE, nameMime)
                    })
                }
            }.onFailure { e ->
                Log.e(P2P_FORENSIC_TAG, "contact_merge action=set_name result=failure error=${e.message}")
            }
        }
        Log.w(P2P_FORENSIC_TAG, "contact_merge action=set_name result=done contacts=${contacts.size}")
    }

    /**
     * Real merge in Android's contacts database: keeps ONE contact (the one with [keepName]), copies over the
     * numbers it doesn't have yet, then deletes the other duplicates, so no app (system dialer included) sees two.
     */
    private fun consolidateContactsInSystem(contacts: List<Contact>, keepName: String?): Boolean {
        if (!hasWriteContactsPermission()) {
            Log.w(P2P_FORENSIC_TAG, "contact_merge action=consolidate result=skip reason=missing_WRITE_CONTACTS")
            return false
        }
        val keeper = contacts.firstOrNull { keepName != null && it.name.trim() == keepName } ?: contacts.firstOrNull()
            ?: return false
        val keeperRawId = resolveRawContactIdsForContacts(listOf(keeper)).firstOrNull() ?: return false
        val otherRawIds = resolveRawContactIdsForContacts(contacts).filter { it != keeperRawId }.distinct()

        fun phoneRows(rawId: Long): List<Triple<String, Int, String?>> = buildList {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null
            )?.use { c -> while (c.moveToNext()) add(Triple(c.getString(0).orEmpty(), c.getInt(1), c.getString(2))) }
        }

        return runCatching {
            val keeperNumbers = phoneRows(keeperRawId).map { normalizePhoneNumber(it.first) }.toMutableSet()
            var copied = 0
            otherRawIds.forEach { rawId ->
                phoneRows(rawId).forEach { (number, type, label) ->
                    val normalized = normalizePhoneNumber(number)
                    if (number.isNotBlank() && normalized !in keeperNumbers) {
                        contentResolver.insert(ContactsContract.Data.CONTENT_URI, android.content.ContentValues().apply {
                            put(ContactsContract.Data.RAW_CONTACT_ID, keeperRawId)
                            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            put(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                            put(ContactsContract.CommonDataKinds.Phone.TYPE, type)
                            label?.let { put(ContactsContract.CommonDataKinds.Phone.LABEL, it) }
                        })
                        keeperNumbers += normalized
                        copied++
                    }
                }
            }
            if (keepName != null) setContactsName(listOf(keeper), keepName)
            otherRawIds.forEach { rawId ->
                contentResolver.delete(
                    android.content.ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                    null, null
                )
            }
            Log.w(
                P2P_FORENSIC_TAG,
                "contact_merge action=consolidate result=ok kept=$keeperRawId deleted=${otherRawIds.size} numbersCopied=$copied"
            )
            true
        }.onFailure { e ->
            Log.e(P2P_FORENSIC_TAG, "contact_merge action=consolidate result=failure error=${e.message}", e)
        }.getOrDefault(false)
    }

    private fun applyDuplicateContactDecision(
        mergeKey: String,
        decision: String,
        contacts: List<Contact> = uiState.contacts,
        candidateContacts: List<Contact> = pendingDuplicateCandidate?.contacts ?: emptyList(),
        keepName: String? = null
    ) {
        val updated = contactMergeDecisions.toMutableMap().apply {
            put(mergeKey, decision)
        }
        contactMergeDecisions = updated
        persistContactMergeDecisions(updated)
        val merging = decision == CONTACT_MERGE_DECISION_MERGED && candidateContacts.size > 1
        // The list still shows this pair as two contacts until the merge reloads it: don't ask it again.
        if (merging) duplicateKeysBeingMerged += mergeKey
        pendingDuplicateCandidate = findNextDuplicateContactCandidate(contacts, updated)
        // All answered: close the check (the Contacts button hides because nothing is pending).
        if (pendingDuplicateCandidate == null) showDuplicateDialog = false
        if (merging) {
            CoroutineScope(Dispatchers.IO).launch {
                // One contact left in Android's database (keeps the name the user picked, if names differed).
                consolidateContactsInSystem(candidateContacts, keepName)
                // Reload either way: merged → the pair is gone; failed → it is asked again.
                duplicateKeysBeingMerged -= mergeKey
                loadContacts()
            }
        }
    }

    @Suppress("RemoveExplicitTypeArguments")
    private fun buildContactDisplayEntries(
        contacts: List<Contact>,
        decisions: Map<String, String>
    ): List<ContactDisplayEntry> {
        val displayEntries = mutableListOf<ContactDisplayEntry>()

        val deferredForLocalMerge = mutableListOf<Contact>()
        contacts
            .groupBy { it.id }
            .values
            .forEach { sameContactIdGroup ->
                val dedupedByNumber = sameContactIdGroup.distinctBy { normalizePhoneNumber(it.phoneNumber) }
                if (dedupedByNumber.size > 1) {
                    displayEntries.add(
                        ContactDisplayEntry(
                            displayKey = "provider:${dedupedByNumber.first().id}",
                            name = dedupedByNumber.first().name,
                            phoneNumbers = dedupedByNumber.map { it.phoneNumber },
                            photoUri = dedupedByNumber.firstOrNull { !it.photoUri.isNullOrBlank() }?.photoUri,
                            sourceContacts = dedupedByNumber,
                            isMerged = true
                        )
                    )
                } else {
                    deferredForLocalMerge.addAll(dedupedByNumber)
                }
            }

        deferredForLocalMerge
            .groupBy { normalizeDuplicateContactName(it.name) }
            .values
            .sortedBy { group -> group.firstOrNull()?.name?.lowercase(Locale.getDefault()) ?: "" }
            .forEach { rawGroup ->
                val dedupedGroup = rawGroup.distinctBy { normalizePhoneNumber(it.phoneNumber) }
                if (dedupedGroup.isEmpty()) return@forEach

                val mergeKey = buildContactMergeKey(dedupedGroup.first().name, dedupedGroup)
                val shouldMerge = dedupedGroup.size > 1 && decisions[mergeKey] == CONTACT_MERGE_DECISION_MERGED

                if (shouldMerge) {
                    displayEntries.add(
                        ContactDisplayEntry(
                            displayKey = "merged:$mergeKey",
                            name = dedupedGroup.first().name,
                            phoneNumbers = dedupedGroup.map { it.phoneNumber },
                            photoUri = dedupedGroup.firstOrNull { !it.photoUri.isNullOrBlank() }?.photoUri,
                            sourceContacts = dedupedGroup,
                            isMerged = true
                        )
                    )
                } else {
                    dedupedGroup.forEach { contact ->
                        displayEntries.add(
                            ContactDisplayEntry(
                                displayKey = "single:${contact.id}:${normalizePhoneNumber(contact.phoneNumber)}",
                                name = contact.name,
                                phoneNumbers = listOf(contact.phoneNumber),
                                photoUri = contact.photoUri,
                                sourceContacts = listOf(contact),
                                isMerged = false
                            )
                        )
                    }
                }
            }

        return displayEntries.sortedWith(
            compareBy<ContactDisplayEntry>(
                { it.name.lowercase(Locale.getDefault()) },
                { normalizePhoneNumber(it.phoneNumbers.firstOrNull().orEmpty()) }
            )
        )
    }

    private fun saveContactsToPreferences(contacts: List<Contact>) {
        val jsonArray = JSONArray()
        contacts.forEach { contact ->
            jsonArray.put(JSONObject().apply {
                put("id", contact.id)
                put("name", contact.name)
                put("phoneNumber", contact.phoneNumber)
                contact.photoUri?.let { put("photoUri", it) }
                contact.rawContactId?.let { put("rawContactId", it) }
            })
        }
        sharedPreferences.edit().putString("cached_contacts", jsonArray.toString()).apply()
    }

    private fun loadContactsFromPreferences(): List<Contact> {
        val jsonString = sharedPreferences.getString("cached_contacts", null) ?: return emptyList()
        val contacts = mutableListOf<Contact>()
        val seenNumbers = mutableSetOf<String>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val phoneNumber = obj.getString("phoneNumber")
                // Same rule as loadContacts: dedupe per contact, not across contacts.
                val normalized = "${obj.getString("id")}|${normalizePhoneNumber(phoneNumber)}"
                if (phoneNumber.isNotEmpty() && isValidPhoneNumber(phoneNumber) && normalized !in seenNumbers) {
                    val photoUri = if (obj.has("photoUri")) obj.getString("photoUri") else null
                    val rawContactId = if (obj.has("rawContactId")) obj.getString("rawContactId") else null
                    contacts.add(Contact(obj.getString("id"), obj.getString("name"), phoneNumber, photoUri, rawContactId))
                    seenNumbers.add(normalized)
                }
            }
        } catch (e: Exception) {}

        return contacts.sortedBy { it.name.lowercase() }
    }

    private fun loadContacts() {
        if (!hasReadContactsPermission()) {
            Log.w(P2P_FORENSIC_TAG, "contacts_load action=skip reason=missing_READ_CONTACTS")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val contactsList = mutableListOf<Contact>()
            val seenNumbers = mutableSetOf<String>()
            try {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                        ContactsContract.Data.RAW_CONTACT_ID
                    ),
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: ""
                        val name = c.getString(1) ?: "Unknown"
                        val number = c.getString(2) ?: ""
                        val photoUri = c.getString(3)
                        val rawContactId = c.getString(4)

                        // Skip a number only when it repeats inside the SAME contact. Two different contacts
                        // with one number are both kept (was: second one dropped, so the duplicate check
                        // could never see "same number, different name").
                        val normalized = "$id|${normalizePhoneNumber(number)}"
                        if (number.isNotEmpty() && isValidPhoneNumber(number) && normalized !in seenNumbers) {
                            contactsList.add(Contact(id, name, number, photoUri, rawContactId))
                            seenNumbers.add(normalized)
                        }
                    }
                }
            } catch (e: Exception) {}

            val sortedContacts = contactsList.sortedBy { it.name.lowercase() }
            if (sortedContacts.isNotEmpty()) saveContactsToPreferences(sortedContacts)

            withContext(Dispatchers.Main) {
                uiState = uiState.copy(contacts = sortedContacts, isLoading = false)
                enqueueDuplicateContactCandidate(sortedContacts)
            }
        }
    }

    private fun buildContactLookupMap(contacts: List<Contact>): Map<String, Contact> {
        val contactMap = linkedMapOf<String, Contact>()
        contacts.forEach { contact ->
            val snapshot = buildP2PNumberSnapshot(contact.phoneNumber)
            snapshot.lookupKeys.forEach { key ->
                if (key.isNotBlank() && key !in contactMap) {
                    contactMap[key] = contact
                }
            }
        }
        return contactMap
    }

    private fun shouldResolveDisplayName(cachedName: String, phoneNumber: String): Boolean {
        return cachedName.isBlank() || cachedName == "Unknown" || cachedName == phoneNumber
    }

    private fun buildResolutionRequestKey(snapshot: P2PNumberSnapshot): String {
        return snapshot.normalized.ifBlank {
            snapshot.last10.ifBlank {
                snapshot.digits.ifBlank {
                    snapshot.raw.ifBlank { "blank" }
                }
            }
        }
    }

    private fun resolveDisplayName(
        phoneNumber: String,
        cachedName: String = "Unknown",
        safeContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() ?: this else this,
        contactMap: Map<String, Contact> = buildContactLookupMap(uiState.contacts),
        dpsPrefs: SharedPreferences = safeContext.getSharedPreferences("dps_bulk_contacts", MODE_PRIVATE),
        resolutionCache: MutableMap<String, Pair<String, String>>? = null,
        trigger: String
    ): Pair<String, String> {
        val numberSnapshot = buildP2PNumberSnapshot(phoneNumber)
        resolutionCache?.let { cache ->
            val cachedResolution = numberSnapshot.lookupKeys.firstNotNullOfOrNull { key -> cache[key] }
            if (cachedResolution != null) {
                return cachedResolution
            }
        }
        val sourcesChecked = mutableListOf<String>()
        var finalName = cachedName
        var finalSource = if (
            cachedName.isNotBlank() &&
            cachedName != "Unknown" &&
            cachedName != phoneNumber
        ) {
            "calllog_cache"
        } else {
            "unresolved"
        }

        logP2PThrottled(
            level = Log.WARN,
            dedupeKey = "lookup_started:$trigger:${buildResolutionRequestKey(numberSnapshot)}",
            message = "lookup_started flow=$trigger raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)}"
        )

        if (finalSource == "unresolved") {
            sourcesChecked.add("contacts")
            val matchedContact = numberSnapshot.lookupKeys.firstNotNullOfOrNull { contactMap[it] }
            if (matchedContact != null && matchedContact.name != "Unknown") {
                finalName = matchedContact.name
                finalSource = if (numberSnapshot.lookupKeys.firstOrNull { contactMap[it] != null } == numberSnapshot.last10) {
                    "contacts_last10"
                } else {
                    "contacts_exact"
                }
            }
        }

        if (finalSource == "unresolved") {
            sourcesChecked.add("p2p_cache")
            val p2pLookup = lookupP2PCache(safeContext, phoneNumber, trigger)
            if (!p2pLookup.name.isNullOrBlank()) {
                finalName = p2pLookup.name
                finalSource = "p2p_${p2pLookup.source}"
            } else {
                logP2PThrottled(
                    level = Log.DEBUG,
                    dedupeKey = "lookup_p2p_miss:$trigger:${numberSnapshot.normalized.ifBlank { numberSnapshot.last10 }}",
                    message = "lookup_p2p_miss flow=$trigger raw=${maskP2PNumberForLog(phoneNumber)} keysTried=${maskP2PKeysForLog(p2pLookup.lookupKeysTried)}"
                )
            }
        }

        if (finalSource == "unresolved") {
            sourcesChecked.add("dps_bulk")
            val matchedKey = numberSnapshot.lookupKeys.firstOrNull {
                val value = dpsPrefs.getString(it, null)
                !value.isNullOrBlank() && value != "Unknown"
            }
            if (matchedKey != null) {
                finalName = dpsPrefs.getString(matchedKey, "Unknown") ?: "Unknown"
                finalSource = if (matchedKey == numberSnapshot.last10) "dps_last10" else "dps_exact"
            }
        }

        if (finalName.isBlank() || finalName == phoneNumber) {
            finalName = "Unknown"
        }

        logP2PThrottled(
            level = Log.WARN,
            dedupeKey = "lookup_final:$trigger:${buildResolutionRequestKey(numberSnapshot)}:$finalSource",
            message = "lookup_final flow=$trigger raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)} sourcesChecked=$sourcesChecked finalSource=$finalSource finalName=${maskP2PDisplayValueForLog(finalName)}"
        )

        return (finalName to finalSource).also { resolution ->
            resolutionCache?.let { cache ->
                numberSnapshot.lookupKeys.forEach { key ->
                    if (key.isNotBlank()) {
                        cache[key] = resolution
                    }
                }
            }
        }
    }

    @Suppress("RedundantVariableInitializer", "CanBeVal", "RemoveRedundantQualifierName")
    private suspend fun loadRecentCalls(requestGen: Long = ++recentCallsRequestGeneration): List<GroupedRecentCall> {
        if (!hasReadCallLogPermission()) {
            Log.w(P2P_FORENSIC_TAG, "recent_calls_load action=skip reason=missing_READ_CALL_LOG")
            return uiState.groupedRecentCalls
        }
        val calls = mutableListOf<RecentCall>()
        val callRows = mutableListOf<RecentCallRow>()
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() ?: this@MainActivity else this@MainActivity
        val contactMap = buildContactLookupMap(uiState.contacts)
        val dpsPrefs = safeContext.getSharedPreferences("dps_bulk_contacts", MODE_PRIVATE)
        val resolutionCache = linkedMapOf<String, Pair<String, String>>()
        val resolutionRequests = linkedMapOf<String, RecentCallRow>()
        val simLabelLookup = DualSimFixes.buildCallLogSimLabelLookup(this@MainActivity)
        var resolutionFreshCount = 0
        var resolutionMemoCount: Int
        var unresolvedRows = 0

        try {
            contentResolver.query(
                Calls.CONTENT_URI,
                arrayOf(
                    Calls._ID,
                    Calls.NUMBER,
                    Calls.CACHED_NAME,
                    Calls.DATE,
                    Calls.TYPE,
                    Calls.DURATION,
                    Calls.PHONE_ACCOUNT_ID,
                    Calls.PHONE_ACCOUNT_COMPONENT_NAME,
                    Calls.FEATURES
                ),
                null, null, Calls.DATE + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    var name = c.getString(2) ?: "Unknown"
                    var number = c.getString(1) ?: ""
                    val timestamp = c.getLong(3)
                    val callType = when (c.getInt(4)) {
                        Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        Calls.INCOMING_TYPE -> CallType.INCOMING
                        Calls.MISSED_TYPE -> CallType.MISSED
                        else -> CallType.OUTGOING
                    }
                    val id = c.getString(0) ?: ""

                    if (number.isNotEmpty()) {
                        val duration = c.getLong(5)
                        val features = try { c.getInt(8) } catch (_: Exception) { 0 }
                        val isVideo = (features and Calls.FEATURES_VIDEO) != 0
                        val isTollFree = !isNumberVideoCallCapable(number)
                        val row = RecentCallRow(
                            id = id,
                            cachedName = name,
                            phoneNumber = number,
                            timestamp = timestamp,
                            callType = callType,
                            duration = duration,
                            phoneAccountId = c.getString(6),
                            phoneAccountComponentName = c.getString(7),
                            isVideo = isVideo,
                            isTollFree = isTollFree
                        )
                        callRows.add(row)
                    }
                    if (callRows.size >= 1000) break
                }
            }
        } catch (e: Exception) {}

        // TIME-BASED: App name → System fallback (first 7 days only) → Phone number
        val installTime = dpsPrefs.getLong("app_install_time", System.currentTimeMillis())
        if (installTime == 0L) {
            dpsPrefs.edit().putLong("app_install_time", System.currentTimeMillis()).apply()
        }
        val daysSinceInstall = (System.currentTimeMillis() - installTime) / (1000 * 60 * 60 * 24)
        val allowSystemFallback = daysSinceInstall < 7  // Fallback only for first 7 days

        // One P2P cache lookup per caller, not per call-log row.
        val p2pNameMemo = HashMap<String, String?>()

        // A row shows its SIM label ("SIM 1 - Jio") only if another SIM was also in the phone when it was made.
        // From when this app started recording SIM presence, that is read from the recorded periods (a SIM
        // taken out and put back has a gap there). Earlier calls can only be judged from the call log: the
        // time falls within a different SIM's span of calls (first to last) made before recording started.
        val trackingSince = simLabelLookup.trackingSince
        val simSpans: Map<String, LongRange> = run {
            callRows
                .filter { trackingSince == 0L || it.timestamp < trackingSince }
                .filter {
                    // A SIM call: its SIM is known, or its account belongs to the SIM calling service (a SIM
                    // removed before its label was saved still shows that another SIM was in use).
                    !it.phoneAccountId.isNullOrBlank() && (
                        simLabelLookup.resolve(it.phoneAccountId, it.phoneAccountComponentName).isNotBlank() ||
                            it.phoneAccountComponentName.orEmpty() in simLabelLookup.simComponents
                        )
                }
                .groupBy { it.phoneAccountId.orEmpty() }
                .mapValues { (_, rows) -> rows.minOf { it.timestamp }..rows.maxOf { it.timestamp } }
        }
        fun simLabelForRow(row: RecentCallRow): String {
            val label = simLabelLookup.resolve(row.phoneAccountId, row.phoneAccountComponentName)
            if (label.isBlank()) return ""
            val rowId = row.phoneAccountId.orEmpty()
            val otherSimInUse = if (trackingSince != 0L && row.timestamp >= trackingSince) {
                simLabelLookup.presenceById.any { (id, periods) ->
                    id != rowId && periods.any { row.timestamp in it }
                }
            } else {
                simSpans.any { (id, span) -> id != rowId && row.timestamp in span }
            }
            return if (otherSimInUse) label else ""
        }

        callRows.forEach { row ->
            val numberSnapshot = buildP2PNumberSnapshot(row.phoneNumber)
            Log.d("RecentCalls_Debug", "Number: ${row.phoneNumber} | Lookup keys: ${numberSnapshot.lookupKeys}")
            // contactMap also files a saved 18004190157 under 8004190157 (its lookupKeys drop the leading 1),
            // so a key hit alone gave 8004190157 that contact's name. Only accept a contact whose number is
            // the same Recent identity as this row.
            val rowIdentity = buildRecentCallerGroupKey(row.phoneNumber)

            // Check app stored name first
            val storedName = numberSnapshot.lookupKeys.firstNotNullOfOrNull { key ->
                val contactMapName = contactMap[key]
                    ?.takeIf { buildRecentCallerGroupKey(it.phoneNumber) == rowIdentity }
                    ?.name
                val prefsName = dpsPrefs.getString(key, null)
                Log.d("RecentCalls_Debug", "  Key: $key | ContactMap: $contactMapName | Prefs: $prefsName")

                contactMapName?.takeIf { it.isNotBlank() && it != "Unknown" }
                    ?: prefsName?.takeIf { it.isNotBlank() && it != "Unknown" }
            }
            // Then the name the caller shared over P2P, shown for a few hours after it arrived.
            // Memo per Recent identity: last10 is 8004190157 for both 18004190157 and 8004190157.
            val p2pMemoKey = rowIdentity
            val appName = storedName ?: if (p2pNameMemo.containsKey(p2pMemoKey)) {
                p2pNameMemo[p2pMemoKey]
            } else {
                lookupRecentP2PName(this@MainActivity, row.phoneNumber, RECENTS_P2P_NAME_WINDOW_MS)
                    .also { p2pNameMemo[p2pMemoKey] = it }
            }
            Log.d("RecentCalls_Debug", "Final appName for ${row.phoneNumber}: $appName")

            val displayName = appName ?: if (allowSystemFallback) {
                // Within 7 days → use system name as fallback ONLY if no app name
                val sysName = getSystemContactName(row.phoneNumber, this@MainActivity)
                Log.d("RecentCalls_Debug", "Using system fallback for ${row.phoneNumber}: $sysName")
                sysName ?: formatPhoneNumberForDisplay(row.phoneNumber)
            } else {
                // After 7 days → NO system fallback, phone only
                // Shown as the title, so format it like the number line under it (was 8004190157 over +918004190157).
                formatPhoneNumberForDisplay(row.phoneNumber)
            }

            val simLabel = simLabelForRow(row)
            calls.add(
                RecentCall(
                    id = row.id,
                    name = displayName,
                    // The call-log number as stored: Recents dials this. Display formatting (+91) is applied
                    // where it is shown; putting it here made Call dial +918004190157 for a stored 8004190157.
                    phoneNumber = row.phoneNumber,
                    timestamp = row.timestamp,
                    callType = row.callType,
                    duration = row.duration,
                    simLabel = simLabel,
                    isVideo = row.isVideo,
                    isTollFree = row.isTollFree
                )
            )
        }

        resolutionMemoCount = (unresolvedRows - resolutionFreshCount).coerceAtLeast(0)

        val groupedCalls = groupRecentCalls(calls)
        Log.w(
            P2P_FORENSIC_TAG,
            "recent_calls_load_complete rows=${calls.size} grouped=${groupedCalls.size} uniqueResolutions=${resolutionRequests.size} unresolvedRows=$unresolvedRows resolutionFresh=$resolutionFreshCount resolutionMemoHits=$resolutionMemoCount memoKeys=${resolutionCache.size} reqGen=$requestGen appliedGen=$recentCallsAppliedGeneration"
        )
        if (requestGen >= recentCallsAppliedGeneration) {
            recentCallsAppliedGeneration = requestGen
            withContext(Dispatchers.Main) {
                uiState = uiState.copy(recentCalls = calls, groupedRecentCalls = groupedCalls)
            }
        } else {
            Log.w(
                P2P_FORENSIC_TAG,
                "recent_calls_load_stale_skipped reqGen=$requestGen appliedGen=$recentCallsAppliedGeneration"
            )
        }
        return groupedCalls
    }

    private fun groupRecentCalls(calls: List<RecentCall>): List<GroupedRecentCall> {
        val grouped = calls.groupBy { buildRecentCallerGroupKey(it.phoneNumber) }
        return grouped.map { (groupKey, callList) ->
            // The group's number is what Recents shows and dials, so it must stay the call-log number as
            // stored. normalizePhoneNumber() is a lookup key (it drops the leading 1 of 11-digit numbers),
            // and using it here turned 18004190157 into 8004190157.
            val canonicalPhoneNumber = callList.first().phoneNumber.trim().ifBlank {
                callList.first().phoneNumber
            }
            val providerRowIds = callList.map { it.id }.filter { it.isNotBlank() }.distinct()
            val name = callList.firstOrNull { it.name != "Unknown" }?.name ?: callList.first().name
            val callHistory = callList.map {
                val isBlockedCall = it.duration <= 0L && isPhoneNumberBlocked(it.phoneNumber)
                CallHistoryItem(
                    timestamp = it.timestamp, callType = it.callType, duration = it.duration,
                    formattedTime = formatTimestamp(it.timestamp),
                    formattedDuration = if (isBlockedCall) "Blocked" else formatCallDuration(it.duration, it.callType),
                    typeString = if (isBlockedCall) "Blocked" else callTypeToString(it.callType.name),
                    isBlocked = isBlockedCall,
                    simLabel = it.simLabel,
                    isVideo = it.isVideo,
                    isTollFree = it.isTollFree
                )
            }.sortedByDescending { it.timestamp }
            Log.w(
                P2P_FORENSIC_TAG,
                "recent_group_bucket source=groupRecentCalls groupKey=$groupKey bucketSize=${callHistory.size} phone=${maskP2PNumberForLog(canonicalPhoneNumber)} rowIdCount=${providerRowIds.size}"
            )
            GroupedRecentCall(
                phoneNumber = canonicalPhoneNumber,
                name = name,
                callHistory = callHistory,
                groupKey = groupKey,
                providerRowIds = providerRowIds
            )
        }.sortedByDescending { it.callHistory.first().timestamp }
    }

    private fun refreshRecentCalls(withDelay: Boolean = false) {
        val reqGen = ++recentCallsRequestGeneration
        val activeJob = recentCallsRefreshJob
        if (activeJob?.isActive == true) {
            pendingRecentCallsRefresh = true
            pendingRecentCallsRefreshDelay = pendingRecentCallsRefreshDelay || withDelay
            Log.w(
                P2P_FORENSIC_TAG,
                "recent_calls_refresh action=coalesced withDelay=$withDelay pendingDelay=$pendingRecentCallsRefreshDelay reqGen=$reqGen"
            )
            return
        }
        Log.w(P2P_FORENSIC_TAG, "recent_calls_refresh action=start withDelay=$withDelay reqGen=$reqGen")
        recentCallsRefreshJob = CoroutineScope(Dispatchers.IO).launch {
            if (withDelay) delay(1500)
            val currentReqGen = recentCallsRequestGeneration
            val groupedCalls = loadRecentCalls(currentReqGen)
            Log.w(P2P_FORENSIC_TAG, "recent_calls_refresh action=end withDelay=$withDelay grouped=${groupedCalls.size} reqGen=$currentReqGen appliedGen=$recentCallsAppliedGeneration")
            val shouldRerun = pendingRecentCallsRefresh
            val rerunWithDelay = pendingRecentCallsRefreshDelay
            pendingRecentCallsRefresh = false
            pendingRecentCallsRefreshDelay = false
            recentCallsRefreshJob = null
            if (shouldRerun) {
                refreshRecentCalls(withDelay = rerunWithDelay)
            }
        }
    }

    private fun hasLiveFrameworkCall(): Boolean = isTelecomInCallSafely()

    private fun launchMessagingActivity(address: String = "", body: String = "") {
        val intent = Intent(this, MessagingActivity::class.java).apply {
            action = ACTION_OPEN_MESSAGES
            putExtra(EXTRA_SMS_ADDRESS, address)
            putExtra(EXTRA_SMS_BODY, body)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun handleIncomingCallIntent() {
        intent?.let { intent ->
            if (isPhoneLauncherIntent(intent)) {
                uiState = uiState.copy(
                    selectedTab = TAB_RECENTS,
                    prefilledNumber = "",
                    recentSearchQuery = "",
                    recentDialpadVisible = false,
                    recentDialpadDigits = ""
                )
                if (hasReadCallLogPermission()) {
                    refreshRecentCalls(withDelay = false)
                }
                return
            }
            if (intent.action.isNullOrBlank() && intent.data == null) {
                launchMessagingActivity()
                finish()
                return
            }
            when (intent.action) {
                Intent.ACTION_MAIN -> {
                    launchMessagingActivity()
                    finish()
                    return
                }
                Intent.ACTION_DIAL -> {
                    val number = intent.data?.schemeSpecificPart ?: ""
                    uiState = uiState.copy(
                        prefilledNumber = number,
                        selectedTab = TAB_KEYPAD,
                        recentSearchQuery = ""
                    )
                }
                Intent.ACTION_VIEW -> {
                    val scheme = intent.data?.scheme?.lowercase(Locale.ROOT).orEmpty()
                    if (scheme == "sms" || scheme == "smsto" || scheme == "mms" || scheme == "mmsto") {
                        launchMessagingActivity(
                            address = extractSmsAddress(intent),
                            body = extractSmsBody(intent)
                        )
                    } else {
                        val number = intent.data?.schemeSpecificPart ?: ""
                        uiState = uiState.copy(
                            prefilledNumber = number,
                            selectedTab = TAB_KEYPAD,
                            recentSearchQuery = ""
                        )
                    }
                }
                Intent.ACTION_SENDTO, Intent.ACTION_SEND, ACTION_OPEN_MESSAGES -> {
                    launchMessagingActivity(
                        address = extractSmsAddress(intent),
                        body = extractSmsBody(intent)
                    )
                }
                "OPEN_RECENTS" -> {
                    uiState = uiState.copy(
                        selectedTab = TAB_RECENTS,
                        prefilledNumber = "",
                        recentSearchQuery = "",
                        recentDialpadVisible = false,
                        recentDialpadDigits = ""
                    )
                    refreshRecentCalls(withDelay = true)
                }
                POWER_END_RECOVERY_ACTION -> {
                    val recoveryNumber = intent.getStringExtra(POWER_END_RECOVERY_PHONE_EXTRA).orEmpty()
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "power_end_recovery_action source=MainActivity.handleIncomingCallIntent number=${maskP2PNumberForLog(recoveryNumber)} valid=${isValidPhoneNumber(recoveryNumber)}"
                    )
                    if (isValidPhoneNumber(recoveryNumber)) {
                        startCall(recoveryNumber)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCall(phoneNumber: String, isVideoCall: Boolean = false) {
        val rawPhoneNumber = phoneNumber.trim()
        val dialableNumber = sanitizePhoneNumberForCall(rawPhoneNumber)
        if (!isValidPhoneNumber(dialableNumber)) {
            showError("Invalid phone number")
            return
        }
        if (!hasCallPhonePermission()) {
            showError("Call permission is required to place calls")
            return
        }
        if (!DualSimFixes.hasRequiredSubscriptionPermissions(this)) {
            Log.w(P2P_FORENSIC_TAG, "sim_detection_missing_permissions missing=${DualSimFixes.missingSubscriptionPermissions(this).joinToString(",")}")
            requestPermissions()
            showError("Phone state permission is required to detect installed SIM cards")
            return
        }

        var effectiveIsVideoCall = isVideoCall
        if (effectiveIsVideoCall && !isNumberVideoCallCapable(dialableNumber)) {
            effectiveIsVideoCall = false
            Toast.makeText(this, "Toll-free or service numbers do not support video calls. Placing voice call instead.", Toast.LENGTH_LONG).show()
            Log.w(P2P_FORENSIC_TAG, "video_call_downgraded_to_voice reason=service_or_toll_free_number raw=${maskP2PNumberForLog(rawPhoneNumber)}")
        }

        try {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val displayNumber = formatPhoneNumberForDisplay(rawPhoneNumber)
            val finalUri = Uri.fromParts("tel", dialableNumber, null)
            val resolvedContactName = getContactName(dialableNumber)
            val hadActiveCallBeforeLaunch = uiState.callState.isInCall
            val simOptions = DualSimFixes.resolveSimCallOptions(this)

            when {
                simOptions.isEmpty() -> {
                    showError("No SIM detected")
                }
                simOptions.size == 1 -> {
                    placeOutgoingCall(
                        telecomManager = telecomManager,
                        finalUri = finalUri,
                        rawPhoneNumber = rawPhoneNumber,
                        dialableNumber = dialableNumber,
                        displayNumber = displayNumber,
                        resolvedContactName = resolvedContactName,
                        hadActiveCallBeforeLaunch = hadActiveCallBeforeLaunch,
                        selectedSim = simOptions.first(),
                        isVideoCall = effectiveIsVideoCall
                    )
                }
                else -> {
                    DualSimFixes.showSimSelectionDialog(
                        activity = this,
                        options = simOptions,
                        onSelected = { selectedSim ->
                            placeOutgoingCall(
                                telecomManager = telecomManager,
                                finalUri = finalUri,
                                rawPhoneNumber = rawPhoneNumber,
                                dialableNumber = dialableNumber,
                                displayNumber = displayNumber,
                                resolvedContactName = resolvedContactName,
                                hadActiveCallBeforeLaunch = hadActiveCallBeforeLaunch,
                                selectedSim = selectedSim,
                                isVideoCall = effectiveIsVideoCall
                            )
                        },
                        onCancelled = {
                            showError("Call cancelled")
                        }
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(P2P_FORENSIC_TAG, "sim_detection_failed reason=security_exception error=${e.message}")
            requestPermissions()
            showError("Phone state permission is required to detect installed SIM cards")
        } catch (e: IllegalStateException) {
            Log.e(P2P_FORENSIC_TAG, "sim_detection_failed reason=illegal_state error=${e.message}", e)
            showError(e.message ?: "Unable to detect installed SIM cards")
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "outgoing_call_failed reason=unexpected error=${e.message}", e)
            showError("Failed to start call")
        }
    }

    @SuppressLint("MissingPermission")
    private fun placeOutgoingCall(
        telecomManager: TelecomManager,
        finalUri: Uri,
        rawPhoneNumber: String,
        dialableNumber: String,
        displayNumber: String,
        resolvedContactName: String,
        hadActiveCallBeforeLaunch: Boolean,
        selectedSim: DualSimFixes.SimCallOption,
        isVideoCall: Boolean = false
    ) {
        val extras = DualSimFixes.buildCallExtras(selectedSim)
        if (isVideoCall) {
            extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
        }
        Log.w(
            P2P_FORENSIC_TAG,
            "outgoing_call_prepare branch=main_activity_start raw=${maskP2PNumberForLog(rawPhoneNumber)} dialable=${maskP2PNumberForLog(dialableNumber)} isVideoCall=$isVideoCall finalUri=${maskP2PNumberForLog(finalUri.schemeSpecificPart.orEmpty())} api=TelecomManager.placeCall selectedSimId=${selectedSim.subscriptionId} slot=${selectedSim.slotIndex} carrier=${selectedSim.carrierName} handle=${selectedSim.phoneAccountHandle.id}"
        )

        uiState = uiState.copy(
            callState = uiState.callState.copy(
                isInCall = true,
                isDialing = true,
                phoneNumber = displayNumber,
                contactName = resolvedContactName,
                simLabel = selectedSim.compactLabel(),
                isVideoCall = isVideoCall
            )
        )

        startActivity(Intent(this, CallActivity::class.java).apply {
            action = CALL_UI_ACTION_WAKE
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            attachCallUiSeed(
                phoneNumber = displayNumber,
                contactName = resolvedContactName,
                stateHint = "DIALING",
                simLabel = selectedSim.compactLabel(),
                isVideoCall = isVideoCall
            )
        })
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val inCallService = MyInCallService.getInstance()
        if (inCallService != null && hadActiveCallBeforeLaunch) {
            Log.w(
                P2P_FORENSIC_TAG,
                "outgoing_call_dispatch branch=hold_then_place raw=${maskP2PNumberForLog(rawPhoneNumber)} dialable=${maskP2PNumberForLog(dialableNumber)} selectedSimId=${selectedSim.subscriptionId}"
            )
            inCallService.holdCall()
            Handler(Looper.getMainLooper()).postDelayed({
                dispatchPlaceCall(telecomManager, finalUri, extras)
            }, 200)
        } else {
            Log.w(
                P2P_FORENSIC_TAG,
                "outgoing_call_dispatch branch=direct_place raw=${maskP2PNumberForLog(rawPhoneNumber)} dialable=${maskP2PNumberForLog(dialableNumber)} selectedSimId=${selectedSim.subscriptionId}"
            )
            dispatchPlaceCall(telecomManager, finalUri, extras)
        }
    }

    @SuppressLint("MissingPermission")
    private fun dispatchPlaceCall(
        telecomManager: TelecomManager,
        finalUri: Uri,
        extras: Bundle
    ) {
        try {
            if (hasCallPhonePermission()) {
                telecomManager.placeCall(finalUri, extras)
            }
        } catch (e: SecurityException) {
            showError("Permission denied to place call")
        } catch (e: Exception) {
            showError("Failed to start call")
        }
    }
    private fun Intent.attachCallUiSeed(
        phoneNumber: String,
        contactName: String,
        stateHint: String,
        simLabel: String = "",
        isConference: Boolean = false,
        isVideoCall: Boolean = false
    ): Intent {
        putExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT, phoneNumber)
        putExtra(CALL_UI_EXTRA_CONTACT_NAME_HINT, contactName)
        putExtra(CALL_UI_EXTRA_STATE_HINT, stateHint)
        putExtra(CALL_UI_EXTRA_SIM_LABEL, simLabel)
        putExtra(CALL_UI_EXTRA_IS_CONFERENCE_HINT, isConference)
        putExtra("EXTRA_IS_VIDEO_CALL", isVideoCall)
        return this
    }

    private var avatarLookupSource: List<Contact>? = null
    private var avatarLookup: Map<String, Contact> = emptyMap()

    private fun avatarContactLookup(): Map<String, Contact> {
        val contacts = uiState.contacts
        if (contacts !== avatarLookupSource) {
            avatarLookup = buildContactLookupMap(contacts)
            avatarLookupSource = contacts
        }
        return avatarLookup
    }

    /**
     * Stable identity for a person's avatar color. A saved contact is keyed by its contact name,
     * so it matches the Contacts tab; an unsaved caller is keyed by number, so the color doesn't
     * change when Recents switches between a P2P-shared name and the bare number.
     */
    private fun avatarColorKey(name: String, phoneNumber: String?): String {
        if (!phoneNumber.isNullOrBlank()) {
            val snapshot = buildP2PNumberSnapshot(phoneNumber)
            val lookup = avatarContactLookup()
            val saved = snapshot.lookupKeys.firstNotNullOfOrNull { lookup[it] }
            if (saved != null && saved.name.isNotBlank()) {
                return "name:" + saved.name.trim().lowercase()
            }
            val digits = snapshot.last10.ifBlank { snapshot.digits }
            if (digits.isNotBlank()) return "num:$digits"
        }
        return "name:" + name.trim().lowercase()
    }

    private fun getAvatarColor(name: String, phoneNumber: String? = null): Color {
        val colors = listOf(
            Color(0xFF1ABC9C), Color(0xFF2ECC71), Color(0xFF3498DB),
            Color(0xFF9B59B6), Color(0xFF34495E), Color(0xFF16A085),
            Color(0xFF27AE60), Color(0xFF2980B9), Color(0xFF8E44AD),
            Color(0xFF2C3E50), Color(0xFFF1C40F), Color(0xFFE67E22),
            Color(0xFFE74C3C), Color(0xFFD35400), Color(0xFFC0392B)
        )
        // String.hashCode is specified by the JVM, so the same key gives the same color on every run.
        return colors[Math.floorMod(avatarColorKey(name, phoneNumber).hashCode(), colors.size)]
    }

    private fun showError(message: String) { runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() } }
    @Deprecated("This method uses a deprecated member. Consider updating to a newer approach.")
    private fun showSuccess(message: String) { runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() } }

    private fun getContactName(phoneNumber: String): String {
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() ?: this else this
        return resolveDisplayName(
            phoneNumber = phoneNumber,
            cachedName = "Unknown",
            safeContext = safeContext,
            contactMap = buildContactLookupMap(uiState.contacts),
            trigger = "MainActivity.startCall"
        ).first
    }

    @Composable
    private fun MainScreen(
        contacts: List<Contact>,
        contactDisplayEntries: List<ContactDisplayEntry>,
        recentCalls: List<RecentCall>,
        groupedRecentCalls: List<GroupedRecentCall>,
        isLoading: Boolean,
        selectedTab: Int,
        prefilledNumber: String = "",
        recentSearchQuery: String,
        recentDialpadVisible: Boolean,
        recentDialpadDigits: String,
        refreshTrigger: Int,
        isPowerSaveMode: Boolean,
        isInCall: Boolean = false,
        onTabSelected: (Int) -> Unit,
        onRecentSearchQueryChange: (String) -> Unit,
        onRecentDialpadVisibilityChange: (Boolean) -> Unit,
        onRecentDialpadDigitsChange: (String) -> Unit,
        onContactClick: (String) -> Unit,
        onContactEntryClick: (ContactDisplayEntry) -> Unit,
        onRecentCallClick: (String) -> Unit,
        onRefresh: () -> Unit,
        onOpenAddContact: () -> Unit,
        onOpenAddContactWithNumber: (String) -> Unit = {},
        onRequestDefaultDialer: () -> Unit,
        onPrefilledNumberConsumed: () -> Unit = {},
        onDeleteContact: (Contact) -> Unit = {},
        onDeleteRecentCall: (PendingRecentDeleteRequest) -> Unit = {},
        onToggleBlockPhoneNumber: (String) -> Unit = {},
        modifier: Modifier = Modifier
    ) {
        val ctx = LocalContext.current
        var contactSearchQuery by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        var pendingContactDelete by remember { mutableStateOf<Contact?>(null) }
        var pendingRecentDelete by remember { mutableStateOf<PendingRecentDeleteRequest?>(null) }
        val requestRecentDelete: (String) -> Unit = requestRecentDelete@{ phoneNumber ->
            val groupedCall = groupedRecentCalls.firstOrNull { it.phoneNumber == phoneNumber }
            val providerRowIds = groupedCall?.providerRowIds?.filter { it.isNotBlank() }?.distinct().orEmpty()
            val targetRowId = providerRowIds.firstOrNull().orEmpty()
            if (groupedCall == null || targetRowId.isBlank()) {
                Log.w(
                    P2P_FORENSIC_TAG,
                    "recent_delete action=request_failed reason=missing_group_or_row_ids phone=${maskP2PNumberForLog(phoneNumber)} groupedFound=${groupedCall != null} rowIdCount=${providerRowIds.size}"
                )
                showError("Unable to resolve recent call delete target")
                return@requestRecentDelete
            }
            val displayName = groupedCall.name
                .takeIf { it.isNotBlank() && it != "Unknown" }
                ?: formatPhoneNumberForDisplay(phoneNumber)
            Log.w(
                P2P_FORENSIC_TAG,
                "recent_delete action=request groupKey=${groupedCall.groupKey} phone=${maskP2PNumberForLog(phoneNumber)} targetRowId=$targetRowId rowIds=${providerRowIds.joinToString(",")}"
            )
            pendingRecentDelete = PendingRecentDeleteRequest(
                phoneNumber = phoneNumber,
                displayName = displayName,
                groupKey = groupedCall.groupKey,
                targetRowId = targetRowId,
                providerRowIds = providerRowIds,
                bucketSize = groupedCall.callHistory.size
            )
        }

        Column(modifier = modifier.fillMaxSize()) {
            if (isInCall) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(48.dp).clickable {
                        val intent = Intent(ctx, CallActivity::class.java).apply {
                            action = "WAKE_UI"
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        ctx.startActivity(intent)
                    },
                    color = OneUi.CallGreen
                ) {
                    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ongoing Call - Tap to return", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            // One UI: text-only tabs at the bottom. Profile (the P2P caller-name profile) keeps its own tab,
            // as before the redesign; the "My profile" card in Contacts opens it too.
            val visibleTabs = listOf(
                TAB_KEYPAD to "Keypad",
                TAB_RECENTS to "Recents",
                TAB_CONTACTS to "Contacts",
                TAB_PROFILE to "Profile",
                TAB_VOICEMAIL to "Voicemail",
                TAB_SETTINGS to "Settings"
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (selectedTab) {
                TAB_CONTACTS -> {
                    var showContactSearch by remember { mutableStateOf(false) }
                    val safeProfileContext = remember(ctx) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            ctx.createDeviceProtectedStorageContext()
                        } else {
                            ctx
                        }
                    }
                    val profilePrefs = remember(safeProfileContext) {
                        safeProfileContext.getSharedPreferences("local_user_profile", MODE_PRIVATE)
                    }
                    val profileName = profilePrefs.getString("my_name", "")?.takeIf { it.isNotBlank() } ?: "Set up your caller profile"
                    val filteredContactEntries = remember(contactSearchQuery, contactDisplayEntries) {
                        if (contactSearchQuery.isBlank()) {
                            contactDisplayEntries
                        } else {
                            contactDisplayEntries.filter { entry ->
                                entry.name.contains(contactSearchQuery, ignoreCase = true) ||
                                entry.phoneNumbers.any { it.contains(contactSearchQuery) }
                            }
                        }
                    }
                    val favoriteContacts = remember(filteredContactEntries, uiState.refreshTrigger) {
                        filteredContactEntries.filter(::isContactEntryFavorite)
                    }
                    val groupedContacts = remember(filteredContactEntries, uiState.refreshTrigger) {
                        filteredContactEntries
                            .filterNot(::isContactEntryFavorite)
                            .groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
                    }
                    val alphabet = remember { ('A'..'Z').toList() }
                    val letterToIndexMap = remember(groupedContacts, favoriteContacts) {
                        val map = mutableMapOf<Char, Int>()
                        val favoriteOffset = if (favoriteContacts.isNotEmpty()) favoriteContacts.size + 1 else 0
                        var currentIndex = 3 + favoriteOffset
                        alphabet.forEach { char ->
                            if (groupedContacts.containsKey(char)) {
                                map[char] = currentIndex
                                currentIndex += (groupedContacts[char]?.size ?: 0) + 1
                            }
                        }
                        map
                    }
                    var selectedAlphabetChar by remember { mutableStateOf<Char?>(null) }
                    var sidebarHeightPx by remember { mutableStateOf(0f) }
                    var draggingLetter by remember { mutableStateOf<Char?>(null) }
                    var isDraggingSidebar by remember { mutableStateOf(false) }

                    val findClosestTargetIndex: (Char) -> Int? = remember(letterToIndexMap) {
                        { char ->
                            letterToIndexMap[char] ?: run {
                                var found: Int? = null
                                for (c in (char + 1)..'Z') {
                                    val idx = letterToIndexMap[c]
                                    if (idx != null) {
                                        found = idx
                                        break
                                    }
                                }
                                if (found == null) {
                                    for (c in (char - 1) downTo 'A') {
                                        val idx = letterToIndexMap[c]
                                        if (idx != null) {
                                            found = idx
                                            break
                                        }
                                    }
                                }
                                found
                            }
                        }
                    }

                    val updateSelectionFromY: (Float) -> Unit = remember(sidebarHeightPx, letterToIndexMap) {
                        { y ->
                            val height = sidebarHeightPx
                            if (height > 0f) {
                                val fraction = (y / height).coerceIn(0f, 0.999f)
                                val letterIndex = (fraction * 26).toInt().coerceIn(0, 25)
                                val char = 'A' + letterIndex
                                val targetIndex = letterToIndexMap[char]
                                if (targetIndex != null && targetIndex != -1) {
                                    draggingLetter = char
                                    selectedAlphabetChar = char
                                    scope.launch { listState.scrollToItem(targetIndex) }
                                }
                            }
                        }
                    }

                    val isListScrollInProgress = listState.isScrollInProgress
                    LaunchedEffect(isListScrollInProgress) {
                        if (isListScrollInProgress && !isDraggingSidebar) {
                            selectedAlphabetChar = null
                            draggingLetter = null
                        }
                    }
                    val currentScrollChar = remember(listState.firstVisibleItemIndex, letterToIndexMap) {
                        val currentIndex = listState.firstVisibleItemIndex
                        alphabet.lastOrNull { char ->
                            val idx = letterToIndexMap[char] ?: Int.MAX_VALUE
                            idx <= currentIndex
                        }
                    }
                    val activeAlphabetChar = draggingLetter ?: selectedAlphabetChar ?: currentScrollChar
                    val oneUi = oneUiColors()
                    val contactPrimaryTextColor = oneUi.textPrimary
                    val contactSecondaryTextColor = oneUi.textSecondary
                    val contactTertiaryTextColor = oneUi.textTertiary
                    val contactAccentColor = OneUi.Blue
                    val contactSearchSurfaceColor = oneUi.chip
                    val contactCardColor = oneUi.card
                    val contactProfileAvatarBg = OneUi.Blue.copy(alpha = 0.15f)
                    val contactProfileAvatarTint = OneUi.Blue

                    OneUiCollapsingPage(
                        title = "Contacts",
                        subtitle = "${contactDisplayEntries.size} contacts with phone numbers",
                        actions = {
                            // Only while a possible duplicate is unanswered; gone once all are checked.
                            if (pendingDuplicateCandidate != null) {
                                IconButton(onClick = { showDuplicateDialog = true }) {
                                    Icon(Icons.Default.PeopleAlt, contentDescription = "Check duplicate contacts", tint = OneUi.Blue)
                                }
                            }
                            IconButton(onClick = onOpenAddContact) {
                                Icon(Icons.Default.Add, contentDescription = "Add contact", tint = contactPrimaryTextColor)
                            }
                            IconButton(
                                onClick = {
                                    val nextVisible = !showContactSearch
                                    showContactSearch = nextVisible
                                    if (!nextVisible) {
                                        contactSearchQuery = ""
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search Contacts", tint = contactPrimaryTextColor)
                            }
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Contacts", tint = contactPrimaryTextColor)
                            }
                        }
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                if (showContactSearch || contactSearchQuery.isNotEmpty()) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 12.dp),
                                        shape = RoundedCornerShape(28.dp),
                                        color = contactSearchSurfaceColor
                                    ) {
                                        OutlinedTextField(
                                            value = contactSearchQuery,
                                            onValueChange = { contactSearchQuery = it },
                                            placeholder = {
                                                Text(
                                                    text = "Search contacts",
                                                    color = contactTertiaryTextColor
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Search,
                                                    contentDescription = null,
                                                    tint = contactSecondaryTextColor
                                                )
                                            },
                                            trailingIcon = {
                                                if (contactSearchQuery.isNotEmpty()) {
                                                    IconButton(onClick = { contactSearchQuery = "" }) {
                                                        Icon(
                                                            Icons.Default.KeyboardArrowDown,
                                                            contentDescription = "Clear Search",
                                                            tint = contactSecondaryTextColor
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(28.dp),
                                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                                containerColor = Color.Transparent,
                                                focusedBorderColor = Color.Transparent,
                                                unfocusedBorderColor = Color.Transparent,
                                                focusedTextColor = contactPrimaryTextColor,
                                                unfocusedTextColor = contactPrimaryTextColor,
                                                cursorColor = contactPrimaryTextColor
                                            )
                                        )
                                    }
                                }
                            }

                            if (isLoading && contactDisplayEntries.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(strokeWidth = 3.dp, color = contactAccentColor)
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 12.dp, end = 0.dp)
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        state = listState,
                                        contentPadding = PaddingValues(bottom = 32.dp)
                                    ) {
                                        item("profile_header") {
                                            Text(
                                                text = "My profile",
                                                modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                                                color = contactSecondaryTextColor,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        item("profile_card") {
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(end = 2.dp, bottom = 18.dp)
                                                    .clip(RoundedCornerShape(30.dp))
                                                    .clickable { onTabSelected(TAB_PROFILE) },
                                                shape = RoundedCornerShape(30.dp),
                                                color = contactCardColor
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 18.dp, vertical = 18.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(56.dp)
                                                            .clip(CircleShape)
                                                            .background(contactProfileAvatarBg),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Person,
                                                            contentDescription = null,
                                                            tint = contactProfileAvatarTint,
                                                            modifier = Modifier.size(28.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(16.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = profileName,
                                                            color = contactPrimaryTextColor,
                                                            style = MaterialTheme.typography.titleLarge,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                        Text(
                                                            text = "Tap to manage your caller identity",
                                                            color = contactSecondaryTextColor,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        item("contacts_list_header") {
                                            Text(
                                                text = if (contactSearchQuery.isBlank()) "All contacts" else "Search results",
                                                modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                                                color = contactSecondaryTextColor,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        if (favoriteContacts.isNotEmpty()) {
                                            item("favorite_contacts_header") {
                                                Text(
                                                    text = "Favorites",
                                                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                                                    color = contactSecondaryTextColor,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            itemsIndexed(items = favoriteContacts, key = { _, entry -> "favorite_${entry.displayKey}" }) { index, entry ->
                                                val isFirst = index == 0
                                                val isLast = index == favoriteContacts.lastIndex
                                                if (entry.isMerged) {
                                                    MergedContactItem(
                                                        entry = entry,
                                                        isFavorite = true,
                                                        onDeleteNumber = { pendingContactDelete = it },
                                                        onToggleFavorite = { toggleContactEntryFavorite(entry, currentlyFavorite = true) },
                                                        isFirst = isFirst,
                                                        isLast = isLast
                                                    )
                                                } else {
                                                    val sourceContact = entry.sourceContacts.first()
                                                    ContactItem(
                                                        contact = sourceContact,
                                                        isFavorite = true,
                                                        onClick = { onContactEntryClick(entry) },
                                                        onDelete = { pendingContactDelete = sourceContact },
                                                        onToggleFavorite = {
                                                            if (setPhoneNumberFavorite(sourceContact.phoneNumber, false)) {
                                                                showSuccess("${sourceContact.name} removed from favorites")
                                                                forceUpdateUI()
                                                            }
                                                        },
                                                        isFirst = isFirst,
                                                        isLast = isLast
                                                    )
                                                }
                                            }
                                        }

                                        if (groupedContacts.isEmpty() && favoriteContacts.isEmpty()) {
                                            item("empty_contacts") {
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp),
                                                    shape = RoundedCornerShape(28.dp),
                                                    color = contactCardColor
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(
                                                            text = "No contacts found",
                                                            color = contactPrimaryTextColor,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        Text(
                                                            text = "Try another name or number.",
                                                            color = contactSecondaryTextColor,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            groupedContacts.forEach { (initial, contactsInGroup) ->
                                                stickyHeader {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(Color.Transparent)
                                                            .padding(start = 8.dp, top = 8.dp, bottom = 6.dp)
                                                    ) {
                                                        Text(
                                                            text = initial.toString(),
                                                            color = contactSecondaryTextColor,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                    }
                                                }

                                                itemsIndexed(items = contactsInGroup, key = { _, entry -> entry.displayKey }) { index, entry ->
                                                    val isFirst = index == 0
                                                    val isLast = index == contactsInGroup.lastIndex
                                                    if (entry.isMerged) {
                                                        MergedContactItem(
                                                            entry = entry,
                                                            isFavorite = false,
                                                            onDeleteNumber = { pendingContactDelete = it },
                                                            onToggleFavorite = { toggleContactEntryFavorite(entry, currentlyFavorite = false) },
                                                            isFirst = isFirst,
                                                            isLast = isLast
                                                        )
                                                    } else {
                                                        val sourceContact = entry.sourceContacts.first()
                                                        ContactItem(
                                                            contact = sourceContact,
                                                            isFavorite = false,
                                                            onClick = { onContactEntryClick(entry) },
                                                            onDelete = { pendingContactDelete = sourceContact },
                                                            onToggleFavorite = {
                                                                if (setPhoneNumberFavorite(sourceContact.phoneNumber, true)) {
                                                                    showSuccess("${sourceContact.name} added to favorites")
                                                                    forceUpdateUI()
                                                                }
                                                            },
                                                            isFirst = isFirst,
                                                            isLast = isLast
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        item { Spacer(modifier = Modifier.height(24.dp)) }
                                    }

                                    if (contactSearchQuery.isEmpty() && !showContactSearch && contactDisplayEntries.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .width(28.dp)
                                                .fillMaxHeight()
                                                .padding(vertical = 6.dp)
                                                .onGloballyPositioned { coordinates ->
                                                    sidebarHeightPx = coordinates.size.height.toFloat()
                                                }
                                                .pointerInput(Unit) {
                                                    detectVerticalDragGestures(
                                                        onDragStart = { offset ->
                                                            isDraggingSidebar = true
                                                            updateSelectionFromY(offset.y)
                                                        },
                                                        onDragEnd = {
                                                            isDraggingSidebar = false
                                                        },
                                                        onDragCancel = {
                                                            isDraggingSidebar = false
                                                        },
                                                        onVerticalDrag = { change, _ ->
                                                            change.consume()
                                                            isDraggingSidebar = true
                                                            updateSelectionFromY(change.position.y)
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.SpaceEvenly
                                            ) {
                                                alphabet.forEach { char ->
                                                    val isAvailable = letterToIndexMap.containsKey(char)
                                                    val isHighlighted = char == activeAlphabetChar

                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxWidth()
                                                            .clickable(enabled = isAvailable) {
                                                                selectedAlphabetChar = char
                                                                val targetIdx = letterToIndexMap[char] ?: -1
                                                                if (targetIdx != -1) {
                                                                    scope.launch { listState.scrollToItem(targetIdx) }
                                                                }
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isHighlighted) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(24.dp)
                                                                    .clip(CircleShape)
                                                                    .background(OneUi.Blue),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(
                                                                    text = char.toString(),
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color.White
                                                                )
                                                            }
                                                        } else {
                                                            Text(
                                                                text = char.toString(),
                                                                fontSize = 13.sp,
                                                                fontWeight = if (isAvailable) FontWeight.Bold else FontWeight.Medium,
                                                                color = if (isAvailable) contactPrimaryTextColor else contactSecondaryTextColor.copy(alpha = 0.65f)
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            if (isDraggingSidebar && draggingLetter != null) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.Center)
                                                        .padding(end = 60.dp)
                                                        .size(58.dp)
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(OneUi.Blue),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = draggingLetter.toString(),
                                                        fontSize = 30.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                TAB_RECENTS, TAB_KEYPAD -> {
                    val isKeypadTab = selectedTab == TAB_KEYPAD
                    var showRecentSearch by remember { mutableStateOf(false) }
                    LaunchedEffect(prefilledNumber) {
                        if (prefilledNumber.isNotEmpty()) {
                            onRecentDialpadDigitsChange(prefilledNumber)
                            onRecentDialpadVisibilityChange(true)
                            onPrefilledNumberConsumed()
                        }
                    }

                    val digitQuery = remember(recentDialpadDigits) { recentDialpadDigits.filter { it.isDigit() } }
                    val filteredRecentCalls = remember(recentSearchQuery, groupedRecentCalls) {
                        if (recentSearchQuery.isEmpty()) groupedRecentCalls else groupedRecentCalls.filter {
                            it.name.contains(recentSearchQuery, ignoreCase = true) || it.phoneNumber.contains(recentSearchQuery)
                        }
                    }
                    val digitMatchedContacts by produceState(
                        initialValue = emptyList<ContactDisplayEntry>(),
                        digitQuery,
                        contactDisplayEntries
                    ) {
                        if (digitQuery.isBlank()) {
                            value = emptyList()
                            return@produceState
                        }
                        value = withContext(Dispatchers.Default) {
                            contactDisplayEntries.filter { entry ->
                                entry.phoneNumbers.any { num -> num.filter { it.isDigit() }.contains(digitQuery) } ||
                                    mapContactNameToT9Digits(entry.name).contains(digitQuery)
                            }.take(40)
                        }
                    }
                    val digitMatchedRecentCalls by produceState(
                        initialValue = emptyList<GroupedRecentCall>(),
                        digitQuery,
                        groupedRecentCalls,
                        digitMatchedContacts
                    ) {
                        if (digitQuery.isBlank()) {
                            value = emptyList()
                            return@produceState
                        }
                        value = withContext(Dispatchers.Default) {
                            val matchedContactNumbers = digitMatchedContacts
                                .flatMap { entry -> entry.phoneNumbers }
                                .map(::normalizePhoneNumber)
                                .toSet()
                            groupedRecentCalls.filter { groupedCall ->
                                val normalizedRecentNumber = normalizePhoneNumber(groupedCall.phoneNumber)
                                (groupedCall.phoneNumber.filter { it.isDigit() }.contains(digitQuery) ||
                                    mapContactNameToT9Digits(groupedCall.name).contains(digitQuery)) &&
                                    normalizedRecentNumber !in matchedContactNumbers
                            }.take(40)
                        }
                    }
                    val hideRecentDialpad = {
                        onRecentDialpadVisibilityChange(false)
                        if (recentDialpadDigits.isNotEmpty()) {
                            onRecentDialpadDigitsChange("")
                        }
                    }
                    val recentListState = rememberLazyListState()
                    LaunchedEffect(
                        digitQuery,
                        digitMatchedContacts,
                        digitMatchedRecentCalls,
                        recentDialpadVisible
                    ) {
                        recentListState.scrollToItem(0)
                    }
                    // After a call ends, the refreshed list puts the new call on top. Rows are keyed, so the
                    // LazyColumn would stay on the old first row and hide the new one above it. Scroll to the
                    // top only when a NEWER call than any seen before arrives (i.e. a call just ended), and only
                    // after one frame so the new list is laid out first. Reloads, tab switches and name updates
                    // keep the same newest call, so they never scroll.
                    // If the new call is with the person who is already on top, their row simply stays first
                    // (only its time changes), so there is nothing hidden above and no scroll.
                    val newestRecentCall = groupedRecentCalls.firstOrNull()
                    val newestRecentCallGroupKey = newestRecentCall?.groupKey.orEmpty()
                    val newestRecentCallTimestamp = newestRecentCall?.callHistory?.firstOrNull()?.timestamp ?: 0L
                    val currentTopEntryId = if (newestRecentCallGroupKey.isNotEmpty() && newestRecentCallTimestamp > 0L) {
                        "${newestRecentCallGroupKey}_$newestRecentCallTimestamp"
                    } else ""

                    LaunchedEffect(currentTopEntryId) {
                        if (currentTopEntryId.isNotBlank()) {
                            val previousTopId = lastSeenNewestRecentCallTopId
                            lastSeenNewestRecentCallTopId = currentTopEntryId
                            if (previousTopId.isNotBlank() && previousTopId != currentTopEntryId) {
                                pendingRecentScrollToTop = true
                            }
                        }
                    }

                    LaunchedEffect(currentTopEntryId, pendingRecentScrollToTop) {
                        if (!pendingRecentScrollToTop || currentTopEntryId.isBlank()) return@LaunchedEffect
                        lifecycle.withResumed { }
                        val expectedGroupKey = newestRecentCallGroupKey
                        repeat(RECENTS_SCROLL_TO_NEW_CALL_MAX_FRAMES) {
                            recentListState.scrollToItem(0)
                            withFrameNanos { }
                            val topVisibleKey = recentListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString().orEmpty()
                            val isIndexZero = recentListState.firstVisibleItemIndex == 0
                            val isOffsetZero = recentListState.firstVisibleItemScrollOffset == 0
                            val isTopRowMatching = topVisibleKey == expectedGroupKey || topVisibleKey == "recent:$expectedGroupKey"
                            if (isIndexZero && isOffsetZero && (expectedGroupKey.isEmpty() || isTopRowMatching)) {
                                pendingRecentScrollToTop = false
                                return@LaunchedEffect
                            }
                        }
                        pendingRecentScrollToTop = false
                    }
                    if (isKeypadTab || recentDialpadVisible) {
                        RecentDialpadModeContent(
                            recentDialpadDigits = recentDialpadDigits,
                            digitQuery = digitQuery,
                            matchedContacts = digitMatchedContacts,
                            matchedRecentCalls = digitMatchedRecentCalls,
                            lastRecentNumber = groupedRecentCalls.firstOrNull()?.phoneNumber.orEmpty(),
                            contacts = contacts,
                            listState = recentListState,
                            onRecentDialpadDigitsChange = onRecentDialpadDigitsChange,
                            onRecentCallClick = onRecentCallClick,
                            onDeleteRecentCall = requestRecentDelete,
                            onContactEntryClick = onContactEntryClick,
                            onOpenAddContactWithNumber = onOpenAddContactWithNumber,
                            onDeleteContactNumber = { pendingContactDelete = it },
                            onCollapseDialpad = if (isKeypadTab) {
                                null
                            } else {
                                {
                                    hideRecentDialpad()
                                    onRecentSearchQueryChange("")
                                    showRecentSearch = false
                                }
                            }
                        )
                    } else {
                        RecentNormalContent(
                            showRecentSearch = showRecentSearch,
                            recentSearchQuery = recentSearchQuery,
                            filteredRecentCalls = filteredRecentCalls,
                            groupedRecentCalls = groupedRecentCalls,
                            digitMatchedContacts = digitMatchedContacts,
                            digitMatchedRecentCalls = digitMatchedRecentCalls,
                            isLoading = isLoading,
                            isPowerSaveMode = isPowerSaveMode,
                            recentListState = recentListState,
                            onRefresh = onRefresh,
                            onRecentSearchQueryChange = onRecentSearchQueryChange,
                            onRecentDialpadVisibilityChange = { visible ->
                                if (visible) onTabSelected(TAB_KEYPAD) else onRecentDialpadVisibilityChange(false)
                            },
                            onRecentCallClick = onRecentCallClick,
                            onDeleteRecentCall = requestRecentDelete,
                            onContactEntryClick = onContactEntryClick,
                            onToggleRecentSearch = { nextVisible ->
                                showRecentSearch = nextVisible
                                if (!nextVisible) {
                                    onRecentSearchQueryChange("")
                                }
                            }
                        )
                    }
                }
                TAB_PROFILE -> LocalProfileScreen(context = ctx)
                TAB_VOICEMAIL -> VoicemailScreen(refreshTrigger = refreshTrigger)
                TAB_SETTINGS -> SettingsScreen(
                    context = ctx,
                    activity = this@MainActivity,
                    onRequestDefaultDialer = { requestDefaultDialerRole() },
                    onRequestCallScreeningRole = { requestCallScreeningRoleIfNeeded() },
                    onRequestDefaultSms = { requestDefaultSmsRole() }
                )
            }
            }

            OneUiBottomTabs(
                tabs = visibleTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }

        pendingContactDelete?.let { contact ->
            AlertDialog(
                onDismissRequest = { pendingContactDelete = null },
                title = { Text("Delete contact?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Do you really want to delete this contact?")
                        Text(contact.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(
                            formatPhoneNumberForDisplay(contact.phoneNumber),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteContact(contact)
                            pendingContactDelete = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingContactDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        pendingRecentDelete?.let { request ->
            val isBlockedNumber = isPhoneNumberBlocked(request.phoneNumber)
            AlertDialog(
                onDismissRequest = { pendingRecentDelete = null },
                title = { Text("Delete recent call?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Do you really want to delete this recent call?")
                        Text(request.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(
                            formatPhoneNumberForDisplay(request.phoneNumber),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (isBlockedNumber) {
                                "This number is currently blocked. You can unblock it here."
                            } else {
                                "You can also block future calls from this number."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteRecentCall(request)
                            pendingRecentDelete = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                onToggleBlockPhoneNumber(request.phoneNumber)
                                pendingRecentDelete = null
                            }
                        ) {
                            Text(if (isBlockedNumber) "Unblock number" else "Block number")
                        }
                        TextButton(onClick = { pendingRecentDelete = null }) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun RecentNormalContent(
        showRecentSearch: Boolean,
        recentSearchQuery: String,
        filteredRecentCalls: List<GroupedRecentCall>,
        groupedRecentCalls: List<GroupedRecentCall>,
        digitMatchedContacts: List<ContactDisplayEntry>,
        digitMatchedRecentCalls: List<GroupedRecentCall>,
        isLoading: Boolean,
        isPowerSaveMode: Boolean,
        recentListState: androidx.compose.foundation.lazy.LazyListState,
        onRefresh: () -> Unit,
        onRecentSearchQueryChange: (String) -> Unit,
        onRecentDialpadVisibilityChange: (Boolean) -> Unit,
        onRecentCallClick: (String) -> Unit,
        onDeleteRecentCall: (String) -> Unit,
        onContactEntryClick: (ContactDisplayEntry) -> Unit,
        onToggleRecentSearch: (Boolean) -> Unit
    ) {
        val palette = oneUiColors()
        var selectedRecentFilter by remember { mutableStateOf(RecentFilter.ALL) }
        var showClearAllRecentDialog by remember { mutableStateOf(false) }
        var showRecentMenu by remember { mutableStateOf(false) }

        val displayRecentCalls = remember(filteredRecentCalls, selectedRecentFilter) {
            filteredRecentCalls.filter { groupedCall ->
                val lastCall = groupedCall.callHistory.firstOrNull() ?: return@filter false
                when (selectedRecentFilter) {
                    RecentFilter.ALL -> true
                    RecentFilter.MISSED -> !lastCall.isBlocked && lastCall.callType == CallType.MISSED
                    RecentFilter.INCOMING -> !lastCall.isBlocked && lastCall.callType == CallType.INCOMING
                    RecentFilter.OUTGOING -> !lastCall.isBlocked && lastCall.callType == CallType.OUTGOING
                    RecentFilter.BLOCKED -> lastCall.isBlocked
                }
            }
        }
        val emptyFilterTitle = when (selectedRecentFilter) {
            RecentFilter.ALL -> "No recent calls yet"
            RecentFilter.MISSED -> "No missed calls"
            RecentFilter.INCOMING -> "No incoming calls"
            RecentFilter.OUTGOING -> "No outgoing calls"
            RecentFilter.BLOCKED -> "No blocked calls"
        }
        val searchVisible = showRecentSearch || recentSearchQuery.isNotEmpty()

        OneUiCollapsingPage(
            title = "Recents",
            subtitle = "${displayRecentCalls.size} call records",
            actions = {
                IconButton(
                    onClick = {
                        val nextVisible = !showRecentSearch
                        onToggleRecentSearch(nextVisible)
                        if (!nextVisible) {
                            onRecentSearchQueryChange("")
                        }
                    }
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search Recents", tint = palette.textPrimary)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Recents", tint = palette.textPrimary)
                }
                Box {
                    IconButton(onClick = { showRecentMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = palette.textPrimary)
                    }
                    DropdownMenu(
                        expanded = showRecentMenu,
                        onDismissRequest = { showRecentMenu = false }
                    ) {
                        listOf(
                            RecentFilter.ALL,
                            RecentFilter.MISSED,
                            RecentFilter.INCOMING,
                            RecentFilter.OUTGOING,
                            RecentFilter.BLOCKED
                        ).forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(filter.label) },
                                onClick = {
                                    selectedRecentFilter = filter
                                    showRecentMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Clear all") },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            },
                            onClick = {
                                showRecentMenu = false
                                showClearAllRecentDialog = true
                            }
                        )
                    }
                }
            }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (searchVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            color = palette.chip
                        ) {
                            OutlinedTextField(
                                value = recentSearchQuery,
                                onValueChange = onRecentSearchQueryChange,
                                placeholder = {
                                    Text(
                                        text = "Search recent calls",
                                        color = palette.textTertiary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = palette.textSecondary
                                    )
                                },
                                trailingIcon = {
                                    if (recentSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { onRecentSearchQueryChange("") }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Clear Search",
                                                tint = palette.textSecondary
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    containerColor = Color.Transparent,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedTextColor = palette.textPrimary,
                                    unfocusedTextColor = palette.textPrimary,
                                    cursorColor = palette.textPrimary
                                )
                            )
                        }
                    }
                }

                if (displayRecentCalls.isEmpty() && !isLoading) {
                    RecentEmptyState(
                        title = emptyFilterTitle,
                        subtitle = "Choose another filter to see more call history.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    RecentUnifiedList(
                        showDigitResults = false,
                        dialpadVisible = false,
                        groupedRecentCalls = displayRecentCalls,
                        matchedContacts = digitMatchedContacts,
                        matchedRecentCalls = digitMatchedRecentCalls,
                        isLoading = isLoading,
                        onContactEntryClick = onContactEntryClick,
                        onRecentCallClick = onRecentCallClick,
                        onDeleteRecentCall = onDeleteRecentCall,
                        listState = recentListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        if (showClearAllRecentDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllRecentDialog = false },
                title = { Text("Clear recent calls?") },
                text = { Text("Do you really want to delete all recent call history from this app view and the system call log?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clearAllRecentCalls()
                            showClearAllRecentDialog = false
                        }
                    ) {
                        Text("Clear all", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllRecentDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    @Composable
    private fun RecentDialpadModeContent(
        recentDialpadDigits: String,
        digitQuery: String,
        matchedContacts: List<ContactDisplayEntry>,
        matchedRecentCalls: List<GroupedRecentCall>,
        lastRecentNumber: String,
        contacts: List<Contact>,
        listState: androidx.compose.foundation.lazy.LazyListState,
        onRecentDialpadDigitsChange: (String) -> Unit,
        onRecentCallClick: (String) -> Unit,
        onDeleteRecentCall: (String) -> Unit,
        onContactEntryClick: (ContactDisplayEntry) -> Unit,
        onOpenAddContactWithNumber: ((String) -> Unit)? = null,
        onCollapseDialpad: (() -> Unit)? = null,
        onDeleteContactNumber: (Contact) -> Unit = {}
    ) {
        val palette = oneUiColors()
        // Like the Google dialer: scrolling the results hides the keypad; the round button brings it back.
        var keypadVisible by remember { mutableStateOf(true) }
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress && digitQuery.isNotBlank()) keypadVisible = false
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                if (digitQuery.isBlank()) {
                    RecentEmptyState(
                        title = "Keypad",
                        subtitle = "Type on the keypad to search contacts and recent calls.",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    RecentUnifiedList(
                        showDigitResults = true,
                        dialpadVisible = false,
                        groupedRecentCalls = emptyList(),
                        matchedContacts = matchedContacts,
                        matchedRecentCalls = matchedRecentCalls,
                        isLoading = false,
                        onContactEntryClick = onContactEntryClick,
                        onRecentCallClick = onRecentCallClick,
                        onDeleteRecentCall = onDeleteRecentCall,
                        onDeleteContactNumber = onDeleteContactNumber,
                        listState = listState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (!keypadVisible) {
                    FloatingActionButton(
                        onClick = { keypadVisible = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp),
                        containerColor = OneUi.Blue,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Dialpad, contentDescription = "Show keypad")
                    }
                }
            }

            if (keypadVisible) {
                KeypadScreen(
                    contacts = contacts,
                    phoneNumber = recentDialpadDigits,
                    lastRecentNumber = lastRecentNumber,
                    onPhoneNumberChange = onRecentDialpadDigitsChange,
                    onCall = { phoneNumber ->
                        if (phoneNumber.isNotBlank()) {
                            onRecentCallClick(phoneNumber)
                        }
                    },
                    onAddContactWithNumber = onOpenAddContactWithNumber
                )
            }
        }
    }

    @Composable
    private fun RecentContextSearchField(
        value: String,
        onValueChange: (String) -> Unit,
        readOnly: Boolean,
        placeholderText: String,
        primaryTextColor: Color,
        secondaryTextColor: Color,
        surfaceColor: Color,
        showSearchIcon: Boolean,
        onCollapse: (() -> Unit)? = null
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = surfaceColor
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                placeholder = {
                    Text(
                        text = placeholderText,
                        color = secondaryTextColor
                    )
                },
                leadingIcon = if (showSearchIcon) {
                    {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = secondaryTextColor
                        )
                    }
                } else {
                    null
                },
                trailingIcon = onCollapse?.let { collapse ->
                    {
                        IconButton(onClick = collapse) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Collapse search",
                                tint = primaryTextColor
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    containerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = primaryTextColor,
                    unfocusedTextColor = primaryTextColor,
                    cursorColor = primaryTextColor
                )
            )
        }
    }

    @Composable
    private fun SwipeToCallContainer(
        onSwipeCall: () -> Unit,
        onSwipeDelete: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        shape: Shape = RoundedCornerShape(28.dp),
        backgroundPadding: PaddingValues = PaddingValues(0.dp),
        content: @Composable () -> Unit
    ) {
        if (!enabled) {
            Box(modifier = modifier) { content() }
            return
        }

        val density = LocalDensity.current
        val swipeThresholdPx = remember(density) { with(density) { 108.dp.toPx() } }
        val pointerExitTolerancePx = remember(density) { with(density) { 48.dp.toPx() } }
        val scope = rememberCoroutineScope()
        var dragOffsetX by remember { mutableStateOf(0f) }
        var containerWidthPx by remember { mutableStateOf(0f) }
        var containerHeightPx by remember { mutableStateOf(0f) }
        var swipeCancelled by remember { mutableStateOf(false) }
        val animatedOffsetX by animateFloatAsState(
            targetValue = dragOffsetX,
            label = "swipeToCallOffset"
        )

        Box(modifier = modifier) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        containerWidthPx = size.width.toFloat()
                        containerHeightPx = size.height.toFloat()
                    }
                    .graphicsLayer { translationX = animatedOffsetX }
                    .pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                swipeCancelled = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                // Accidental-call guard: cancel only when the finger slides clearly up/down off
                                // the row. Going past the left/right edge is a normal long swipe, so it must not
                                // cancel (that silently dropped real swipes that had passed the call distance).
                                val pointerOutOfBounds =
                                    containerHeightPx > 0f &&
                                        (
                                            change.position.y < -pointerExitTolerancePx ||
                                                change.position.y > containerHeightPx + pointerExitTolerancePx
                                            )
                                if (pointerOutOfBounds) {
                                    swipeCancelled = true
                                    dragOffsetX = 0f
                                    change.consume()
                                    return@detectHorizontalDragGestures
                                }

                                change.consume()
                                val minOffset = if (onSwipeDelete != null) -swipeThresholdPx else 0f
                                val proposedOffset = dragOffsetX + dragAmount
                                if (onSwipeDelete == null && proposedOffset <= 0f) {
                                    dragOffsetX = 0f
                                    return@detectHorizontalDragGestures
                                }
                                dragOffsetX = proposedOffset.coerceIn(minOffset, swipeThresholdPx)
                            },
                            onDragCancel = {
                                swipeCancelled = false
                                dragOffsetX = 0f
                            },
                            onDragEnd = {
                                if (swipeCancelled) {
                                    Log.w(P2P_FORENSIC_TAG, "swipe_end result=cancelled reason=finger_left_row_vertically")
                                    swipeCancelled = false
                                    dragOffsetX = 0f
                                    return@detectHorizontalDragGestures
                                }
                                val shouldCall = dragOffsetX >= swipeThresholdPx
                                val shouldDelete = onSwipeDelete != null && dragOffsetX <= -swipeThresholdPx
                                Log.w(
                                    P2P_FORENSIC_TAG,
                                    "swipe_end offsetPx=${dragOffsetX.toInt()} thresholdPx=${swipeThresholdPx.toInt()} call=$shouldCall delete=$shouldDelete"
                                )
                                if (shouldCall) {
                                    onSwipeCall()
                                } else if (shouldDelete) {
                                    onSwipeDelete?.invoke()
                                }
                                scope.launch {
                                    dragOffsetX = 0f
                                }
                            }
                        )
                    }
            ) {
                content()
            }
        }
    }

    @Composable
    private fun ContactHeaderActionButton(
        backgroundColor: Color,
        onClick: () -> Unit,
        icon: @Composable () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = backgroundColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
    }

    @Composable
    private fun ContactActionButton(
        backgroundColor: Color,
        onClick: () -> Unit,
        icon: @Composable () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = backgroundColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
    }

    @Composable
    private fun ActionIconButton(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor: Color = MaterialTheme.colorScheme.onSurface
    ) {
        Surface(
            modifier = modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(20.dp),
            color = containerColor
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    @Composable
    private fun ActionPill(
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor: Color = MaterialTheme.colorScheme.onSurface
    ) {
        Surface(
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(999.dp),
            color = containerColor
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    @Composable
    private fun CompactActionPill(
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor: Color = MaterialTheme.colorScheme.onSurface
    ) {
        Surface(
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(999.dp),
            color = containerColor
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    @Composable
    private fun CompactActionChip(
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor: Color = MaterialTheme.colorScheme.onSurface
    ) {
        Surface(
            modifier = modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(999.dp),
            color = containerColor
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    @Composable
    fun ContactItem(
        contact: Contact,
        isFavorite: Boolean,
        onClick: () -> Unit,
        onDelete: () -> Unit,
        onToggleFavorite: () -> Unit,
        isFirst: Boolean = true,
        isLast: Boolean = true
    ) {
        val formattedNumber = remember(contact.phoneNumber) { formatPhoneNumberForDisplay(contact.phoneNumber) }
        val avatarColor = remember(contact.name) { getAvatarColor(contact.name) }
        val oneUi = oneUiColors()
        val contactCardColor = oneUi.card
        val contactNestedCardColor = oneUi.chip
        val contactPrimaryTextColor = oneUi.textPrimary
        val contactSecondaryTextColor = oneUi.textSecondary

        val itemShape = RoundedCornerShape(
            topStart = if (isFirst) OneUi.CardRadius else 0.dp,
            topEnd = if (isFirst) OneUi.CardRadius else 0.dp,
            bottomStart = if (isLast) OneUi.CardRadius else 0.dp,
            bottomEnd = if (isLast) OneUi.CardRadius else 0.dp
        )

        val context = LocalContext.current
        var profileBitmap by remember(contact.photoUri) { mutableStateOf<Bitmap?>(null) }
        var showContactMenu by remember(contact.phoneNumber, isFavorite) { mutableStateOf(false) }

        LaunchedEffect(contact.photoUri) {
            if (!contact.photoUri.isNullOrEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        val uri = Uri.parse(contact.photoUri)
                        val inputStream = context.contentResolver.openInputStream(uri)
                        profileBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                    } catch (e: Exception) {
                        profileBitmap = null
                    }
                }
            }
        }

        SwipeToCallContainer(
            onSwipeCall = onClick,
            onSwipeDelete = onDelete,
            modifier = Modifier.fillMaxWidth(),
            shape = itemShape,
            backgroundPadding = PaddingValues(start = 0.dp, end = 2.dp, top = 1.dp, bottom = 1.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 0.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
                    shape = itemShape,
                    color = contactCardColor
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (profileBitmap != null) {
                            Image(
                                bitmap = profileBitmap!!.asImageBitmap(),
                                contentDescription = "Profile Photo",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initialLetter = remember(contact.name) {
                                contact.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(avatarColor),
                                contentAlignment = Alignment.Center
                            ) {
                                if (initialLetter != null) {
                                    Text(
                                        text = initialLetter.toString(),
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = "Contact avatar",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = contactPrimaryTextColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formattedNumber,
                                style = MaterialTheme.typography.bodyMedium,
                                color = contactSecondaryTextColor
                            )
                        }

                        Box {
                            ContactActionButton(
                                backgroundColor = contactNestedCardColor,
                                onClick = { showContactMenu = true }
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Contact actions for ${contact.name}",
                                    tint = contactPrimaryTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showContactMenu,
                                onDismissRequest = { showContactMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Message") },
                                    onClick = {
                                        showContactMenu = false
                                        openSmsComposer(contact.phoneNumber)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    onClick = {
                                        showContactMenu = false
                                        sharePhoneNumber(contact.phoneNumber, contact.name)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isFavorite) "Unfavorite" else "Favorite") },
                                    onClick = {
                                        showContactMenu = false
                                        onToggleFavorite()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        showContactMenu = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
                if (!isLast) {
                    androidx.compose.material3.Divider(
                        modifier = Modifier.padding(start = 82.dp, end = 26.dp),
                        thickness = 1.dp,
                        color = oneUi.divider
                    )
                }
            }
        }
    }

    @Composable
    fun MergedContactItem(
        entry: ContactDisplayEntry,
        isFavorite: Boolean,
        onDeleteNumber: (Contact) -> Unit,
        onToggleFavorite: () -> Unit,
        isFirst: Boolean = true,
        isLast: Boolean = true,
        showNumberActions: Boolean = true // false = no ⋮ menu (Keypad search results)
    ) {
        val avatarColor = remember(entry.name) { getAvatarColor(entry.name) }
        val numberRows = remember(entry.sourceContacts, entry.phoneNumbers) {
            if (entry.sourceContacts.isNotEmpty()) {
                entry.sourceContacts
                    .distinctBy { contact ->
                        normalizePhoneNumber(contact.phoneNumber).ifBlank { contact.phoneNumber }
                    }
                    .map { contact -> contact.phoneNumber to contact }
            } else {
                entry.phoneNumbers
                    .distinctBy { phoneNumber ->
                        normalizePhoneNumber(phoneNumber).ifBlank { phoneNumber }
                    }
                    .map { phoneNumber -> phoneNumber to null }
            }
        }
        val oneUi = oneUiColors()
        val contactCardColor = oneUi.card
        val contactNestedCardColor = oneUi.chip
        val contactPrimaryTextColor = oneUi.textPrimary
        val contactSecondaryTextColor = oneUi.textSecondary

        val itemShape = RoundedCornerShape(
            topStart = if (isFirst) OneUi.CardRadius else 0.dp,
            topEnd = if (isFirst) OneUi.CardRadius else 0.dp,
            bottomStart = if (isLast) OneUi.CardRadius else 0.dp,
            bottomEnd = if (isLast) OneUi.CardRadius else 0.dp
        )

        val context = LocalContext.current
        var profileBitmap by remember(entry.photoUri) { mutableStateOf<Bitmap?>(null) }
        var activeNumberMenuKey by remember(entry.displayKey, isFavorite) { mutableStateOf<String?>(null) }

        LaunchedEffect(entry.photoUri) {
            if (!entry.photoUri.isNullOrEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        val uri = Uri.parse(entry.photoUri)
                        val inputStream = context.contentResolver.openInputStream(uri)
                        profileBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                    } catch (_: Exception) {
                        profileBitmap = null
                    }
                }
            } else {
                profileBitmap = null
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
                shape = itemShape,
                color = contactCardColor
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (profileBitmap != null) {
                        Image(
                            bitmap = profileBitmap!!.asImageBitmap(),
                            contentDescription = "Profile Photo",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        val initialLetter = remember(entry.name) {
                            entry.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(avatarColor),
                            contentAlignment = Alignment.Center
                        ) {
                            if (initialLetter != null) {
                                Text(
                                    text = initialLetter.toString(),
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Contact avatar",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = contactPrimaryTextColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        numberRows.forEachIndexed { index, (phoneNumber, sourceContact) ->
                            val menuKey = normalizePhoneNumber(phoneNumber).ifBlank { phoneNumber }
                            val formattedPhoneNumber = formatPhoneNumberForDisplay(phoneNumber)
                            if (index > 0) {
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            SwipeToCallContainer(
                                onSwipeCall = { startCall(phoneNumber) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formattedPhoneNumber,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = contactSecondaryTextColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (showNumberActions) Spacer(modifier = Modifier.width(8.dp))
                                    // No ⋮ button: keep its 42dp height so rows keep the same spacing as Contacts.
                                    else Spacer(modifier = Modifier.height(42.dp))
                                    if (showNumberActions) Box {
                                        ContactActionButton(
                                            backgroundColor = contactNestedCardColor,
                                            onClick = { activeNumberMenuKey = menuKey }
                                        ) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "Contact actions for $formattedPhoneNumber",
                                                tint = contactPrimaryTextColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = activeNumberMenuKey == menuKey,
                                            onDismissRequest = { activeNumberMenuKey = null }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Message") },
                                                onClick = {
                                                    activeNumberMenuKey = null
                                                    openSmsComposer(phoneNumber)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Share") },
                                                onClick = {
                                                    activeNumberMenuKey = null
                                                    sharePhoneNumber(phoneNumber, entry.name)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Copy") },
                                                onClick = {
                                                    activeNumberMenuKey = null
                                                    copyNumberToClipboard(phoneNumber)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (isFavorite) "Unfavorite" else "Favorite") },
                                                onClick = {
                                                    activeNumberMenuKey = null
                                                    onToggleFavorite()
                                                }
                                            )
                                            if (sourceContact != null) {
                                                DropdownMenuItem(
                                                    text = { Text("Delete number") },
                                                    onClick = {
                                                        activeNumberMenuKey = null
                                                        onDeleteNumber(sourceContact)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!isLast) {
                androidx.compose.material3.Divider(
                    modifier = Modifier.padding(start = 82.dp, end = 26.dp),
                    thickness = 1.dp,
                    color = oneUi.divider
                )
            }
        }
    }

    @Composable
    private fun RecentInitialBadge(name: String, modifier: Modifier = Modifier, phoneNumber: String? = null) {
        val badgeColor = remember(name, phoneNumber, uiState.contacts) { getAvatarColor(name, phoneNumber) }
        val initialLetter = remember(name) {
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        }
        Box(
            modifier = modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(badgeColor),
            contentAlignment = Alignment.Center
        ) {
            if (initialLetter != null) {
                Text(
                    text = initialLetter.toString(),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Person",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }

    @Composable
    private fun RecentPersonMenuBadge(
        name: String,
        phoneNumber: String,
        isBlockedNumber: Boolean,
        callHistory: List<CallHistoryItem> = emptyList(),
        onCall: () -> Unit,
        onMessage: () -> Unit,
        onCopy: () -> Unit,
        onShare: () -> Unit,
        onToggleBlock: () -> Unit,
        onDelete: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val safeName = remember(name) { sanitizeDisplayNameForUi(name) }
        val isDarkRecentTheme = isSystemInDarkTheme()
        val fullScreenBackground = if (isDarkRecentTheme) Color.Black else Color(0xFFF5F7FB)
        val panelBackground = if (isDarkRecentTheme) Color(0xFF111318) else Color.White
        val panelSecondaryText = if (isDarkRecentTheme) Color.White.copy(alpha = 0.72f) else Color(0xFF667085)
        val panelChipColor = if (isDarkRecentTheme) Color.White.copy(alpha = 0.08f) else Color(0xFFF2F4F7)
        var showActionPanel by remember { mutableStateOf(false) }

        Box(modifier = modifier) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showActionPanel = true }
            ) {
                RecentInitialBadge(name = safeName, phoneNumber = phoneNumber)
            }

            if (showActionPanel) {
                var showSecondaryMenu by remember { mutableStateOf(false) }

                val onVideoCallAction = {
                    showActionPanel = false
                    startCall(phoneNumber, isVideoCall = true)
                }

                Dialog(
                    onDismissRequest = { showActionPanel = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = fullScreenBackground
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 24.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Contact details",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = panelSecondaryText,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { showActionPanel = false }) {
                                    Text("Close", fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Compact Contact Header Card
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(28.dp),
                                color = panelBackground
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    RecentInitialBadge(name = safeName, modifier = Modifier.size(80.dp), phoneNumber = phoneNumber)
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = safeName,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Phone Number Card with Press & Hold Contextual Menu
                                    Box {
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .combinedClickable(
                                                    onClick = {
                                                        copyNumberToClipboard(phoneNumber)
                                                    },
                                                    onLongClick = {
                                                        showSecondaryMenu = true
                                                    }
                                                ),
                                            shape = RoundedCornerShape(12.dp),
                                            color = panelChipColor
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = formatPhoneNumberForDisplay(phoneNumber),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = panelSecondaryText
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "More options",
                                                    tint = panelSecondaryText,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }

                                        // Press-and-Hold Secondary Actions Contextual Popup
                                        DropdownMenu(
                                            expanded = showSecondaryMenu,
                                            onDismissRequest = { showSecondaryMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Copy number") },
                                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = "Copy number") },
                                                onClick = {
                                                    showSecondaryMenu = false
                                                    onCopy()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Share contact") },
                                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = "Share contact") },
                                                onClick = {
                                                    showSecondaryMenu = false
                                                    onShare()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (isBlockedNumber) "Unblock number" else "Block number") },
                                                leadingIcon = { Icon(Icons.Default.Block, contentDescription = "Block or unblock") },
                                                onClick = {
                                                    showSecondaryMenu = false
                                                    onToggleBlock()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete recent") },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "Delete recent", tint = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    showSecondaryMenu = false
                                                    showActionPanel = false
                                                    onDelete()
                                                }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    // Primary Actions Row — Icon-First Design
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    showActionPanel = false
                                                    onCall()
                                                },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Call,
                                                    contentDescription = "Call",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        Surface(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    showActionPanel = false
                                                    onMessage()
                                                },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Send,
                                                    contentDescription = "Message",
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }

                                        Surface(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    onVideoCallAction()
                                                },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Videocam,
                                                    contentDescription = "Video call",
                                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (callHistory.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "Call History (${callHistory.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    callHistory.forEach { call ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp),
                                            color = panelBackground
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    RecentDirectionIcon(
                                                        callType = call.callType,
                                                        isVideo = call.isVideo,
                                                        isTollFree = call.isTollFree
                                                    )
                                                    Column {
                                                        Text(
                                                            text = if (call.isBlocked) "Blocked" else call.typeString,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        if (call.simLabel.isNotBlank()) {
                                                            Text(
                                                                text = call.simLabel,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = panelSecondaryText
                                                            )
                                                        }
                                                        Text(
                                                            text = call.formattedDuration,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = panelSecondaryText
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = call.formattedTime,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = panelSecondaryText
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RecentSectionLabel(text: String) {
        val isDarkRecentTheme = isSystemInDarkTheme()
        val sectionLabelColor = if (isDarkRecentTheme) Color.White.copy(alpha = 0.74f) else Color(0xFF667085)
        Text(
            text = text,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp),
            color = sectionLabelColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }

    @Composable
    private fun RecentEmptyState(
        title: String,
        subtitle: String,
        modifier: Modifier = Modifier
    ) {
        val isDarkRecentTheme = isSystemInDarkTheme()
        val emptyIconBackground = if (isDarkRecentTheme) Color.White.copy(alpha = 0.08f) else Color(0xFFE7ECF4)
        val emptyPrimaryText = if (isDarkRecentTheme) Color(0xFFD0D0D0) else Color(0xFF101828)
        val emptySecondaryText = if (isDarkRecentTheme) Color.White.copy(alpha = 0.62f) else Color(0xFF667085)
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(emptyIconBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = null,
                        tint = emptyPrimaryText,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = title,
                    color = emptyPrimaryText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    color = emptySecondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun RecentDirectionIcon(
        callType: CallType,
        isVideo: Boolean = false,
        isTollFree: Boolean = false
    ) {
        val isDarkRecentTheme = isSystemInDarkTheme()
        val (icon, tint) = when (callType) {
            CallType.OUTGOING -> Icons.Default.CallMade to Color(0xFF18C37E)
            CallType.INCOMING -> Icons.Default.CallReceived to if (isDarkRecentTheme) Color.White.copy(alpha = 0.72f) else Color(0xFF667085)
            CallType.MISSED -> Icons.Default.CallMissed to Color(0xFFFF5A52)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isTollFree) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.padding(end = 2.dp)
                ) {
                    Text(
                        text = "Toll-Free",
                        color = Color(0xFF60A5FA),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            if (isVideo) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Video Call",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(20.dp)
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    @Composable
    fun RecentUnifiedList(
        showDigitResults: Boolean,
        dialpadVisible: Boolean,
        groupedRecentCalls: List<GroupedRecentCall>,
        matchedContacts: List<ContactDisplayEntry>,
        matchedRecentCalls: List<GroupedRecentCall>,
        isLoading: Boolean,
        onContactEntryClick: (ContactDisplayEntry) -> Unit,
        onRecentCallClick: (String) -> Unit,
        onDeleteRecentCall: (String) -> Unit = {},
        onDeleteContactNumber: (Contact) -> Unit = {},
        listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
        modifier: Modifier = Modifier
    ) {
        val isDarkRecentTheme = isSystemInDarkTheme()
        val recentPrimaryTextColor = if (isDarkRecentTheme) Color(0xFFD0D0D0) else Color(0xFF101828)
        val recentSecondaryTextColor = if (isDarkRecentTheme) Color(0xFF8E8E8E) else Color(0xFF667085)
        val recentMutedTextColor = if (isDarkRecentTheme) Color.White.copy(alpha = 0.72f) else Color(0xFF667085)
        val recentCardBorderColor = if (isDarkRecentTheme) Color.White.copy(alpha = 0.08f) else Color(0xFFDDE3EC)
        val recentCardFillColor = if (isDarkRecentTheme) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.96f)
        val recentChipColor = if (isDarkRecentTheme) Color.White.copy(alpha = 0.07f) else Color(0xFFF2F4F7)
        val recentDetailFillColor = if (isDarkRecentTheme) Color.White.copy(alpha = 0.05f) else Color(0xFFF8FAFC)
        val groupedCallsByKey = remember(groupedRecentCalls) {
            groupedRecentCalls.associateBy { it.groupKey }
        }
        val expandedHistoryByGroup = remember { mutableStateMapOf<String, Boolean>() }
        val visibleLimitByGroup = remember { mutableStateMapOf<String, Int>() }
        val activeGroupKeys = remember(groupedRecentCalls) {
            groupedRecentCalls.mapTo(linkedSetOf<String>()) { it.groupKey }
        }

        LaunchedEffect(activeGroupKeys) {
            expandedHistoryByGroup.keys
                .toList()
                .filterNot(activeGroupKeys::contains)
                .forEach(expandedHistoryByGroup::remove)
            visibleLimitByGroup.keys
                .toList()
                .filterNot(activeGroupKeys::contains)
                .forEach(visibleLimitByGroup::remove)
        }

        if (!showDigitResults) {
            if (isLoading) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                return
            }
            if (groupedRecentCalls.isEmpty()) {
                RecentEmptyState(
                    title = "No recent calls yet",
                    subtitle = "Once you place or receive calls, your grouped history will appear here.",
                    modifier = modifier
                )
                return
            }
        } else if (matchedContacts.isEmpty() && matchedRecentCalls.isEmpty()) {
            RecentEmptyState(
                title = "No matches found",
                subtitle = "Try another name or number to search contacts and recent calls.",
                modifier = modifier
            )
            return
        }

        LazyColumn(
            modifier = modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 18.dp,
                bottom = 24.dp
            )
        ) {
            if (showDigitResults) {
                if (matchedContacts.isNotEmpty()) {
                    item("contacts_header") {
                        RecentSectionLabel("Contacts")
                    }

                    items(
                        items = matchedContacts,
                        key = { it.displayKey }
                    ) { entry ->
                        if (entry.phoneNumbers.size > 1) {
                            // Several numbers: reuse the Contacts tab card (each number swipes on its own).
                            val isFavorite = isContactEntryFavorite(entry)
                            Box(modifier = Modifier.padding(vertical = 6.dp)) {
                                MergedContactItem(
                                    entry = entry,
                                    isFavorite = isFavorite,
                                    onDeleteNumber = onDeleteContactNumber,
                                    onToggleFavorite = { toggleContactEntryFavorite(entry, isFavorite) },
                                    showNumberActions = false
                                )
                            }
                            return@items
                        }
                        val subtitle = formatPhoneNumberForDisplay(entry.phoneNumbers.firstOrNull().orEmpty())
                        SwipeToCallContainer(
                            onSwipeCall = { onContactEntryClick(entry) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            backgroundPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .border(1.dp, recentCardBorderColor, RoundedCornerShape(24.dp))
                                    .clip(RoundedCornerShape(24.dp)),
                                color = recentCardFillColor
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RecentInitialBadge(entry.name)
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = recentPrimaryTextColor
                                        )
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = recentSecondaryTextColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (matchedRecentCalls.isNotEmpty()) {
                    item("recent_header") {
                        RecentSectionLabel("Recent Calls")
                    }

                    items(
                        items = matchedRecentCalls,
                        key = { "recent:${it.groupKey}" }
                    ) { groupedCall ->
                        val displayName = sanitizeDisplayNameForUi(
                            if (groupedCall.name == "Unknown") groupedCall.phoneNumber else groupedCall.name
                        )
                        val lastCall = groupedCall.callHistory.firstOrNull()
                        val isBlockedNumber = isPhoneNumberBlocked(groupedCall.phoneNumber)
                        val subtitleText = when {
                            lastCall == null -> ""
                            else -> "${lastCall.formattedTime} - ${lastCall.formattedDuration}"
                        }
                        SwipeToCallContainer(
                            onSwipeCall = { onRecentCallClick(groupedCall.phoneNumber) },
                            onSwipeDelete = { onDeleteRecentCall(groupedCall.phoneNumber) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(26.dp),
                            backgroundPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .border(1.dp, recentCardBorderColor, RoundedCornerShape(26.dp))
                                    .clip(RoundedCornerShape(26.dp)),
                                shape = RoundedCornerShape(26.dp),
                                color = recentCardFillColor
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 18.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RecentPersonMenuBadge(
                                            name = displayName,
                                            phoneNumber = groupedCall.phoneNumber,
                                            isBlockedNumber = isBlockedNumber,
                                            onCall = { onRecentCallClick(groupedCall.phoneNumber) },
                                            onMessage = { openSmsComposer(groupedCall.phoneNumber) },
                                            onCopy = { copyNumberToClipboard(groupedCall.phoneNumber) },
                                            onShare = { sharePhoneNumber(groupedCall.phoneNumber, displayName) },
                                            onToggleBlock = { togglePhoneNumberBlockState(groupedCall.phoneNumber) },
                                            onDelete = { onDeleteRecentCall(groupedCall.phoneNumber) }
                                        )
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = recentPrimaryTextColor
                                            )
                                            RecentSubtitleLine(
                                                baseText = subtitleText,
                                                simLabel = lastCall?.simLabel.orEmpty(),
                                                textColor = recentSecondaryTextColor,
                                                simBadgeColor = recentCardBorderColor.copy(alpha = 0.18f),
                                                simTextColor = recentPrimaryTextColor
                                            )
                                        }
                                        if (lastCall != null) {
                                            Column(
                                                horizontalAlignment = Alignment.End,
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                RecentDirectionIcon(
                                                    callType = lastCall.callType,
                                                    isVideo = lastCall.isVideo,
                                                    isTollFree = lastCall.isTollFree || !isNumberVideoCallCapable(groupedCall.phoneNumber)
                                                )
                                                Text(
                                                    text = lastCall.formattedTime,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = recentSecondaryTextColor
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                items(
                    items = groupedRecentCalls,
                    key = { it.groupKey }
                ) { groupedCall ->
                    val groupKey = groupedCall.groupKey
                    val rowBucket = groupedCallsByKey[groupKey] ?: groupedCall
                    val rowPhoneNumber = rowBucket.phoneNumber
                    val rowCallHistory = rowBucket.callHistory
                    val bucketSize = rowCallHistory.size
                    val titleText = remember(rowBucket.name, rowPhoneNumber) {
                        sanitizeDisplayNameForUi(
                            if (rowBucket.name == "Unknown") rowPhoneNumber else rowBucket.name
                        )
                    }
                    val lastCall = rowCallHistory.first()
                    val isBlockedNumber = isPhoneNumberBlocked(rowPhoneNumber)
                    val titleWithCount = remember(titleText, bucketSize) {
                        if (bucketSize > 1) "$titleText ($bucketSize)" else titleText
                    }
                    // Saved names always show the number too; unsaved rows already show it as the title.
                    val showRowNumber = rowBucket.name != "Unknown" && titleText != formatPhoneNumberForDisplay(rowPhoneNumber)
                    val subtitleText = remember(lastCall.formattedTime, lastCall.formattedDuration, showRowNumber, rowPhoneNumber) {
                        val base = "${lastCall.formattedTime} - ${lastCall.formattedDuration}"
                        // Same person on two rows (two numbers): show which number this row is.
                        if (showRowNumber) "${formatPhoneNumberForDisplay(rowPhoneNumber)} · $base" else base
                    }
                    val cardShape = RoundedCornerShape(28.dp)
                    val canExpandHistory = bucketSize > 1
                    val showAllHistory = expandedHistoryByGroup[groupKey] == true
                    val showExpandedHistory = canExpandHistory && showAllHistory
                    val visibleLimit = visibleLimitByGroup[groupKey] ?: 15

                    LaunchedEffect(groupKey, bucketSize) {
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "recent_row_bucket_binding source=RecentUnifiedList groupKey=$groupKey bucketSize=$bucketSize phone=${maskP2PNumberForLog(rowPhoneNumber)}"
                        )
                    }

                    SwipeToCallContainer(
                        onSwipeCall = { onRecentCallClick(rowPhoneNumber) },
                        onSwipeDelete = { onDeleteRecentCall(rowPhoneNumber) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = cardShape,
                        backgroundPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(cardShape),
                            shape = cardShape,
                            color = Color.Transparent
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 18.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RecentPersonMenuBadge(
                                        name = titleText,
                                        phoneNumber = rowPhoneNumber,
                                        isBlockedNumber = isBlockedNumber,
                                        callHistory = rowCallHistory,
                                        onCall = { onRecentCallClick(rowPhoneNumber) },
                                        onMessage = { openSmsComposer(rowPhoneNumber) },
                                        onCopy = { copyNumberToClipboard(rowPhoneNumber) },
                                        onShare = { sharePhoneNumber(rowPhoneNumber, titleText) },
                                        onToggleBlock = { togglePhoneNumberBlockState(rowPhoneNumber) },
                                        onDelete = { onDeleteRecentCall(rowPhoneNumber) }
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = titleWithCount,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = recentPrimaryTextColor
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        RecentSubtitleLine(
                                            baseText = subtitleText,
                                            simLabel = lastCall.simLabel,
                                            textColor = recentSecondaryTextColor,
                                            simBadgeColor = recentCardBorderColor.copy(alpha = 0.18f),
                                            simTextColor = recentPrimaryTextColor
                                        )
                                        if (showAllHistory && !showRowNumber) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = formatPhoneNumberForDisplay(rowPhoneNumber),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = recentSecondaryTextColor
                                            )
                                        }
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RecentDirectionIcon(
                                            callType = lastCall.callType,
                                            isVideo = lastCall.isVideo,
                                            isTollFree = lastCall.isTollFree || !isNumberVideoCallCapable(rowPhoneNumber)
                                        )
                                        Text(
                                            text = lastCall.formattedTime,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Normal,
                                            color = recentSecondaryTextColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun RecentSearchResults(
        matchedContacts: List<ContactDisplayEntry>,
        matchedRecentCalls: List<GroupedRecentCall>,
        onContactEntryClick: (ContactDisplayEntry) -> Unit,
        onRecentCallClick: (String) -> Unit,
        onDeleteRecentCall: (String) -> Unit = {},
        modifier: Modifier = Modifier
    ) {
        if (matchedContacts.isEmpty() && matchedRecentCalls.isEmpty()) {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching contacts or recent calls")
            }
            return
        }

        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            if (matchedContacts.isNotEmpty()) {
                item("contacts_header") {
                    Text(
                        text = "Contacts",
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(
                    items = matchedContacts,
                    key = { it.displayKey }
                ) { entry ->
                    val subtitle = when {
                        entry.phoneNumbers.size > 1 -> "${entry.phoneNumbers.size} numbers"
                        else -> formatPhoneNumberForDisplay(entry.phoneNumbers.firstOrNull().orEmpty())
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onContactEntryClick(entry) },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(getAvatarColor(entry.name)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = entry.name.firstOrNull()?.uppercase() ?: "?",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Default.Call,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            if (matchedRecentCalls.isNotEmpty()) {
                item("recent_header") {
                    Text(
                        text = "Recent Calls",
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(
                    items = matchedRecentCalls,
                    key = { "recent:${it.phoneNumber}_${it.callHistory.firstOrNull()?.timestamp ?: 0L}" }
                ) { groupedCall ->
                    val displayName = if (groupedCall.name == "Unknown") groupedCall.phoneNumber else groupedCall.name
                    val lastCall = groupedCall.callHistory.firstOrNull()
                    val subtitleText = when {
                        lastCall == null -> ""
                        else -> "${lastCall.formattedTime} - ${lastCall.formattedDuration}"
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )  
                                RecentSubtitleLine(
                                    baseText = subtitleText,
                                    simLabel = lastCall?.simLabel.orEmpty(),
                                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    simBadgeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    simTextColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (lastCall != null) {
                                RecentDirectionIcon(
                                    callType = lastCall.callType,
                                    isVideo = lastCall.isVideo,
                                    isTollFree = lastCall.isTollFree || !isNumberVideoCallCapable(groupedCall.phoneNumber)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            IconButton(onClick = { onRecentCallClick(groupedCall.phoneNumber) }) {
                                Icon(
                                    Icons.Default.Call,
                                    contentDescription = "Call recent result",
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                            IconButton(onClick = { onDeleteRecentCall(groupedCall.phoneNumber) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete recent result",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun RecentCallsScreen(
        groupedRecentCalls: List<GroupedRecentCall>,
        isLoading: Boolean,
        onRecentCallClick: (String) -> Unit,
        onDelete: (String) -> Unit = {},
        modifier: Modifier = Modifier
    ) {
        if (isLoading) {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (groupedRecentCalls.isEmpty()) {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No recent calls") }
        } else {
            LazyColumn(modifier = modifier.fillMaxSize()) {
                items(items = groupedRecentCalls, key = { it.phoneNumber + "_" + it.callHistory.first().timestamp }) { groupedCall ->
                    val titleText = remember(groupedCall.name, groupedCall.phoneNumber) { if (groupedCall.name == "Unknown") groupedCall.phoneNumber else groupedCall.name }
                    val callsCountText = remember(groupedCall.callHistory.size) { "${groupedCall.callHistory.size} calls" }
                    val lastCall = groupedCall.callHistory.first()
                    val subtitleText = remember(lastCall.formattedTime, lastCall.formattedDuration) { "${lastCall.formattedTime} - ${lastCall.formattedDuration}" }

                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
                        var showAllHistory by remember { mutableStateOf(false) }
                        var visibleLimit by remember { mutableIntStateOf(15) }
                        val autoLoadedLimits = remember { mutableSetOf<Int>() }

                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = titleText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = callsCountText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(2.dp))
                                RecentSubtitleLine(
                                    baseText = subtitleText,
                                    simLabel = lastCall.simLabel,
                                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    simBadgeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    simTextColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onRecentCallClick(groupedCall.phoneNumber) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                                    Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(24.dp))
                                }
                                Button(onClick = { onDelete(groupedCall.phoneNumber) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)), modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(24.dp))
                                }
                            }
                        }

                        if (groupedCall.callHistory.size > 1) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    if (showAllHistory) {
                                        showAllHistory = false
                                        visibleLimit = 15
                                    } else {
                                        showAllHistory = true
                                    }
                                }) {
                                    Text(text = if (showAllHistory) "Show Less" else "Show All ${groupedCall.callHistory.size} Calls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        if (showAllHistory) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val callsToShow = groupedCall.callHistory.take(visibleLimit)
                            callsToShow.forEach { call ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CallTypeIcon(callType = call.callType)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = call.formattedTime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(text = appendRecentSimLabel(call.typeString, call.simLabel), style = MaterialTheme.typography.bodySmall, color = if (call.callType == CallType.MISSED) Color(0xFFFF5A52) else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text = call.formattedDuration, style = MaterialTheme.typography.bodySmall, color = if (call.callType == CallType.MISSED) Color(0xFFFF5A52) else if (call.duration > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (groupedCall.callHistory.size > visibleLimit) {
                                val remaining = groupedCall.callHistory.size - visibleLimit
                                val nextChunk = if (remaining > 15) 15 else remaining

                                Log.w("AUTO_LOAD_DEBUG", "IF_CONDITION_TRUE: totalCalls=${groupedCall.callHistory.size} visibleLimit=$visibleLimit remaining=$remaining")

                                // Auto-load on every recompose
                                SideEffect {
                                    Log.w("AUTO_LOAD_DEBUG", "SIDEEFFECT_RUNNING: visibleLimit=$visibleLimit inSet=${autoLoadedLimits.contains(visibleLimit)} setContents=$autoLoadedLimits")

                                    if (!autoLoadedLimits.contains(visibleLimit)) {
                                        Log.w("AUTO_LOAD_DEBUG", "AUTO_LOADING: Adding $visibleLimit to set, incrementing visibleLimit")
                                        autoLoadedLimits.add(visibleLimit)
                                        visibleLimit += 15
                                        Log.w("AUTO_LOAD_DEBUG", "AFTER_INCREMENT: visibleLimit=$visibleLimit setContents=$autoLoadedLimits")
                                    } else {
                                        Log.w("AUTO_LOAD_DEBUG", "SKIP_LOAD: $visibleLimit already in set")
                                    }
                                }

                                // Button visible (optional fallback tap)
                                TextButton(
                                    onClick = {
                                        Log.w("AUTO_LOAD_DEBUG", "BUTTON_CLICKED: incrementing visibleLimit from $visibleLimit")
                                        visibleLimit += 15
                                        Log.w("AUTO_LOAD_DEBUG", "AFTER_CLICK: visibleLimit=$visibleLimit")
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                ) {
                                    Text(text = "Load next $nextChunk older calls ($remaining left)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    @Suppress("RemoveExplicitTypeArguments")
    fun KeypadScreen(
        contacts: List<Contact> = emptyList(),
        phoneNumber: String,
        lastRecentNumber: String = "",
        onPhoneNumberChange: (String) -> Unit,
        onCall: (String) -> Unit,
        onAddContactWithNumber: ((String) -> Unit)? = null
    ) {
        val context = LocalContext.current
        val palette = oneUiColors()
        val speedDialPrefs = remember(context) {
            context.getSharedPreferences("speed_dial", MODE_PRIVATE)
        }
        val clipboardManager = remember(context) {
            context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        }
        var assigningKey by remember { mutableStateOf<String?>(null) }
        var managingSpeedDialKey by remember { mutableStateOf<String?>(null) }
        var promptZeroAction by remember { mutableStateOf(false) }
        var speedDialDialogActionsEnabled by remember { mutableStateOf(true) }

        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp
        val canRedial = phoneNumber.isEmpty() && lastRecentNumber.isNotBlank()
        val callTargetNumber = if (phoneNumber.isNotEmpty()) phoneNumber else lastRecentNumber

        val buttonSize = if (screenHeight < 700) 62.dp else 72.dp
        val rowSpacing = if (screenHeight < 700) 10.dp else 14.dp
        val speedDialKeys = remember { setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0") }
        val speedDialAssignableContacts = remember(contacts) {
            contacts
                .distinctBy { normalizePhoneNumber(it.phoneNumber) }
                .sortedWith(
                    compareBy<Contact>(
                        { it.name.lowercase(Locale.getDefault()) },
                        { normalizePhoneNumber(it.phoneNumber) }
                    )
                )
        }

        LaunchedEffect(assigningKey, managingSpeedDialKey, promptZeroAction) {
            val hasSpeedDialDialog = assigningKey != null || managingSpeedDialKey != null || promptZeroAction
            if (hasSpeedDialDialog) {
                speedDialDialogActionsEnabled = false
                delay(250)
            }
            speedDialDialogActionsEnabled = true
        }

        // Typed number plus cursor position: tapping the number moves the cursor, and keys insert or
        // delete at the cursor instead of only at the end.
        var numberField by remember { mutableStateOf(TextFieldValue(phoneNumber, TextRange(phoneNumber.length))) }
        if (numberField.text != phoneNumber) {
            // Changed from outside (paste chip, clear, redial): put the cursor at the end.
            numberField = TextFieldValue(phoneNumber, TextRange(phoneNumber.length))
        }
        val numberFocusRequester = remember { FocusRequester() }
        val setNumberField: (TextFieldValue) -> Unit = { value ->
            numberField = value
            if (value.text != phoneNumber) onPhoneNumberChange(value.text)
        }

        val appendKeypadChar: (String) -> Unit = { value ->
            if (phoneNumber.length < 20) {
                val start = numberField.selection.min.coerceIn(0, phoneNumber.length)
                val end = numberField.selection.max.coerceIn(0, phoneNumber.length)
                setNumberField(
                    TextFieldValue(
                        phoneNumber.replaceRange(start, end, value),
                        TextRange(start + value.length)
                    )
                )
            }
        }
        val removeLastKeypadChar = {
            val start = numberField.selection.min.coerceIn(0, phoneNumber.length)
            val end = numberField.selection.max.coerceIn(0, phoneNumber.length)
            when {
                start != end -> setNumberField(TextFieldValue(phoneNumber.removeRange(start, end), TextRange(start)))
                start > 0 -> setNumberField(TextFieldValue(phoneNumber.removeRange(start - 1, start), TextRange(start - 1)))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Typed Phone Number Display Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (screenHeight < 700) 64.dp else 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (phoneNumber.isNotEmpty()) {
                    // Shows the digits exactly as typed (cursor positions must match them), with a cursor
                    // but no soft keyboard: the dial pad below is the input.
                    // The cursor's drag handle (the blue teardrop) can't be resized in Compose, and it looked too
                    // big here, so it is made invisible; tapping the number still moves the cursor.
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalTextInputService provides null,
                        androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                            androidx.compose.foundation.text.selection.TextSelectionColors(
                                handleColor = Color.Transparent,
                                backgroundColor = OneUi.Blue.copy(alpha = 0.3f)
                            )
                    ) {
                        BasicTextField(
                            value = numberField,
                            onValueChange = { value ->
                                // Cursor moves and selections; typed/pasted text keeps only dialable characters.
                                val cleaned = value.text.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }.take(20)
                                if (cleaned == value.text) {
                                    setNumberField(value)
                                }
                            },
                            singleLine = true,
                            cursorBrush = SolidColor(OneUi.Blue),
                            textStyle = TextStyle(
                                fontSize = if (phoneNumber.length > 12) 24.sp else 30.sp,
                                fontWeight = FontWeight.Light,
                                color = palette.textPrimary,
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .focusRequester(numberFocusRequester)
                        )
                    }
                    LaunchedEffect(Unit) {
                        runCatching { numberFocusRequester.requestFocus() }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "+ Add to contacts",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = OneUi.Blue,
                        modifier = Modifier.clickable {
                            onAddContactWithNumber?.invoke(phoneNumber)
                        }
                    )
                } else {
                    val clipText = remember(phoneNumber) {
                        runCatching {
                            clipboardManager.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                .orEmpty()
                        }.getOrDefault("")
                    }
                    val sanitizedClip = remember(clipText) { sanitizeDialableClipboardText(clipText) }
                    if (sanitizedClip.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { onPhoneNumberChange(sanitizedClip) },
                            shape = RoundedCornerShape(20.dp),
                            color = palette.chip
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Paste ${formatPhoneNumberForDisplay(sanitizedClip)}",
                                    fontSize = 13.sp,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Keypad 3x4 Grid
            val keyRows = listOf(
                listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                listOf("*" to "", "0" to "+", "#" to "")
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                for (row in keyRows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (key in row) {
                            Box(
                                modifier = Modifier
                                    .size(buttonSize)
                                    .clip(CircleShape)
                                    .background(palette.chip)
                                    .combinedClickable(
                                        onClick = { appendKeypadChar(key.first) },
                                        onLongClick = {
                                            if (key.first == "0") {
                                                appendKeypadChar("+")
                                            } else if (key.first in speedDialKeys) {
                                                val savedNum = speedDialPrefs.getString(key.first, null)
                                                if (savedNum != null) {
                                                    managingSpeedDialKey = key.first
                                                } else {
                                                    assigningKey = key.first
                                                }
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = key.first,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textPrimary
                                    )
                                    if (key.second.isNotEmpty()) {
                                        Text(
                                            text = key.second,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = palette.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Control Row: [Empty Slot] [Green Circular Call Button] [Backspace Button]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(buttonSize))

                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .clip(CircleShape)
                            .background(OneUi.CallGreen)
                            .clickable {
                                if (callTargetNumber.isNotBlank()) {
                                    onCall(callTargetNumber)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = if (canRedial) "Redial" else "Call",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    if (phoneNumber.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(buttonSize)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { removeLastKeypadChar() },
                                    onLongClick = { onPhoneNumberChange("") }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Backspace,
                                contentDescription = "Delete",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Box(modifier = Modifier.size(buttonSize))
                    }
                }
            }

            managingSpeedDialKey?.let { key ->
                val assignedNumber = speedDialPrefs.getString(key, null).orEmpty()
                if (assignedNumber.isNotBlank()) {
                    val contactName = speedDialAssignableContacts
                        .firstOrNull {
                            normalizePhoneNumber(it.phoneNumber) == normalizePhoneNumber(assignedNumber)
                        }
                        ?.name
                        ?: "Unknown"
                    AlertDialog(
                        onDismissRequest = { managingSpeedDialKey = null },
                        title = { Text("Speed Dial '$key'") },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = contactName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = assignedNumber,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = speedDialDialogActionsEnabled,
                                onClick = {
                                    Log.w(
                                        P2P_FORENSIC_TAG,
                                        "speed_dial action=call key=$key number=${maskP2PNumberForLog(assignedNumber)}"
                                    )
                                    managingSpeedDialKey = null
                                    onCall(assignedNumber)
                                }
                            ) { Text("Call") }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    enabled = speedDialDialogActionsEnabled,
                                    onClick = {
                                        Log.w(P2P_FORENSIC_TAG, "speed_dial action=change key=$key")
                                        managingSpeedDialKey = null
                                        assigningKey = key
                                    }
                                ) { Text("Change") }
                                TextButton(
                                    enabled = speedDialDialogActionsEnabled,
                                    onClick = {
                                        speedDialPrefs.edit().remove(key).apply()
                                        Log.w(P2P_FORENSIC_TAG, "speed_dial action=remove key=$key")
                                        Toast.makeText(context, "Speed dial removed", Toast.LENGTH_SHORT).show()
                                        managingSpeedDialKey = null
                                    }
                                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    )
                } else {
                    managingSpeedDialKey = null
                }
            }

            if (assigningKey != null) {
                AlertDialog(
                    onDismissRequest = { assigningKey = null },
                    title = { Text("Set Speed Dial '$assigningKey'") },
                    text = {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(
                                items = speedDialAssignableContacts,
                                key = { contact -> "assign_${normalizePhoneNumber(contact.phoneNumber)}" }
                            ) { contact ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = speedDialDialogActionsEnabled) {
                                            assigningKey?.let { key ->
                                                speedDialPrefs.edit().putString(key, contact.phoneNumber).apply()
                                                Log.w(
                                                    P2P_FORENSIC_TAG,
                                                    "speed_dial action=assign key=$key number=${maskP2PNumberForLog(contact.phoneNumber)} contact=${maskP2PDisplayValueForLog(contact.name)}"
                                                )
                                                Toast.makeText(context, "Assigned to ${contact.name}", Toast.LENGTH_SHORT).show()
                                                assigningKey = null
                                            }
                                        }
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = contact.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = contact.phoneNumber,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = speedDialDialogActionsEnabled,
                            onClick = { assigningKey = null }
                        ) { Text("Cancel") }
                    }
                )
            }

            if (promptZeroAction) {
                AlertDialog(
                    onDismissRequest = { promptZeroAction = false },
                    title = { Text("Long Press '0'") },
                    text = { Text("What would you like to do?") },
                    confirmButton = {
                        Button(
                            enabled = speedDialDialogActionsEnabled,
                            onClick = {
                                promptZeroAction = false
                                assigningKey = "0"
                            }
                        ) { Text("Assign Speed Dial") }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = speedDialDialogActionsEnabled,
                            onClick = {
                                promptZeroAction = false
                                appendKeypadChar("+")
                            }
                        ) { Text("Type '+'") }
                    }
                )
            }
        }
    }

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormatter = SimpleDateFormat("MMM dd", Locale.getDefault())

    private fun formatTimestamp(timestamp: Long): String {
        val callDate = Date(timestamp)
        return synchronized(timeFormatter) { timeFormatter.format(callDate) }
    }

    private fun formatCallDuration(durationSeconds: Long, callType: CallType): String {
        if (callType == CallType.MISSED) return "Missed call"
        if (durationSeconds <= 0L) return "Didn't connect"
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        return if (mins > 0) "Talked $mins min $secs sec" else "Talked $secs sec"
    }

    private fun callTypeToString(callType: String): String = callType

    @Composable
    fun CallTypeIcon(callType: CallType) {
        val (icon, tint) = when (callType) {
            CallType.OUTGOING -> "\u2197" to MaterialTheme.colorScheme.primary
            CallType.INCOMING -> "\u2199" to MaterialTheme.colorScheme.secondary
            CallType.MISSED -> "\u21A9" to Color(0xFFFF5A52)
        }
        Text(text = icon, color = tint, fontWeight = FontWeight.Bold)
    }

    @Composable
    fun LocalProfileScreen(context: Context) {
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
        val prefs = safeContext.getSharedPreferences("local_user_profile", Context.MODE_PRIVATE)
        var myName by remember(uiState.profileName) { mutableStateOf(prefs.getString("my_name", "") ?: "") }
        var isEditing by remember(uiState.profileName) { mutableStateOf(myName.isEmpty()) }
        var showConfirmDialog by remember { mutableStateOf(false) }
        var tempName by remember { mutableStateOf("") }
        var showBlockedNumbersDialog by remember { mutableStateOf(false) }
        var blockedNumbers by remember { mutableStateOf(emptyList<String>()) }
        val refreshBlockedNumbers = {
            blockedNumbers = getBlockedPhoneNumbers()
        }

        val initialLetter = remember(myName) {
            myName.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "P"
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initialLetter,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (myName.isNotBlank()) myName else "My Caller Profile",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = if (myName.isNotBlank()) "Caller ID Active" else "Setup Required",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isEditing) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Edit Caller Name",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = myName,
                            onValueChange = { myName = it },
                            label = { Text("Your Display Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                tempName = myName.trim()
                                val currentName = prefs.getString("my_name", "") ?: ""
                                if (tempName == currentName) {
                                    Toast.makeText(context, "No changes detected", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (currentName.isEmpty()) {
                                    prefs.edit().putString("my_name", tempName).commit()
                                    saveP2PBackupFile(context)
                                    uploadP2PServerProfileName(context, tempName)
                                    isEditing = false
                                    Toast.makeText(context, "Profile Saved Locally!", Toast.LENGTH_SHORT).show()
                                } else {
                                    showConfirmDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = myName.trim().isNotEmpty()
                        ) {
                            Text("Save Changes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        if (!prefs.getString("my_name", "").isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { isEditing = false; myName = prefs.getString("my_name", "") ?: "" },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                        Text(
                            text = "Caller ID Information",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = myName.ifBlank { "No name set" },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This name is shared over encrypted P2P when calling other users.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { isEditing = true },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Edit Name", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { restorePickerLauncher.launch(arrayOf("text/plain")) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        refreshBlockedNumbers()
                        showBlockedNumbersDialog = true
                    },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Blocked Numbers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Manage numbers blocked by call screening", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false; myName = prefs.getString("my_name", "") ?: "" },
                title = { Text("Confirm Profile Change") },
                text = {
                    Column {
                        Text("Are you sure you want to change your Caller ID name?")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("From: ${prefs.getString("my_name", "")}", style = MaterialTheme.typography.bodyMedium)
                        Text("To: $tempName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString("my_name", tempName).commit()
                        // saveProfileBackup() copies the normal-storage name over this one, so update that copy
                        // first; otherwise the change was reverted to the old name.
                        getSharedPreferences("local_user_profile", MODE_PRIVATE).edit().putString("my_name", tempName).commit()
                        saveProfileBackup()
                        isEditing = false; showConfirmDialog = false
                        Toast.makeText(context, "Profile Updated & Protected!", Toast.LENGTH_SHORT).show()
                    }) { Text("Confirm", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { showConfirmDialog = false; myName = prefs.getString("my_name", "") ?: "" }) { Text("Cancel") } }
            )
        }

        if (showBlockedNumbersDialog) {
            AlertDialog(
                onDismissRequest = { showBlockedNumbersDialog = false },
                title = { Text("Blocked numbers") },
                text = {
                    if (blockedNumbers.isEmpty()) {
                        Text(
                            text = "No blocked numbers yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(blockedNumbers, key = { it }) { blockedNumber ->
                                val displayName = uiState.contacts.firstOrNull {
                                    normalizePhoneNumber(it.phoneNumber) == normalizePhoneNumber(blockedNumber)
                                }?.name
                                    ?.let(::sanitizeDisplayNameForUi)
                                    ?.takeIf { it.isNotBlank() }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            if (!displayName.isNullOrBlank()) {
                                                Text(
                                                    text = displayName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                            }
                                            Text(
                                                text = formatPhoneNumberForDisplay(blockedNumber),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                if (removePhoneNumberFromBlocklist(blockedNumber)) {
                                                    blockedNumbers = blockedNumbers.filterNot {
                                                        normalizePhoneNumber(it) == normalizePhoneNumber(blockedNumber)
                                                    }
                                                    refreshRecentCalls(withDelay = false)
                                                    showSuccess("${formatPhoneNumberForDisplay(blockedNumber)} unblocked")
                                                } else {
                                                    showError("Unable to unblock this number")
                                                }
                                            }
                                        ) {
                                            Text("Unblock")
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBlockedNumbersDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }

    /** One-time "verify my number" for the name server (silent SMS to this phone's own number). */
    @Composable
    private fun P2PServerVerifySection(context: Context) {
        var verifiedNumber by remember { mutableStateOf(p2pServerVerifiedNumber(context)) }
        var numberInput by remember {
            // Pre-fill from the SIM only when allowed; otherwise the user types it.
            val numberPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Manifest.permission.READ_PHONE_NUMBERS
            } else {
                Manifest.permission.READ_PHONE_STATE
            }
            val simNumber = if (ContextCompat.checkSelfPermission(context, numberPermission) == PackageManager.PERMISSION_GRANTED) {
                try {
                    (context.getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager)
                        .line1Number.orEmpty().filter { it.isDigit() }.takeLast(10)
                } catch (_: SecurityException) {
                    ""
                }
            } else {
                ""
            }
            mutableStateOf(simNumber)
        }
        var status by remember { mutableStateOf("") }
        var waiting by remember { mutableStateOf(false) }

        // The SMS is handled in the background; check until the server confirms (or ~60 s pass).
        LaunchedEffect(waiting) {
            if (!waiting) return@LaunchedEffect
            repeat(60) {
                delay(1000)
                p2pServerVerifiedNumber(context)?.let {
                    verifiedNumber = it
                    status = ""
                    waiting = false
                    return@LaunchedEffect
                }
            }
            status = "No SMS arrived. Check the number and try again."
            waiting = false
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                if (verifiedNumber != null) {
                    Text("Name server", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Verified: ${formatPhoneNumberForDisplay(verifiedNumber.orEmpty())}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Verify your number", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "One time: sends one SMS to your own number so other app users see your name during calls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = { numberInput = it.filter { c -> c.isDigit() }.take(13) },
                        label = { Text("Your mobile number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            status = "Sending…"
                            startP2PServerVerification(context, numberInput) { message ->
                                status = message
                                waiting = message.startsWith("Verifying")
                            }
                        },
                        enabled = !waiting && numberInput.length >= 10,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (waiting) "Waiting for SMS…" else "Verify") }
                    if (status.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    private fun saveProfileBackup() {
        try {
            val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                createDeviceProtectedStorageContext() else this
            val prefs = getSharedPreferences("local_user_profile", MODE_PRIVATE)
            val name = prefs.getString("my_name", "") ?: ""

            val dpsPrefs = safeContext.getSharedPreferences("local_user_profile", MODE_PRIVATE)
            dpsPrefs.edit().putString("my_name", name).apply()

            UltraForensicLogger.debug("Profile backed up to DPS storage")
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Failed to save profile backup: ${e.message}")
        }
        // Also into the Download/CallerApp file, which survives Clear data.
        saveP2PBackupFile(this)
        // And to the name server (does nothing until this phone is verified there).
        uploadP2PServerProfileName(this, getSharedPreferences("local_user_profile", MODE_PRIVATE).getString("my_name", "").orEmpty())
    }

    /**
     * After Clear data the profile name is empty. Restore it and the P2P names from the backup file
     * in Download/CallerApp if the app can still read it; otherwise the Profile tab's
     * "Restore from Backup" button lets the user pick that file.
     */
    private fun autoRestoreBackupIfCleared() {
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
        val hasProfile = !safeContext.getSharedPreferences("local_user_profile", MODE_PRIVATE)
            .getString("my_name", "").isNullOrBlank()
        if (hasProfile) {
            // Refresh the backup on every start, so Block Store is filled before any future Clear data
            // (before this, it was only written when a name changed or a P2P name arrived).
            saveP2PBackupFile(this)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = autoRestoreP2PBackup(this@MainActivity) ?: return@launch
            withContext(Dispatchers.Main) { onBackupRestored(result) }
        }
    }

    private fun onBackupRestored(result: P2PBackupRestoreResult) {
        result.profileName?.let { uiState = uiState.copy(profileName = it) }
        refreshRecentCalls(withDelay = false)
        showSuccess(
            buildString {
                append("Backup restored")
                if (result.profileName != null) append(": ${result.profileName}")
                // P2P caller names are restored quietly; the message only shows the user's own profile name.
            }
        )
    }

    private fun loadProfileBackup() {
        try {
            val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                createDeviceProtectedStorageContext() else this
            val dpsPrefs = safeContext.getSharedPreferences("local_user_profile", MODE_PRIVATE)
            val backedUpName = dpsPrefs.getString("my_name", "") ?: ""

            if (backedUpName.isNotEmpty()) {
                val prefs = getSharedPreferences("local_user_profile", MODE_PRIVATE)
                if (prefs.getString("my_name", "").isNullOrEmpty()) {
                    prefs.edit().putString("my_name", backedUpName).apply()
                    uiState = uiState.copy(profileName = backedUpName)
                    UltraForensicLogger.debug("Profile restored from DPS backup")
                }
            }
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Failed to load profile backup: ${e.message}")
        }
    }

    private fun handleRestoreUri(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()?.let { applyP2PBackupText(this@MainActivity, it) }
            withContext(Dispatchers.Main) {
                if (result == null) {
                    showError("Not a Caller backup file (pick caller_p2p_backup.txt in Download/CallerApp)")
                } else {
                    onBackupRestored(result)
                    // Re-create the backup file this install can read again.
                    saveP2PBackupFile(this@MainActivity)
                }
            }
        }
    }

    private fun getDefaultGoogleAccount(): Pair<String?, String?> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(P2P_FORENSIC_TAG, "google_account_lookup action=skip reason=missing_READ_CONTACTS")
            return Pair(null, null)
        }
        try {
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            )
            val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"
            val selectionArgs = arrayOf("com.google")

            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val accountName = cursor.getString(0)
                    val accountType = cursor.getString(1)
                    return Pair(accountName, accountType)
                }
            }
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Failed to fetch Google Account: ${e.message}")
        }
        return Pair(null, null)
                    }

    fun deleteContactFromSystem(contactId: String, rawContactId: String? = null) {
        if (!hasWriteContactsPermission()) {
            Log.w(
                P2P_FORENSIC_TAG,
                "delete_contact action=skip reason=missing_WRITE_CONTACTS contactId=$contactId rawContactId=${rawContactId.orEmpty()}"
            )
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!rawContactId.isNullOrBlank()) {
                    contentResolver.delete(
                        ContactsContract.RawContacts.CONTENT_URI,
                        "${ContactsContract.RawContacts._ID} = ?",
                        arrayOf(rawContactId)
                    )
                } else {
                    contentResolver.delete(
                        ContactsContract.RawContacts.CONTENT_URI,
                        "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                        arrayOf(contactId)
                    )
                }
            } catch (e: Exception) {}
        }
    }

    private fun saveNewContactToSystem(name: String, phoneNumber: String, accountName: String?, accountType: String?): Boolean {
        if (!hasWriteContactsPermission()) {
            Log.w(
                P2P_FORENSIC_TAG,
                "save_contact action=skip reason=missing_WRITE_CONTACTS phoneNumber=${maskP2PNumberForLog(phoneNumber)}"
            )
            return false
        }
        try {
            val ops = ArrayList<ContentProviderOperation>()

            val rawContactInsertIndex = ops.size
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .build())

            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())

            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())

            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            return true
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Failed to save contact natively: ${e.message}")
            return false
        }
    }

    private fun queryProviderRowIdsForRecentGroup(groupKey: String): List<String> {
        val providerRowIds = linkedSetOf<String>()
        try {
            contentResolver.query(
                Calls.CONTENT_URI,
                arrayOf(Calls._ID, Calls.NUMBER),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getString(0).orEmpty()
                    val rowNumber = cursor.getString(1).orEmpty()
                    if (rowId.isBlank()) continue
                    if (buildRecentCallerGroupKey(rowNumber) == groupKey) {
                        providerRowIds.add(rowId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(P2P_FORENSIC_TAG, "recent_delete action=query_group_row_ids_failure groupKey=$groupKey error=${e.message}", e)
        }
        return providerRowIds.toList()
    }

    private fun resolveProviderRowIdsForRecentDelete(request: PendingRecentDeleteRequest): List<String> {
        val freshGroupRowIds = queryProviderRowIdsForRecentGroup(request.groupKey)
        val targetId = request.targetRowId.takeIf { it.isNotBlank() && freshGroupRowIds.contains(it) }
            ?: request.providerRowIds.firstOrNull { it.isNotBlank() && freshGroupRowIds.contains(it) }
            ?: freshGroupRowIds.firstOrNull().orEmpty()
        val finalRowIds = if (targetId.isNotBlank()) listOf(targetId) else freshGroupRowIds.take(1)
        Log.w(
            P2P_FORENSIC_TAG,
            "recent_delete action=resolve_single_row_id groupKey=${request.groupKey} phone=${maskP2PNumberForLog(request.phoneNumber)} targetRowId=$targetId freshCount=${freshGroupRowIds.size}"
        )
        return finalRowIds
    }

    private fun deleteRecentCallFromSystem(request: PendingRecentDeleteRequest): RecentDeleteResult {
        val targetNumberForLog = maskP2PNumberForLog(request.phoneNumber)
        if (!hasWriteCallLogPermission()) {
            Log.w(
                P2P_FORENSIC_TAG,
                "recent_delete action=skip reason=missing_WRITE_CALL_LOG groupKey=${request.groupKey} phone=$targetNumberForLog"
            )
            return RecentDeleteResult(0, 0, emptyList(), emptyList())
        }
        val resolvedRowIds = resolveProviderRowIdsForRecentDelete(request)
        if (resolvedRowIds.isEmpty()) {
            Log.w(
                P2P_FORENSIC_TAG,
                "recent_delete action=skip reason=missing_row_ids groupKey=${request.groupKey} phone=$targetNumberForLog"
            )
            return RecentDeleteResult(0, 0, emptyList(), queryProviderRowIdsForRecentGroup(request.groupKey))
        }
        Log.w(
            P2P_FORENSIC_TAG,
            "recent_delete action=provider_delete_invoke groupKey=${request.groupKey} phone=$targetNumberForLog rowIds=${resolvedRowIds.joinToString(",")}"
        )
        var deletedRows = 0
        return try {
            resolvedRowIds.forEach { rowId ->
                var rows = 0
                val longId = rowId.toLongOrNull()
                if (longId != null && longId > 0L) {
                    try {
                        val singleUri = ContentUris.withAppendedId(Calls.CONTENT_URI, longId)
                        rows = contentResolver.delete(singleUri, null, null)
                    } catch (e: Exception) {
                        Log.w(P2P_FORENSIC_TAG, "recent_delete uri_delete_failed rowId=$rowId err=${e.message}")
                    }
                }
                if (rows <= 0) {
                    try {
                        rows = contentResolver.delete(Calls.CONTENT_URI, "${Calls._ID} = ?", arrayOf(rowId))
                    } catch (e: Exception) {
                        Log.w(P2P_FORENSIC_TAG, "recent_delete id_where_delete_failed rowId=$rowId err=${e.message}")
                    }
                }
                deletedRows += rows
            }
            val remainingRowIds = queryProviderRowIdsForRecentGroup(request.groupKey)
            if (deletedRows <= 0) {
                Log.w(
                    P2P_FORENSIC_TAG,
                    "recent_delete action=zero_row_delete groupKey=${request.groupKey} phone=$targetNumberForLog rowIds=${resolvedRowIds.joinToString(",")} deletedRows=$deletedRows"
                )
            } else {
                Log.w(
                    P2P_FORENSIC_TAG,
                    "recent_delete action=provider_delete_result groupKey=${request.groupKey} phone=$targetNumberForLog requestedRows=${resolvedRowIds.size} deletedRows=$deletedRows remainingInGroup=${remainingRowIds.size}"
                )
            }
            RecentDeleteResult(resolvedRowIds.size, deletedRows, resolvedRowIds, remainingRowIds)
        } catch (e: SecurityException) {
            Log.e(
                P2P_FORENSIC_TAG,
                "recent_delete action=security_failure groupKey=${request.groupKey} phone=$targetNumberForLog error=${e.message}",
                e
            )
            RecentDeleteResult(resolvedRowIds.size, deletedRows, resolvedRowIds, queryProviderRowIdsForRecentGroup(request.groupKey))
        } catch (e: Exception) {
            Log.e(
                P2P_FORENSIC_TAG,
                "recent_delete action=failure groupKey=${request.groupKey} phone=$targetNumberForLog error=${e.message}",
                e
            )
            RecentDeleteResult(resolvedRowIds.size, deletedRows, resolvedRowIds, queryProviderRowIdsForRecentGroup(request.groupKey))
        }
    }

    private fun clearAllRecentCalls() {
        uiState = uiState.copy(
            recentCalls = emptyList(),
            groupedRecentCalls = emptyList()
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (hasWriteCallLogPermission()) {
                    contentResolver.delete(Calls.CONTENT_URI, null, null)
                }
                val recentKeys = sharedPreferences.all.keys.filter { it.startsWith("recent_call_") }
                if (recentKeys.isNotEmpty()) {
                    sharedPreferences.edit().apply {
                        recentKeys.forEach(::remove)
                        apply()
                    }
                }
            } catch (e: Exception) {
                Log.e(P2P_FORENSIC_TAG, "clear_recent_calls action=failure error=${e.message}", e)
            }
        }

        showSuccess("Recent calls cleared")
    }

    fun isValidPhoneNumber(phoneNumber: String): Boolean = isSafePhoneNumber(phoneNumber)
    
    fun normalizePhoneNumber(phoneNumber: String): String {
        return buildP2PNumberSnapshot(phoneNumber).normalized
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && uiState.callState.isInCall) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Composable
    fun WelcomeScreen(onSetDefaultDialer: () -> Unit) {
        val dialerLabel = stringResource(R.string.launcher_phone_label)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Welcome to $dialerLabel", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Set $dialerLabel as your default dialer. Messages keeps its own separate default SMS setup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                DefaultAppButton(
                    title = "Set as Default Dialer",
                    subtitle = "Swipe to make $dialerLabel your phone app",
                    onClick = onSetDefaultDialer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    context: Context,
    activity: Activity,
    onRequestDefaultDialer: () -> Unit,
    onRequestCallScreeningRole: () -> Boolean,
    onRequestDefaultSms: () -> Unit
) {
    val prefs = remember(context) {
        val safe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
        safe.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    var blockUnknownNumbers by remember { mutableStateOf(prefs.getBoolean("block_unknown_numbers", false)) }
    var spamProtectionEnabled by remember { mutableStateOf(prefs.getBoolean("spam_protection_enabled", true)) }
    var showLogDialog by remember { mutableStateOf(false) }

    val isDefaultDialer = remember(context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecomManager?.defaultDialerPackage == context.packageName
    }

    val isDefaultSms = remember(context) {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    val isCallScreeningActive = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else false
    }

    val hasOverlayPermission = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configure app roles, permissions, spam protection & updates",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Card 1: Default App Roles
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Default App Roles",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                SettingItemRow(
                    title = "Default Phone App",
                    subtitle = if (isDefaultDialer) "Caller is set as your default dialer" else "Tap to set Caller as your default dialer",
                    badgeText = if (isDefaultDialer) "Default" else "Not Default",
                    badgeColor = if (isDefaultDialer) Color(0xFF2E7D32) else Color(0xFFD84315),
                    onClick = onRequestDefaultDialer
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingItemRow(
                    title = "Spam & Call Screening",
                    subtitle = if (isCallScreeningActive) "Active — blocking & caller identification enabled" else "Tap to enable Call Screening role",
                    badgeText = if (isCallScreeningActive) "Active" else "Disabled",
                    badgeColor = if (isCallScreeningActive) Color(0xFF2E7D32) else Color(0xFFD84315),
                    onClick = { onRequestCallScreeningRole() }
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingItemRow(
                    title = "Default Messaging App",
                    subtitle = if (isDefaultSms) "Caller is your default SMS app" else "Tap to set Caller as your default SMS app",
                    badgeText = if (isDefaultSms) "Default" else "Not Default",
                    badgeColor = if (isDefaultSms) Color(0xFF2E7D32) else Color(0xFFD84315),
                    onClick = onRequestDefaultSms
                )
            }
        }

        // Card 2: System Permissions & Overlays
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Display & System Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                SettingItemRow(
                    title = "In-Call Floating Bubble / Overlay",
                    subtitle = if (hasOverlayPermission) "Overlay permission granted" else "Grant permission to show ongoing call bubbles",
                    badgeText = if (hasOverlayPermission) "Granted" else "Required",
                    badgeColor = if (hasOverlayPermission) Color(0xFF2E7D32) else Color(0xFFD84315),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingItemRow(
                    title = "Usage Access Permission",
                    subtitle = "Allows detecting when you are on home screen for full-screen calls",
                    badgeText = "Configure",
                    badgeColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingItemRow(
                    title = "Ignore Battery Optimizations",
                    subtitle = "Ensure background calls and notifications are never delayed",
                    badgeText = "Configure",
                    badgeColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                )

                val isXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                              Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
                              Build.MANUFACTURER.equals("POCO", ignoreCase = true)
                if (isXiaomi) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingItemRow(
                        title = "MIUI / HyperOS Background Popups",
                        subtitle = "Enable Lock screen & Background pop-up permissions on Xiaomi devices",
                        badgeText = "MIUI Fix",
                        badgeColor = Color(0xFFFF8F00),
                        onClick = {
                            if (activity is MainActivity) {
                                activity.openXiaomiOtherPermissionsSettings()
                            }
                        }
                    )
                }
            }
        }

        // Card 3: Spam & Filtering
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Spam & Filtering Preferences",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Smart Spam Protection", fontWeight = FontWeight.SemiBold)
                        Text(text = "Automatically detect and flag suspected spam calls and messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = spamProtectionEnabled,
                        onCheckedChange = { checked ->
                            spamProtectionEnabled = checked
                            prefs.edit().putBoolean("spam_protection_enabled", checked).apply()
                        }
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Block Private & Unknown Callers", fontWeight = FontWeight.SemiBold)
                        Text(text = "Automatically drop calls from private, hidden, or unlisted numbers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = blockUnknownNumbers,
                        onCheckedChange = { checked ->
                            blockUnknownNumbers = checked
                            prefs.edit().putBoolean("block_unknown_numbers", checked).apply()
                        }
                    )
                }
            }
        }

        // Card 4: Software Updates & Downloader
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Software Updates",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Caller v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Channel: GitHub Release (thetech113-create/dialer)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Includes direct HTTP downloader fallback for devices without system DownloadManager.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                val currentDownloadState by AppUpdateManager.downloadState.collectAsState()
                if (currentDownloadState is AppUpdateManager.DownloadState.Downloading) {
                    val state = currentDownloadState as AppUpdateManager.DownloadState.Downloading
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Downloading Update...",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (state.progressPercent >= 0) "${state.progressPercent}%" else "...",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (state.progressPercent >= 0) {
                            LinearProgressIndicator(
                                progress = state.progressPercent / 100f,
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }

                        val downloadedMb = String.format("%.1f", state.downloadedBytes / (1024f * 1024f))
                        val totalMb = if (state.totalBytes > 0) String.format("%.1f MB", state.totalBytes / (1024f * 1024f)) else "MB"
                        Text(
                            text = "$downloadedMb MB / $totalMb",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val pendingUpdate = remember { AppUpdateManager.getDownloadedUpdateFile(context) }
                if (pendingUpdate != null) {
                    Button(
                        onClick = { AppUpdateManager.installDownloadedUpdate(context) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Install Downloaded Update Now")
                    }
                }

                Button(
                    onClick = { AppUpdateManager.checkForUpdate(activity, isManual = true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check for Updates")
                }
            }
        }

        // Card 5: Diagnostics & Logs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Diagnostics & Forensic Logs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "View app diagnostics, call state history and system event logs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { showLogDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Diagnostic Logs")
                }
            }
        }
    }

    if (showLogDialog) {
        val logDump = remember<String> { com.caller.logging.UltraForensicLogger.dumpLogHistory().takeLast(2000) }
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Forensic Diagnostic Logs") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color.Black, shape = RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = if (logDump.isNotBlank()) logDump else "No log events recorded yet.",
                        color = Color.Green,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SettingItemRow(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(100.dp),
            color = badgeColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = badgeText,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}














