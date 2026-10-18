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

        if (cleanNum.isEmpty()) {
            return ForensicSpamSignal(
                finalThreatScore = 0f,
                isMachineCadence = false,
                isWangiriFraud = false,
                isSpoofedEntropy = false,
                threatSignatures = emptyList()
            )
        }

        // 1. SHANNON ENTROPY (Fixed range: 10 distinct digits = 3.32)
        // Only flag extreme anomalies (e.g. 0000000000 where entropy is 0.0)
        val entropy = calculateShannonEntropy(cleanNum)
        val isSpoofed = cleanNum.length >= 7 && (entropy < 0.5 || entropy > 3.8)
        if (isSpoofed) {
            baseThreatScore += 25f
            signatures.add("ANOMALOUS_ENTROPY_SPOOFING")
        }

        // 2. WANGIRI FRAUD (High-risk exotic toll prefixes)
        val isWangiri = detectWangiriSignature(phoneNumber)
        if (isWangiri) {
            baseThreatScore += 80f
            signatures.add("WANGIRI_FRAUD_SIGNATURE")
        }

        // 3. TELECOM FORMAT STRICTNESS (Standard numbers are 10 to 15 digits)
        if (cleanNum.length < 3) {
            baseThreatScore += 30f
            signatures.add("NON_STANDARD_LENGTH")
        }

        // 4. ROBOCALLER CADENCE (Requires at least 4 rapid automated calls)
        val callHistory = extractLocalCallHistory(cleanNum, context)
        val isMachine = detectMachineCadence(callHistory)
        if (isMachine) {
            baseThreatScore += 75f
            signatures.add("ROBOCALLER_MACHINE_CADENCE")
        }

        // 5. VELOCITY (High frequency burst: >= 5 calls within 2 minutes)
        val behaviorThreat = analyzeVelocityAndDrops(callHistory)
        baseThreatScore += behaviorThreat

        if (behaviorThreat > 0f) {
            signatures.add("AGGRESSIVE_VELOCITY_OR_DROPS")
        }

        return ForensicSpamSignal(
            finalThreatScore = baseThreatScore.coerceIn(0f, 100f),
            isMachineCadence = isMachine,
            isWangiriFraud = isWangiri,
            isSpoofedEntropy = isSpoofed,
            threatSignatures = signatures
        )
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
        val highRiskPrefixes = listOf("+247", "+252", "+269", "+371", "+375", "+381", "+685", "+678", "+223", "+225", "+881", "+882")
        return highRiskPrefixes.any { number.startsWith(it) }
    }

    private fun detectMachineCadence(calls: List<Long>): Boolean {
        if (calls.size < 4) return false

        val sortedCalls = calls.sorted()
        val deltas = mutableListOf<Long>()
        for (i in 1 until sortedCalls.size) {
            deltas.add(sortedCalls[i] - sortedCalls[i - 1])
        }

        val meanDelta = deltas.average()
        val variance = deltas.sumOf { (it - meanDelta).pow(2) } / deltas.size
        val standardDeviation = sqrt(variance)

        // Strict machine cadence: exact interval timing with standard deviation < 1 second (1000ms)
        return standardDeviation < 1000.0 && meanDelta > 0
    }

    private fun analyzeVelocityAndDrops(calls: List<Long>): Float {
        if (calls.size >= 5) {
            val timeSpanMinutes = (calls.maxOrNull()!! - calls.minOrNull()!!) / 60000.0
            if (timeSpanMinutes <= 2.0) return 50f
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
                "${CallLog.Calls.DATE} DESC LIMIT 5"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    callTimestamps.add(cursor.getLong(0))
                }
            }
        } catch (e: Exception) {}
        return callTimestamps
    }
}
