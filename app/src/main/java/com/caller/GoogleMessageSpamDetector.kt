package com.caller

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

data class SmsSpamVerdict(
    val isPotentialSpam: Boolean = false,
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
    val summary: String = "",
    val provider: String = "",
    val detectedUrl: String = "",
    val googleThreatTypes: List<String> = emptyList()
)

private data class GoogleUrlThreatResult(
    val threatTypes: List<String>,
    val checkedAtMs: Long
) {
    val isUnsafe: Boolean
        get() = threatTypes.isNotEmpty()
}

private const val GOOGLE_MESSAGE_SPAM_TAG = "GOOGLE_SMS_SPAM"
private const val GOOGLE_WEB_RISK_BASE_URL = "https://webrisk.googleapis.com/v1/uris:search"
private const val GOOGLE_WEB_RISK_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
private const val GOOGLE_WEB_RISK_TIMEOUT_MS = 4000
private const val GOOGLE_WEB_RISK_PREFS = "google_message_spam_detector"

// Known Indian DLT 2-letter operator prefixes for verified headers (e.g. AX-HDFCBK, VK-SBIINB)
private val INDIAN_DLT_PREFIXES = setOf(
    "ax", "ad", "am", "ap", "at", "av", "ba", "bp", "bw", "bz", "ca", "cb", "cp", "cs",
    "dm", "dn", "dq", "dr", "fm", "ft", "ga", "hd", "ic", "ik", "im", "in", "ip", "ix",
    "jk", "jm", "jx", "ka", "kd", "kl", "km", "ks", "kx", "la", "ld", "lk", "lm", "mb",
    "md", "mg", "mh", "mk", "ml", "mn", "mp", "mr", "ms", "mt", "mz", "nb", "nl", "no",
    "nr", "nw", "od", "or", "pb", "pd", "pg", "pj", "pn", "pr", "pt", "py", "ra", "rj",
    "rk", "rm", "rn", "sd", "sk", "sm", "sn", "sr", "st", "td", "tg", "tm", "tn", "tr",
    "ts", "tt", "ua", "uk", "up", "ut", "va", "vd", "vi", "vk", "vm", "vn", "vr", "wb",
    "wg", "wx", "xy"
)

// High-risk scam keywords (requires multi-token or context match)
private val SCAM_KYC_KEYWORDS = listOf("kyc update", "account blocked", "account suspended", "pan update", "yono sbi blocked", "hdfc netbanking blocked", "netbanking suspended", "unauthorized transaction click", "update kyc immediately", "sim block")
private val SCAM_UTILITY_KEYWORDS = listOf("electricity bill unpaid", "power will be disconnected", "electricity connection cut", "unpaid bill tonight", "contact electricity officer")
private val SCAM_JOB_KEYWORDS = listOf("earn 5000 daily", "part time job", "like youtube videos", "daily income 2000", "work from home job", "telegram channel")
private val SCAM_LOTTERY_KEYWORDS = listOf("won 25 lakhs", "kbc lottery winner", "lucky draw winner", "claim reward 5000", "cash prize recipient")

// Suspicious URL domain extensions often used in Indian SMS phishing
private val SUSPICIOUS_TLDS = setOf("xyz", "top", "tk", "icu", "cfd", "rest", "work", "click", "link", "site", "online", "cc", "vip", "buzz", "club", "space")

private val SMS_SHORTENER_HOSTS = setOf("bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd", "cutt.ly", "rb.gy", "shorturl.at")

fun analyzeSmsSpam(context: Context, sender: String, body: String): SmsSpamVerdict {
    if (body.isBlank()) return SmsSpamVerdict()

    val reasons = mutableListOf<String>()
    var score = 0
    var detectedUrl = ""
    var googleThreatTypes = emptyList<String>()
    var provider = ""

    val normalizedBody = body.lowercase(Locale.ROOT)
    val senderTrimmed = sender.trim().lowercase(Locale.ROOT)

    // 1. Extract URLs using strict token boundary detector
    val detectedEntities = detectSmsEntities(body)
    val urls = detectedEntities.filter { it.type == SmsEntityType.URL }.map { it.actionValue }

    // 2. Check for Genuine Transactional / OTP Exemptions FIRST (Section 6)
    val isOtpMessage = normalizedBody.contains("otp") || normalizedBody.contains("one time password") || normalizedBody.contains("verification code") || normalizedBody.contains("secret code") || normalizedBody.contains("valid for")
    val isBankTransaction = normalizedBody.contains("debited") || normalizedBody.contains("credited") || normalizedBody.contains("a/c x") || normalizedBody.contains("account bal") || normalizedBody.contains("upi ref") || normalizedBody.contains("vpa") || normalizedBody.contains("salary credited") || normalizedBody.contains("swiggy") || normalizedBody.contains("zomato") || normalizedBody.contains("amazon") || normalizedBody.contains("flipkart")

    val isVerifiedDltHeader = isVerifiedIndianDltHeader(senderTrimmed)

    // If it's a genuine OTP / Bank transaction and has no scam threat phrases, exempt completely
    val hasScamThreatPhrase = SCAM_KYC_KEYWORDS.any { normalizedBody.contains(it) } ||
        SCAM_UTILITY_KEYWORDS.any { normalizedBody.contains(it) } ||
        SCAM_JOB_KEYWORDS.any { normalizedBody.contains(it) } ||
        SCAM_LOTTERY_KEYWORDS.any { normalizedBody.contains(it) } ||
        normalizedBody.contains(".apk")

    if ((isOtpMessage || isBankTransaction || isVerifiedDltHeader) && !hasScamThreatPhrase) {
        return SmsSpamVerdict(
            isPotentialSpam = false,
            score = 0,
            reasons = listOf("verified_transactional_or_otp"),
            summary = "Legitimate Transactional SMS"
        )
    }

    // 3. Rule Evaluation: High-Confidence Threat Patterns
    if (SCAM_KYC_KEYWORDS.any { normalizedBody.contains(it) }) {
        score += 70
        reasons += "bank_kyc_phishing_scam"
    }

    if (SCAM_UTILITY_KEYWORDS.any { normalizedBody.contains(it) }) {
        score += 80
        reasons += "electricity_bill_unpaid_scam"
    }

    if (SCAM_JOB_KEYWORDS.any { normalizedBody.contains(it) }) {
        score += 70
        reasons += "part_time_job_telegram_scam"
    }

    if (SCAM_LOTTERY_KEYWORDS.any { normalizedBody.contains(it) }) {
        score += 75
        reasons += "kbc_lottery_fake_reward_scam"
    }

    if (normalizedBody.contains(".apk") || normalizedBody.contains("download apk")) {
        score += 85
        reasons += "malware_apk_download_threat"
    }

    // 4. URL & Phishing Analysis
    if (urls.isNotEmpty()) {
        urls.forEach { url ->
            val lowerUrl = url.lowercase(Locale.ROOT)
            val domainExt = lowerUrl.substringAfterLast('.', "")
            if (domainExt in SUSPICIOUS_TLDS) {
                score += 35
                detectedUrl = url
                reasons += "suspicious_domain_extension"
            }
            if (isShortenedUrl(url)) {
                score += 15
                if (detectedUrl.isBlank()) detectedUrl = url
                reasons += "shortened_url"
            }
        }
    }

    // 5. Sender Identity Check (Non-DLT suspicious format)
    if (!isVerifiedDltHeader && looksLikeSuspiciousSenderFormat(senderTrimmed)) {
        score += 10
        reasons += "unverified_sender_format"
    }

    // 6. Optional Cached Google Web Risk Check
    urls.forEach { url ->
        val googleResult = lookupGoogleWebRiskCached(context, url) ?: return@forEach
        if (googleResult.isUnsafe) {
            detectedUrl = url
            googleThreatTypes = googleResult.threatTypes
            provider = "Google Web Risk"
            score = maxOf(score, 95)
            reasons += "google_unsafe_url"
            return@forEach
        }
    }

    val finalScore = score.coerceIn(0, 100)
    val isPotentialSpam = finalScore >= 45

    return SmsSpamVerdict(
        isPotentialSpam = isPotentialSpam,
        score = finalScore,
        reasons = reasons.distinct(),
        summary = buildSmsSpamSummary(
            isPotentialSpam = isPotentialSpam,
            provider = provider,
            detectedUrl = detectedUrl,
            reasons = reasons
        ),
        provider = provider,
        detectedUrl = detectedUrl,
        googleThreatTypes = googleThreatTypes
    )
}

private fun isVerifiedIndianDltHeader(sender: String): Boolean {
    val clean = sender.trim().lowercase(Locale.ROOT)
    if (clean.contains('-')) {
        val parts = clean.split('-')
        if (parts.size == 2 && parts[0] in INDIAN_DLT_PREFIXES && parts[1].length in 3..8) {
            return true
        }
    }
    // 4 to 6 digit official shortcodes (e.g. 1900, 1901, 880011)
    val digits = clean.filter { it.isDigit() }
    if (digits.length in 4..6 && clean.all { it.isDigit() }) {
        return true
    }
    return false
}

private fun looksLikeSuspiciousSenderFormat(sender: String): Boolean {
    val clean = sender.trim().lowercase(Locale.ROOT)
    if (clean.isBlank()) return true
    val digits = clean.filter { it.isDigit() }
    // Random 7-9 digit unformatted number or random string
    if (digits.length in 7..9 && clean.all { it.isDigit() }) return true
    return false
}

private fun buildSmsSpamSummary(
    isPotentialSpam: Boolean,
    provider: String,
    detectedUrl: String,
    reasons: List<String>
): String {
    if (!isPotentialSpam) return ""
    return when {
        provider.isNotBlank() && detectedUrl.isNotBlank() -> "Potential spam: unsafe link flagged by $provider"
        reasons.contains("electricity_bill_unpaid_scam") -> "Potential scam: electricity bill disconnection threat"
        reasons.contains("bank_kyc_phishing_scam") -> "Potential scam: bank/KYC account block threat"
        reasons.contains("malware_apk_download_threat") -> "Potential threat: suspicious APK download link"
        reasons.contains("part_time_job_telegram_scam") -> "Potential scam: fake job or Telegram earning offer"
        reasons.contains("kbc_lottery_fake_reward_scam") -> "Potential scam: fake lottery or prize claim"
        reasons.contains("suspicious_domain_extension") -> "Potential spam: suspicious link domain"
        else -> "Potential spam"
    }
}

private fun isShortenedUrl(rawUrl: String): Boolean {
    return runCatching {
        val host = URL(rawUrl).host.lowercase(Locale.ROOT).removePrefix("www.")
        host in SMS_SHORTENER_HOSTS
    }.getOrDefault(false)
}

private fun lookupGoogleWebRiskCached(context: Context, rawUrl: String): GoogleUrlThreatResult? {
    val apiKey = resolveGoogleWebRiskApiKey(context)
    if (apiKey.isBlank()) {
        return null
    }

    val prefs = context.getSharedPreferences(GOOGLE_WEB_RISK_PREFS, Context.MODE_PRIVATE)
    val cacheKey = sha256(rawUrl)
    val now = System.currentTimeMillis()
    prefs.getString(cacheKey, null)?.let { cachedJson ->
        runCatching {
            val cached = JSONObject(cachedJson)
            val checkedAt = cached.optLong("checkedAt", 0L)
            if (now - checkedAt <= GOOGLE_WEB_RISK_CACHE_TTL_MS) {
                val threatArray = cached.optJSONArray("threatTypes") ?: JSONArray()
                val threatTypes = buildList {
                    for (index in 0 until threatArray.length()) {
                        add(threatArray.optString(index))
                    }
                }
                return GoogleUrlThreatResult(threatTypes = threatTypes, checkedAtMs = checkedAt)
            }
        }.onFailure {
            Log.w(GOOGLE_MESSAGE_SPAM_TAG, "Failed to parse cached Google spam result: ${it.message}")
        }
    }

    return fetchGoogleWebRiskResult(apiKey, rawUrl)?.also { result ->
        val cached = JSONObject().apply {
            put("checkedAt", result.checkedAtMs)
            put("threatTypes", JSONArray(result.threatTypes))
        }
        prefs.edit().putString(cacheKey, cached.toString()).apply()
    }
}

private fun fetchGoogleWebRiskResult(apiKey: String, rawUrl: String): GoogleUrlThreatResult? {
    val encodedUrl = URLEncoder.encode(rawUrl, Charsets.UTF_8.name())
    val requestUrl = buildString {
        append(GOOGLE_WEB_RISK_BASE_URL)
        append("?threatTypes=MALWARE")
        append("&threatTypes=SOCIAL_ENGINEERING")
        append("&threatTypes=UNWANTED_SOFTWARE")
        append("&threatTypes=SOCIAL_ENGINEERING_EXTENDED_COVERAGE")
        append("&uri=")
        append(encodedUrl)
        append("&key=")
        append(apiKey)
    }

    return runCatching {
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = GOOGLE_WEB_RISK_TIMEOUT_MS
        connection.readTimeout = GOOGLE_WEB_RISK_TIMEOUT_MS
        connection.useCaches = false
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: return@runCatching null

            val body = BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
            if (body.isBlank()) {
                return@runCatching GoogleUrlThreatResult(emptyList(), System.currentTimeMillis())
            }

            val json = JSONObject(body)
            val threatTypes = json.optJSONObject("threat")
                ?.optJSONArray("threatTypes")
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            add(array.optString(index))
                        }
                    }
                }
                ?: emptyList()

            GoogleUrlThreatResult(
                threatTypes = threatTypes,
                checkedAtMs = System.currentTimeMillis()
            )
        } finally {
            connection.disconnect()
        }
    }.onFailure {
        Log.w(GOOGLE_MESSAGE_SPAM_TAG, "Google Web Risk lookup failed: ${it.message}")
    }.getOrNull()
}

private fun resolveGoogleWebRiskApiKey(context: Context): String {
    return runCatching {
        context.getString(R.string.google_webrisk_api_key).trim()
    }.getOrDefault("")
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append("%02x".format(byte))
        }
    }
}
