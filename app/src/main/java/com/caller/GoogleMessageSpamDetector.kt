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

internal const val SMS_SPAM_MODEL_VERSION = "uci_sms_lr_word_char_v1"
private const val SMS_SPAM_PREFS = "google_message_spam_detector"
private const val SENDER_SPAM_COUNT_PREFIX = "sender_spam_cnt_"
private const val SENDER_HAM_COUNT_PREFIX = "sender_ham_cnt_"
private const val MSG_USER_OVERRIDE_PREFIX = "msg_override_"
private const val SMS_SPAM_MODEL_ASSET = "sms_spam_model.json"

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

private val MODEL_WORD_TOKEN_REGEX = Regex("""[a-z0-9]{2,}""")

private data class ModelFeature(val inverseDocumentFrequency: Double, val coefficient: Double)

private data class TrainedSmsTextModel(
    val intercept: Double,
    val suspiciousThreshold: Double,
    val highConfidenceThreshold: Double,
    val wordFeatures: Map<String, ModelFeature>,
    val charFeatures: Map<String, ModelFeature>
)

private val modelLoadLock = Any()
@Volatile private var trainedTextModel: TrainedSmsTextModel? = null
@Volatile private var modelLoadAttempted = false

private fun loadTrainedSmsTextModel(context: Context): TrainedSmsTextModel? {
    trainedTextModel?.let { return it }
    if (modelLoadAttempted) return null
    synchronized(modelLoadLock) {
        trainedTextModel?.let { return it }
        if (modelLoadAttempted) return null
        modelLoadAttempted = true
        return runCatching {
            val root = context.assets.open(SMS_SPAM_MODEL_ASSET).bufferedReader().use { JSONObject(it.readText()) }
            require(root.getString("model_version") == SMS_SPAM_MODEL_VERSION) { "Unexpected SMS model version" }
            fun parseFeatures(name: String): Map<String, ModelFeature> {
                val source = root.getJSONObject(name)
                return buildMap(source.length()) {
                    val keys = source.keys()
                    while (keys.hasNext()) {
                        val term = keys.next()
                        val values = source.getJSONArray(term)
                        put(term, ModelFeature(values.getDouble(0), values.getDouble(1)))
                    }
                }
            }
            val thresholds = root.getJSONObject("thresholds")
            TrainedSmsTextModel(
                intercept = root.getDouble("intercept"),
                suspiciousThreshold = thresholds.getDouble("suspicious"),
                highConfidenceThreshold = thresholds.getDouble("high_confidence"),
                wordFeatures = parseFeatures("word_features"),
                charFeatures = parseFeatures("char_features")
            )
        }.onFailure {
            android.util.Log.e("SmsSpamDetector", "Unable to load bundled SMS model", it)
        }.getOrNull().also { trainedTextModel = it }
    }
}

private fun tfIdfLogit(text: String, features: Map<String, ModelFeature>, terms: Sequence<String>): Double {
    val counts = HashMap<String, Int>()
    terms.forEach { term -> if (features.containsKey(term)) counts[term] = (counts[term] ?: 0) + 1 }
    var squaredMagnitude = 0.0
    var weightedSum = 0.0
    for ((term, count) in counts) {
        val feature = features.getValue(term)
        val value = (1.0 + ln(count.toDouble())) * feature.inverseDocumentFrequency
        squaredMagnitude += value * value
        weightedSum += value * feature.coefficient
    }
    return if (squaredMagnitude > 0.0) weightedSum / sqrt(squaredMagnitude) else 0.0
}

private fun predictTrainedSmsProbability(context: Context, text: String): Pair<Float, TrainedSmsTextModel>? {
    val model = loadTrainedSmsTextModel(context) ?: return null
    val normalized = text.lowercase(Locale.ROOT)
    val wordTokens = MODEL_WORD_TOKEN_REGEX.findAll(normalized).map { it.value }.toList()
    val wordTerms = sequence {
        yieldAll(wordTokens)
        for (index in 0 until wordTokens.lastIndex) yield("${wordTokens[index]} ${wordTokens[index + 1]}")
    }
    val charTerms = sequence {
        normalized.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.forEach { word ->
            val padded = " $word "
            for (size in 3..5) {
                if (padded.length >= size) {
                    for (index in 0..padded.length - size) yield(padded.substring(index, index + size))
                }
            }
        }
    }
    val logit = model.intercept + tfIdfLogit(normalized, model.wordFeatures, wordTerms) +
        tfIdfLogit(normalized, model.charFeatures, charTerms)
    return (1.0 / (1.0 + exp(-logit))).toFloat() to model
}

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
    val verdict = evaluateHybridSmsSpam(context, prefs, normalizedSender, strippedBody)
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
    context: Context,
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

    // 2. URL extraction and structural phishing analysis. These signals explain a positive
    // classification and only independently escalate objectively deceptive links or APK lures.
    val detectedEntities = detectSmsEntities(rawBody)
    val urls = detectedEntities.filter { it.type == SmsEntityType.URL }.map { it.actionValue }
    var detectedUrl = urls.firstOrNull().orEmpty()
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

    // 3. Contextual multi-signal scam analysis. It provides human-readable evidence, but a
    // hand-written phrase combination cannot by itself classify a message as spam.
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

    // 5. Sender format and local reputation are explanatory only. A sender is never classified
    // solely because it is unknown, alphanumeric, or has one unverified report.
    val isVerifiedDlt = isVerifiedIndianDltHeader(senderTrimmed)
    if (!isVerifiedDlt && looksLikeMobileNumberSender(senderTrimmed) &&
        (hasAccountOrSimTarget || hasUtilityOrSimContext || hasParcelContext || hasLegalThreat) &&
        zContext > 0.0
    ) {
        // Institutional/bank/utility/government claim from a regular 10-digit mobile number.
        reasons += "institutional_claim_from_personal_number"
    } else if (!isVerifiedDlt && looksLikeSuspiciousSenderFormat(senderTrimmed) && zContext > 0.0) {
        reasons += "unverified_sender_format"
    }

    // Local verified sender reputation (requires >= 2 net reports before shifting score significantly)
    if (prefs != null && senderTrimmed.isNotBlank()) {
        val senderKey = sha256(senderTrimmed).take(24)
        val spamReports = prefs.getInt(SENDER_SPAM_COUNT_PREFIX + senderKey, 0)
        val hamReports = prefs.getInt(SENDER_HAM_COUNT_PREFIX + senderKey, 0)
        val netSpamReports = spamReports - hamReports
        if (netSpamReports >= 2 && !isVerifiedDlt) {
            reasons += "sender_reported_as_spam"
        }
    }

    // 6. The trained model is the primary decision maker. Its thresholds were selected by the
    // reproducible training pipeline and stored alongside the model. Contextual text signals are
    // intentionally not added to its probability because that would invalidate calibration.
    val prediction = predictTrainedSmsProbability(context, rawBody)
    val probability = prediction?.first ?: 0f
    val model = prediction?.second
    if (model == null) reasons += "model_unavailable"
    val finalScore = (probability * 100f).roundToInt().coerceIn(0, 100)

    val hasIndependentTechnicalThreat = hasLookalikeDomain || hasIpOrDeceptiveUrl ||
        reasons.contains("malware_apk_or_remote_access_threat")
    val hasKnownLegitimatePattern = !hasIndependentTechnicalThreat && (
        (hasOtpCode && (hasDefensiveDoNotShare || bigrams.contains("valid for"))) ||
            hasStandardBankLedger || hasStandardDeliveryStatus
        )
    val riskTier = when {
        // These are independently deceptive technical indicators, not ordinary keywords.
        hasIndependentTechnicalThreat -> SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM
        // Strong one-way transactional patterns prevent a generic corpus model from warning on
        // routine OTPs, ledger entries, and delivery status messages.
        hasKnownLegitimatePattern -> SmsSpamRiskTier.LOW_RISK
        model != null && probability >= model.highConfidenceThreshold -> SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM
        model != null && probability >= model.suspiciousThreshold -> SmsSpamRiskTier.SUSPICIOUS
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
