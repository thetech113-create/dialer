package com.caller

/** Shortest voicemail key the app accepts. The voicemail server enforces the same minimum. */
const val VOICEMAIL_KEY_MIN_LENGTH = 16

/**
 * Why [input] cannot be the voicemail key, or null if it can. The key travels in an HTTP header, so it has to be plain
 * printable characters without spaces.
 */
internal fun voicemailKeyProblem(input: String): String? {
    val key = input.trim()
    return when {
        key.isEmpty() -> "Enter your voicemail key."
        key.length < VOICEMAIL_KEY_MIN_LENGTH -> "The key must have at least $VOICEMAIL_KEY_MIN_LENGTH characters."
        key.any { it.code !in 33..126 } -> "Use only letters, numbers and symbols, no spaces."
        else -> null
    }
}
