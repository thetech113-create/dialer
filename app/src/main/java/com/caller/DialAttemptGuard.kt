package com.caller

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle of one outgoing dial attempt. Only [DISPATCHING], [WAITING_FOR_TELECOM] and [TELECOM_ADDED] hold the
 * guard: that is the initial dial-establishment window, during which Telecom refuses a second outgoing call (the OEM
 * then shows its own "Call not sent"). An established call (ACTIVE / HOLDING) does not hold it, so hold-then-place and
 * calling again after a call has ended are never blocked by the guard.
 */
internal enum class DialAttemptState {
    /** No attempt has been made yet. */
    IDLE,

    /** Accepted (button press, widget, PC link, voicemail); not yet handed to Telecom (SIM choice, hold-then-place). */
    DISPATCHING,

    /** placeCall() was called; Telecom has not added this attempt's call yet. */
    WAITING_FOR_TELECOM,

    /** Telecom added this attempt's call and it is still being set up (NEW / CONNECTING / DIALING / SIM choice). */
    TELECOM_ADDED,

    /** This attempt's call became ACTIVE or HOLDING. */
    CALL_ESTABLISHED,

    /** The attempt never produced a call: cancelled, refused, threw, or no call appeared in time. */
    FAILED,

    /** This attempt's call ended (or is no longer live) before or after being set up. */
    DISCONNECTED;

    val holdsGuard: Boolean
        get() = this == DISPATCHING || this == WAITING_FOR_TELECOM || this == TELECOM_ADDED
}

/** What a call the InCallService holds right now means for dial setup. */
internal enum class LiveCallPhase {
    /** An outgoing call still being set up: NEW, CONNECTING, DIALING or SELECT_PHONE_ACCOUNT. */
    OUTGOING_SETTING_UP,

    /** ACTIVE or HOLDING. */
    ESTABLISHED,

    /** An incoming call that is ringing (never bound to a dial attempt). */
    RINGING_INCOMING,

    /** Anything else that is live (e.g. audio processing). */
    OTHER
}

/**
 * Live call state read at the moment of a decision, never cached. [calls]: the calls live in the InCallService
 * (stableCallId -> phase; ended / disconnecting calls are not included). [telecomInCall]: TelecomManager.isInCall.
 */
internal data class LiveCallSnapshot(
    val calls: Map<String, LiveCallPhase>,
    val telecomInCall: Boolean
) {
    companion object {
        val NONE = LiveCallSnapshot(emptyMap(), telecomInCall = false)
    }
}

/** One outgoing dial attempt. [id] is its generation token: every transition is scoped to it. */
internal data class DialAttempt(
    val id: Long,
    val source: String,
    val state: DialAttemptState,
    /** When the current unconfirmed state stops being trusted (attempt-specific, not a global timer). */
    val deadline: Long,
    /** Calls that were live before this attempt: none of them can be this attempt's call. */
    val callsLiveBefore: Set<String>,
    val placedAt: Long = 0L,
    /** The Telecom call that belongs to this attempt, once Telecom has added it. */
    val callId: String? = null
)

/**
 * The single guard every outgoing dial goes through: the keypad / contacts / recents (MainActivity, both the
 * direct-place and the hold-then-place branch), the home-screen widget, the PC link and voicemail.
 *
 * - [tryAcquire] is atomic (compare-and-set on [current]): of any number of simultaneous or repeated requests,
 *   exactly one gets an attempt; the others are refused and nothing is dialled for them.
 * - [dispatch] is the only way to run placeCall, and it runs it at most once per attempt (DISPATCHING ->
 *   WAITING_FOR_TELECOM, again by compare-and-set), so even two dispatches of the same attempt place one call.
 * - The guard is never trusted on a flag alone: before blocking, the current attempt is re-checked against live
 *   call state ([revalidate]) and its own deadline, so a failed, refused, timed-out or ended attempt is released
 *   instead of blocking a new, legitimate dial.
 * - Callbacks are scoped to the attempt id / its own Telecom call id; an older attempt's late callback cannot
 *   change a newer attempt ([DialAttemptEvents.STALE_CALLBACK_IGNORED]).
 *
 * The keypad's call button is disabled from [locked], which is derived from the current attempt's state.
 */
internal class DialAttemptGuard(
    private val clock: () -> Long,
    private val log: (String) -> Unit
) {
    companion object {
        /** Accepted -> placeCall without a user choice (hold-then-place waits 200 ms for the hold). */
        const val DISPATCH_WINDOW_MS = 5_000L

        /** Accepted -> placeCall while the SIM picker is open. */
        const val USER_CHOICE_WINDOW_MS = 120_000L

        /** placeCall -> Telecom adds the call (normally well under a second; the post-dial check runs at 2.5 s). */
        const val TELECOM_ADD_WINDOW_MS = 3_000L

        /**
         * Telecom reports a call that only this dial can have made (none was live before it) but the InCallService
         * has not been given it yet: stay locked a little longer, then stop trusting it.
         */
        const val UNSEEN_CALL_WINDOW_MS = 15_000L

        private const val RETIRED_CALL_IDS_KEPT = 16
    }

    private val nextId = AtomicLong(0L)
    private val current = AtomicReference<DialAttempt?>(null)
    private val _locked = MutableStateFlow(false)

    // Telecom call id -> id of the (older) attempt it belonged to, to recognise late callbacks for them.
    private val retiredCallIds = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > RETIRED_CALL_IDS_KEPT
    }

    /** True while an unresolved attempt holds the guard; the keypad call button is disabled from this. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** The current (most recent) attempt, or null before the first one. */
    fun currentAttempt(): DialAttempt? = current.get()

    /** State of the current attempt; [DialAttemptState.IDLE] before the first one. */
    fun currentState(): DialAttemptState = current.get()?.state ?: DialAttemptState.IDLE

    /**
     * Accept a new dial, or refuse it. Returns the new attempt's id, or null if a dial is already being set up -
     * by an unresolved attempt (re-checked against [snapshot] first) or by a live outgoing call still being set up
     * that came from elsewhere. The guard is held from the moment this returns, before any asynchronous work.
     */
    fun tryAcquire(source: String, snapshot: LiveCallSnapshot): Long? {
        while (true) {
            val existing = current.get()
            val checked = existing?.let { revalidated(it, snapshot) }
            if (checked !== existing) {
                if (!current.compareAndSet(existing, checked)) continue
                onTransition(existing!!, checked!!, "revalidate_before_acquire")
            }
            if (checked != null && checked.state.holdsGuard) {
                log("${DialAttemptEvents.DUPLICATE_BLOCKED} attempt=${checked.id} state=${checked.state} source=$source reason=attempt_unresolved")
                return null
            }
            val settingUp = snapshot.calls.filterValues { it == LiveCallPhase.OUTGOING_SETTING_UP }.keys
            if (settingUp.isNotEmpty()) {
                log("${DialAttemptEvents.DUPLICATE_BLOCKED} attempt=${checked?.id ?: 0} source=$source reason=outgoing_call_being_set_up callIds=${settingUp.joinToString(",")}")
                return null
            }
            val now = clock()
            val attempt = DialAttempt(
                id = nextId.incrementAndGet(),
                source = source,
                state = DialAttemptState.DISPATCHING,
                deadline = now + DISPATCH_WINDOW_MS,
                callsLiveBefore = snapshot.calls.keys.toSet()
            )
            if (current.compareAndSet(checked, attempt)) {
                retire(checked)
                log("${DialAttemptEvents.STARTED} attempt=${attempt.id} source=$source callsLiveBefore=${attempt.callsLiveBefore.size}")
                log("${DialAttemptEvents.GUARD_ACQUIRED} attempt=${attempt.id}")
                publish(attempt)
                return attempt.id
            }
            // Another request won the race: loop, see its attempt, and be refused.
        }
    }

    /** The SIM picker is open for [attemptId]: the attempt stays locked while the user chooses. */
    fun awaitUserChoice(attemptId: Long) {
        update(attemptId, "await_user_choice") { a ->
            if (a.state == DialAttemptState.DISPATCHING) a.copy(deadline = clock() + USER_CHOICE_WINDOW_MS) else null
        }
    }

    /** A SIM was chosen for [attemptId]: dispatch is expected shortly. */
    fun userChoiceMade(attemptId: Long) {
        update(attemptId, "user_choice_made") { a ->
            if (a.state == DialAttemptState.DISPATCHING) a.copy(deadline = clock() + DISPATCH_WINDOW_MS) else null
        }
    }

    /**
     * Run [placeCall] for [attemptId] - at most once, and only while the attempt is still current and DISPATCHING.
     * Both MainActivity branches (direct_place and hold_then_place) and the other entry points dispatch through
     * here. Returns false (nothing placed) for a stale, released or already dispatched attempt. If [placeCall]
     * throws, the attempt is marked failed (guard released) and the exception is rethrown to the caller.
     */
    fun dispatch(attemptId: Long, branch: String, placeCall: () -> Unit): Boolean {
        while (true) {
            val a = current.get()
            if (a == null || a.id != attemptId) {
                log("${DialAttemptEvents.STALE_CALLBACK_IGNORED} attempt=$attemptId current=${a?.id ?: 0} event=dispatch branch=$branch")
                return false
            }
            if (a.state != DialAttemptState.DISPATCHING) {
                log("${DialAttemptEvents.DUPLICATE_BLOCKED} attempt=$attemptId state=${a.state} branch=$branch reason=already_dispatched_or_resolved")
                return false
            }
            val now = clock()
            val waiting = a.copy(
                state = DialAttemptState.WAITING_FOR_TELECOM,
                placedAt = now,
                deadline = now + TELECOM_ADD_WINDOW_MS
            )
            if (!current.compareAndSet(a, waiting)) continue
            log("${DialAttemptEvents.DISPATCHED} attempt=$attemptId branch=$branch")
            try {
                placeCall()
            } catch (e: Exception) {
                fail(attemptId, "place_call_threw:${e.javaClass.simpleName}")
                throw e
            }
            return true
        }
    }

    /** [attemptId] did not (or will not) produce a call: release it. No effect on any other attempt. */
    fun fail(attemptId: Long, reason: String) {
        update(attemptId, "fail:$reason") { a ->
            if (a.state.holdsGuard) a.copy(state = DialAttemptState.FAILED) else null
        }
    }

    /** Telecom added [callId] (InCallService.onCallAdded): binds it to the attempt waiting for its call. */
    fun onTelecomCallAdded(callId: String, phase: LiveCallPhase) {
        while (true) {
            val a = current.get() ?: return
            if (retiredCallIds.containsKeySynchronized(callId)) {
                log("${DialAttemptEvents.STALE_CALLBACK_IGNORED} attempt=${retiredCallIds.getSynchronized(callId)} current=${a.id} event=call_added callId=$callId")
                return
            }
            if (a.callId == callId) return
            val waitingForItsCall = a.state == DialAttemptState.DISPATCHING || a.state == DialAttemptState.WAITING_FOR_TELECOM
            if (!waitingForItsCall || callId in a.callsLiveBefore || phase == LiveCallPhase.RINGING_INCOMING) return
            val bound = bind(a, callId, phase)
            if (current.compareAndSet(a, bound)) {
                onTransition(a, bound, "call_added")
                return
            }
        }
    }

    /**
     * [callId] changed state ([phase]) or ended (null). Only the current attempt's own call moves its state; a
     * callback for an older attempt's call is ignored.
     */
    fun onCallUpdate(callId: String, phase: LiveCallPhase?) {
        while (true) {
            val a = current.get() ?: return
            if (a.callId != callId) {
                retiredCallIds.getSynchronized(callId)?.let { owner ->
                    log("${DialAttemptEvents.STALE_CALLBACK_IGNORED} attempt=$owner current=${a.id} event=call_update callId=$callId phase=${phase ?: "ENDED"}")
                }
                return
            }
            if (a.state != DialAttemptState.TELECOM_ADDED) return
            val next = when (phase) {
                null -> a.copy(state = DialAttemptState.DISCONNECTED)
                LiveCallPhase.OUTGOING_SETTING_UP -> return
                else -> a.copy(state = DialAttemptState.CALL_ESTABLISHED)
            }
            if (current.compareAndSet(a, next)) {
                onTransition(a, next, if (phase == null) "call_ended" else "call_$phase")
                return
            }
        }
    }

    /**
     * Re-check the current attempt against live state and its own deadline: binds a call Telecom has added, and
     * releases an attempt whose call has been established or has ended, or that is past its deadline with no call.
     * Called before every acquire, by the keypad while its button is disabled, and after the post-dial check.
     */
    fun revalidate(snapshot: LiveCallSnapshot) {
        while (true) {
            val a = current.get() ?: return
            val checked = revalidated(a, snapshot)
            if (checked === a) return
            if (current.compareAndSet(a, checked)) {
                onTransition(a, checked, "revalidate")
                return
            }
        }
    }

    private fun revalidated(a: DialAttempt, snapshot: LiveCallSnapshot): DialAttempt {
        val now = clock()
        return when (a.state) {
            DialAttemptState.DISPATCHING ->
                if (now > a.deadline) a.copy(state = DialAttemptState.FAILED) else a

            DialAttemptState.WAITING_FOR_TELECOM -> {
                val newCall = snapshot.calls.entries.firstOrNull { (id, phase) ->
                    id !in a.callsLiveBefore && phase != LiveCallPhase.RINGING_INCOMING &&
                        !retiredCallIds.containsKeySynchronized(id)
                }
                when {
                    newCall != null -> bind(a, newCall.key, newCall.value)
                    now <= a.deadline -> a
                    // Only this dial can have made the call Telecom reports (none was live before it). Telecom
                    // being "in call" is not taken as proof that the dial worked, only as a reason to keep the
                    // button locked a little longer instead of allowing a dial Telecom would refuse.
                    snapshot.telecomInCall && a.callsLiveBefore.isEmpty() && now <= a.placedAt + UNSEEN_CALL_WINDOW_MS -> a
                    else -> a.copy(state = DialAttemptState.FAILED)
                }
            }

            DialAttemptState.TELECOM_ADDED -> when (a.callId?.let { snapshot.calls[it] }) {
                null -> a.copy(state = DialAttemptState.DISCONNECTED)
                LiveCallPhase.OUTGOING_SETTING_UP -> a
                else -> a.copy(state = DialAttemptState.CALL_ESTABLISHED)
            }

            else -> a
        }
    }

    private fun bind(a: DialAttempt, callId: String, phase: LiveCallPhase): DialAttempt {
        val state = if (phase == LiveCallPhase.OUTGOING_SETTING_UP) DialAttemptState.TELECOM_ADDED else DialAttemptState.CALL_ESTABLISHED
        return a.copy(state = state, callId = callId)
    }

    /** Applies [change] to the attempt [attemptId] if it is still current; a stale id is logged and ignored. */
    private fun update(attemptId: Long, event: String, change: (DialAttempt) -> DialAttempt?) {
        while (true) {
            val a = current.get()
            if (a == null || a.id != attemptId) {
                log("${DialAttemptEvents.STALE_CALLBACK_IGNORED} attempt=$attemptId current=${a?.id ?: 0} event=$event")
                return
            }
            val next = change(a) ?: return
            if (current.compareAndSet(a, next)) {
                onTransition(a, next, event)
                return
            }
        }
    }

    private fun onTransition(from: DialAttempt, to: DialAttempt, reason: String) {
        if (from.state != to.state) {
            when (to.state) {
                DialAttemptState.TELECOM_ADDED ->
                    log("${DialAttemptEvents.TELECOM_ADDED} attempt=${to.id} callId=${to.callId} via=$reason")
                DialAttemptState.CALL_ESTABLISHED -> {
                    if (from.state != DialAttemptState.TELECOM_ADDED) {
                        log("${DialAttemptEvents.TELECOM_ADDED} attempt=${to.id} callId=${to.callId} via=$reason")
                    }
                    log("${DialAttemptEvents.ESTABLISHED} attempt=${to.id} callId=${to.callId} via=$reason")
                }
                DialAttemptState.FAILED ->
                    log("${DialAttemptEvents.FAILED} attempt=${to.id} from=${from.state} reason=${failureReason(from, reason)}")
                DialAttemptState.DISCONNECTED ->
                    log("${DialAttemptEvents.DISCONNECTED} attempt=${to.id} callId=${to.callId} via=$reason")
                else -> Unit
            }
            if (from.state.holdsGuard && !to.state.holdsGuard) {
                log("${DialAttemptEvents.GUARD_RELEASED} attempt=${to.id} state=${to.state}")
            }
        }
        publish(to)
    }

    private fun failureReason(from: DialAttempt, reason: String): String = when {
        !reason.startsWith("revalidate") -> reason.removePrefix("fail:")
        from.state == DialAttemptState.DISPATCHING -> "never_dispatched_before_deadline"
        else -> "telecom_never_added_call"
    }

    // Derived from the attempt that is current NOW (not the one passed in), under a lock, so two transitions racing
    // on different threads cannot leave a stale "locked" behind: the last publisher always reads the latest state.
    private fun publish(@Suppress("UNUSED_PARAMETER") changed: DialAttempt?) {
        synchronized(_locked) {
            val now = current.get()
            val isLocked = now?.state?.holdsGuard == true
            if (isLocked && !_locked.value) log("${DialAttemptEvents.BUTTON_DISABLED} attempt=${now!!.id}")
            _locked.value = isLocked
        }
    }

    private fun retire(a: DialAttempt?) {
        val id = a?.callId ?: return
        synchronized(retiredCallIds) { retiredCallIds[id] = a.id }
    }

    private fun LinkedHashMap<String, Long>.containsKeySynchronized(key: String): Boolean =
        synchronized(this) { containsKey(key) }

    private fun LinkedHashMap<String, Long>.getSynchronized(key: String): Long? =
        synchronized(this) { get(key) }
}

/** Log event names (every line carries the attempt id; never a phone number). */
internal object DialAttemptEvents {
    const val STARTED = "dial_attempt_started"
    const val BUTTON_DISABLED = "dial_button_disabled"
    const val GUARD_ACQUIRED = "dial_attempt_guard_acquired"
    const val DUPLICATE_BLOCKED = "dial_attempt_duplicate_blocked"
    const val DISPATCHED = "dial_attempt_dispatched"
    const val TELECOM_ADDED = "dial_attempt_telecom_added"
    const val ESTABLISHED = "dial_attempt_established"
    const val FAILED = "dial_attempt_failed"
    const val DISCONNECTED = "dial_attempt_disconnected"
    const val GUARD_RELEASED = "dial_attempt_guard_released"
    const val STALE_CALLBACK_IGNORED = "dial_attempt_stale_callback_ignored"
}
