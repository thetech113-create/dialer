@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.caller

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler // ADDED FOR BACK NAVIGATION
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MessagesPage {
    THREADS,
    CONTACTS,
    CONVERSATION
}

@Composable
fun MessagesScreenContent(
    contacts: List<Contact>,
    isDefaultSmsApp: Boolean,
    hasContactAccess: Boolean,
    isContactsLoading: Boolean,
    refreshKey: Int,
    initialComposeAddress: String,
    initialComposeBody: String,
    initialThreadId: String = "",
    onConsumeComposeIntent: () -> Unit,
    onRequestDefaultSms: () -> Unit,
    onRequestSmsPermissions: () -> Unit,
    onRequestContactsPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasReadSmsAccess = remember(refreshKey) { hasReadSmsPermission(context) }
    val hasSendSmsAccess = remember(refreshKey) { hasSendSmsPermission(context) }
    // Switched to Dynamic Material 3 Colors for perfectly visible contrast
    val screenBackground = MaterialTheme.colorScheme.background
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val titleColor = MaterialTheme.colorScheme.onBackground
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val avatarColor = MaterialTheme.colorScheme.primaryContainer
    val avatarTint = MaterialTheme.colorScheme.onPrimaryContainer

    val contactLookup = remember(contacts) { buildSmsContactLookup(contacts) }
    val sortedContacts = remember(contacts) {
        contacts
            .distinctBy { buildP2PNumberSnapshot(it.phoneNumber).normalized.ifBlank { it.phoneNumber } }
            .sortedWith(compareBy<Contact> { it.name.lowercase(Locale.getDefault()) }.thenBy { it.phoneNumber })
    }

    var smsThreads by remember { mutableStateOf<List<SmsThread>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var localRefreshKey by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableStateOf(MessagesPage.THREADS) }
    var contactSearchQuery by remember { mutableStateOf("") }
    var selectedThreadId by remember { mutableStateOf("") }
    var composeAddress by remember { mutableStateOf("") }
    var composeDisplayName by remember { mutableStateOf("") }
    var composeBody by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    BackHandler(enabled = currentPage != MessagesPage.THREADS) {
        currentPage = MessagesPage.THREADS
        composeAddress = ""
        composeBody = ""
        selectedThreadId = ""
    }

    LaunchedEffect(initialComposeAddress, initialComposeBody, initialThreadId, contactLookup) {
        if (initialComposeAddress.isNotBlank() || initialComposeBody.isNotBlank()) {
            composeAddress = initialComposeAddress
            composeDisplayName = resolveSmsThreadDisplayName(context, composeAddress, contactLookup)
            composeBody = initialComposeBody
            selectedThreadId = ""
            currentPage = MessagesPage.CONVERSATION
            onConsumeComposeIntent()
        } else if (initialThreadId.isNotBlank()) {
            selectedThreadId = initialThreadId
            composeAddress = ""
            composeBody = ""
            currentPage = MessagesPage.CONVERSATION
        }
    }

    LaunchedEffect(isDefaultSmsApp, hasReadSmsAccess, refreshKey, localRefreshKey) {
        if (!isDefaultSmsApp || !hasReadSmsAccess) {
            smsThreads = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        smsThreads = withContext(Dispatchers.IO) {
            runCatching { loadSmsThreads(context) }.getOrDefault(emptyList())
        }
        isLoading = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = screenBackground,
        floatingActionButton = {
            if (currentPage == MessagesPage.THREADS && isDefaultSmsApp && hasReadSmsAccess) {
                FloatingActionButton(
                    onClick = {
                        contactSearchQuery = ""
                        currentPage = MessagesPage.CONTACTS
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Message")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBackground)
                .padding(innerPadding)
        ) {
            val messagesLabel = stringResource(R.string.launcher_messages_label)
            when {
                !isDefaultSmsApp -> MessagesStateCard(
                    title = "$messagesLabel Unavailable",
                    message = "Set $messagesLabel as your default SMS app to unlock messaging.",
                    buttonLabel = "Set as Default Messages App",
                    onClick = onRequestDefaultSms,
                    buttonSubtitle = "Swipe to make $messagesLabel your SMS app"
                )

                !hasReadSmsAccess -> MessagesStateCard(
                    title = "SMS Permission Needed",
                    message = "Allow SMS access so the app can load your message threads.",
                    buttonLabel = "Grant SMS Permissions",
                    onClick = onRequestSmsPermissions
                )

                currentPage == MessagesPage.THREADS -> MessagesThreadsPage(
                    threads = smsThreads,
                    isLoading = isLoading,
                    titleColor = titleColor,
                    bodyColor = bodyColor,
                    avatarColor = avatarColor,
                    avatarTint = avatarTint,
                    contactLookup = contactLookup,
                    onRefresh = { localRefreshKey++ },
                    onOpenThread = { thread ->
                        selectedThreadId = thread.threadId
                        composeAddress = thread.address
                        composeDisplayName = resolveSmsThreadDisplayName(context, thread.address, contactLookup)
                        composeBody = ""
                        currentPage = MessagesPage.CONVERSATION
                    }
                )

                currentPage == MessagesPage.CONTACTS -> ContactPickerPage(
                    contacts = sortedContacts,
                    hasContactAccess = hasContactAccess,
                    isContactsLoading = isContactsLoading,
                    searchQuery = contactSearchQuery,
                    titleColor = titleColor,
                    bodyColor = bodyColor,
                    outlineColor = outlineColor,
                    avatarColor = avatarColor,
                    avatarTint = avatarTint,
                    onSearchChange = { contactSearchQuery = it },
                    onBack = { currentPage = MessagesPage.THREADS },
                    onRequestContactsPermission = onRequestContactsPermission,
                    onSelectContact = { contact ->
                        selectedThreadId = ""
                        composeAddress = contact.phoneNumber
                        composeDisplayName = contact.name
                        composeBody = ""
                        currentPage = MessagesPage.CONVERSATION
                    }
                )

                else -> MessageConversationPage(
                    refreshKey = refreshKey + localRefreshKey,
                    selectedThreadId = selectedThreadId,
                    titleColor = titleColor,
                    bodyColor = bodyColor,
                    outlineColor = outlineColor,
                    avatarColor = avatarColor,
                    avatarTint = avatarTint,
                    hasSendSmsAccess = hasSendSmsAccess,
                    composeAddress = composeAddress,
                    composeDisplayName = composeDisplayName,
                    composeBody = composeBody,
                    isSending = isSending,
                    onBack = { currentPage = MessagesPage.THREADS },
                    onBodyChange = { composeBody = it },
                    onRequestSmsPermissions = onRequestSmsPermissions,
                    onSend = {
                        scope.launch {
                            isSending = true
                            val result = withContext(Dispatchers.IO) {
                                sendSmsMessage(context, composeAddress, composeBody)
                            }
                            isSending = false
                            if (result.isSuccess) {
                                Toast.makeText(context, "Message sent", Toast.LENGTH_SHORT).show()
                                composeBody = ""
                                localRefreshKey++
                            } else {
                                Toast.makeText(
                                    context,
                                    result.exceptionOrNull()?.message ?: "Unable to send message",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MessagesStateCard(
    title: String,
    message: String,
    buttonLabel: String,
    onClick: () -> Unit,
    buttonSubtitle: String? = null
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Message, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(20.dp))
                if (buttonSubtitle != null) {
                    // Same animated call-to-action as the dialer's "Set as Default Dialer".
                    DefaultAppButton(
                        title = buttonLabel,
                        subtitle = buttonSubtitle,
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.Message
                    )
                } else {
                    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
                }
            }
        }
    }
}

@Composable
private fun MessagesThreadsPage(
    threads: List<SmsThread>,
    isLoading: Boolean,
    titleColor: Color,
    bodyColor: Color,
    avatarColor: Color,
    avatarTint: Color,
    contactLookup: Map<String, String>,
    onRefresh: () -> Unit,
    onOpenThread: (SmsThread) -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp), // Extra padding for FAB
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("messages_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Messages", color = titleColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your recent conversations",
                        color = bodyColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = bodyColor)
                }
            }
        }

        if (isLoading) {
            item("messages_loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (threads.isEmpty()) {
            item("messages_empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Message, contentDescription = null, tint = bodyColor.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "No messages found", color = titleColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the + button to choose a person and start a message.",
                        color = bodyColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(threads) { thread ->
                val displayName = remember(thread.address, contactLookup) {
                    resolveSmsThreadDisplayName(context, thread.address, contactLookup)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onOpenThread(thread) }
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(CircleShape).background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.People, contentDescription = null, tint = avatarTint)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            color = titleColor,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = thread.snippet,
                            color = bodyColor,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = formatSmsTimestamp(thread.date), color = bodyColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ContactPickerPage(
    contacts: List<Contact>,
    hasContactAccess: Boolean,
    isContactsLoading: Boolean,
    searchQuery: String,
    titleColor: Color,
    bodyColor: Color,
    outlineColor: Color,
    avatarColor: Color,
    avatarTint: Color,
    onSearchChange: (String) -> Unit,
    onBack: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onSelectContact: (Contact) -> Unit
) {
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) || it.phoneNumber.contains(searchQuery)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = titleColor)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Choose Contact", color = titleColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            placeholder = { Text("Search Contacts...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = bodyColor) }
        )

        Spacer(modifier = Modifier.height(12.dp))


        if (!hasContactAccess) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Contacts access needed", color = titleColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Allow contacts to show people from your phone book.",
                    color = bodyColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRequestContactsPermission) {
                    Text("Allow Contacts")
                }
            }
        } else if (isContactsLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (filteredContacts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "No contacts found" else "No contact found",
                    color = titleColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (searchQuery.isBlank()) "Your contacts list looks empty right now." else "Try another name or number.",
                    color = bodyColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredContacts) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onSelectContact(contact) }
                            .padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(46.dp).clip(CircleShape).background(avatarColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.People, contentDescription = null, tint = avatarTint)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.name,
                                color = titleColor,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatSmsPhoneNumberForDisplay(contact.phoneNumber),
                                color = bodyColor,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow =  TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageConversationPage(
    refreshKey: Int,
    selectedThreadId: String,
    titleColor: Color,
    bodyColor: Color,
    outlineColor: Color,
    avatarColor: Color,
    avatarTint: Color,
    hasSendSmsAccess: Boolean,
    composeAddress: String,
    composeDisplayName: String,
    composeBody: String,
    isSending: Boolean,
    onBack: () -> Unit,
    onBodyChange: (String) -> Unit,
    onRequestSmsPermissions: () -> Unit,
    onSend: () -> Unit
) {
    val context = LocalContext.current
    val conversationListState = rememberLazyListState()
    var conversationMessages by remember { mutableStateOf<List<SmsConversationMessage>>(emptyList()) }
    var isConversationLoading by remember { mutableStateOf(false) }

    LaunchedEffect(composeAddress, selectedThreadId, refreshKey) {
        if (composeAddress.isBlank() && selectedThreadId.isBlank()) {
            conversationMessages = emptyList()
            isConversationLoading = false
            return@LaunchedEffect
        }
        if (composeAddress.isBlank()) {
            return@LaunchedEffect
        }
        isConversationLoading = true
        conversationMessages = withContext(Dispatchers.IO) {
            runCatching {
                loadSmsConversation(
                    context = context,
                    addressRaw = composeAddress,
                    threadId = selectedThreadId
                )
            }.getOrDefault(emptyList())
        }
        isConversationLoading = false
    }

    LaunchedEffect(composeAddress, selectedThreadId, conversationMessages.size) {
        if (conversationMessages.isNotEmpty()) {
            conversationListState.scrollToItem(conversationMessages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = titleColor)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.People, contentDescription = null, tint = avatarTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = composeDisplayName.ifBlank { composeAddress },
                    color = titleColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSmsPhoneNumberForDisplay(composeAddress),
                    color = bodyColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                isConversationLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                conversationMessages.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Message, contentDescription = null, tint = bodyColor, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "No old messages yet", color = titleColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Start the conversation below.",
                                color = bodyColor,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = conversationListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(conversationMessages) { message ->
                            ConversationMessageBubble(
                                message = message,
                                bodyColor = bodyColor
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (!hasSendSmsAccess) {
            Text(
                text = "Sending is disabled until SMS send permission is granted.",
                color = bodyColor,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRequestSmsPermissions) {
                Text("Grant Send Permission")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = composeBody,
                onValueChange = onBodyChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type message") },
                shape = RoundedCornerShape(24.dp),
                minLines = 1,
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = onSend,
                enabled = hasSendSmsAccess && composeBody.isNotBlank() && !isSending,
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send Message"
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationMessageBubble(
    message: SmsConversationMessage,
    bodyColor: Color
) {
    val bubbleColor = if (message.isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val bubbleTextColor = if (message.isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (message.isOutgoing) 22.dp else 4.dp,
                bottomEnd = if (message.isOutgoing) 4.dp else 22.dp
            ),
            color = bubbleColor
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                LinkifiedSmsText(
                    text = message.body,
                    textColor = bubbleTextColor,
                    linkColor = if (message.isOutgoing) Color(0xFF93C5FD) else Color(0xFF2563EB),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatSmsMessageTimestamp(message.date),
                    color = if (message.isOutgoing) bubbleTextColor.copy(alpha = 0.7f) else bodyColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}



private fun buildSmsContactLookup(contacts: List<Contact>): Map<String, String> {
    val lookup = linkedMapOf<String, String>()
    contacts.forEach { contact ->
        val snapshot = buildP2PNumberSnapshot(contact.phoneNumber)
        snapshot.lookupKeys.forEach { key ->
            if (key.isNotBlank()) {
                lookup.putIfAbsent(key, contact.name)
            }
        }
    }
    return lookup
}

private fun resolveSmsThreadDisplayName(
    context:Context,
    address: String,
    contactLookup: Map<String, String>
): String {
    val snapshot = buildP2PNumberSnapshot(address)
    val contactName = snapshot.lookupKeys.firstNotNullOfOrNull { key -> contactLookup[key] }
    if (!contactName.isNullOrBlank()) {
        return contactName
    }

    val p2pName = lookupP2PCache(
        context = context,
        phoneNumber = address,
        trigger = "MessagesUi.resolveSmsThreadDisplayName"
    ).name
    return p2pName?.takeIf { it.isNotBlank() } ?: address.ifBlank { "Unknown" }
}

private fun formatSmsTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = SimpleDateFormat("MMM dd", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun formatSmsMessageTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun formatSmsPhoneNumberForDisplay(phoneNumber: String): String {
    if (phoneNumber.contains("*") || phoneNumber.contains("#")) return phoneNumber
    if (phoneNumber.startsWith("+")) return phoneNumber
    val digitsOnly = phoneNumber.filter { it.isDigit() }
    if (digitsOnly.startsWith("1800") || digitsOnly.startsWith("1860") || digitsOnly.length <= 5) return phoneNumber
    if (digitsOnly.startsWith("0")) return phoneNumber
    if (digitsOnly.length == 10) return "+91$digitsOnly"
    if (digitsOnly.length == 12 && digitsOnly.startsWith("91")) return "+$digitsOnly"
    return phoneNumber
}






