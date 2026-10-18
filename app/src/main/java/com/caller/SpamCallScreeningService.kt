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

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import androidx.core.content.ContextCompat
import com.caller.logging.UltraForensicLogger

class SpamCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            allowCall(callDetails)
            return
        }

        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
        val appSettings = safeContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isSpamProtectionEnabled = appSettings.getBoolean("spam_protection_enabled", true)
        val isBlockUnknownEnabled = appSettings.getBoolean("block_unknown_numbers", false)

        val incomingNumber = callDetails.handle?.schemeSpecificPart ?: ""
        val cleanNum = incomingNumber.filter { it.isDigit() }

        // --- 1. LOCAL BLACKLIST OVERRIDE ---
        val blocklistPrefs = safeContext.getSharedPreferences("local_spam_blocklist", Context.MODE_PRIVATE)
        if (cleanNum.isNotEmpty() && (blocklistPrefs.getBoolean(cleanNum, false) || (cleanNum.length >= 10 && blocklistPrefs.getBoolean(cleanNum.takeLast(10), false)))) {
            blockCall(callDetails, rejectAndLog = true)
            return
        }

        // --- 2. WHITELIST (Contacts Bypass) ---
        if (incomingNumber.isNotEmpty() && isNumberInContacts(incomingNumber)) {
            allowCall(callDetails)
            return
        }

        // --- 3. BLOCK UNKNOWN CALLERS IF SETTING ENABLED ---
        if (isBlockUnknownEnabled) {
            UltraForensicLogger.debug("SpamForensics", "BLOCKED_UNKNOWN", "Blocked unknown caller: ${maskPhoneNumber(cleanNum)}")
            blockCall(callDetails, rejectAndLog = true)
            return
        }

        // If smart spam protection is disabled by user in Settings, allow call
        if (!isSpamProtectionEnabled) {
            allowCall(callDetails)
            return
        }

        // --- 4. NETWORK-LEVEL CRYPTOGRAPHIC CHECK (STIR/SHAKEN) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED) {
                UltraForensicLogger.deepest("SpamForensics", "NETWORK_KILL", "STIR/SHAKEN Verification Failed: ${maskPhoneNumber(cleanNum)}")
                blockCall(callDetails, rejectAndLog = true)
                return
            }
        }

        // --- 5. FORENSIC ANALYSIS ---
        val forensicSignal = ZeroDaySpamDetector.analyzeForensicThreat(incomingNumber, this)

        // Block Wangiri international fraud or verified severe threat
        if (forensicSignal.isWangiriFraud || forensicSignal.finalThreatScore >= 80f) {
            UltraForensicLogger.deepest("SpamForensics", "SPAM_KILL", "Threats: ${forensicSignal.threatSignatures} Score: ${forensicSignal.finalThreatScore}")
            blockCall(callDetails, rejectAndLog = true)
            return
        }

        // Allow legitimate incoming calls
        allowCall(callDetails)
    }

    private fun allowCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }

    private fun blockCall(callDetails: Call.Details, rejectAndLog: Boolean) {
        val response = CallResponse.Builder().apply {
            setDisallowCall(true) 
            setRejectCall(true)   
            setSkipNotification(true) 
            setSkipCallLog(!rejectAndLog) // Delete severe fraud entirely
        }.build()
        respondToCall(callDetails, response)
    }

    private fun isNumberInContacts(phoneNumber: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false
        }
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { cursor ->
                return cursor.count > 0
            }
        } catch (e: Exception) {}
        return false
    }
}
