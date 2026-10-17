package com.caller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Real dual-SIM detection and routing helpers.
 *
 * This intentionally fails fast when SIM state is ambiguous so we never
 * silently fall back to SIM 1 on multi-SIM devices.
 */
object DualSimFixes {

    private const val TAG = "DualSimFixes"

    data class SimCallOption(
        val subscriptionId: Int,
        val slotIndex: Int,
        val carrierName: String,
        val phoneAccountHandle: PhoneAccountHandle,
        // False when this is the only active SIM: then "SIM 1" tells the user nothing.
        val isMultiSim: Boolean = true
    ) {
        /** Label for the call screen and Recents; blank unless two or more SIMs are really active. */
        fun compactLabel(): String {
            if (!isMultiSim) return ""
            return "SIM ${slotIndex + 1}"
        }

        fun displayLabel(): String {
            val safeCarrier = carrierName.ifBlank { "Unknown" }
            return "SIM ${slotIndex + 1} - $safeCarrier"
        }
    }

    data class CallLogSimLabelLookup(
        val byPair: Map<String, String> = emptyMap(),
        val byId: Map<String, String> = emptyMap(),
        val byComponent: Map<String, String> = emptyMap(),
        // When each SIM (by account id) was actually in the phone, as recorded by this app. A SIM removed and
        // put back later gets separate periods, so the time it was out never counts as dual-SIM time.
        val presenceById: Map<String, List<LongRange>> = emptyMap(),
        // When presence recording started (0 = never); earlier calls can only be judged from the call log.
        val trackingSince: Long = 0L,
        // Telecom components of SIM accounts; a call-log row with one of these was made on a SIM even when
        // that SIM's label is unknown (removed before its slot/carrier could be saved).
        val simComponents: Set<String> = emptySet()
    ) {
        fun resolve(phoneAccountId: String?, phoneAccountComponentName: String?): String {
            val id = phoneAccountId.orEmpty()
            val componentName = phoneAccountComponentName.orEmpty()

            if (id.isNotBlank() && componentName.isNotBlank()) {
                byPair["$componentName|$id"]?.let { return it }
            }
            if (id.isNotBlank()) {
                byId[id]?.let { return it }
            }
            if (componentName.isNotBlank()) {
                byComponent[componentName]?.let { return it }
            }
            return ""
        }
    }
    fun hasRequiredSubscriptionPermissions(context: Context): Boolean {
        return missingSubscriptionPermissions(context).isEmpty()
    }

    fun missingSubscriptionPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.READ_PHONE_STATE
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.READ_PHONE_NUMBERS
        }
        return missing
    }

    @SuppressLint("MissingPermission")
    fun resolveSimCallOptions(context: Context): List<SimCallOption> {
        if (!hasRequiredSubscriptionPermissions(context)) {
            throw SecurityException("SIM detection permissions missing")
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val multiSimCapable = telephonyManager.phoneCount > 1

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: throw IllegalStateException("Subscription service unavailable")
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        val activeSubscriptions = (subscriptionManager.activeSubscriptionInfoList ?: emptyList())
            .sortedBy { it.simSlotIndex }

        logActiveSubscriptions(activeSubscriptions, null, multiSimCapable)

        if (activeSubscriptions.isEmpty()) {
            throw IllegalStateException("No SIM detected")
        }

        val simPhoneAccounts = telecomManager.callCapablePhoneAccounts.mapNotNull { handle ->
            val account = telecomManager.getPhoneAccount(handle) ?: return@mapNotNull null
            if (!account.isEnabled || !account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                return@mapNotNull null
            }
            handle to account
        }

        if (simPhoneAccounts.isEmpty()) {
            throw IllegalStateException("No enabled SIM calling accounts available")
        }

        val options = activeSubscriptions.map { subscriptionInfo ->
            val handle = findMatchingHandle(subscriptionInfo, activeSubscriptions, simPhoneAccounts)
                ?: throw IllegalStateException(
                    "Unable to map SIM ${subscriptionInfo.subscriptionId} to a Telecom phone account"
                )
            SimCallOption(
                subscriptionId = subscriptionInfo.subscriptionId,
                slotIndex = subscriptionInfo.simSlotIndex,
                carrierName = subscriptionInfo.carrierName?.toString().orEmpty().ifBlank { "Unknown" },
                phoneAccountHandle = handle
            )
        }.distinctBy { it.subscriptionId }
            .let { list -> list.map { it.copy(isMultiSim = list.size > 1) } }

        if (options.size != activeSubscriptions.size) {
            throw IllegalStateException(
                "Mapped ${options.size} SIM call options for ${activeSubscriptions.size} active subscriptions"
            )
        }

        if (multiSimCapable && activeSubscriptions.size > 1 && options.size < 2) {
            throw IllegalStateException("Multi-SIM device detected but chooser cannot be built")
        }

        return options
    }

    fun resolveCompactSimLabel(context: Context, handle: PhoneAccountHandle?): String {
        if (handle == null) return ""
        return runCatching {
            resolveSimCallOptions(context).firstOrNull { it.phoneAccountHandle == handle }?.compactLabel().orEmpty()
        }.getOrDefault("")
    }

    private const val SIM_LABEL_HISTORY_PREFS = "sim_label_history"
    private const val HISTORY_PAIR_PREFIX = "pair:"
    private const val HISTORY_ID_PREFIX = "id:"
    private const val HISTORY_PERIODS_PREFIX = "periods:"
    private const val HISTORY_LAST_CHECK = "last_check"
    private const val HISTORY_TRACKING_SINCE = "tracking_since"
    private const val MAX_PRESENCE_PERIODS = 50

    private fun parsePresencePeriods(raw: String?): MutableList<LongRange> {
        return raw.orEmpty().split(";").mapNotNull { part ->
            val start = part.substringBefore("-").toLongOrNull() ?: return@mapNotNull null
            val end = part.substringAfter("-").toLongOrNull() ?: return@mapNotNull null
            start..end
        }.toMutableList()
    }

    private fun formatPresencePeriods(periods: List<LongRange>): String {
        return periods.takeLast(MAX_PRESENCE_PERIODS).joinToString(";") { "${it.first}-${it.last}" }
    }

    /**
     * Labels ("SIM 1 - Jio") for call-log rows, by the row's phone account.
     *
     * Each SIM's slot and carrier are saved whenever that SIM is active, so a call made on a SIM that has
     * since been removed still gets its label. Whether a row should show the label at all (only calls made
     * while two SIMs were in use) is decided by the caller from the call log.
     */
    fun buildCallLogSimLabelLookup(context: Context): CallLogSimLabelLookup {
        val liveOptions = if (hasRequiredSubscriptionPermissions(context)) {
            runCatching { resolveSimCallOptions(context) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return runCatching {
            val history = context.getSharedPreferences(SIM_LABEL_HISTORY_PREFS, Context.MODE_PRIVATE)
            val byComponent = linkedMapOf<String, String>()

            if (liveOptions.isNotEmpty()) {
                val editor = history.edit()
                val now = System.currentTimeMillis()
                val previousCheck = history.getLong(HISTORY_LAST_CHECK, 0L)
                liveOptions.map { it.phoneAccountHandle.id }.filter { it.isNotBlank() }.distinct().forEach { id ->
                    // Present at the previous check too: extend its current period. Otherwise it was just
                    // inserted (or put back), so it starts a new period.
                    val periods = parsePresencePeriods(history.getString(HISTORY_PERIODS_PREFIX + id, null))
                    val last = periods.lastOrNull()
                    if (last != null && previousCheck != 0L && last.last == previousCheck) {
                        periods[periods.lastIndex] = last.first..now
                    } else {
                        periods += now..now
                    }
                    editor.putString(HISTORY_PERIODS_PREFIX + id, formatPresencePeriods(periods))
                }
                editor.putLong(HISTORY_LAST_CHECK, now)
                if (!history.contains(HISTORY_TRACKING_SINCE)) {
                    editor.putLong(HISTORY_TRACKING_SINCE, now)
                }
                liveOptions.forEach { option ->
                    val handle = option.phoneAccountHandle
                    val label = option.displayLabel()
                    val componentNames = listOf(
                        handle.componentName.flattenToString(),
                        handle.componentName.flattenToShortString()
                    ).filter { it.isNotBlank() }

                    if (handle.id.isNotBlank()) {
                        editor.putString(HISTORY_ID_PREFIX + handle.id, label)
                    }
                    componentNames.forEach { componentName ->
                        // Component alone is the same for every SIM, so it is only a live fallback.
                        byComponent.putIfAbsent(componentName, label)
                        if (handle.id.isNotBlank()) {
                            editor.putString("$HISTORY_PAIR_PREFIX$componentName|${handle.id}", label)
                        }
                    }
                }
                editor.apply()
            }

            val byPair = linkedMapOf<String, String>()
            val byId = linkedMapOf<String, String>()
            // Live SIMs first so a SIM's current carrier name wins over an older saved one.
            liveOptions.forEach { option ->
                val handle = option.phoneAccountHandle
                if (handle.id.isNotBlank()) {
                    byId[handle.id] = option.displayLabel()
                    listOf(handle.componentName.flattenToString(), handle.componentName.flattenToShortString())
                        .filter { it.isNotBlank() }
                        .forEach { byPair["$it|${handle.id}"] = option.displayLabel() }
                }
            }
            val presenceById = linkedMapOf<String, List<LongRange>>()
            history.all.forEach { (key, value) ->
                val text = value as? String ?: return@forEach
                when {
                    key.startsWith(HISTORY_PAIR_PREFIX) -> byPair.putIfAbsent(key.removePrefix(HISTORY_PAIR_PREFIX), text)
                    key.startsWith(HISTORY_ID_PREFIX) -> byId.putIfAbsent(key.removePrefix(HISTORY_ID_PREFIX), text)
                    key.startsWith(HISTORY_PERIODS_PREFIX) ->
                        presenceById[key.removePrefix(HISTORY_PERIODS_PREFIX)] = parsePresencePeriods(text)
                }
            }

            CallLogSimLabelLookup(
                byPair = byPair,
                byId = byId,
                byComponent = if (liveOptions.size == 1) byComponent else emptyMap(),
                presenceById = presenceById,
                trackingSince = history.getLong(HISTORY_TRACKING_SINCE, 0L),
                simComponents = byComponent.keys +
                    byPair.keys.map { it.substringBeforeLast('|') }.filter { it.isNotBlank() }
            )
        }.getOrDefault(CallLogSimLabelLookup())
    }
    fun buildCallExtras(option: SimCallOption): Bundle {
        return Bundle().apply {
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY)
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, option.phoneAccountHandle)
            putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, option.subscriptionId)
        }
    }

    fun showSimSelectionDialog(
        activity: Activity,
        options: List<SimCallOption>,
        onSelected: (SimCallOption) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (options.isEmpty()) {
            onCancelled()
            return
        }
        if (options.size == 1) {
            logOptionSnapshot(options, options.first().subscriptionId)
            onSelected(options.first())
            return
        }

        val labels = options.map { it.displayLabel() }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Choose SIM")
            .setCancelable(true)
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                val selected = options[which]
                logOptionSnapshot(options, selected.subscriptionId)
                onSelected(selected)
            }
            .setOnCancelListener {
                logOptionSnapshot(options, null)
                onCancelled()
            }
            .show()
    }

    private fun findMatchingHandle(
        subscriptionInfo: SubscriptionInfo,
        activeSubscriptions: List<SubscriptionInfo>,
        simPhoneAccounts: List<Pair<PhoneAccountHandle, PhoneAccount>>
    ): PhoneAccountHandle? {
        val exactIdMatch = simPhoneAccounts.firstOrNull { (handle, _) ->
            handle.id == subscriptionInfo.subscriptionId.toString()
        }?.first
        if (exactIdMatch != null) {
            return exactIdMatch
        }

        val extrasMatch = simPhoneAccounts.firstOrNull { (_, account) ->
            account.extras?.getInt(
                SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            ) == subscriptionInfo.subscriptionId
        }?.first
        if (extrasMatch != null) {
            return extrasMatch
        }

        val carrierName = subscriptionInfo.carrierName?.toString().orEmpty().trim()
        if (carrierName.isNotEmpty()) {
            val carrierMatches = simPhoneAccounts.filter { (_, account) ->
                val label = account.label?.toString().orEmpty().trim()
                val shortDescription = account.shortDescription?.toString().orEmpty().trim()
                label.equals(carrierName, ignoreCase = true) ||
                    shortDescription.equals(carrierName, ignoreCase = true)
            }
            if (carrierMatches.size == 1) {
                return carrierMatches.first().first
            }
        }

        val normalizedSubscriptionNumber = normalizePhoneNumber(subscriptionInfo.number.orEmpty())
        if (normalizedSubscriptionNumber.isNotEmpty()) {
            val numberMatches = simPhoneAccounts.filter { (_, account) ->
                normalizePhoneNumber(account.address?.schemeSpecificPart.orEmpty()) == normalizedSubscriptionNumber
            }
            if (numberMatches.size == 1) {
                return numberMatches.first().first
            }
        }

        if (activeSubscriptions.size == 1 && simPhoneAccounts.size == 1) {
            return simPhoneAccounts.first().first
        }

        return null
    }

    private fun normalizePhoneNumber(number: String): String {
        return buildString(number.length) {
            number.forEach { ch ->
                if (ch.isDigit() || ch == '+') {
                    append(ch)
                }
            }
        }
    }

    private fun logActiveSubscriptions(
        subscriptions: List<SubscriptionInfo>,
        selectedSimId: Int?,
        multiSimCapable: Boolean
    ) {
        val lines = mutableListOf<String>()
        lines += "[SIM_DEBUG]"
        lines += "multiSimCapable=$multiSimCapable"
        lines += "activeSimCount=${subscriptions.size}"
        subscriptions.forEachIndexed { index, subscriptionInfo ->
            val carrier = subscriptionInfo.carrierName?.toString().orEmpty().ifBlank { "Unknown" }
            lines += "sim[$index]={id=${subscriptionInfo.subscriptionId}, slot=${subscriptionInfo.simSlotIndex}, carrier=$carrier}"
        }
        if (selectedSimId != null) {
            lines += "selectedSimId=$selectedSimId"
        }
        Log.w(TAG, lines.joinToString("\n"))
    }

    private fun logOptionSnapshot(options: List<SimCallOption>, selectedSimId: Int?) {
        val lines = mutableListOf<String>()
        lines += "[SIM_DEBUG]"
        lines += "activeSimCount=${options.size}"
        options.forEachIndexed { index, option ->
            lines += "sim[$index]={id=${option.subscriptionId}, slot=${option.slotIndex}, carrier=${option.carrierName}}"
        }
        if (selectedSimId != null) {
            lines += "selectedSimId=$selectedSimId"
        }
        Log.w(TAG, lines.joinToString("\n"))
    }
}



