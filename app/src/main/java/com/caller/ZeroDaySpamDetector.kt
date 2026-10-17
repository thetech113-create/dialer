package com.caller

import android.content.Context
import android.provider.CallLog
import java.util.*
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

data class ForensicSpamSignal(
    val finalThreatScore: Float,
    val isMachineCadence: Boolean,
    val isWangiriFraud: Boolean,
    val isSpoofedEntropy: Boolean,
    val threatSignatures: List<String>
)

object ZeroDaySpamDetector {

    fun analyzeForensicThreat(phoneNumber: String, context: Context): ForensicSpamSignal {
        val cleanNum = phoneNumber.filter { it.isDigit() }
        val signatures = mutableListOf<String>()
        var baseThreatScore = 0f

        // 1. SHANNON ENTROPY (TIGHTENED)
        // Fraudsters often use numbers with repeated digits (e.g. 888-888-9999) or entirely sequential.
        val entropy = calculateShannonEntropy(cleanNum)
        val isSpoofed = entropy < 1.8 || entropy > 3.0 // Much tighter bounds for human numbers
        if (isSpoofed) {
            baseThreatScore += 50f // Fatal
            signatures.add("ANOMALOUS_ENTROPY_SPOOFING")
        }

        // 2. WANGIRI FRAUD (EXPANDED TO ALL NON-STANDARD EXOTICS)
        val isWangiri = detectWangiriSignature(phoneNumber)
        if (isWangiri) {
            baseThreatScore += 100f // Fatal
            signatures.add("WANGIRI_FRAUD_SIGNATURE")
        }

        // 3. TELECOM FORMAT STRICTNESS (MILITARIZED)
        // Standard mobile/landline numbers are strictly 10-15 digits. Anything else is blocked if not in contacts.
        if (cleanNum.length < 10 || cleanNum.length > 15) {
            baseThreatScore += 100f // Fatal. Kills all 5/6 digit marketing shortcodes.
            signatures.add("NON_STANDARD_LENGTH")
        }

        // 4. ROBOCALLER CADENCE (TIGHTENED)
        val callHistory = extractLocalCallHistory(cleanNum, context)
        val isMachine = detectMachineCadence(callHistory)
        if (isMachine) {
            baseThreatScore += 100f // Fatal
            signatures.add("ROBOCALLER_MACHINE_CADENCE")
        }

        // 5. VELOCITY (PARANOID LEVEL)
        // If an unknown number calls 2 times in a span of 5 minutes, it is an aggressive spammer.
        val behaviorThreat = analyzeVelocityAndDrops(callHistory)
        baseThreatScore += behaviorThreat

        if (behaviorThreat > 0f) {
            signatures.add("AGGRESSIVE_VELOCITY_OR_DROPS")
        }

        // 6. NEIGHBOR SPOOFING DETECTION (Psychological Fraud)
        // Fraudsters spoof the first 6 digits of your OWN phone number to fake local familiarity.
        val deviceNum = getDevicePhoneNumber(context)
        if (deviceNum.length >= 10 && cleanNum.length >= 10) {
            // Compare the localized Area Code + Prefix (First 6 digits of the 10-digit number)
            val userPrefix = deviceNum.takeLast(10).take(6)
            val callerPrefix = cleanNum.takeLast(10).take(6)
            
            // If the prefixes match exactly, but it's not actually your own number calling itself
            if (userPrefix == callerPrefix && cleanNum != deviceNum) {
                baseThreatScore += 80f // FATAL THREAT
                signatures.add("NEIGHBOR_SPOOFING_DETECTED")
            }
        }

        return ForensicSpamSignal(
            finalThreatScore = baseThreatScore.coerceIn(0f, 100f),
            isMachineCadence = isMachine,
            isWangiriFraud = isWangiri,
            isSpoofedEntropy = isSpoofed,
            threatSignatures = signatures
        )
    }

    @android.annotation.SuppressLint("MissingPermission", "HardwareIds")
    private fun getDevicePhoneNumber(context: Context): String {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val number = telephonyManager.line1Number ?: ""
            return number.filter { it.isDigit() }
        } catch (e: Exception) {
            return "" // Fails gracefully if SIM cannot be read or permission is missing
        }
    }

    private fun calculateShannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = s.groupingBy { it }.eachCount()
        return freq.values.sumOf { count ->
            val p = count.toDouble() / s.length
            -p * log2(p)
        }
    }

    private fun detectWangiriSignature(number: String): Boolean {
        // High-risk toll fraud + satellite prefixes
        val highRiskPrefixes = listOf("+247", "+252", "+269", "+371", "+375", "+381", "+685", "+678", "+223", "+225", "+881", "+882")
        return highRiskPrefixes.any { number.startsWith(it) }
    }

    private fun detectMachineCadence(calls: List<Long>): Boolean {
        if (calls.size < 2) return false // Lowered to 2 calls for strictness

        val sortedCalls = calls.sorted()
        val deltas = mutableListOf<Long>()
        for (i in 1 until sortedCalls.size) {
            deltas.add(sortedCalls[i] - sortedCalls[i - 1])
        }

        val meanDelta = deltas.average()
        val variance = deltas.sumOf { (it - meanDelta).pow(2) } / deltas.size
        val standardDeviation = sqrt(variance)

        // Machine precision detection: If standard deviation is extremely small (< 60 seconds)
        return standardDeviation < 60000.0 && meanDelta > 0
    }

    private fun analyzeVelocityAndDrops(calls: List<Long>): Float {
        if (calls.size >= 2) {
            val timeSpanMinutes = (calls.maxOrNull()!! - calls.minOrNull()!!) / 60000.0
            // If they called 2 or more times within 10 minutes, treat as hostile attack
            if (timeSpanMinutes <= 10.0) return 60f 
        }
        return 0f
    }

    private fun extractLocalCallHistory(cleanNum: String, context: Context): List<Long> {
        val callTimestamps = mutableListOf<Long>()
        try {
            val selection = "${CallLog.Calls.NUMBER} LIKE ?"
            val args = arrayOf("%$cleanNum%")
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE),
                selection,
                args,
                "${CallLog.Calls.DATE} DESC LIMIT 5" // Only care about recent hostile bursts
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    callTimestamps.add(cursor.getLong(0))
                }
            }
        } catch (e: Exception) {}
        return callTimestamps
    }
}
