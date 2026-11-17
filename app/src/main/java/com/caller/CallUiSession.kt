package com.caller

/**
 * A call session is the time from the first live call being added until the last one is gone. Calls that overlap (call
 * waiting, a conference) share one session and one screen. A call that starts after the previous session ended is a NEW
 * session: nothing the call screen kept for the old one (its state, its "end requested" flag, a saved snapshot of it)
 * may carry into the new one.
 */
internal fun newCallUiSessionId(): String = java.util.UUID.randomUUID().toString().take(8)

/**
 * True when a call screen that belongs to [ownSessionId] is now told about a different, known session. An unknown id on
 * either side never counts as a change, so a launch that carries no id keeps working exactly as before.
 */
internal fun callUiSessionChanged(ownSessionId: String, incomingSessionId: String): Boolean =
    ownSessionId.isNotBlank() && incomingSessionId.isNotBlank() && ownSessionId != incomingSessionId

/**
 * The saved call snapshot may seed a call screen only if it was saved for the same session. A snapshot with no id (saved
 * before any session was known) or a screen with no id yet is allowed, as before.
 */
internal fun stickySnapshotBelongsToSession(snapshotSessionId: String, ownSessionId: String): Boolean =
    snapshotSessionId.isBlank() || ownSessionId.isBlank() || snapshotSessionId == ownSessionId
