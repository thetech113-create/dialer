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
    private var digitsDialedFrom: String? = null

    /**
     * A call to [dialed] is being placed. Remembers to clear the keypad later when the call was placed from the keypad
     * screen ([keypadOnScreen]: its green button, or a contact or recent row suggested for the typed digits, which
     * differ from the number that gets dialed) or when the keypad holds exactly the dialed number.
     */
    fun request(keypadDigits: String, dialed: String, keypadOnScreen: Boolean) {
        val fromKeypad = keypadOnScreen || keypadShowsDialedNumber(keypadDigits, dialed)
        digitsDialedFrom = if (keypadDigits.isNotEmpty() && fromKeypad) keypadDigits else null
    }

    /** The call did not start: the number stays on the keypad. */
    fun cancel() {
        digitsDialedFrom = null
    }

    /** The keypad is no longer visible. Returns the digits it should hold from now on. */
    fun digitsOnceHidden(keypadDigits: String): String {
        val dialedFrom = digitsDialedFrom ?: return keypadDigits
        digitsDialedFrom = null
        return if (keypadDigits == dialedFrom) "" else keypadDigits
    }
}
