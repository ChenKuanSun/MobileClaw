package ai.affiora.mobileclaw.ui.chat

import ai.affiora.mobileclaw.R
import ai.affiora.mobileclaw.data.model.ActionType
import ai.affiora.mobileclaw.data.model.MessageAction
import ai.affiora.mobileclaw.data.model.MessageRole
import ai.affiora.mobileclaw.data.db.ConversationEntity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val allMessages by viewModel.messages.collectAsStateWithLifecycle()
    // Filter out TOOL_RESULT messages — tool info is now embedded in assistant messages
    val messages = remember(allMessages) {
        allMessages.filter { it.role != MessageRole.TOOL_RESULT }
    }
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val tokenUsage by viewModel.tokenUsage.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val isThinking by viewModel.isThinking.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()

    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Consume shared intent data (ACTION_SEND from other apps)
    LaunchedEffect(Unit) {
        val (text, _) = ai.affiora.mobileclaw.ui.SharedIntentData.consume()
        if (text != null) {
            inputText = text
        }
    }

    // Search state
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Input history state
    var historyIndex by remember { mutableIntStateOf(-1) }
    val inputHistory by viewModel.inputHistory.collectAsStateWithLifecycle()

    // Slash command menu state
    val showSlashMenu by remember {
        derivedStateOf { inputText.startsWith("/") && !isProcessing }
    }
    val slashMatches by remember {
        derivedStateOf {
            if (inputText.startsWith("/")) {
                val query = inputText.split(" ").firstOrNull() ?: inputText
                SlashCommands.findMatches(query)
            } else {
                emptyList()
            }
        }
    }

    val canSend by remember {
        derivedStateOf { inputText.isNotBlank() }
    }

    // Find matching message indices for scrolling
    val matchingIndices by remember(messages, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) emptyList()
            else messages.mapIndexedNotNull { index, msg ->
                if (msg.content.contains(searchQuery, ignoreCase = true)) index else null
            }
        }
    }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty() && searchQuery.isBlank()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                conversations = conversations,
                onNewChat = {
                    viewModel.startNewConversation()
                    scope.launch { drawerState.close() }
                },
                onSwitchConversation = { id ->
                    viewModel.switchConversation(id)
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { id ->
                    viewModel.deleteConversation(id)
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Open sessions",
                                )
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = "MobileClaw",
                                    fontWeight = FontWeight.Bold,
                                )
                                if (tokenUsage.totalTokens > 0) {
                                    Text(
                                        text = "${formatNumber(tokenUsage.totalTokens)} tokens | \$${formatCost(tokenUsage.estimatedCostUsd)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                isSearchVisible = !isSearchVisible
                                if (!isSearchVisible) {
                                    searchQuery = ""
                                    currentMatchIndex = 0
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search messages",
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )

                    // Connection status bar
                    StatusBar(status = connectionStatus, isThinking = isThinking)

                    // Search bar
                    AnimatedVisibility(
                        visible = isSearchVisible,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut(),
                    ) {
                        SearchBar(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                currentMatchIndex = 0
                            },
                            matchCount = matchingIndices.size,
                            currentMatch = if (matchingIndices.isNotEmpty()) currentMatchIndex + 1 else 0,
                            onPrevious = {
                                if (matchingIndices.isNotEmpty()) {
                                    currentMatchIndex = (currentMatchIndex - 1 + matchingIndices.size) % matchingIndices.size
                                    scope.launch {
                                        listState.animateScrollToItem(matchingIndices[currentMatchIndex])
                                    }
                                }
                            },
                            onNext = {
                                if (matchingIndices.isNotEmpty()) {
                                    currentMatchIndex = (currentMatchIndex + 1) % matchingIndices.size
                                    scope.launch {
                                        listState.animateScrollToItem(matchingIndices[currentMatchIndex])
                                    }
                                }
                            },
                            onClose = {
                                isSearchVisible = false
                                searchQuery = ""
                                currentMatchIndex = 0
                            },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
            ) {
                // Message list
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        items = messages,
                        key = { it.id },
                    ) { message ->
                        MessageBubble(
                            message = message,
                            searchQuery = searchQuery,
                            isSpeaking = speakingMessageId == message.id,
                            onSpeak = { msgId, text -> viewModel.speak(msgId, text) },
                            onActionClicked = { action ->
                                when (action.type) {
                                    ActionType.SEND_MESSAGE -> {
                                        viewModel.sendMessage(action.value)
                                    }
                                    ActionType.SWITCH_MODEL -> {
                                        viewModel.switchModel(action.value)
                                    }
                                    else -> {}
                                }
                            },
                            onRetry = { text -> viewModel.sendMessage(text) },
                            onDelete = { id -> viewModel.deleteMessage(id) },
                        )
                    }

                    // Typing/thinking indicator
                    if (isProcessing) {
                        item {
                            if (isThinking) {
                                ThinkingIndicator()
                            } else {
                                TypingIndicator()
                            }
                        }
                    }
                }

                // Exec approval dialog
                pendingConfirmation?.let { confirmation ->
                    ExecApprovalDialog(
                        toolName = confirmation.toolName,
                        parameters = emptyMap(),
                        preview = confirmation.preview,
                        riskLevel = determineRiskLevel(confirmation.toolName),
                        onApprove = { choice ->
                            viewModel.confirmAction(
                                confirmed = true,
                                alwaysAllowChoice = choice,
                                toolName = confirmation.toolName,
                            )
                        },
                        onDeny = { viewModel.confirmAction(confirmed = false) },
                    )
                }

                // Slash command menu
                AnimatedVisibility(
                    visible = showSlashMenu && slashMatches.isNotEmpty(),
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    SlashCommandMenu(
                        commands = slashMatches,
                        onCommandSelected = { command ->
                            viewModel.sendMessage(command.name)
                            inputText = ""
                        },
                    )
                }

                // Quick command chips (hide when slash menu is visible)
                if (!showSlashMenu) {
                    QuickCommandChips(
                        onCommandSelected = { command ->
                            viewModel.sendMessage(command)
                        },
                    )
                }

                // Input bar with voice, history, and attachments
                InputBar(
                    text = inputText,
                    onTextChange = {
                        inputText = it
                        historyIndex = -1
                    },
                    onSend = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                        historyIndex = -1
                    },
                    canSend = canSend,
                    isProcessing = isProcessing,
                    onStop = { viewModel.cancelAgentRun() },
                    onSendWithAttachment = { uri, mimeType ->
                        viewModel.sendMessageWithAttachment(inputText, uri, mimeType)
                        inputText = ""
                        historyIndex = -1
                    },
                    onHistoryUp = {
                        if (inputHistory.isNotEmpty()) {
                            val newIndex = if (historyIndex < 0) 0
                            else (historyIndex + 1).coerceAtMost(inputHistory.size - 1)
                            historyIndex = newIndex
                            inputText = inputHistory[newIndex]
                        }
                    },
                    onHistoryDown = {
                        if (historyIndex > 0) {
                            historyIndex -= 1
                            inputText = inputHistory[historyIndex]
                        } else if (historyIndex == 0) {
                            historyIndex = -1
                            inputText = ""
                        }
                    },
                    onVoiceResult = { text ->
                        inputText = text
                    },
                )
            }
        }
    }
}

// ── Status Bar ───────────────────────────────────────────────────────────────

@Composable
private fun StatusBar(
    status: ConnectionStatus,
    isThinking: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.surface
            ConnectionStatus.PROCESSING -> MaterialTheme.colorScheme.primaryContainer
            ConnectionStatus.THINKING -> MaterialTheme.colorScheme.tertiaryContainer
            ConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        },
        label = "statusBarColor",
    )

    val dotColor = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) // green
        ConnectionStatus.PROCESSING -> MaterialTheme.colorScheme.primary
        ConnectionStatus.THINKING -> MaterialTheme.colorScheme.tertiary
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    val statusText = when (status) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.chat_status_connected)
        ConnectionStatus.PROCESSING -> stringResource(R.string.chat_status_processing)
        ConnectionStatus.THINKING -> stringResource(R.string.chat_status_thinking)
        ConnectionStatus.ERROR -> stringResource(R.string.chat_status_error)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Status dot — animated pulse for processing/thinking
        if (status == ConnectionStatus.PROCESSING || status == ConnectionStatus.THINKING) {
            val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulseAlpha",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        if (status == ConnectionStatus.PROCESSING || status == ConnectionStatus.THINKING) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = dotColor,
            )
        }
    }
}

// ── Typing Indicator (three animated dots) ───────────────────────────────────

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val delay = index * 200
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = delay, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .offset(y = offset.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_responding),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingAlpha",
    )

    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = alpha),
        )
        Text(
            text = stringResource(R.string.chat_status_thinking),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = alpha),
        )
    }
}

// ── Slash Command Menu ───────────────────────────────────────────────────────

@Composable
private fun SlashCommandMenu(
    commands: List<SlashCommand>,
    onCommandSelected: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        LazyColumn(
            modifier = Modifier.padding(vertical = 4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            val grouped = commands.groupBy { it.category }
            for ((category, cmds) in grouped) {
                item {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(cmds) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCommandSelected(cmd) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = cmd.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── Session Drawer ───────────────────────────────────────────────────────────

@Composable
private fun SessionDrawer(
    conversations: List<ConversationEntity>,
    onNewChat: () -> Unit,
    onSwitchConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier.fillMaxHeight()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_conversations),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                FilledTonalButton(onClick = onNewChat) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chat_new_chat),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_new_chat))
                }
            }

            // Conversation list
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_no_conversations),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    items(
                        items = conversations,
                        key = { it.id },
                    ) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            onClick = { onSwitchConversation(conversation.id) },
                            onDelete = { onDeleteConversation(conversation.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormatted = remember(conversation.updatedAt) {
        val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        formatter.format(Date(conversation.updatedAt))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete conversation",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ── Existing components (preserved) ──────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_search_placeholder)) },
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            singleLine = true,
            trailingIcon = {
                if (query.isNotBlank()) {
                    Text(
                        text = "$currentMatch/$matchCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
        )
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = "Previous match",
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Next match",
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close search",
            )
        }
    }
}

@Composable
fun ToolExecutionCard(
    toolName: String,
    isLoading: Boolean,
    result: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmationCard(
    toolName: String,
    preview: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Confirm: $toolName",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = onConfirm) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Confirm",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Confirm")
                }
            }
        }
    }
}

@Composable
private fun QuickCommandChips(
    onCommandSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val morningReport = stringResource(R.string.chat_quick_morning)
    val missedCalls = stringResource(R.string.chat_quick_calls)
    val todaySchedule = stringResource(R.string.chat_quick_schedule)
    val commands = remember(morningReport, missedCalls, todaySchedule) {
        listOf(morningReport, missedCalls, todaySchedule)
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(commands) { command ->
            AssistChip(
                onClick = { onCommandSelected(command) },
                label = { Text(command) },
            )
        }
    }
}

data class AttachmentState(
    val uri: Uri,
    val mimeType: String,
    val fileName: String,
    val thumbnail: Bitmap? = null,
)

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    isProcessing: Boolean,
    onStop: () -> Unit,
    onSendWithAttachment: (Uri, String) -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onVoiceResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingAttachment by remember { mutableStateOf<AttachmentState?>(null) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
            val fileName = getFileName(context, it)
            val thumbnail = if (mimeType.startsWith("image/")) {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    bitmap?.let { bmp ->
                        val scale = 80f / maxOf(bmp.width, bmp.height)
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    }
                } catch (_: Exception) { null }
            } else null
            pendingAttachment = AttachmentState(it, mimeType, fileName, thumbnail)
        }
    }

    // Camera launcher — uses TakePicture for full-resolution capture
    val cameraImageFile = remember {
        java.io.File(context.cacheDir, "camera_capture.jpg")
    }
    val cameraImageUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cameraImageFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success: Boolean ->
        if (success && cameraImageFile.exists()) {
            // Re-create URI from file for the attachment
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cameraImageFile)
            val thumbnail = try {
                val bitmap = BitmapFactory.decodeFile(cameraImageFile.absolutePath)
                bitmap?.let { bmp ->
                    val scale = 80f / maxOf(bmp.width, bmp.height)
                    Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                }
            } catch (_: Exception) { null }
            pendingAttachment = AttachmentState(uri, "image/jpeg", cameraImageFile.name, thumbnail)
        }
    }

    var showAttachmentMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Attachment preview
        pendingAttachment?.let { attachment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        attachment.thumbnail?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Preview",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (attachment.thumbnail == null) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = attachment.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                IconButton(
                    onClick = { pendingAttachment = null },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove attachment",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                singleLine = false,
                maxLines = 4,
                trailingIcon = {
                    Row {
                        IconButton(
                            onClick = onHistoryUp,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Previous input",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        IconButton(
                            onClick = onHistoryDown,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Next input",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.width(4.dp))

            // Attachment button
            Box {
                IconButton(
                    onClick = { showAttachmentMenu = !showAttachmentMenu },
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showAttachmentMenu,
                    onDismissRequest = { showAttachmentMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_camera)) },
                        onClick = {
                            showAttachmentMenu = false
                            cameraLauncher.launch(cameraImageUri)
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_gallery)) },
                        onClick = {
                            showAttachmentMenu = false
                            filePickerLauncher.launch("image/*")
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Image, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_file)) },
                        onClick = {
                            showAttachmentMenu = false
                            filePickerLauncher.launch("*/*")
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Voice input button
            VoiceInputButton(
                onResult = { result -> onVoiceResult(result) },
                onPartialResult = { partial -> onTextChange(partial) },
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Send / Stop button
            IconButton(
                onClick = {
                    if (isProcessing) {
                        onStop()
                    } else {
                        val attachment = pendingAttachment
                        if (attachment != null) {
                            onSendWithAttachment(attachment.uri, attachment.mimeType)
                            pendingAttachment = null
                        } else {
                            onSend()
                        }
                    }
                },
                enabled = if (isProcessing) true else canSend || pendingAttachment != null,
            ) {
                Icon(
                    imageVector = if (isProcessing) Icons.Filled.Stop
                    else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isProcessing) "Stop" else "Send",
                    tint = if (isProcessing) MaterialTheme.colorScheme.error
                    else if (canSend || pendingAttachment != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }
        }
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var name = "attachment"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

private fun formatNumber(n: Int): String {
    return NumberFormat.getNumberInstance(Locale.US).format(n)
}

private fun formatCost(cost: Double): String {
    return if (cost < 0.01 && cost > 0) "< 0.01"
    else String.format(Locale.US, "%.2f", cost)
}
