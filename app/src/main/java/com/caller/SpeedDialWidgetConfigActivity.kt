package com.caller

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpeedDialWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the result to CANCELED. This causes the widget host to cancel
        // out of widget placement if the user presses the back button.
        setResult(RESULT_CANCELED)

        // Find the widget id from the intent.
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            var contacts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                contacts = loadContactsSimple()
                isLoading = false
            }

            Scaffold(
                topBar = {
                    TopAppBar(title = { Text("Select Contact for Widget") })
                }
            ) { padding ->
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                        items(contacts) { contact ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onContactSelected(contact.first, contact.second) }
                                    .padding(16.dp)
                            ) {
                                Text(text = contact.first, style = MaterialTheme.typography.bodyLarge)
                                Text(text = contact.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Divider()
                        }
                    }
                }
            }
        }
    }

    private fun onContactSelected(name: String, number: String) {
        // 1. Save the contact info to SharedPreferences for this specific widget ID
        val prefs = getSharedPreferences("speed_dial_widgets", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("name_$appWidgetId", name)
            .putString("number_$appWidgetId", number)
            .apply()

        // 2. Force the widget to update its UI with the new name
        val appWidgetManager = AppWidgetManager.getInstance(this)
        SpeedDialWidgetProvider.updateAppWidget(this, appWidgetManager, appWidgetId)

        // 3. Pass back the original appWidgetId so the launcher knows it was successful
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }

    // A lightweight contact fetcher just for the   widget picker
    private suspend fun loadContactsSimple(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Pair<String, String>>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: "Unknown"
                    val rawNumber = c.getString(1) ?: ""
                    
                    // CRITICAL FIX: Clean number of spaces and dashes so duplicates are caught
                    val cleanNumber = rawNumber.replace(Regex("[^\\d+]"), "")
                    
                    if (cleanNumber.isNotEmpty()) {
                        list.add(Pair(name, cleanNumber))
                    }
                }
            }
        } catch (e: Exception) {}
        
        // CRITICAL FIX: Removed the "return" keyword. Kotlin automatically returns the last line!
        list.distinctBy { it.second }
    }
}
