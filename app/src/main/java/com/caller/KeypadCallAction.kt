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
