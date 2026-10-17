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

        val incomingNumber = callDetails.handle?.schemeSpecificPart ?: ""
        val cleanNum = incomingNumber.filter { it.isDigit() }

        if (cleanNum.isEmpty()) {
            blockCall(callDetails, rejectAndLog = false) // HIDDEN NUMBER = INSTANT KILL
            return
        }

        // --- 1. LOCAL BLACKLIST OVERRIDE ---
        // Blocked numbers must win even if the caller is saved in contacts.
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
        val blocklistPrefs = safeContext.getSharedPreferences("local_spam_blocklist", Context.MODE_PRIVATE)
        if (blocklistPrefs.getBoolean(cleanNum, false) || (cleanNum.length >= 10 && blocklistPrefs.getBoolean(cleanNum.takeLast(10), false))) {
            blockCall(callDetails, rejectAndLog = true)
            return
        }

        // --- 2. WHITELIST (Contacts Bypass) ---
        if (isNumberInContacts(incomingNumber)) {
            allowCall(callDetails)
            return
        }

        // --- 3. NETWORK-LEVEL CRYPTOGRAPHIC CHECK (STIR/SHAKEN) ---
        // If the carrier network confirms the caller ID is spoofed/faked, kill it instantly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED) {
                UltraForensicLogger.deepest("SpamForensics", "NETWORK_KILL", "STIR/SHAKEN Verification Failed: ${maskPhoneNumber(cleanNum)}")
                blockCall(callDetails, rejectAndLog = true)
                return
            }
        }

        // --- 4. ULTRA-STRICT FORENSIC ANALYSIS ---
        var totalSpamScore = 30f // Severe base penalty for unknown callers
        val forensicSignal = ZeroDaySpamDetector.analyzeForensicThreat(incomingNumber, this)
        totalSpamScore += forensicSignal.finalThreatScore

        // --- 5. PARANOID EXECUTION RULES ---
        
        // Rule A: ANY Machine or Fraud Signature = Instant Kill
        if (forensicSignal.isMachineCadence || forensicSignal.isWangiriFraud || forensicSignal.isSpoofedEntropy || forensicSignal.threatSignatures.isNotEmpty()) {
            UltraForensicLogger.deepest("SpamForensics", "ZERO_TOLERANCE_KILL", "Threats: ${forensicSignal.threatSignatures}")
            blockCall(callDetails, rejectAndLog = false) // True silent drop
            return
        }

        // Rule B: Brutal Threshold Execution (Lowered to 50 for Infinite Strictness)
        when {
            totalSpamScore >= 50f -> {
                // High Threat: Instant block
                UltraForensicLogger.debug("SpamForensics", "BLOCKED_STRICT", "Score: $totalSpamScore")
                blockCall(callDetails, rejectAndLog = true)
            }
            else -> {
                // Suspicious but passed all aggressive checks: Allow to ring
                allowCall(callDetails)
            }
        }
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
