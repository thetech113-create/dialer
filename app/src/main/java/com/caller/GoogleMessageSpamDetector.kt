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

private val SMS_URL_REGEX = Regex("""(?i)\b((?:https?://|www\.)[^\s<>()]+)""")
private val SMS_SPAM_KEYWORDS = listOf(
    "verify account",
    "account suspended",
    "kyc",
    "urgent action",
    "click link",
    "claim reward",
    "claim prize",
    "bank update",
    "upi",
    "loan approved",
    "gift",
    "winner",
    "refund pending",
    "limited time"
)
private val SMS_SHORTENER_HOSTS = setOf(
    "bit.ly",
    "tinyurl.com",
    "goo.gl",
    "t.co",
    "is.gd",
    "cutt.ly",
    "rb.gy",
    "shorturl.at"
)

fun analyzeSmsSpam(context: Context, sender: String, body: String): SmsSpamVerdict {
    if (body.isBlank()) return SmsSpamVerdict()

    val reasons = mutableListOf<String>()
    var score = 0
    var detectedUrl = ""
    var googleThreatTypes = emptyList<String>()
    var provider = ""

    val normalizedBody = body.lowercase(Locale.getDefault())
    val urls = extractUrlsFromSms(body)
    val keywordHits = SMS_SPAM_KEYWORDS.count { normalizedBody.contains(it) }

    if (keywordHits >= 2 && urls.isNotEmpty()) {
        score += 40
        reasons += "phishing_language_with_link"
    } else if (keywordHits >= 3) {
        score += 25
        reasons += "aggressive_spam_language"
    }

    if (looksLikeSuspiciousSender(sender)) {
        score += 10
        reasons += "suspicious_sender_format"
    }

    if (urls.size >= 2) {
        score += 10
        reasons += "multiple_links"
    }

    urls.firstOrNull { isShortenedUrl(it) }?.let {
        score += 10
        reasons += "shortened_link"
    }

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

private fun buildSmsSpamSummary(
    isPotentialSpam: Boolean,
    provider: String,
    detectedUrl: String,
    reasons: List<String>
): String {
    if (!isPotentialSpam) return ""
    return when {
        provider.isNotBlank() && detectedUrl.isNotBlank() -> "Potential spam: unsafe link flagged by $provider"
        reasons.contains("phishing_language_with_link") -> "Potential spam: phishing-style message with link"
        reasons.contains("aggressive_spam_language") -> "Potential spam: suspicious financial or prize language"
        else -> "Potential spam"
    }
}

private fun extractUrlsFromSms(body: String): List<String> {
    return SMS_URL_REGEX.findAll(body)
        .map { match ->
            var url = match.groupValues[1].trim().trimEnd('.', ',', ';', ':', '!')
            if (url.startsWith("www.", ignoreCase = true)) {
                url = "https://$url"
            }
            url
        }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

private fun looksLikeSuspiciousSender(sender: String): Boolean {
    val trimmed = sender.trim()
    val digitsOnly = trimmed.filter { it.isDigit() }
    if (trimmed.isBlank()) return false
    if (digitsOnly.length in 4..7) return true
    if (trimmed.any { it.isLetter() } && trimmed.any { it.isDigit() } && trimmed.length <= 10) return true
    return false
}

private fun isShortenedUrl(rawUrl: String): Boolean {
    return runCatching {
        val host = URL(rawUrl).host.lowercase(Locale.getDefault()).removePrefix("www.")
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
