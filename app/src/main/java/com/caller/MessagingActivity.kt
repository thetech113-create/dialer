package com.caller

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.caller.ui.theme.CallerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MessagingActivity : ComponentActivity() {
    companion object {
        private const val STATE_COMPOSE_ADDRESS = "state_compose_address"
        private const val STATE_COMPOSE_BODY = "state_compose_body"
        private const val STATE_SELECTED_THREAD_ID = "state_selected_thread_id"
        private const val STATE_PRESERVED_ADDRESS = "state_preserved_address"
        private const val STATE_PRESERVED_BODY = "state_preserved_body"
        private const val STATE_PRESERVED_THREAD_ID = "state_preserved_thread_id"
        private const val STATE_NEEDS_RESTORE_ON_ROLE = "state_needs_restore_on_role"
        private const val STATE_AUTO_PROMPTED_NON_DEFAULT = "state_auto_prompted_non_default"
    }

    private var contacts by mutableStateOf<List<Contact>>(emptyList())
    private var isDefaultSmsRoleHeld by mutableStateOf(false)
    private var isRoleValidationComplete by mutableStateOf(false)
    private var hasContactsAccess by mutableStateOf(false)
    private var isContactsLoading by mutableStateOf(false)
    private var refreshKey by mutableIntStateOf(0)
    private var composeAddress by mutableStateOf("")
    private var composeBody by mutableStateOf("")
    private var selectedConversationThreadId by mutableStateOf("")

    // Preserves the last legitimate UI state across non-default intervals and Activity recreation
    private var preservedComposeAddress = ""
    private var preservedComposeBody = ""
    private var preservedThreadId = ""
    private var needsRestoreOnRoleGrant = false
    private var roleRequestInFlight = false
    private var autoPromptedInCurrentNonDefaultSession = false

    private var smsThreadsReceiverRegistered = false

    private val smsThreadsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshKey++
        }
    }

    private var smsRoleRequestLaunchedAtMs = -1L
    private val SMS_ROLE_SILENT_DENY_THRESHOLD_MS = 700L

    private val smsRoleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        roleRequestInFlight = false
        val isNowDefault = verifyCurrentDefaultSmsRole()
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - smsRoleRequestLaunchedAtMs
        isDefaultSmsRoleHeld = isNowDefault
        isRoleValidationComplete = true

        if (!isNowDefault && elapsedMs in 0L..SMS_ROLE_SILENT_DENY_THRESHOLD_MS) {
            openDefaultAppsSettings()
            return@registerForActivityResult
        }

        if (isNowDefault) {
            autoPromptedInCurrentNonDefaultSession = false
            restorePreservedUiStateAfterRoleConfirmed()
            showToast("Set as default messaging app")
            requestSmsPermissions()
        } else {
            showToast("App needs to be the default messaging app to manage SMS")
        }
        refreshKey++
    }

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val hasAllSmsPermissions =
            hasReadSmsPermission(this) &&
                hasSendSmsPermission(this) &&
                hasReceiveSmsPermission()

        if (hasAllSmsPermissions) {
            showToast("Messaging permissions granted")
        } else {
            showToast("SMS permissions are needed to read and send messages")
        }
        refreshKey++
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContactsAccess = granted
        if (granted) {
            refreshContacts()
        } else {
            contacts = emptyList()
            showToast("Allow contacts to pick people with +")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val splashStartMs = android.os.SystemClock.elapsedRealtime()
        splashScreen.setKeepOnScreenCondition {
            android.os.SystemClock.elapsedRealtime() - splashStartMs < 250L
        }
        super.onCreate(savedInstanceState)
        SmsMonitoringService.startService(this)

        restoreSavedActivityState(savedInstanceState)

        // 1. Validate current Default SMS role FIRST before exposing or restoring any UI state
        validateRoleAndSyncState(triggerAutoPromptIfLost = false)

        // 2. Capture intent state into preserved storage; only expose if role is currently held
        if (savedInstanceState == null) {
            updateComposeIntent(intent)
        } else if (isDefaultSmsRoleHeld && needsRestoreOnRoleGrant) {
            restorePreservedUiStateAfterRoleConfirmed()
        }

        ensureContactsReady()

        setContent {
            CallerTheme {
                val canExposeMessagingUi = isRoleValidationComplete && isDefaultSmsRoleHeld

                Box(modifier = Modifier.fillMaxSize()) {
                    // Keep MessagesScreenContent in the composition tree so its internal
                    // conversation and navigation state is preserved across non-default intervals,
                    // while hiding and blocking interaction until role validation succeeds.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (canExposeMessagingUi) 1f else 0f
                            }
                    ) {
                        MessagesScreenContent(
                            contacts = contacts,
                            isDefaultSmsApp = canExposeMessagingUi,
                            hasContactAccess = hasContactsAccess,
                            isContactsLoading = isContactsLoading,
                            refreshKey = refreshKey,
                            initialComposeAddress = if (canExposeMessagingUi) composeAddress else "",
                            initialComposeBody = if (canExposeMessagingUi) composeBody else "",
                            initialThreadId = if (canExposeMessagingUi) selectedConversationThreadId else "",
                            onConsumeComposeIntent = {
                                if (canExposeMessagingUi) {
                                    composeAddress = ""
                                    composeBody = ""
                                    selectedConversationThreadId = ""
                                }
                            },
                            onRequestDefaultSms = { requestDefaultSmsRole() },
                            onRequestSmsPermissions = { requestSmsPermissions() },
                            onRequestContactsPermission = { requestContactsPermission() }
                        )

                        if (!canExposeMessagingUi) {
                            // Consume all pointer events so stale underlying conversation UI cannot be tapped
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

                    if (!canExposeMessagingUi) {
                        DefaultSmsRoleRequiredGate(
                            onSetDefaultSms = { requestDefaultSmsRole() }
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_COMPOSE_ADDRESS, composeAddress)
        outState.putString(STATE_COMPOSE_BODY, composeBody)
        outState.putString(STATE_SELECTED_THREAD_ID, selectedConversationThreadId)
        outState.putString(STATE_PRESERVED_ADDRESS, preservedComposeAddress)
        outState.putString(STATE_PRESERVED_BODY, preservedComposeBody)
        outState.putString(STATE_PRESERVED_THREAD_ID, preservedThreadId)
        outState.putBoolean(STATE_NEEDS_RESTORE_ON_ROLE, needsRestoreOnRoleGrant)
        outState.putBoolean(STATE_AUTO_PROMPTED_NON_DEFAULT, autoPromptedInCurrentNonDefaultSession)
    }

    private fun restoreSavedActivityState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        composeAddress = savedInstanceState.getString(STATE_COMPOSE_ADDRESS).orEmpty()
        composeBody = savedInstanceState.getString(STATE_COMPOSE_BODY).orEmpty()
        selectedConversationThreadId = savedInstanceState.getString(STATE_SELECTED_THREAD_ID).orEmpty()
        preservedComposeAddress = savedInstanceState.getString(STATE_PRESERVED_ADDRESS).orEmpty()
        preservedComposeBody = savedInstanceState.getString(STATE_PRESERVED_BODY).orEmpty()
        preservedThreadId = savedInstanceState.getString(STATE_PRESERVED_THREAD_ID).orEmpty()
        needsRestoreOnRoleGrant = savedInstanceState.getBoolean(STATE_NEEDS_RESTORE_ON_ROLE, false)
        autoPromptedInCurrentNonDefaultSession =
            savedInstanceState.getBoolean(STATE_AUTO_PROMPTED_NON_DEFAULT, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        validateRoleAndSyncState(triggerAutoPromptIfLost = false)
        updateComposeIntent(intent)
        refreshKey++
    }

    override fun onStart() {
        super.onStart()
        registerSmsThreadsReceiver()
        // Validate Default SMS role in onStart() BEFORE the restored Recents UI becomes interactive
        validateRoleAndSyncState(triggerAutoPromptIfLost = true)
    }

    override fun onResume() {
        super.onResume()
        validateRoleAndSyncState(triggerAutoPromptIfLost = true)
        hasContactsAccess = hasReadContactsPermission()
        if (contacts.isEmpty() && hasReadContactsPermission()) {
            refreshContacts()
        }
        refreshKey++
    }

    override fun onStop() {
        if (smsThreadsReceiverRegistered) {
            unregisterReceiver(smsThreadsUpdatedReceiver)
            smsThreadsReceiverRegistered = false
        }
        // Require fresh role validation before exposing UI on next return from Recents
        isRoleValidationComplete = false
        super.onStop()
    }

    private fun verifyCurrentDefaultSmsRole(): Boolean {
        val roleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    roleManager.isRoleHeld(RoleManager.ROLE_SMS)
                } else {
                    isDefaultSmsApp(this)
                }
            }.getOrDefault(false)
        } else {
            isDefaultSmsApp(this)
        }
        return roleHeld || isDefaultSmsApp(this)
    }

    private fun validateRoleAndSyncState(triggerAutoPromptIfLost: Boolean) {
        val wasDefault = isDefaultSmsRoleHeld
        val isNowDefault = verifyCurrentDefaultSmsRole()

        if (wasDefault && !isNowDefault) {
            // Lost default SMS role while backgrounded: preserve any unconsumed state and gate UI
            if (composeAddress.isNotBlank() || composeBody.isNotBlank() || selectedConversationThreadId.isNotBlank()) {
                preservedComposeAddress = composeAddress
                preservedComposeBody = composeBody
                preservedThreadId = selectedConversationThreadId
            }
            needsRestoreOnRoleGrant = true
        }

        isDefaultSmsRoleHeld = isNowDefault
        isRoleValidationComplete = true

        if (isNowDefault) {
            autoPromptedInCurrentNonDefaultSession = false
            if (needsRestoreOnRoleGrant) {
                restorePreservedUiStateAfterRoleConfirmed()
            }
        } else {
            needsRestoreOnRoleGrant = true
            if (triggerAutoPromptIfLost && !roleRequestInFlight && !autoPromptedInCurrentNonDefaultSession) {
                autoPromptedInCurrentNonDefaultSession = true
                requestDefaultSmsRole()
            }
        }
    }

    private fun restorePreservedUiStateAfterRoleConfirmed() {
        if (!isDefaultSmsRoleHeld) return
        if (preservedComposeAddress.isNotBlank() && composeAddress.isBlank()) {
            composeAddress = preservedComposeAddress
        }
        if (preservedComposeBody.isNotBlank() && composeBody.isBlank()) {
            composeBody = preservedComposeBody
        }
        if (preservedThreadId.isNotBlank() && selectedConversationThreadId.isBlank()) {
            selectedConversationThreadId = preservedThreadId
        } else if (selectedConversationThreadId.isBlank() && composeAddress.isNotBlank() && hasReadSmsPermission(this)) {
            selectedConversationThreadId = getThreadIdForNumber(composeAddress)
        }
        needsRestoreOnRoleGrant = false
    }

    private fun syncMessagingState() {
        validateRoleAndSyncState(triggerAutoPromptIfLost = false)
    }

    private fun openDefaultAppsSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            } else {
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun requestDefaultSmsRole() {
        try {
            if (verifyCurrentDefaultSmsRole()) {
                isDefaultSmsRoleHeld = true
                isRoleValidationComplete = true
                autoPromptedInCurrentNonDefaultSession = false
                restorePreservedUiStateAfterRoleConfirmed()
                showToast("Already set as default messaging app")
                requestSmsPermissions()
                return
            }

            if (roleRequestInFlight) return
            roleRequestInFlight = true
            smsRoleRequestLaunchedAtMs = android.os.SystemClock.elapsedRealtime()

            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                } else {
                    Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                        putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                    }
                }
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                }
            }
            smsRoleRequestLauncher.launch(intent)
        } catch (_: Exception) {
            roleRequestInFlight = false
            openDefaultAppsSettings()
        }
    }

    private fun requestSmsPermissions() {
        val permissionsToRequest = buildList {
            if (!hasReadSmsPermission(this@MessagingActivity)) add(Manifest.permission.READ_SMS)
            if (!hasSendSmsPermission(this@MessagingActivity)) add(Manifest.permission.SEND_SMS)
            if (!hasReceiveSmsPermission()) add(Manifest.permission.RECEIVE_SMS)
        }

        if (permissionsToRequest.isEmpty()) {
            refreshKey++
            return
        }

        smsPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun hasReceiveSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureContactsReady() {
        hasContactsAccess = hasReadContactsPermission()
        if (hasContactsAccess) {
            refreshContacts()
        } else {
            requestContactsPermission()
        }
    }

    private fun requestContactsPermission() {
        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun refreshContacts() {
        lifecycleScope.launch {
            isContactsLoading = true
            contacts = withContext(Dispatchers.IO) {
                loadMessagingContacts(this@MessagingActivity)
            }
            isContactsLoading = false
        }
    }

    private fun updateComposeIntent(sourceIntent: Intent?) {
        if (sourceIntent == null) return

        val shouldHandleIntent =
            sourceIntent.action == ACTION_OPEN_MESSAGES ||
                sourceIntent.action == Intent.ACTION_SENDTO ||
                sourceIntent.action == Intent.ACTION_SEND ||
                sourceIntent.action == Intent.ACTION_VIEW ||
                sourceIntent.hasExtra(EXTRA_SMS_ADDRESS) ||
                sourceIntent.hasExtra(EXTRA_SMS_BODY)

        if (!shouldHandleIntent) {
            return
        }

        val address = extractSmsAddress(sourceIntent)
        val body = extractSmsBody(sourceIntent)

        preservedComposeAddress = address
        preservedComposeBody = body

        if (sourceIntent.action == ACTION_OPEN_MESSAGES && address.isNotBlank()) {
            loadConversationThread(address)
        } else {
            composeAddress = address
            composeBody = body
        }

        if (!isDefaultSmsRoleHeld) {
            needsRestoreOnRoleGrant = true
        }
    }

    private fun loadConversationThread(phoneNumber: String) {
        try {
            val resolvedThreadId = if (isDefaultSmsRoleHeld && hasReadSmsPermission(this)) {
                getThreadIdForNumber(phoneNumber)
            } else {
                ""
            }
            composeAddress = phoneNumber
            composeBody = ""
            selectedConversationThreadId = resolvedThreadId
            preservedComposeAddress = phoneNumber
            preservedComposeBody = ""
            preservedThreadId = resolvedThreadId
            Log.w("MessagingActivity", "Loading conversation for $phoneNumber (threadId=$resolvedThreadId)")
        } catch (e: Exception) {
            Log.w("MessagingActivity", "Error loading conversation thread for $phoneNumber", e)
        }
    }

    private fun getThreadIdForNumber(phoneNumber: String): String {
        return try {
            val numberSnapshot = buildP2PNumberSnapshot(phoneNumber)
            val contentResolver = this.contentResolver
            val uri = Telephony.Threads.CONTENT_URI

            contentResolver.query(
                uri,
                arrayOf(Telephony.Threads._ID),
                null,
                null,
                null
            )?.use { cursor ->
                val colIdx = cursor.getColumnIndex(Telephony.Threads._ID)
                while (cursor.moveToNext()) {
                    val threadId = if (colIdx >= 0) cursor.getString(colIdx) else ""
                    val threadNumber = getNumberForThreadId(threadId)

                    if (threadNumber.isNotBlank()) {
                        val threadSnapshot = buildP2PNumberSnapshot(threadNumber)
                        if (threadSnapshot.normalized == numberSnapshot.normalized ||
                            threadSnapshot.last10 == numberSnapshot.last10
                        ) {
                            return threadId
                        }
                    }
                }
            }
            ""
        } catch (e: Exception) {
            Log.e("MessagingActivity", "Error getting thread ID for $phoneNumber", e)
            ""
        }
    }

    private fun getNumberForThreadId(threadId: String): String {
        return try {
            val uri = Telephony.Sms.CONTENT_URI
            val selection = Telephony.Sms.THREAD_ID + " = ?"
            val selectionArgs = arrayOf(threadId)

            contentResolver.query(
                uri,
                arrayOf(Telephony.Sms.ADDRESS),
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                    if (addrIdx >= 0) cursor.getString(addrIdx) else ""
                } else {
                    ""
                }
            } ?: ""
        } catch (e: Exception) {
            Log.e("MessagingActivity", "Error getting number for thread $threadId", e)
            ""
        }
    }

    private fun registerSmsThreadsReceiver() {
        if (smsThreadsReceiverRegistered) return

        val filter = IntentFilter(SMS_THREADS_UPDATED_ACTION)
        ContextCompat.registerReceiver(
            this,
            smsThreadsUpdatedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        smsThreadsReceiverRegistered = true
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DefaultSmsRoleRequiredGate(
    onSetDefaultSms: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Set as Default Messaging App",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "To view and manage your conversations, set this app as your default SMS app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(28.dp))
            DefaultAppButton(
                title = "Set Default SMS App",
                subtitle = "Swipe right to confirm",
                onClick = onSetDefaultSms,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Send
            )
        }
    }
}

private fun loadMessagingContacts(context: Context): List<Contact> {
    val contactsByNumber = linkedMapOf<String, Contact>()
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.PHOTO_URI
    )

    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection,
        null,
        null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val photoUriIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

        while (cursor.moveToNext()) {
            val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
            if (number.isBlank()) {
                continue
            }

            val normalizedNumber = buildP2PNumberSnapshot(number).normalized
                .ifBlank { number.filter { it.isDigit() } }
                .ifBlank { number.trim() }
            if (normalizedNumber.isBlank() || contactsByNumber.containsKey(normalizedNumber)) {
                continue
            }

            val id = if (idIndex >= 0) cursor.getString(idIndex).orEmpty() else normalizedNumber
            val displayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
            val photoUri = if (photoUriIndex >= 0) cursor.getString(photoUriIndex) else null

            contactsByNumber[normalizedNumber] = Contact(
                id = id.ifBlank { normalizedNumber },
                name = displayName.ifBlank { number },
                phoneNumber = number,
                photoUri = photoUri
            )
        }
    }

    return contactsByNumber.values.sortedWith(
        compareBy<Contact> { it.name.lowercase(Locale.getDefault()) }
            .thenBy { it.phoneNumber }
    )
}
