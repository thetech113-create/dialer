package com.caller

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.caller.ui.theme.CallerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MessagingActivity : ComponentActivity() {
    private var contacts by mutableStateOf<List<Contact>>(emptyList())
    private var isDefaultSmsRoleHeld by mutableStateOf(false)
    private var hasContactsAccess by mutableStateOf(false)
    private var isContactsLoading by mutableStateOf(false)
    private var refreshKey by mutableIntStateOf(0)
    private var composeAddress by mutableStateOf("")
    private var composeBody by mutableStateOf("")
    private var selectedConversationThreadId by mutableStateOf("")
    private var smsThreadsReceiverRegistered = false

    private val smsThreadsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshKey++
        }
    }

    private val smsRoleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val userGrantedRole = result.resultCode == Activity.RESULT_OK
        val isNowDefault = userGrantedRole || isDefaultSmsApp(this)
        isDefaultSmsRoleHeld = isNowDefault

        if (isNowDefault) {
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
        syncMessagingState()
        updateComposeIntent(intent)
        ensureContactsReady()

        setContent {
            CallerTheme {
                MessagesScreenContent(
                    contacts = contacts,
                    isDefaultSmsApp = isDefaultSmsRoleHeld,
                    hasContactAccess = hasContactsAccess,
                    isContactsLoading = isContactsLoading,
                    refreshKey = refreshKey,
                    initialComposeAddress = composeAddress,
                    initialComposeBody = composeBody,
                    initialThreadId = selectedConversationThreadId,
                    onConsumeComposeIntent = {
                        composeAddress = ""
                        composeBody = ""
                        selectedConversationThreadId = ""
                    },
                    onRequestDefaultSms = { requestDefaultSmsRole() },
                    onRequestSmsPermissions = { requestSmsPermissions() },
                    onRequestContactsPermission = { requestContactsPermission() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateComposeIntent(intent)
        syncMessagingState()
        refreshKey++
    }

    override fun onStart() {
        super.onStart()
        registerSmsThreadsReceiver()
    }

    override fun onResume() {
        super.onResume()
        syncMessagingState()
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
        super.onStop()
    }

    private fun syncMessagingState() {
        isDefaultSmsRoleHeld = isDefaultSmsApp(this)
    }

    private fun requestDefaultSmsRole() {
        try {
            if (isDefaultSmsApp(this)) {
                isDefaultSmsRoleHeld = true
                showToast("Already set as default messaging app")
                requestSmsPermissions()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    smsRoleRequestLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
                    return
                }
            }

            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
            smsRoleRequestLauncher.launch(intent)
        } catch (_: Exception) {
            showToast("Unable to request default messaging role")
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
        composeAddress = address
        composeBody = extractSmsBody(sourceIntent)

        // If opening from notification, load the conversation thread
        if (sourceIntent.action == ACTION_OPEN_MESSAGES && address.isNotBlank()) {
            loadConversationThread(address)
        }
    }

    private fun loadConversationThread(phoneNumber: String) {
        try {
            composeAddress = phoneNumber
            composeBody = ""
            selectedConversationThreadId = ""
            Log.w("MessagingActivity", "Loading conversation for $phoneNumber")
        } catch (e: Exception) {
            Log.w("MessagingActivity", "Error loading conversation thread for $phoneNumber", e)
        }
    }

    private fun getThreadIdForNumber(phoneNumber: String): String {
        return try {
            val numberSnapshot = buildP2PNumberSnapshot(phoneNumber)
            val contentResolver = this.contentResolver
            val uri = Telephony.Threads.CONTENT_URI
            val selection = Telephony.Threads.RECIPIENT_IDS + " = ?"

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
