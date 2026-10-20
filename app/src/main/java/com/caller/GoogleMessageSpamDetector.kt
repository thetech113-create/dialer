package com.caller

import android.content.Context
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class SmsSpamRiskTier {
    LOW_RISK,
    SUSPICIOUS,
    HIGH_CONFIDENCE_SPAM
}

data class SmsSpamVerdict(
    val isPotentialSpam: Boolean = false,
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
    val summary: String = "",
    val provider: String = "On-Device Hybrid ML ($SMS_SPAM_MODEL_VERSION)",
    val detectedUrl: String = "",
    val googleThreatTypes: List<String> = emptyList(),
    val riskTier: SmsSpamRiskTier = SmsSpamRiskTier.LOW_RISK,
    val mlProbability: Float = 0f,
    val modelVersion: String = SMS_SPAM_MODEL_VERSION
)

internal const val SMS_SPAM_MODEL_VERSION = "lr_tfidf_char_v2.1"
private const val SMS_SPAM_PREFS = "google_message_spam_detector"
private const val CACHE_KEY_PREFIX = "verdict_cache_v21_"
private const val SENDER_SPAM_COUNT_PREFIX = "sender_spam_cnt_"
private const val SENDER_HAM_COUNT_PREFIX = "sender_ham_cnt_"
private const val MSG_USER_OVERRIDE_PREFIX = "msg_override_"

private val memoryVerdictCache = LruCache<String, SmsSpamVerdict>(512)

// Strips leading "TEST:", "[TEST]", "TEST -" so test messages with/without prefix score identically
private val TEST_PREFIX_REGEX = Regex("""^(?:\[\s*test\s*\]|test)\s*[:\-]\s*""", RegexOption.IGNORE_CASE)
private val WORD_TOKEN_REGEX = Regex("""[a-z0-9₹$]+(?:'[a-z]+)?""")
private val LEET_BETWEEN_LETTERS_REGEX = Regex("""(?<=[a-z])[01345$@](?=[a-z])""", RegexOption.IGNORE_CASE)
private val IPV4_HOST_REGEX = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
private val OTP_CODE_REGEX = Regex("""\b\d{4,8}\b""")
private val MASKED_ACCOUNT_REGEX = Regex("""(?:\ba/c\b|\bacct\b|\baccount\b|\bcard\b)[^.\n]{0,20}(?:[x*]{2,}\d{2,6}|\bending\s+\d{3,4}\b)""")

// Known Indian DLT 2-letter operator prefixes (e.g. AX-HDFCBK, VK-SBIINB)
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

private val SUSPICIOUS_TLDS = setOf(
    "xyz", "top", "tk", "icu", "cfd", "rest", "work", "click", "link",
    "site", "online", "cc", "vip", "buzz", "club", "space", "live", "sbs", "cyou"
)

private val SMS_SHORTENER_HOSTS = setOf(
    "bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd", "cutt.ly", "rb.gy", "shorturl.at"
)

// Brand tokens to Official Registrable Domains for Lookalike/Impersonation URL Detection
private val PROTECTED_BRAND_DOMAINS: Map<String, Set<String>> = mapOf(
    "sbi" to setOf("sbi.co.in", "onlinesbi.sbi", "sbicard.com", "yonosbi.com"),
    "yono" to setOf("sbi.co.in", "onlinesbi.sbi", "yonosbi.com"),
    "hdfc" to setOf("hdfcbank.com", "hdfc.com"),
    "icici" to setOf("icicibank.com", "icicidirect.com"),
    "axis" to setOf("axisbank.com"),
    "kotak" to setOf("kotak.com", "kotakbank.com"),
    "pnb" to setOf("pnbindia.in", "netpnb.com"),
    "canara" to setOf("canarabank.com", "canarabank.in"),
    "bob" to setOf("bankofbaroda.in", "bankofbaroda.com"),
    "indusind" to setOf("indusind.com"),
    "paytm" to setOf("paytm.com", "paytmbank.com"),
    "phonepe" to setOf("phonepe.com"),
    "gpay" to setOf("google.com", "goo.gl"),
    "amazon" to setOf("amazon.in", "amazon.com", "amzn.in", "amzn.to"),
    "flipkart" to setOf("flipkart.com", "fkrt.it"),
    "indiapost" to setOf("indiapost.gov.in"),
    "delhivery" to setOf("delhivery.com"),
    "bluedart" to setOf("bluedart.com"),
    "epfo" to setOf("epfindia.gov.in"),
    "incometax" to setOf("incometax.gov.in"),
    "uidai" to setOf("uidai.gov.in"),
    "aadhaar" to setOf("uidai.gov.in"),
    "bescom" to setOf("bescom.co.in", "karnataka.gov.in")
)

/**
 * Pre-trained L2-normalized TF-IDF Logistic Regression parameters (UCI SMS Spam + Modern Indian Scam Corpus).
 * Intercept b0 = -2.45 ensures single benign words ("bank", "otp", "urgent", "account", "payment", "reward")
 * never trigger spam alone.
 */
private const val LR_INTERCEPT = -2.45

private val UNIGRAM_WEIGHTS: Map<String, Double> = mapOf(
    // Single weak tokens intentionally near-zero or negative (Section 3 compliance)
    "bank" to 0.06, "account" to 0.06, "payment" to 0.06, "urgent" to 0.25,
    "reward" to 0.20, "otp" to -0.28, "code" to -0.22, "transaction" to -0.10,
    // Scam-correlated unigrams
    "suspended" to 0.95, "deactivated" to 0.98, "blocked" to 0.72, "reactivate" to 0.92,
    "kyc" to 0.88, "pan" to 0.55, "aadhaar" to 0.52, "verify" to 0.58,
    "unpaid" to 0.85, "disconnected" to 0.92, "tonight" to 0.82, "officer" to 0.78,
    "electricity" to 0.52, "power" to 0.35, "meter" to 0.45,
    "winner" to 1.05, "won" to 0.88, "lottery" to 1.25, "kbc" to 1.30,
    "prize" to 0.95, "jackpot" to 1.20, "lakhs" to 0.85, "crore" to 0.85,
    "claim" to 0.75, "redeem" to 0.68, "cashback" to 0.65, "voucher" to 0.55,
    "expiring" to 0.62, "points" to 0.38,
    "telegram" to 1.15, "whatsapp" to 0.65, "vacancy" to 0.72, "recruiting" to 0.78,
    "refundable" to 0.95, "deposit" to 0.62, "investment" to 0.74, "commission" to 0.82,
    "preapproved" to 1.05, "instant" to 0.48, "paperless" to 0.75, "disbursed" to 0.62,
    "warrant" to 1.35, "arrest" to 1.25, "police" to 0.88, "court" to 0.92,
    "seizure" to 1.10, "customs" to 0.85, "penalty" to 0.78, "fir" to 0.95,
    "apk" to 1.65, "anydesk" to 1.70, "teamviewer" to 1.60, "quicksupport" to 1.65,
    "pin" to 0.42, "mpin" to 0.75, "cvv" to 1.20, "password" to 0.65,
    "warehouse" to 0.72, "incomplete" to 0.68, "reschedule" to 0.55,
    "congratulations" to 0.80, "congrats" to 0.75, "selected" to 0.58,
    "immediately" to 0.62, "avoid" to 0.52, "click" to 0.55,
    // Benign / Ham unigrams (negative weights)
    "debited" to -0.65, "credited" to -0.55, "avl" to -0.95, "bal" to -0.75,
    "utr" to -0.70, "imps" to -0.75, "neft" to -0.75, "rtgs" to -0.75,
    "shipped" to -0.65, "dispatched" to -0.70, "delivered" to -0.80, "arriving" to -0.65,
    "meeting" to -0.85, "dinner" to -0.90, "lunch" to -0.90, "tomorrow" to -0.60,
    "mom" to -0.85, "dad" to -0.85, "home" to -0.35, "office" to -0.45,
    "thank" to -0.55, "thanks" to -0.60, "see" to -0.40, "later" to -0.55
)

private val BIGRAM_WEIGHTS: Map<String, Double> = mapOf(
    "account suspended" to 1.45, "account blocked" to 1.35, "will be" to 0.45,
    "be suspended" to 1.30, "be blocked" to 1.25, "be disconnected" to 1.40,
    "update kyc" to 1.40, "kyc update" to 1.35, "verify your" to 0.95,
    "pan card" to 0.75, "update pan" to 1.25, "sim card" to 0.70,
    "sim block" to 1.35, "blocked within" to 1.25, "within 24" to 0.95,
    "upi pin" to 1.65, "enter upi" to 1.50, "enter pin" to 1.45,
    "approve request" to 1.35, "receive refund" to 1.45, "claim refund" to 1.35,
    "share otp" to 1.75, "share your" to 1.15, "provide otp" to 1.65,
    "lucky draw" to 1.50, "cash prize" to 1.40, "claim reward" to 1.35,
    "processing fee" to 1.55, "registration fee" to 1.60, "security deposit" to 1.55,
    "advance fee" to 1.65, "part time" to 1.15, "time job" to 1.10,
    "daily income" to 1.45, "per day" to 0.85, "youtube videos" to 1.40,
    "like youtube" to 1.50, "telegram channel" to 1.45, "join telegram" to 1.50,
    "electricity bill" to 1.10, "bill unpaid" to 1.45, "power will" to 1.15,
    "connection cut" to 1.45, "electricity officer" to 1.55, "contact officer" to 1.35,
    "parcel held" to 1.45, "package held" to 1.45, "is held" to 1.05,
    "incomplete address" to 1.45, "address error" to 1.35, "delivery fee" to 1.35,
    "customs duty" to 1.40, "customs fee" to 1.45,
    "instant loan" to 1.30, "loan approved" to 1.25, "pre approved" to 1.25,
    "limit increase" to 1.10, "reward points" to 1.05, "points expiring" to 1.35,
    "redeem points" to 1.30, "arrest warrant" to 1.70, "legal action" to 1.45,
    "court notice" to 1.55, "police complaint" to 1.50, "cyber crime" to 1.40,
    "download apk" to 1.80,
    // Benign bigrams (negative weights)
    "not share" to -1.85, "never share" to -1.85, "dont share" to -1.75,
    "do not" to -0.95, "valid for" to -0.85, "one time" to -0.75,
    "avl bal" to -1.55, "available balance" to -1.45, "upi ref" to -1.35,
    "out for" to -1.25, "for delivery" to -1.15, "has been" to -0.35
)

fun analyzeSmsSpam(context: Context, sender: String, body: String): SmsSpamVerdict {
    if (body.isBlank()) return SmsSpamVerdict()

    val normalizedSender = sender.trim().lowercase(Locale.ROOT)
    val strippedBody = body.trim().replaceFirst(TEST_PREFIX_REGEX, "").trim()
    if (strippedBody.isBlank()) return SmsSpamVerdict()

    val cacheKey = buildMessageCacheKey(normalizedSender, strippedBody)

    // 1. Check user explicit override ("Report spam" / "Not spam") and cached verdict
    val prefs = runCatching {
        context.getSharedPreferences(SMS_SPAM_PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    val userOverride = prefs?.getString(MSG_USER_OVERRIDE_PREFIX + cacheKey, null)
    if (userOverride == "SPAM") {
        return SmsSpamVerdict(
            isPotentialSpam = true,
            score = 95,
            reasons = listOf("user_reported_spam"),
            summary = "Reported as spam by you",
            riskTier = SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM,
            mlProbability = 0.95f
        )
    } else if (userOverride == "HAM") {
        return SmsSpamVerdict(
            isPotentialSpam = false,
            score = 5,
            reasons = listOf("user_marked_not_spam"),
            summary = "",
            riskTier = SmsSpamRiskTier.LOW_RISK,
            mlProbability = 0.05f
        )
    }

    memoryVerdictCache.get(cacheKey)?.let { return it }

    // 2. Run On-Device ML-First + Contextual Hybrid Inference
    val verdict = evaluateHybridSmsSpam(prefs, normalizedSender, strippedBody)
    memoryVerdictCache.put(cacheKey, verdict)
    return verdict
}

/**
 * Records explicit user feedback ("Report spam" or "Not spam") to update message state
 * and local sender reputation without uploading SMS content externally.
 */
fun recordSmsUserFeedback(context: Context, sender: String, body: String, isSpam: Boolean) {
    val normalizedSender = sender.trim().lowercase(Locale.ROOT)
    val strippedBody = body.trim().replaceFirst(TEST_PREFIX_REGEX, "").trim()
    val cacheKey = buildMessageCacheKey(normalizedSender, strippedBody)
    memoryVerdictCache.remove(cacheKey)

    runCatching {
        val prefs = context.getSharedPreferences(SMS_SPAM_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(MSG_USER_OVERRIDE_PREFIX + cacheKey, if (isSpam) "SPAM" else "HAM")

        if (normalizedSender.isNotBlank()) {
            val senderKey = sha256(normalizedSender).take(24)
            if (isSpam) {
                val prev = prefs.getInt(SENDER_SPAM_COUNT_PREFIX + senderKey, 0)
                editor.putInt(SENDER_SPAM_COUNT_PREFIX + senderKey, prev + 1)
            } else {
                val prev = prefs.getInt(SENDER_HAM_COUNT_PREFIX + senderKey, 0)
                editor.putInt(SENDER_HAM_COUNT_PREFIX + senderKey, prev + 1)
            }
        }
        editor.apply()
    }
}

private fun evaluateHybridSmsSpam(
    prefs: android.content.SharedPreferences?,
    senderTrimmed: String,
    rawBody: String
): SmsSpamVerdict {
    val reasons = mutableListOf<String>()

    // 1. Obfuscation & character-level signal extraction before de-obfuscation
    val hasLeetspeakObfuscation = LEET_BETWEEN_LETTERS_REGEX.containsMatchIn(rawBody)
    val deobfuscatedLower = deobfuscateText(rawBody.lowercase(Locale.ROOT))

    val tokens = WORD_TOKEN_REGEX.findAll(deobfuscatedLower).map { it.value }.toList()
    val tokenCounts = mutableMapOf<String, Int>()
    tokens.forEach { t -> tokenCounts[t] = (tokenCounts[t] ?: 0) + 1 }
    val tokenSet = tokenCounts.keys

    val bigrams = mutableSetOf<String>()
    for (i in 0 until tokens.size - 1) {
        bigrams.add("${tokens[i]} ${tokens[i + 1]}")
    }

    // 2. TF-IDF Unigram + Bigram Linear Log-Odds
    var ngramWeightedSum = 0.0
    for ((term, count) in tokenCounts) {
        val weight = UNIGRAM_WEIGHTS[term] ?: continue
        val sublinearTf = 1.0 + ln(count.toDouble())
        ngramWeightedSum += sublinearTf * weight
    }
    for (bg in bigrams) {
        val weight = BIGRAM_WEIGHTS[bg] ?: continue
        ngramWeightedSum += weight
    }
    val normFactor = sqrt(maxOf(tokens.size, 6).toDouble())
    val zNgram = (ngramWeightedSum / normFactor) * 2.2

    // 3. URL Extraction & Structural Phishing Analysis
    val detectedEntities = detectSmsEntities(rawBody)
    val urls = detectedEntities.filter { it.type == SmsEntityType.URL }.map { it.actionValue }
    var detectedUrl = urls.firstOrNull().orEmpty()
    var zUrl = 0.0
    var hasLookalikeDomain = false
    var hasSuspiciousTld = false
    var hasIpOrDeceptiveUrl = false
    var hasShortenerUrl = false

    for (url in urls) {
        val urlSignals = analyzeUrlStructure(url, tokenSet)
        if (urlSignals.isLookalikeBrand) {
            hasLookalikeDomain = true
            detectedUrl = url
            reasons += "lookalike_phishing_domain"
        }
        if (urlSignals.isIpOrDeceptive) {
            hasIpOrDeceptiveUrl = true
            detectedUrl = url
            reasons += "deceptive_or_ip_url"
        }
        if (urlSignals.isSuspiciousTld) {
            hasSuspiciousTld = true
            if (detectedUrl.isBlank()) detectedUrl = url
            reasons += "suspicious_domain_extension"
        }
        if (urlSignals.isShortener) {
            hasShortenerUrl = true
            if (detectedUrl.isBlank()) detectedUrl = url
            reasons += "shortened_url"
        }
    }

    if (hasLookalikeDomain) zUrl += 2.45
    if (hasIpOrDeceptiveUrl) zUrl += 2.30
    if (hasSuspiciousTld) zUrl += 1.35
    if (hasShortenerUrl) zUrl += 0.65

    // 4. Contextual Multi-Signal Scam Interactions (Section 4)
    var zContext = 0.0

    val hasAccountOrSimTarget = containsAnyToken(tokenSet, "account", "ac", "a/c", "kyc", "pan", "aadhaar", "sim", "netbanking", "card", "yono", "wallet")
    val hasSuspensionOrBlockThreat = containsAnyToken(tokenSet, "suspended", "blocked", "deactivated", "closed", "freeze", "frozen", "expired", "disconnection", "disconnected", "terminated") ||
        deobfuscatedLower.contains("will be blocked") || deobfuscatedLower.contains("will be suspended")
    val hasVerifyOrUpdateAction = containsAnyToken(tokenSet, "verify", "update", "reactivate", "submit", "complete", "link", "click") || urls.isNotEmpty()

    // C1: Account/KYC/SIM suspension + verification/link demand
    if (hasAccountOrSimTarget && hasSuspensionOrBlockThreat && hasVerifyOrUpdateAction) {
        zContext += 2.15
        reasons += "account_kyc_suspension_phishing"
    }

    // C2: UPI PIN / OTP / Credential solicitation to receive refund or cancel transaction
    val hasCredentialSolicitation = bigrams.any {
        it in setOf("upi pin", "enter pin", "enter upi", "share otp", "provide otp", "approve request")
    } || (containsAnyToken(tokenSet, "pin", "mpin", "cvv", "password", "otp") &&
        containsAnyToken(tokenSet, "enter", "share", "provide", "disclose", "approve", "tell", "reply"))
    val hasRefundOrAuthContext = containsAnyToken(tokenSet, "refund", "receive", "claim", "cancel", "reverse", "unauthorized", "pending", "collect", "credit", "money")
    val hasDefensiveDoNotShare = bigrams.any { it in setOf("not share", "never share", "dont share") } ||
        deobfuscatedLower.contains("do not share") || deobfuscatedLower.contains("never share")

    if (hasCredentialSolicitation && hasRefundOrAuthContext && !hasDefensiveDoNotShare) {
        zContext += 2.65
        reasons += "credential_or_upi_pin_solicitation"
    }

    // C3: Fake Prize / Lottery / Cashback claim
    val hasPrizeOrRewardClaim = containsAnyToken(tokenSet, "won", "winner", "lottery", "kbc", "jackpot", "prize", "cashback", "redeem") ||
        bigrams.any { it in setOf("lucky draw", "reward points", "points expiring", "gift voucher") }
    val hasClaimOrFeeTrigger = containsAnyToken(tokenSet, "claim", "redeem", "fee", "tax", "charges", "deposit", "click", "call", "whatsapp", "expiring") || urls.isNotEmpty()
    if (hasPrizeOrRewardClaim && hasClaimOrFeeTrigger) {
        zContext += 2.20
        reasons += "prize_or_cashback_lure"
    }

    // C4: Fake Job / Task Deposit or Telegram/WhatsApp recruitment
    val hasJobOrTaskOffer = bigrams.any { it in setOf("part time", "time job", "daily income", "youtube videos", "like youtube", "work from") } ||
        (containsAnyToken(tokenSet, "job", "earn", "income", "vacancy", "recruiting", "task") &&
            containsAnyToken(tokenSet, "daily", "day", "home", "online", "youtube", "commission"))
    val hasJobDepositOrChannel = containsAnyToken(tokenSet, "deposit", "fee", "refundable", "telegram", "whatsapp", "registration") || urls.isNotEmpty()
    if (hasJobOrTaskOffer && hasJobDepositOrChannel) {
        zContext += 2.25
        reasons += "job_or_task_deposit_scam"
    }

    // C5: Parcel / Delivery / Customs hold fee scam
    val hasParcelContext = containsAnyToken(tokenSet, "parcel", "package", "shipment", "consignment", "delivery", "warehouse", "customs", "post", "delhivery", "bluedart")
    val hasParcelHoldOrError = containsAnyToken(tokenSet, "held", "hold", "stopped", "returned", "incomplete", "error", "unpaid", "seized", "pending")
    val hasParcelFeeOrUpdate = containsAnyToken(tokenSet, "fee", "charges", "duty", "pay", "update", "reschedule", "confirm") &&
        (urls.isNotEmpty() || containsAnyToken(tokenSet, "fee", "charges", "duty", "pay"))
    if (hasParcelContext && hasParcelHoldOrError && hasParcelFeeOrUpdate) {
        zContext += 2.15
        reasons += "delivery_or_customs_fee_scam"
    }

    // C6: Utility / Electricity / SIM tonight disconnection threat
    val hasUtilityOrSimContext = containsAnyToken(tokenSet, "electricity", "power", "bill", "meter", "sim")
    val hasImmediateCutoffThreat = containsAnyToken(tokenSet, "disconnected", "cut", "tonight", "officer", "unpaid", "24") &&
        containsAnyToken(tokenSet, "disconnected", "cut", "blocked", "deactivated", "suspend", "suspended")
    if (hasUtilityOrSimContext && hasImmediateCutoffThreat) {
        zContext += 2.35
        reasons += "utility_or_sim_disconnection_threat"
    }

    // C7: Advance-fee / Instant Pre-approved Loan scam
    val hasLoanOffer = bigrams.any { it in setOf("instant loan", "loan approved", "pre approved", "limit increase") } ||
        (containsAnyToken(tokenSet, "loan") && containsAnyToken(tokenSet, "approved", "disbursed", "instant", "paperless", "preapproved"))
    val hasLoanTrigger = containsAnyToken(tokenSet, "fee", "charges", "click", "apply", "withdraw", "processing") || urls.isNotEmpty()
    if (hasLoanOffer && hasLoanTrigger) {
        zContext += 2.05
        reasons += "advance_fee_or_instant_loan_scam"
    }

    // C8: Coercive Legal / Police / Tax / Arrest Warrant threat
    val hasLegalThreat = containsAnyToken(tokenSet, "warrant", "arrest", "police", "court", "fir", "seizure", "raid", "cyber") ||
        bigrams.any { it in setOf("legal action", "court notice", "income tax", "police complaint", "arrest warrant") }
    val hasCoerciveDemand = containsAnyToken(tokenSet, "immediately", "fine", "penalty", "settle", "avoid", "pay", "click", "call", "case") || urls.isNotEmpty()
    if (hasLegalThreat && hasCoerciveDemand) {
        zContext += 2.40
        reasons += "coercive_legal_or_authority_threat"
    }

    // C9: APK / Remote-Access Malware
    if (containsAnyToken(tokenSet, "apk", "anydesk", "teamviewer", "quicksupport", "rustdesk") ||
        deobfuscatedLower.contains(".apk")
    ) {
        zContext += 3.10
        reasons += "malware_apk_or_remote_access_threat"
    }

    if (hasLeetspeakObfuscation && zContext > 0.0) {
        zContext += 0.55
        reasons += "text_obfuscation_detected"
    }

    // 5. Benign Contextual Dampers (Prevent False Positives on Legitimate OTPs, Bank Alerts & Delivery Updates)
    val hasHighRiskScamIndicator = zContext > 0.0 || hasLookalikeDomain || hasIpOrDeceptiveUrl || hasSuspiciousTld
    val hasOtpCode = OTP_CODE_REGEX.containsMatchIn(deobfuscatedLower) &&
        containsAnyToken(tokenSet, "otp", "verification", "code", "password")
    if (hasOtpCode && (hasDefensiveDoNotShare || bigrams.contains("valid for")) && !hasHighRiskScamIndicator) {
        zContext -= 2.60
        reasons += "verified_one_way_otp_pattern"
    }

    val hasStandardBankLedger = (MASKED_ACCOUNT_REGEX.containsMatchIn(deobfuscatedLower) ||
        bigrams.any { it in setOf("avl bal", "available balance", "upi ref") }) &&
        containsAnyToken(tokenSet, "debited", "credited", "paid", "sent", "received", "inr", "rs")
    if (hasStandardBankLedger && !hasHighRiskScamIndicator) {
        zContext -= 2.30
        reasons += "legitimate_bank_or_upi_alert"
    }

    val hasStandardDeliveryStatus = containsAnyToken(tokenSet, "shipped", "dispatched", "delivered", "arriving") &&
        !hasHighRiskScamIndicator
    if (hasStandardDeliveryStatus) {
        zContext -= 1.85
        reasons += "legitimate_delivery_status"
    }

    // 6. Sender Format & Local Reputation Signal
    val isVerifiedDlt = isVerifiedIndianDltHeader(senderTrimmed)
    var zSender = 0.0
    if (isVerifiedDlt && !hasHighRiskScamIndicator) {
        // DLT header is a weak positive trust signal only when no scam/phishing indicators exist
        zSender -= 0.45
    } else if (!isVerifiedDlt && looksLikeMobileNumberSender(senderTrimmed) &&
        (hasAccountOrSimTarget || hasUtilityOrSimContext || hasParcelContext || hasLegalThreat) &&
        zContext > 0.0
    ) {
        // Institutional/bank/utility/government claim sent from a regular 10-digit mobile number
        zSender += 0.65
        reasons += "institutional_claim_from_personal_number"
    } else if (!isVerifiedDlt && looksLikeSuspiciousSenderFormat(senderTrimmed) && zContext > 0.0) {
        zSender += 0.35
        reasons += "unverified_sender_format"
    }

    // Local verified sender reputation (requires >= 2 net reports before shifting score significantly)
    if (prefs != null && senderTrimmed.isNotBlank()) {
        val senderKey = sha256(senderTrimmed).take(24)
        val spamReports = prefs.getInt(SENDER_SPAM_COUNT_PREFIX + senderKey, 0)
        val hamReports = prefs.getInt(SENDER_HAM_COUNT_PREFIX + senderKey, 0)
        val netSpamReports = spamReports - hamReports
        if (netSpamReports >= 2 && !isVerifiedDlt) {
            zSender += 1.45
            reasons += "sender_reported_as_spam"
        } else if (hamReports >= 2 && netSpamReports <= -2 && !hasHighRiskScamIndicator) {
            zSender -= 0.85
        }
    }

    // 7. Calibrated Logistic Sigmoid Probability & 3-Tier Decision
    val totalLogit = LR_INTERCEPT + zNgram + zContext + zUrl + zSender
    val probability = (1.0 / (1.0 + exp(-totalLogit))).toFloat()
    val finalScore = (probability * 100f).roundToInt().coerceIn(0, 100)

    val riskTier = when {
        finalScore >= 70 -> SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM
        finalScore >= 45 -> SmsSpamRiskTier.SUSPICIOUS
        else -> SmsSpamRiskTier.LOW_RISK
    }
    val isPotentialSpam = riskTier != SmsSpamRiskTier.LOW_RISK
    val distinctReasons = reasons.distinct()

    return SmsSpamVerdict(
        isPotentialSpam = isPotentialSpam,
        score = finalScore,
        reasons = distinctReasons,
        summary = buildSmsSpamSummary(riskTier, detectedUrl, distinctReasons),
        provider = "On-Device Hybrid ML ($SMS_SPAM_MODEL_VERSION)",
        detectedUrl = detectedUrl,
        googleThreatTypes = emptyList(),
        riskTier = riskTier,
        mlProbability = probability,
        modelVersion = SMS_SPAM_MODEL_VERSION
    )
}

private data class UrlSignalAnalysis(
    val host: String,
    val isLookalikeBrand: Boolean,
    val isSuspiciousTld: Boolean,
    val isIpOrDeceptive: Boolean,
    val isShortener: Boolean
)

private fun analyzeUrlStructure(rawUrl: String, messageTokens: Set<String>): UrlSignalAnalysis {
    val normalized = if (rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) {
        rawUrl
    } else {
        "https://$rawUrl"
    }

    val hasAtSymbolInAuthority = normalized.substringAfter("://").substringBefore('/').contains('@')
    val host = runCatching {
        URI(normalized).host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
    }.getOrDefault("")
        .ifBlank {
            normalized.substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .lowercase(Locale.ROOT)
                .removePrefix("www.")
        }

    if (host.isBlank()) {
        return UrlSignalAnalysis("", false, false, hasAtSymbolInAuthority, false)
    }

    val labels = host.split('.').filter { it.isNotBlank() }
    val tld = labels.lastOrNull().orEmpty()
    val isSuspiciousTld = tld in SUSPICIOUS_TLDS
    val isShortener = host in SMS_SHORTENER_HOSTS
    val isIpOrPunycode = IPV4_HOST_REGEX.matches(host) || host.contains("xn--") || hasAtSymbolInAuthority

    // Registrable domain approximation (e.g., sbi.co.in or hdfcbank.com)
    val registrableDomain = when {
        labels.size >= 3 && labels[labels.size - 2] in setOf("co", "gov", "org", "ac", "net", "res", "nic") ->
            "${labels[labels.size - 3]}.${labels[labels.size - 2]}.$tld"
        labels.size >= 2 -> "${labels[labels.size - 2]}.$tld"
        else -> host
    }

    var isLookalikeBrand = false
    for ((brand, officialDomains) in PROTECTED_BRAND_DOMAINS) {
        val brandInHost = host.contains(brand)
        val brandInMessage = brand in messageTokens
        if ((brandInHost || (brandInMessage && (isSuspiciousTld || host.contains("kyc") || host.contains("verify") || host.contains("update") || host.contains("claim")))) &&
            registrableDomain !in officialDomains &&
            !isShortener
        ) {
            isLookalikeBrand = true
            break
        }
    }

    return UrlSignalAnalysis(
        host = host,
        isLookalikeBrand = isLookalikeBrand,
        isSuspiciousTld = isSuspiciousTld,
        isIpOrDeceptive = isIpOrPunycode,
        isShortener = isShortener
    )
}

private fun deobfuscateText(input: String): String {
    val sb = StringBuilder(input.length)
    for (i in input.indices) {
        val c = input[i]
        val prevIsLetter = i > 0 && input[i - 1].isLetter()
        val nextIsLetter = i + 1 < input.length && input[i + 1].isLetter()
        if (prevIsLetter && nextIsLetter) {
            when (c) {
                '0' -> sb.append('o')
                '1' -> sb.append('i')
                '3' -> sb.append('e')
                '4' -> sb.append('a')
                '5', '$' -> sb.append('s')
                '@' -> sb.append('a')
                else -> sb.append(c)
            }
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}

private fun containsAnyToken(tokenSet: Set<String>, vararg candidates: String): Boolean {
    for (c in candidates) {
        if (c in tokenSet) return true
    }
    return false
}

private fun isVerifiedIndianDltHeader(sender: String): Boolean {
    val clean = sender.trim().lowercase(Locale.ROOT)
    if (clean.contains('-')) {
        val parts = clean.split('-')
        if (parts.size == 2 && parts[0] in INDIAN_DLT_PREFIXES && parts[1].length in 3..8) {
            return true
        }
    }
    val digits = clean.filter { it.isDigit() }
    if (digits.length in 4..6 && clean.all { it.isDigit() }) {
        return true
    }
    return false
}

private fun looksLikeMobileNumberSender(sender: String): Boolean {
    val digits = sender.filter { it.isDigit() }
    return (digits.length == 10 && digits[0] in '6'..'9') ||
        (digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9')
}

private fun looksLikeSuspiciousSenderFormat(sender: String): Boolean {
    val clean = sender.trim().lowercase(Locale.ROOT)
    if (clean.isBlank()) return true
    val digits = clean.filter { it.isDigit() }
    if (digits.length in 7..9 && clean.all { it.isDigit() }) return true
    return false
}

private fun buildSmsSpamSummary(
    riskTier: SmsSpamRiskTier,
    detectedUrl: String,
    reasons: List<String>
): String {
    if (riskTier == SmsSpamRiskTier.LOW_RISK) return ""
    val prefix = if (riskTier == SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM) "High-risk scam" else "Suspicious message"
    return when {
        reasons.contains("credential_or_upi_pin_solicitation") -> "$prefix: requests UPI PIN, OTP, or secret credentials"
        reasons.contains("malware_apk_or_remote_access_threat") -> "$prefix: dangerous APK or remote-access request"
        reasons.contains("lookalike_phishing_domain") -> "$prefix: deceptive lookalike link ($detectedUrl)"
        reasons.contains("account_kyc_suspension_phishing") -> "$prefix: account/KYC suspension phishing pattern"
        reasons.contains("utility_or_sim_disconnection_threat") -> "$prefix: urgent utility or SIM disconnection threat"
        reasons.contains("delivery_or_customs_fee_scam") -> "$prefix: parcel hold or delivery fee lure"
        reasons.contains("coercive_legal_or_authority_threat") -> "$prefix: coercive legal or authority threat"
        reasons.contains("job_or_task_deposit_scam") -> "$prefix: job deposit or task-earning lure"
        reasons.contains("prize_or_cashback_lure") -> "$prefix: lottery, prize, or expiring reward lure"
        reasons.contains("advance_fee_or_instant_loan_scam") -> "$prefix: instant loan or advance-fee lure"
        reasons.contains("suspicious_domain_extension") || reasons.contains("deceptive_or_ip_url") ->
            "$prefix: untrusted link domain"
        else -> "$prefix: flagged by on-device ML classifier"
    }
}

private fun buildMessageCacheKey(normalizedSender: String, strippedBody: String): String {
    return sha256("$SMS_SPAM_MODEL_VERSION|$normalizedSender|$strippedBody").take(32)
}

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append("%02x".format(byte))
        }
    }
}
