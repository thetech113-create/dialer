package com.caller

private const val REDACTED_PHONE_TOKEN = "REDACTED_PHONE"
private const val NO_PHONE_TOKEN = "NO_PHONE"
private val PHONE_LIKE_DIGITS_REGEX = Regex("(?<!\\d)\\+?[\\d()\\-\\s]{7,}(?!\\d)")
// The longest USSD string GSM allows (3GPP TS 23.038): longer text is not a dial string.
private const val MAX_DIAL_STRING_LENGTH = 182

/**
 * The dial string of [phoneNumber]: formatting (spaces, dashes, brackets) removed. The pause (,) and wait (;) marks of an
 * extension are kept, and nothing is cut off: a 15-character limit used to drop extensions and the end of USSD codes.
 */
fun sanitizePhoneNumberForCall(phoneNumber: String): String {
    val trimmed = phoneNumber.trim()
    if (trimmed.isBlank()) return ""
    val builder = StringBuilder()
    trimmed.forEachIndexed { index, char ->
        when {
            char.isDigit() || char == '*' || char == '#' || char == ',' || char == ';' -> builder.append(char)
            char == '+' && index == 0 && builder.isEmpty() -> builder.append(char)
        }
    }
    return builder.toString()
}

fun isSafePhoneNumber(phoneNumber: String): Boolean {
    val normalized = sanitizePhoneNumberForCall(phoneNumber)
    return normalized.length in 3..MAX_DIAL_STRING_LENGTH
}

fun maskPhoneNumber(phoneNumber: String): String {
    return if (phoneNumber.any { it.isDigit() }) REDACTED_PHONE_TOKEN else NO_PHONE_TOKEN
}

fun containsPhoneLikeDigits(logLine: String): Boolean {
    return PHONE_LIKE_DIGITS_REGEX.containsMatchIn(logLine)
}
