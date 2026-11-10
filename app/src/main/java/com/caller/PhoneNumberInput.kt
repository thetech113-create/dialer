package com.caller

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Turns whatever was typed or pasted into the part of an Indian mobile number that follows +91, so a wrong number
 * cannot be entered: digits only, a country code or leading 0 that came with a paste is dropped, the first digit is
 * 6-9 (what Indian mobile numbers start with) and there are at most 10 digits.
 */
fun cleanIndianMobileInput(raw: String): String {
    var digits = raw.filter { it in '0'..'9' }
    // Pasted with its country code (+91 98765 43210, 0091 ..., 91 98765 43210). A shorter string that happens to
    // begin with 91 is a number being typed, not a country code.
    if (digits.startsWith("0091")) {
        digits = digits.drop(4)
    } else if (digits.length >= 12 && digits.startsWith("91")) {
        digits = digits.drop(2)
    }
    return digits.dropWhile { it !in '6'..'9' }.take(10)
}

/**
 * One entry per phone number. The same number is kept in several forms (9876543210, 919876543210, +91 98765 43210),
 * which are different strings but the same number, so the first form seen stands for all of them.
 */
fun distinctMobileNumbers(numbers: List<String>): List<String> {
    val seen = HashSet<String>()
    return numbers.map { it.trim() }.filter { it.isNotEmpty() }
        .filter { seen.add(buildP2PNumberSnapshot(it).last10.ifEmpty { it }) }
}

/** "9876543210" -> "98765 43210". */
fun groupMobileDigits(digits: String): String =
    if (digits.length > 5) digits.substring(0, 5) + " " + digits.substring(5) else digits

/** What the phone's SIMs tell us: how many are active, and the clean numbers of those that report one. */
data class SimScan(
    val permissionGranted: Boolean,
    val simCount: Int,
    val numbers: List<DetectedSimNumber>
)

data class DetectedSimNumber(val slot: Int, val number: String)

private fun simNumberPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Manifest.permission.READ_PHONE_NUMBERS
    else Manifest.permission.READ_PHONE_STATE

private fun hasSimNumberPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

/**
 * Reads the active SIMs. Many carriers do not store the number on the SIM, so a SIM can be found without a number;
 * [SimScan.simCount] still counts it.
 */
@SuppressLint("MissingPermission", "HardwareIds")
fun scanSimNumbers(context: Context): SimScan {
    if (!hasSimNumberPermission(context)) return SimScan(false, 0, emptyList())
    val found = mutableListOf<DetectedSimNumber>()
    var count = 0
    runCatching {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val infos = subscriptions?.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
        count = infos.size
        infos.forEach { info ->
            val fromSystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { subscriptions?.getPhoneNumber(info.subscriptionId) }.getOrNull().orEmpty()
            } else ""
            val number = cleanIndianMobileInput(fromSystem.ifBlank { info.number.orEmpty() })
            if (number.length == 10) {
                found += DetectedSimNumber(info.simSlotIndex + 1, number)
            }
        }
    }
    if (found.isEmpty()) {
        runCatching {
            val line1 = (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.line1Number.orEmpty()
            val number = cleanIndianMobileInput(line1)
            if (number.length == 10) {
                found += DetectedSimNumber(1, number)
                if (count == 0) count = 1
            }
        }
    }
    return SimScan(true, count, found.distinctBy { it.number })
}

/** Shows the 10 digits as "98765 43210"; the text that is stored and edited stays plain digits. */
private object MobileNumberGrouping : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 5) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 5) offset - 1 else offset
        }
        return TransformedText(AnnotatedString(groupMobileDigits(text.text)), mapping)
    }
}

/**
 * The one field for typing this phone's own mobile number. [value] is always the clean 10 digits after +91;
 * [onValueChange] only ever receives a clean value. While it is enabled it also looks at the phone's SIMs: a single
 * readable number fills an empty field by itself, with two SIMs each number is offered to tap.
 */
@Composable
fun OwnMobileNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(cleanIndianMobileInput(it)) },
            label = { Text(label) },
            prefix = { Text("+91 ") },
            supportingText = { Text("${value.length}/10") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            visualTransformation = MobileNumberGrouping,
            modifier = Modifier.fillMaxWidth()
        )
        if (enabled) SimNumberSuggestions(value, onValueChange)
    }
}

@Composable
private fun SimNumberSuggestions(value: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var scan by remember { mutableStateOf(scanSimNumbers(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scan = scanSimNumbers(context)
    }
    // Only one number can be the answer, so it is filled in without a tap. Never over something already typed.
    LaunchedEffect(scan) {
        if (value.isEmpty() && scan.numbers.size == 1) onPick(scan.numbers.first().number)
    }

    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        !scan.permissionGranted -> TextButton(onClick = { permissionLauncher.launch(simNumberPermission()) }) {
            Text("Detect number from SIM")
        }
        scan.numbers.isEmpty() -> Text(
            if (scan.simCount > 0) {
                "${scan.simCount} SIM${if (scan.simCount > 1) "s" else ""} found, but the carrier does not share the number. Type it above."
            } else {
                "No SIM found. Type your number above."
            },
            style = MaterialTheme.typography.bodySmall,
            color = hintColor
        )
        else -> Column {
            Text(
                if (scan.simCount > 1) "${scan.simCount} SIMs found. Tap yours:" else "Found on your SIM:",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor
            )
            scan.numbers.forEach { sim ->
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = { onPick(sim.number) }, modifier = Modifier.fillMaxWidth()) {
                    Text("SIM ${sim.slot}  •  +91 ${groupMobileDigits(sim.number)}")
                }
            }
        }
    }
}
