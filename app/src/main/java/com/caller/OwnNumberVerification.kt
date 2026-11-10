package com.caller

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

sealed interface OwnNumberState {
    /** Verified with the name server and still this phone's SIM: nothing to ask and nothing to edit. */
    data class Verified(val number: String) : OwnNumberState

    /** Not verified yet, or the SIM now carries another number than the verified one. */
    data class NeedsVerification(val suggested: String, val simChanged: Boolean, val oldVerified: String) : OwnNumberState
}

/**
 * [simNumbersWhenVerified] is what the SIMs reported when the number was verified. It keeps a number that the server
 * verified (so it really belongs to this phone) from being asked again just because the SIM reports it differently.
 */
fun decideOwnNumberState(
    verified: String?,
    simNumbers: List<String>,
    simNumbersWhenVerified: Set<String>? = null
): OwnNumberState {
    val verifiedNumber = verified?.let(::cleanIndianMobileInput)?.takeIf { it.length == 10 }
    return when {
        verifiedNumber == null -> OwnNumberState.NeedsVerification(simNumbers.firstOrNull().orEmpty(), false, "")
        simNumbers.isEmpty() || verifiedNumber in simNumbers || simNumbers.toSet() == simNumbersWhenVerified ->
            OwnNumberState.Verified(verifiedNumber)
        else -> OwnNumberState.NeedsVerification(simNumbers.first(), true, verifiedNumber)
    }
}

private const val NUMBER_PREFS = "own_number_verification"
private const val KEY_SIMS_WHEN_VERIFIED = "sims_when_verified"
private const val KEY_PROMPTED_NUMBER = "prompted_number"

private fun numberPrefs(context: Context) =
    context.applicationContext.getSharedPreferences(NUMBER_PREFS, Context.MODE_PRIVATE)

private fun currentSimNumbers(context: Context): List<String> = scanSimNumbers(context).numbers.map { it.number }

fun ownNumberState(context: Context): OwnNumberState {
    val whenVerified = runCatching {
        numberPrefs(context).getString(KEY_SIMS_WHEN_VERIFIED, null)?.split(',')?.filter { it.isNotBlank() }?.toSet()
    }.getOrNull()
    return decideOwnNumberState(p2pServerVerifiedNumber(context), currentSimNumbers(context), whenVerified)
}

/** Called when the server confirms the number: remembers which SIM numbers this phone had at that moment. */
fun rememberVerifiedSimNumbers(context: Context) {
    runCatching {
        val sims = currentSimNumbers(context).toSortedSet().joinToString(",")
        numberPrefs(context).edit().putString(KEY_SIMS_WHEN_VERIFIED, sims).apply()
    }
}

/** A new number is asked about once; after that the user finds it behind the button. */
fun shouldPromptForNumber(context: Context, suggested: String): Boolean =
    suggested.isNotBlank() && runCatching { numberPrefs(context).getString(KEY_PROMPTED_NUMBER, "") }.getOrNull() != suggested

fun markNumberPrompted(context: Context, suggested: String) {
    runCatching { numberPrefs(context).edit().putString(KEY_PROMPTED_NUMBER, suggested).apply() }
}

/**
 * The one dialog for this phone's own number, for the Settings card and the Recents menu alike.
 * Verified: shows the number, locked. A SIM with another number: that number is filled in and verification is asked
 * for. After the server confirms it, the number is locked again.
 */
@Composable
fun OwnNumberVerificationDialog(onDismiss: () -> Unit, onVerified: (String) -> Unit = {}) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ownNumberState(context)) }
    var number by remember {
        mutableStateOf(
            when (val s = state) {
                is OwnNumberState.Verified -> s.number
                is OwnNumberState.NeedsVerification -> s.suggested
            }
        )
    }
    var status by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    val verified = state is OwnNumberState.Verified
    val simChanged = (state as? OwnNumberState.NeedsVerification)?.simChanged == true

    // The SMS is handled in the background: wait until the server has confirmed THIS number (about a minute at most).
    LaunchedEffect(waiting) {
        if (!waiting) return@LaunchedEffect
        repeat(60) {
            delay(1000L)
            if (cleanIndianMobileInput(p2pServerVerifiedNumber(context).orEmpty()) == number) {
                rememberVerifiedSimNumbers(context)
                state = ownNumberState(context)
                status = "Verified successfully"
                waiting = false
                onVerified(number)
                return@LaunchedEffect
            }
        }
        status = "No verification SMS arrived. Check the number and try again."
        waiting = false
    }

    AlertDialog(
        onDismissRequest = { if (!waiting) onDismiss() },
        title = { Text(if (verified) "Phone verified" else if (simChanged) "Verify new SIM" else "Verify this phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        verified -> "This phone's number is verified. It stays locked while the SIM stays the same."
                        simChanged -> "A different SIM number was found. Verify it so this phone keeps working with your other devices. The app sends one SMS to that number."
                        else -> "Enter this phone's own SIM number. The app sends a one-time SMS to that same number to securely enable cloud syncing across different networks."
                    }
                )
                OwnMobileNumberField(
                    value = number,
                    onValueChange = { number = it },
                    label = "This phone's mobile number",
                    enabled = !waiting && !verified,
                    modifier = Modifier.fillMaxWidth()
                )
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (verified) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(
                    enabled = number.length == 10 && !waiting,
                    onClick = {
                        val old = (state as? OwnNumberState.NeedsVerification)?.oldVerified.orEmpty()
                        AccountDeviceHistoryManager.registerVerifiedNumber(context, number)
                        // The old SIM's number is replaced, not kept next to the new one.
                        if (old.isNotEmpty() && old != number) AccountDeviceHistoryManager.removeVerifiedNumber(context, old)
                        status = "Sending verification SMS…"
                        startP2PServerVerification(context, number) { message ->
                            status = message
                            waiting = message.startsWith("Verifying")
                        }
                    }
                ) { Text(if (waiting) "Waiting for SMS…" else "Verify") }
            }
        },
        dismissButton = {
            if (!waiting && !verified) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
