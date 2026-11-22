package com.caller

/** What a dial string from the keypad is, before it is handed to Telecom. */
internal sealed interface DialCode {
    /** A phone number, or a USSD / MMI code such as *123# or *31#number: placed through Telecom. */
    data object Call : DialCode

    /** An Android secret code *#*#code#*#*: sent to the phone's own apps, never to the network (as Google's Dialer). */
    data class Secret(val code: String) : DialCode

    /**
     * A code that starts with * or # and ends with *, such as *#2864#*#*. It is neither a phone number nor a USSD code
     * (those end with #), so the network can only reject it: it is not dialled.
     */
    data object NotDialable : DialCode
}

private val SECRET_CODE = Regex("""^\*#\*#([0-9]+)#\*#\*$""")

internal fun dialCodeOf(dialable: String): DialCode {
    SECRET_CODE.matchEntire(dialable)?.let { return DialCode.Secret(it.groupValues[1]) }
    val startsAsCode = dialable.startsWith('*') || dialable.startsWith('#')
    return if (startsAsCode && dialable.endsWith('*')) DialCode.NotDialable else DialCode.Call
}
