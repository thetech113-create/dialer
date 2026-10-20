package com.caller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import java.util.Locale

private const val TAG_URL = "URL_TAG"
private const val TAG_PHONE = "PHONE_TAG"
private const val TAG_NUMERIC_ID = "NUMERIC_ID_TAG"

internal enum class SmsEntityType(val tag: String) {
    URL(TAG_URL),
    PHONE(TAG_PHONE),
    NUMERIC_ID(TAG_NUMERIC_ID)
}

internal data class DetectedSmsEntity(
    val start: Int,
    val end: Int,
    val type: SmsEntityType,
    val rawText: String,
    val actionValue: String
)

private val KNOWN_PUBLIC_TLDS = setOf(
    "com", "org", "net", "edu", "gov", "mil", "int", "io", "co", "in",
    "ai", "app", "dev", "me", "info", "biz", "xyz", "online", "site",
    "store", "tech", "cloud", "page", "link", "live", "news", "bank",
    "uk", "us", "ca", "au", "de", "fr", "jp", "sg", "ae", "za", "br",
    "ly", "gl", "gd", "gy", "at", "tv", "gg"
)

private val BARE_DOMAIN_SAFE_TLDS = setOf(
    "com", "org", "net", "edu", "gov", "mil", "io", "app", "dev"
)

private val BARE_DOMAIN_COMPOUND_SUFFIXES = setOf(
    "co.in", "ac.in", "gov.in", "org.in", "net.in", "res.in", "nic.in",
    "co.uk", "org.uk", "ac.uk", "com.au", "co.jp", "co.za", "co.ae"
)

private val KNOWN_SHORTENER_HOSTS = setOf(
    "bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd", "cutt.ly", "rb.gy", "shorturl.at"
)

private val NON_DOMAIN_PREFIX_WORDS = setOf(
    "dr", "mr", "mrs", "ms", "prof", "sr", "jr", "st", "rs", "inr",
    "usd", "eur", "gbp", "eg", "ie", "etc", "vs", "no", "fig", "vol"
)

private val CURRENCY_PREFIX_WORDS = setOf(
    "rs", "inr", "usd", "eur", "gbp", "cad", "aud", "aed", "sgd", "jpy",
    "mrp", "amt", "amount", "price", "cost", "paid", "bal", "balance", "fare", "total"
)

private val UNIT_SUFFIX_WORDS = setOf(
    "rs", "inr", "usd", "eur", "gbp", "paise", "cents",
    "kg", "g", "mg", "km", "m", "cm", "mm", "ml", "l", "ltr",
    "kb", "mb", "gb", "tb", "bps", "kbps", "mbps",
    "sec", "secs", "min", "mins", "hr", "hrs", "day", "days",
    "pc", "pcs", "unit", "units", "item", "items", "qty"
)

private val ID_CONTEXT_KEYWORDS = setOf(
    "id", "ref", "reference", "order", "txn", "utr", "awb", "account", "acct",
    "a/c", "inv", "invoice", "ticket", "pnr", "crn", "prn", "arn", "srn",
    "folio", "policy", "docket", "consignment", "tracking", "application", "bill"
)

// Candidate finder for non-whitespace tokens that could be URLs or emails
private val TOKEN_REGEX = Regex("""\S+""")

// Broad numeric/phone candidate scan (strictly filtered by full-token boundary and numbering rules)
private val NUMERIC_CANDIDATE_REGEX = Regex("""\+?\d(?:[\d\s()-]{4,18}\d|\d{5,19})""")

// Date patterns to exclude (YYYY-MM-DD, DD-MM-YYYY, DD/MM/YYYY, YYYY.MM.DD)
private val DATE_LIKE_REGEX = Regex(
    """^(?:\d{4}[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12]\d|3[01])|(?:0?[1-9]|[12]\d|3[01])[-/.](?:0?[1-9]|1[0-2])[-/.]\d{2,4})$"""
)

internal fun detectSmsEntities(text: String): List<DetectedSmsEntity> {
    if (text.isBlank()) return emptyList()

    val entities = mutableListOf<DetectedSmsEntity>()
    val excludedRanges = mutableListOf<IntRange>()

    // 1. Scan tokens for Emails (to exclude) and strict URLs
    TOKEN_REGEX.findAll(text).forEach { match ->
        val rawToken = match.value
        val tokenStart = match.range.first

        // Trim surrounding brackets/quotes/punctuation
        var startOffset = 0
        var endOffset = rawToken.length
        while (startOffset < endOffset && rawToken[startOffset] in "([<\"'") {
            startOffset++
        }
        while (endOffset > startOffset) {
            val lastChar = rawToken[endOffset - 1]
            val isUnbalancedClosingParen = lastChar == ')' &&
                rawToken.substring(startOffset, endOffset).count { it == '(' } <
                rawToken.substring(startOffset, endOffset).count { it == ')' }
            if (lastChar in ".,;:!?>]\"'" || isUnbalancedClosingParen) {
                endOffset--
            } else {
                break
            }
        }

        if (startOffset >= endOffset) return@forEach
        val candidate = rawToken.substring(startOffset, endOffset)
        val absStart = tokenStart + startOffset
        val absEnd = tokenStart + endOffset

        // Exclude email addresses completely so neither domain nor digits inside match
        if (candidate.contains('@')) {
            excludedRanges.add(match.range)
            return@forEach
        }

        val normalizedUrl = validateAndNormalizeUrl(candidate)
        if (normalizedUrl != null) {
            entities.add(
                DetectedSmsEntity(
                    start = absStart,
                    end = absEnd,
                    type = SmsEntityType.URL,
                    rawText = candidate,
                    actionValue = normalizedUrl
                )
            )
            excludedRanges.add(absStart until absEnd)
        }
    }

    // 2. Scan for Phone Numbers and Numeric Identifiers
    NUMERIC_CANDIDATE_REGEX.findAll(text).forEach { match ->
        var candStart = match.range.first
        var candEnd = match.range.last + 1

        // Trim any trailing space/hyphen captured by broad regex
        while (candEnd > candStart && (text[candEnd - 1].isWhitespace() || text[candEnd - 1] == ')')) {
            if (text[candEnd - 1] == ')' && text.substring(candStart, candEnd).contains('(')) break
            candEnd--
        }

        // If candidate starts with '+' or digit, check if a newline or dual-space split two separate tokens
        val rawSlice = text.substring(candStart, candEnd)
        if (rawSlice.contains('\n') || rawSlice.contains('\r') || rawSlice.contains("  ")) {
            return@forEach
        }

        // Skip if overlapping any URL or email range
        if (excludedRanges.any { candStart <= it.last && candEnd > it.first }) {
            return@forEach
        }

        // Check full-token boundaries (reject embedded alphanumeric, hex, decimal, currency, dates)
        if (!hasValidNumericTokenBoundaries(text, candStart, candEnd)) {
            return@forEach
        }

        val candidate = text.substring(candStart, candEnd)
        if (DATE_LIKE_REGEX.matches(candidate.trim())) {
            return@forEach
        }

        val precededByIdKeyword = isPrecededByIdKeyword(text, candStart)
        val isContiguousDigits = candidate.all { it.isDigit() }

        // If explicitly labeled as an ID/Ref/Order/Txn/Account and contiguous digits (8..20), classify as NUMERIC_ID
        if (precededByIdKeyword && isContiguousDigits && candidate.length in 8..20 && !allSameDigit(candidate)) {
            entities.add(
                DetectedSmsEntity(
                    start = candStart,
                    end = candEnd,
                    type = SmsEntityType.NUMERIC_ID,
                    rawText = candidate,
                    actionValue = candidate
                )
            )
            excludedRanges.add(candStart until candEnd)
            return@forEach
        }

        // Otherwise test strict phone-number validation first
        val dialablePhone = validatePhoneCandidate(candidate)
        if (dialablePhone != null) {
            entities.add(
                DetectedSmsEntity(
                    start = candStart,
                    end = candEnd,
                    type = SmsEntityType.PHONE,
                    rawText = candidate,
                    actionValue = dialablePhone
                )
            )
            excludedRanges.add(candStart until candEnd)
            return@forEach
        }

        // Independent Numeric ID check: ONLY pure contiguous digits (never a failed formatted/hyphenated/+ phone candidate)
        if (isValidStandaloneNumericId(candidate)) {
            entities.add(
                DetectedSmsEntity(
                    start = candStart,
                    end = candEnd,
                    type = SmsEntityType.NUMERIC_ID,
                    rawText = candidate,
                    actionValue = candidate
                )
            )
            excludedRanges.add(candStart until candEnd)
        }
    }

    return entities.sortedBy { it.start }
}

private fun validateAndNormalizeUrl(candidate: String): String? {
    if (candidate.length < 4 || candidate.any { it.isWhitespace() }) return null

    val lower = candidate.lowercase(Locale.ROOT)
    val hasHttp = lower.startsWith("http://") || lower.startsWith("https://")
    val hasWww = lower.startsWith("www.")

    // Reject other schemes or malformed scheme prefixes
    if (lower.contains("://") && !hasHttp) return null

    val withoutScheme = when {
        lower.startsWith("https://") -> candidate.substring(8)
        lower.startsWith("http://") -> candidate.substring(7)
        else -> candidate
    }
    if (withoutScheme.isEmpty()) return null

    val slashIdx = withoutScheme.indexOfAny(charArrayOf('/', '?', '#'))
    val authority = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
    val pathAndQuery = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else ""

    if (authority.isEmpty() || authority.contains('@')) return null

    val hostPortParts = authority.split(':')
    if (hostPortParts.size > 2) return null
    val host = hostPortParts[0]
    if (hostPortParts.size == 2) {
        val port = hostPortParts[1].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
    }

    if (host.startsWith('.') || host.endsWith('.') || host.contains("..")) return null
    val labels = host.split('.')
    if (labels.size < 2) return null

    for (label in labels) {
        if (label.isEmpty() || label.length > 63) return null
        if (label.startsWith('-') || label.endsWith('-')) return null
        if (!label.all { it.isLetterOrDigit() || it == '-' }) return null
    }

    val tld = labels.last().lowercase(Locale.ROOT)
    if (tld.length < 2 || !tld.all { it.isLetter() } || tld !in KNOWN_PUBLIC_TLDS) {
        return null
    }

    val hostLower = host.lowercase(Locale.ROOT)
    val firstLabelLower = labels.first().lowercase(Locale.ROOT)
    if (firstLabelLower in NON_DOMAIN_PREFIX_WORDS) {
        return null
    }

    if (!hasHttp && !hasWww) {
        // Strict rules for bare domains so "Dr.Smith", "hello.world", "come.in" never match
        val isShortenerWithPath = hostLower in KNOWN_SHORTENER_HOSTS && pathAndQuery.length > 1
        val lastTwoLabels = if (labels.size >= 2) {
            "${labels[labels.size - 2].lowercase(Locale.ROOT)}.$tld"
        } else {
            ""
        }
        val hasSafeBareTld = tld in BARE_DOMAIN_SAFE_TLDS || lastTwoLabels in BARE_DOMAIN_COMPOUND_SUFFIXES
        // Reject CamelCase names like "Dr.Smith" or "John.Com" when no scheme is present
        val hasMixedCaseInHost = host.any { it.isUpperCase() } && host.any { it.isLowerCase() }
        if (hasMixedCaseInHost) return null
        if (!isShortenerWithPath && !hasSafeBareTld) return null
    }

    return if (hasHttp) candidate else "https://$candidate"
}

private fun hasValidNumericTokenBoundaries(text: String, start: Int, end: Int): Boolean {
    // 1. Check character immediately before `start`
    if (start > 0) {
        val prev = text[start - 1]
        if (prev.isLetterOrDigit() || prev == '_' || prev == '@' || prev == '#' || prev == '*') {
            return false
        }
        // Reject if preceded by decimal point or comma/slash/hyphen connected to a token (e.g., 12.3456789, TXN-9876543210, /9876543210)
        if ((prev == '.' || prev == ',' || prev == '/' || prev == '-') && start >= 2) {
            val beforePrev = text[start - 2]
            if (beforePrev.isLetterOrDigit()) return false
        }
        if (prev in listOf('₹', '$', '€', '£', '¥')) {
            return false
        }
    }

    // 2. Check character immediately after `end`
    if (end < text.length) {
        val next = text[end]
        if (next.isLetterOrDigit() || next == '_' || next == '@') {
            return false
        }
        // Reject decimal values (e.g., 9876543210.50), comma-grouped numbers, slash-separated dates/paths, or trailing identifier hyphens (9876543210-X)
        if ((next == '.' || next == ',' || next == '/' || next == '-') && end + 1 < text.length) {
            val afterNext = text[end + 1]
            if (afterNext.isLetterOrDigit()) return false
        }
        if (next in listOf('₹', '$', '€', '£', '¥', '%')) {
            return false
        }
    }

    // 3. Check preceding word for currency prefix (e.g., "Rs. 9876543210", "INR 1234567890", "USD 12345678")
    val prefixWindowStart = (start - 16).coerceAtLeast(0)
    val prefixSlice = text.substring(prefixWindowStart, start).trimEnd()
    if (prefixSlice.isNotEmpty()) {
        if (prefixSlice.last() in listOf('₹', '$', '€', '£', '¥')) return false
        val cleanedPrefix = prefixSlice.trimEnd('.', ':', ' ').lowercase(Locale.ROOT)
        val prevWord = cleanedPrefix.takeLastWhile { it.isLetter() }
        if (prevWord in CURRENCY_PREFIX_WORDS) {
            return false
        }
    }

    // 4. Check following word for unit/currency suffix (e.g., "1234567890 INR", "1234567890 kg")
    val suffixWindowEnd = (end + 12).coerceAtMost(text.length)
    val suffixSlice = text.substring(end, suffixWindowEnd).trimStart()
    if (suffixSlice.isNotEmpty()) {
        val nextWord = suffixSlice.takeWhile { it.isLetter() }.lowercase(Locale.ROOT)
        if (nextWord in UNIT_SUFFIX_WORDS) {
            return false
        }
    }

    return true
}

private fun isPrecededByIdKeyword(text: String, start: Int): Boolean {
    val windowStart = (start - 24).coerceAtLeast(0)
    val before = text.substring(windowStart, start).trimEnd()
    val cleaned = before.trimEnd(':', '-', '#', '.', ' ')
    val lastToken = cleaned.takeLastWhile { it.isLetter() || it == '/' }.lowercase(Locale.ROOT)
    return lastToken in ID_CONTEXT_KEYWORDS
}

private fun validatePhoneCandidate(candidate: String): String? {
    val trimmed = candidate.trim()
    if (trimmed.isEmpty()) return null

    // Reject consecutive separators or invalid parentheses
    if (trimmed.contains("--") || trimmed.contains("  ")) return null
    val openParens = trimmed.count { it == '(' }
    val closeParens = trimmed.count { it == ')' }
    if (openParens != closeParens || openParens > 1) return null
    if (openParens == 1) {
        val openIdx = trimmed.indexOf('(')
        val closeIdx = trimmed.indexOf(')')
        if (closeIdx < openIdx || closeIdx - openIdx !in 2..5) return null
    }

    val hasPlus = trimmed.startsWith("+")
    // Ensure '+' only appears at index 0
    if (trimmed.indexOf('+', startIndex = 1) != -1) return null

    val digits = trimmed.filter { it.isDigit() }
    if (digits.length !in 10..15) return null
    if (allSameDigit(digits)) return null

    // Check separator grouping sanity (if spaces or hyphens are used)
    val groups = trimmed.removePrefix("+")
        .replace("(", " ")
        .replace(")", " ")
        .trim()
        .split(Regex("""[\s-]+"""))
        .filter { it.isNotEmpty() }

    if (groups.size > 5) return null
    if (groups.size > 1 && groups.any { !it.all(Char::isDigit) }) return null

    if (hasPlus) {
        // International E.164 rules with '+' prefix
        if (digits[0] == '0') return null
        return when {
            // India (+91): 12 digits total, national number starts with 6..9 (mobile) or 1..8 (STD landline)
            digits.startsWith("91") -> {
                if (digits.length == 12 && digits[2] in '6'..'9') "+$digits"
                else if (digits.length == 12 && digits[2] in '1'..'5' && groups.size >= 2) "+$digits"
                else null
            }
            // NANP (+1 US/Canada): 11 digits total, area code & exchange start with 2..9
            digits.startsWith("1") -> {
                if (digits.length == 11 && digits[1] in '2'..'9' && digits[4] in '2'..'9') "+$digits"
                else null
            }
            // UK (+44): 11 or 12 digits total
            digits.startsWith("44") -> {
                if (digits.length in 11..12 && digits[2] in '1'..'9') "+$digits"
                else null
            }
            // Other international country codes: 10..15 digits total, valid separator groups
            else -> {
                if (digits.length in 10..15 && groups.drop(1).all { it.length >= 2 }) "+$digits"
                else null
            }
        }
    } else {
        // Domestic (no '+') numbering rules
        if (openParens > 0) return null
        return when (digits.length) {
            10 -> {
                // Indian 10-digit mobile: must start with 6..9
                if (digits[0] !in '6'..'9') return null
                // Allowed groupings: continuous (10), 5-5, 3-3-4, 4-3-3
                val groupLengths = groups.map { it.length }
                val validGrouping = groupLengths == listOf(10) ||
                    groupLengths == listOf(5, 5) ||
                    groupLengths == listOf(3, 3, 4) ||
                    groupLengths == listOf(4, 3, 3)
                if (validGrouping) digits else null
            }
            11 -> {
                // 0-prefixed Indian mobile (0 + 10 digits) or Indian Toll-Free (1800 / 1860 + 7 digits)
                val isZeroMobile = digits.startsWith("0") && digits[1] in '6'..'9' &&
                    (groups.size == 1 || groups.map { it.length } in listOf(listOf(6, 5), listOf(1, 5, 5), listOf(3, 4, 4), listOf(4, 3, 4)))
                val isStdLandline = digits.startsWith("0") && digits[1] in '1'..'5' &&
                    groups.size == 2 && groups[0].length in 3..5
                val isTollFree = (digits.startsWith("1800") || digits.startsWith("1860")) &&
                    (groups.size == 1 || groups[0] == "1800" || groups[0] == "1860")
                if (isZeroMobile || isStdLandline || isTollFree) digits else null
            }
            else -> null
        }
    }
}

private fun isValidStandaloneNumericId(candidate: String): Boolean {
    // Must be contiguous digits only — never a rejected phone candidate with '+', spaces, or hyphens
    if (!candidate.all { it.isDigit() }) return false
    if (candidate.length !in 10..20) return false
    if (allSameDigit(candidate)) return false
    // Reject round numbers ending in 6+ zeros (e.g. 1000000000)
    if (candidate.endsWith("000000")) return false

    return when (candidate.length) {
        10 -> {
            // 10-digit numbers starting with 6..9 are phone numbers; 0-prefixed 10-digit is invalid;
            // 1..5 contiguous 10-digit numbers are non-phone numeric IDs
            candidate[0] in '1'..'5'
        }
        11 -> {
            // Exclude valid 11-digit Indian mobile/toll-free numbers (already handled by phone check)
            !candidate.startsWith("0") && !candidate.startsWith("1800") && !candidate.startsWith("1860")
        }
        else -> true // 12..20 contiguous digits
    }
}

private fun allSameDigit(digits: String): Boolean {
    if (digits.isEmpty()) return true
    val first = digits[0]
    return digits.all { it == first }
}

@Composable
fun LinkifiedSmsText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    linkColor: Color = Color(0xFF3B82F6),
    style: TextStyle = LocalTextStyle.current,
    onMessageClick: (() -> Unit)? = null,
    onMessageLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val annotatedText: AnnotatedString = remember(text, linkColor) {
        val detectedEntities = detectSmsEntities(text)
        buildAnnotatedString {
            append(text)
            detectedEntities.forEach { entity ->
                addStyle(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    ),
                    start = entity.start,
                    end = entity.end
                )
                addStringAnnotation(
                    tag = entity.type.tag,
                    annotation = entity.actionValue,
                    start = entity.start,
                    end = entity.end
                )
            }
        }
    }

    Text(
        text = annotatedText,
        color = textColor,
        style = style,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotatedText, onMessageClick, onMessageLongClick) {
            detectTapGestures(
                onLongPress = {
                    onMessageLongClick?.invoke()
                },
                onTap = { pos ->
                    val layout = layoutResult
                    if (layout != null) {
                        val offset = layout.getOffsetForPosition(pos)

                        // 1. URL Tap -> Open Browser
                        val urlAnnotation = annotatedText
                            .getStringAnnotations(TAG_URL, offset, offset)
                            .firstOrNull()
                        if (urlAnnotation != null) {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlAnnotation.item)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
                            }
                            return@detectTapGestures
                        }

                        // 2. Phone Number Tap -> Open Dialer (ACTION_DIAL)
                        val phoneAnnotation = annotatedText
                            .getStringAnnotations(TAG_PHONE, offset, offset)
                            .firstOrNull()
                        if (phoneAnnotation != null) {
                            runCatching {
                                val intent = Intent(
                                    Intent.ACTION_DIAL,
                                    Uri.fromParts("tel", phoneAnnotation.item, null)
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "Cannot dial number", Toast.LENGTH_SHORT).show()
                            }
                            return@detectTapGestures
                        }

                        // 3. Numeric ID Tap -> Copy Complete ID to Clipboard (Never Call)
                        val numericIdAnnotation = annotatedText
                            .getStringAnnotations(TAG_NUMERIC_ID, offset, offset)
                            .firstOrNull()
                        if (numericIdAnnotation != null) {
                            runCatching {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Numeric ID", numericIdAnnotation.item)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied: ${numericIdAnnotation.item}", Toast.LENGTH_SHORT).show()
                            }
                            return@detectTapGestures
                        }
                    }

                    onMessageClick?.invoke()
                }
            )
        }
    )
}
