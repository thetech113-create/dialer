package com.caller

private const val REDACTED_PHONE_TOKEN = "REDACTED_PHONE"
private const val NO_PHONE_TOKEN = "NO_PHONE"
private val PHONE_LIKE_DIGITS_REGEX = Regex("(?<!\\d)\\+?[\\d()\\-\\s]{7,}(?!\\d)")

fun sanitizePhoneNumberForCall(phoneNumber: String): String {
    val trimmed = phoneNumber.trim()
    if (trimmed.isBlank()) return ""
    val builder = StringBuilder()
    trimmed.forEachIndexed { index, char ->
        when {
            char.isDigit() || char == '*' || char == '#' -> builder.append(char)
            char == '+' && index == 0 && builder.isEmpty() -> builder.append(char)
        }
    }
    return builder.toString().take(15)
}

fun isSafePhoneNumber(phoneNumber: String): Boolean {
    val normalized = sanitizePhoneNumberForCall(phoneNumber)
    return normalized.length in 3..15
}

fun maskPhoneNumber(phoneNumber: String): String {
    return if (phoneNumber.any { it.isDigit() }) REDACTED_PHONE_TOKEN else NO_PHONE_TOKEN
}

fun containsPhoneLikeDigits(logLine: String): Boolean {
    return PHONE_LIKE_DIGITS_REGEX.containsMatchIn(logLine)
}
