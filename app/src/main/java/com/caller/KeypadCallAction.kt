package com.caller

/** What the green call button on the keypad does for a tap. */
internal sealed interface KeypadCallAction {
    /** Place a call to this number. */
    data class Dial(val number: String) : KeypadCallAction

    /** Put this number on the keypad without calling it, so it can be checked or edited first. */
    data class ShowLastNumber(val number: String) : KeypadCallAction

    /** Nothing to dial and nothing to show. */
    data object Nothing : KeypadCallAction
}

/**
 * A number on the keypad is dialed. On an empty keypad the button is a redial: the first tap only shows the last
 * number, and the next tap (now a number on the keypad) dials it. An empty keypad never places a call by itself.
 */
internal fun keypadCallAction(typedNumber: String, lastRecentNumber: String): KeypadCallAction = when {
    typedNumber.isNotEmpty() -> KeypadCallAction.Dial(typedNumber)
    lastRecentNumber.isNotBlank() -> KeypadCallAction.ShowLastNumber(lastRecentNumber)
    else -> KeypadCallAction.Nothing
}

/**
 * True when the keypad holds the number that was just dialed, so the keypad is cleared for the next call. A call placed
 * from somewhere else (a contact, another recent row) leaves a different number on the keypad alone.
 */
internal fun keypadShowsDialedNumber(keypadDigits: String, dialedNumber: String): Boolean {
    if (keypadDigits.isEmpty()) return false
    val dialed = sanitizePhoneNumberForCall(dialedNumber)
    return dialed.isNotEmpty() && sanitizePhoneNumberForCall(keypadDigits) == dialed
}

/**
 * Clears the keypad after a call, but only once the keypad is out of sight. Clearing at the moment the call is placed
 * showed an empty keypad for a moment, before the call screen had opened over it.
 */
internal class DeferredKeypadClear {
    private var dialedNumber: String? = null

    /** A call to [dialed] is being placed. Remembers to clear the keypad later if it holds that number. */
    fun request(keypadDigits: String, dialed: String) {
        dialedNumber = if (keypadShowsDialedNumber(keypadDigits, dialed)) dialed else null
    }

    /** The call did not start: the number stays on the keypad. */
    fun cancel() {
        dialedNumber = null
    }

    /** The keypad is no longer visible. Returns the digits it should hold from now on. */
    fun digitsOnceHidden(keypadDigits: String): String {
        val dialed = dialedNumber ?: return keypadDigits
        dialedNumber = null
        return if (keypadShowsDialedNumber(keypadDigits, dialed)) "" else keypadDigits
    }
}
