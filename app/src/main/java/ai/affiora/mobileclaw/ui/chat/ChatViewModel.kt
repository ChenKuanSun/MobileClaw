package ai.affiora.mobileclaw.ui.chat

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.affiora.mobileclaw.agent.AgentRuntime
import ai.affiora.mobileclaw.agent.ContextCompactor
import ai.affiora.mobileclaw.agent.PermissionManager
import ai.affiora.mobileclaw.agent.TokenUsage
import ai.affiora.mobileclaw.agent.UsageTracker
import ai.affiora.mobileclaw.skills.SkillInstaller
import ai.affiora.mobileclaw.data.db.ChatMessageDao
import ai.affiora.mobileclaw.data.db.ChatMessageEntity
import ai.affiora.mobileclaw.data.db.ConversationDao
import ai.affiora.mobileclaw.data.db.ConversationEntity
import ai.affiora.mobileclaw.data.model.AgentEvent
import ai.affiora.mobileclaw.data.model.ChatMessage
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.data.model.ImageSource
import ai.affiora.mobileclaw.data.model.MessageRole
import ai.affiora.mobileclaw.data.model.ToolActivity
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.agent.SystemPromptBuilder
import ai.affiora.mobileclaw.tools.ToolResult
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import ai.affiora.mobileclaw.MobileClawApplication
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class ConfirmationState(
    val requestId: String,
    val toolName: String,
    val preview: String,
)

enum class ConnectionStatus {
    CONNECTED,
    PROCESSING,
    THINKING,
    ERROR,
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val application: Application,
    private val agentRuntime: AgentRuntime,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val chatMessageDao: ChatMessageDao,
    private val conversationDao: ConversationDao,
    private val userPreferences: UserPreferences,
    private val usageTracker: UsageTracker,
    private val contextCompactor: ContextCompactor,
    private val ttsManager: TtsManager,
    private val permissionManager: PermissionManager,
    private val skillInstaller: SkillInstaller,
    val networkMonitor: ai.affiora.mobileclaw.util.NetworkMonitor,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<ConfirmationState?>(null)
    val pendingConfirmation: StateFlow<ConfirmationState?> = _pendingConfirmation.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** Token usage for the current session. */
    val tokenUsage: StateFlow<TokenUsage> = usageTracker.sessionUsage

    /** ID of the message currently being spoken by TTS. */
    val speakingMessageId: StateFlow<String?> = ttsManager.speakingMessageId

    /** Speak or stop speaking a message via TTS. */
    fun speak(messageId: String, text: String) = ttsManager.speak(messageId, text)

    /** Input history — last 20 user messages. */
    private val _inputHistory = MutableStateFlow<List<String>>(emptyList())
    val inputHistory: StateFlow<List<String>> = _inputHistory.asStateFlow()

    companion object {
        private const val MAX_INPUT_HISTORY = 20
    }

    val conversations: StateFlow<List<ConversationEntity>> = conversationDao.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var currentConversationId: String = UUID.randomUUID().toString()
    private var agentJob: Job? = null
    private var dbCollectionJob: Job? = null

    /** A queued message with optional attachment info. */
    private data class QueuedMessage(
        val text: String,
        val attachmentUri: Uri? = null,
        val attachmentMimeType: String? = null,
    )

    /** Queue of messages to send after current processing completes. */
    private val messageQueue = mutableListOf<QueuedMessage>()

    /** ID of the message currently being streamed. */
    private var streamingMessageId: String? = null

    /** Accumulated text for the current streaming message. */
    private var streamingText: StringBuilder = StringBuilder()

    /** Accumulated tool activities for the current agent turn. */
    private val currentToolActivities = mutableListOf<ToolActivity>()

    /** ID of the assistant message hosting tool activity markers. */
    private var toolHostMessageId: String? = null

    /** ID of the most recent assistant message (for RawAssistantTurn targeting). */
    private var lastAssistantMessageId: String? = null

    /** Current thinking level: off, low, medium, high */
    private var thinkingLevel: String = "off"

    private val json = Json { ignoreUnknownKeys = true }

    init {
        ttsManager.initialize()
        loadConversation(currentConversationId)
    }

    fun loadConversation(conversationId: String) {
        currentConversationId = conversationId
        streamingMessageId = null
        streamingText.clear()
        currentToolActivities.clear()
        toolHostMessageId = null
        lastAssistantMessageId = null
        dbCollectionJob?.cancel()
        dbCollectionJob = chatMessageDao.getMessagesByConversation(conversationId)
            .onEach { entities ->
                _messages.value = entities.map { it.toChatMessage() }
            }
            .launchIn(viewModelScope)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Track input history (most recent first)
        _inputHistory.value = (listOf(text) + _inputHistory.value)
            .distinct()
            .take(MAX_INPUT_HISTORY)

        // Check for slash commands
        if (text.startsWith("/")) {
            handleSlashCommand(text)
            return
        }

        if (_isProcessing.value) {
            // Queue the message
            messageQueue.add(QueuedMessage(text))
            insertSystemMessage("Message queued (#${messageQueue.size}). Will send after current task.")
            return
        }

        viewModelScope.launch {
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = text,
                timestamp = System.currentTimeMillis(),
                conversationId = currentConversationId,
            )

            ensureConversationExists(text)
            chatMessageDao.insert(userMessage.toEntity())

            runAgent(text)
        }
    }

    fun sendMessageWithAttachment(text: String, uri: Uri, mimeType: String) {
        if (_isProcessing.value) {
            // Queue the attachment message with its attachment info
            val displayText = if (text.isBlank()) "[Attachment]" else text
            messageQueue.add(QueuedMessage(displayText, attachmentUri = uri, attachmentMimeType = mimeType))
            insertSystemMessage("Attachment queued (#${messageQueue.size}). Will send after current task.")
            return
        }

        viewModelScope.launch {
            try {
                val displayText = if (text.isBlank()) "[Attachment]" else text

                val userMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.USER,
                    content = displayText,
                    timestamp = System.currentTimeMillis(),
                    conversationId = currentConversationId,
                )

                ensureConversationExists(displayText)
                chatMessageDao.insert(userMessage.toEntity())

                if (mimeType.startsWith("image/")) {
                    // Read image, convert to base64, send as image content
                    val inputStream = application.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()

                    if (bytes != null) {
                        val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val mediaType = when {
                            mimeType.contains("png") -> "image/png"
                            mimeType.contains("gif") -> "image/gif"
                            mimeType.contains("webp") -> "image/webp"
                            else -> "image/jpeg"
                        }

                        val contentBlocks = mutableListOf<ContentBlock>()
                        if (text.isNotBlank()) {
                            contentBlocks.add(ContentBlock.TextBlock(text))
                        }
                        contentBlocks.add(
                            ContentBlock.ImageBlock(
                                source = ImageSource(
                                    mediaType = mediaType,
                                    data = base64Data,
                                ),
                            )
                        )

                        runAgentWithContent(
                            userText = displayText,
                            content = ClaudeContent.ContentList(contentBlocks),
                        )
                    } else {
                        insertSystemMessage("Failed to read image file.")
                    }
                } else {
                    // Non-image file: try to read as text
                    val inputStream = application.contentResolver.openInputStream(uri)
                    val fileContent = inputStream?.bufferedReader()?.use { it.readText() }
                    inputStream?.close()

                    val messageText = if (fileContent != null) {
                        "$displayText\n\n--- File content ---\n$fileContent"
                    } else {
                        displayText
                    }
                    runAgent(messageText)
                }
            } catch (e: Exception) {
                insertSystemMessage("Error processing attachment: ${e.message}")
            }
        }
    }

    fun confirmAction(
        confirmed: Boolean,
        alwaysAllowChoice: AlwaysAllowChoice = AlwaysAllowChoice.NONE,
        toolName: String? = null,
    ) {
        val confirmation = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null

        if (confirmed && toolName != null) {
            when (alwaysAllowChoice) {
                AlwaysAllowChoice.SESSION -> permissionManager.sessionAllow(toolName)
                AlwaysAllowChoice.PERMANENT -> permissionManager.allowTool(toolName)
                AlwaysAllowChoice.NONE -> { /* one-time approval */ }
            }
        }

        agentRuntime.confirmAction(confirmation.requestId, confirmed)
    }

    // ── Multi-session methods ────────────────────────────────────────────────

    fun startNewConversation() {
        agentJob?.cancel()
        _isProcessing.value = false
        _pendingConfirmation.value = null
        _connectionStatus.value = ConnectionStatus.CONNECTED
        streamingMessageId = null
        streamingText.clear()
        messageQueue.clear()
        val newId = UUID.randomUUID().toString()
        loadConversation(newId)
    }

    fun switchConversation(id: String) {
        if (id == currentConversationId) return
        agentJob?.cancel()
        _isProcessing.value = false
        _pendingConfirmation.value = null
        _connectionStatus.value = ConnectionStatus.CONNECTED
        streamingMessageId = null
        streamingText.clear()
        messageQueue.clear()
        loadConversation(id)
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatMessageDao.deleteByConversation(id)
            conversationDao.deleteById(id)
            if (id == currentConversationId) {
                startNewConversation()
            }
        }
    }

    // ── Slash command support methods ────────────────────────────────────────

    fun insertSystemMessage(text: String) {
        viewModelScope.launch {
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = text,
                timestamp = System.currentTimeMillis(),
                conversationId = currentConversationId,
            )
            chatMessageDao.insert(message.toEntity())
        }
    }

    fun clearCurrentConversation() {
        viewModelScope.launch {
            agentJob?.cancel()
            _isProcessing.value = false
            streamingMessageId = null
            streamingText.clear()
            chatMessageDao.deleteByConversation(currentConversationId)
        }
    }

    fun cancelAgentRun() {
        agentJob?.cancel()
        _isProcessing.value = false
        _connectionStatus.value = ConnectionStatus.CONNECTED
        _isThinking.value = false
        _pendingConfirmation.value = null

        // Append [cancelled] to the incomplete streaming message so the user sees what was generated
        val incompleteId = streamingMessageId ?: toolHostMessageId
        if (incompleteId != null) {
            viewModelScope.launch {
                val existing = chatMessageDao.getMessageById(incompleteId)
                if (existing != null) {
                    chatMessageDao.update(existing.copy(content = existing.content + " [cancelled]"))
                }
            }
        }

        streamingMessageId = null
        streamingText.clear()
        currentToolActivities.clear()
        toolHostMessageId = null
        insertSystemMessage("Agent run cancelled.")
    }

    suspend fun getCurrentModel(): String {
        return userPreferences.selectedModel.first()
    }

    fun switchModel(modelName: String) {
        viewModelScope.launch {
            userPreferences.setSelectedModel(modelName)
            insertSystemMessage("Model switched to **$modelName**")
        }
    }

    fun compactConversation() {
        viewModelScope.launch {
            val currentMessages = _messages.value
            if (currentMessages.size < 4) {
                insertSystemMessage("Conversation too short to compact.")
                return@launch
            }

            insertSystemMessage("Compacting conversation history...")

            try {
                val claudeHistory = buildClaudeHistory(currentMessages)
                val systemPrompt = systemPromptBuilder.build()
                val compacted = contextCompactor.compact(claudeHistory, systemPrompt)

                // Clear existing messages and insert the compacted summary
                chatMessageDao.deleteByConversation(currentConversationId)

                val summaryContent = when (val content = compacted.firstOrNull()?.content) {
                    is ClaudeContent.Text -> content.text
                    else -> "Conversation compacted."
                }

                val summaryMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.USER,
                    content = summaryContent,
                    timestamp = System.currentTimeMillis(),
                    conversationId = currentConversationId,
                )
                chatMessageDao.insert(summaryMessage.toEntity())
                insertSystemMessage("Conversation compacted: ${currentMessages.size} messages reduced to summary.")
            } catch (e: Exception) {
                insertSystemMessage("Compaction failed: ${e.message}")
            }
        }
    }

    fun getStatus(): String {
        val messageCount = _messages.value.size
        val estimatedTokens = _messages.value.sumOf { it.content.length } / 4
        return buildString {
            appendLine("**Status**")
            appendLine("- Messages: $messageCount")
            appendLine("- Estimated tokens: ~$estimatedTokens")
            appendLine("- Thinking level: $thinkingLevel")
            appendLine("- Conversation ID: ${currentConversationId.take(8)}...")
        }
    }

    fun exportConversation() {
        val text = buildString {
            for (msg in _messages.value) {
                val role = when (msg.role) {
                    MessageRole.USER -> "You"
                    MessageRole.ASSISTANT -> "Assistant"
                    MessageRole.SYSTEM -> "System"
                    MessageRole.TOOL_RESULT -> {
                        if (msg.toolName == "__raw_turn__") continue // Skip hidden API-only entries
                        "Tool (${msg.toolName ?: "unknown"})"
                    }
                }
                appendLine("[$role]")
                appendLine(msg.content)
                appendLine()
            }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MobileClaw Conversation")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share conversation").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        application.startActivity(chooser)
    }

    fun setThinkingLevel(level: String) {
        thinkingLevel = level
    }

    fun getPermissionManager(): PermissionManager = permissionManager

    fun getToolNames(): List<String> = agentRuntime.getToolNames()

    fun installSkillFromUrl(url: String) {
        viewModelScope.launch {
            insertSystemMessage("Downloading skill from $url...")

            val result = skillInstaller.downloadSkill(url)
            if (result.isFailure) {
                insertSystemMessage("Failed to download: ${result.exceptionOrNull()?.message}")
                return@launch
            }

            val content = result.getOrThrow()

            // Security scan
            val scan = skillInstaller.scanContent(content)

            if (scan.riskLevel == SkillInstaller.RiskLevel.BLOCKED) {
                insertSystemMessage(
                    "BLOCKED -- This skill contains dangerous patterns:\n" +
                        scan.blockedReasons.joinToString("\n- ", prefix = "- ") +
                        "\n\nInstallation rejected for your safety.",
                )
                return@launch
            }

            if (scan.warnings.isNotEmpty()) {
                insertSystemMessage(
                    "Security warnings:\n" +
                        scan.warnings.joinToString("\n- ", prefix = "- "),
                )
            }

            // Send to AI for adaptation
            insertSystemMessage("Analyzing skill and adapting for Android...")

            val adaptPrompt = buildString {
                appendLine("You are adapting an OpenClaw desktop skill for MobileClaw (Android).")
                appendLine()
                appendLine("Here is the original SKILL.md content:")
                appendLine("```")
                appendLine(content)
                appendLine("```")
                appendLine()
                appendLine("Analyze this skill and create an Android-adapted version using MobileClaw's available tools:")
                appendLine(getToolNames().joinToString(", "))
                appendLine()
                appendLine("Rules:")
                appendLine("1. Replace any CLI tool references (curl, gh, jq, etc.) with equivalent MobileClaw tools (web, ui, app, etc.)")
                appendLine("2. Keep the same functionality but adapted for phone use")
                appendLine("3. Use our SKILL.md format with frontmatter: name, description, version, author, tools_required")
                appendLine("4. Add triggers (keywords that should activate this skill)")
                appendLine("5. If something is impossible on Android, note it clearly")
                appendLine("6. Use the skills_author tool to create the adapted skill")
                appendLine("7. After creating the skill, tell the user the skill ID and confirm it was auto-enabled")
                appendLine()
                appendLine("Create the adapted skill now using the skills_author tool.")
            }
            // Send as a user message to trigger the agent
            sendMessage(adaptPrompt)
        }
    }

    fun uninstallSkill(skillId: String) {
        if (skillInstaller.deleteSkill(skillId)) {
            insertSystemMessage("Skill '$skillId' uninstalled.")
        } else {
            insertSystemMessage("Skill '$skillId' not found or already removed.")
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val entity = chatMessageDao.getMessageById(messageId)
            if (entity != null) {
                chatMessageDao.delete(entity)
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    private fun showCompletionNotification() {
        try {
            val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val intent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            val pendingIntent = PendingIntent.getActivity(
                application, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(application, MobileClawApplication.CHANNEL_AGENT_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Task completed")
                .setContentText("MobileClaw finished processing your request.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(3001, notification)
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "Could not show completion notification: ${e.message}")
        }
    }

    private fun handleSlashCommand(text: String) {
        val parts = text.trimStart('/').split(" ", limit = 2)
        val commandName = "/${parts[0]}"
        val args = if (parts.size > 1) parts[1] else ""

        val command = SlashCommands.all.find { it.name == commandName }
        if (command == null) {
            insertSystemMessage("Unknown command: `$commandName`. Type `/help` for available commands.")
            return
        }

        viewModelScope.launch {
            command.action(args, this@ChatViewModel)
        }
    }

    // ── Agent execution ─────────────────────────────────────────────────────

    private suspend fun ensureConversationExists(firstMessageText: String) {
        val existing = conversationDao.getConversationById(currentConversationId)
        if (existing == null) {
            val title = firstMessageText.take(50).let {
                if (firstMessageText.length > 50) "$it..." else it
            }
            conversationDao.insert(
                ConversationEntity(
                    id = currentConversationId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        } else {
            conversationDao.update(existing.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private fun runAgent(userMessage: String) {
        agentJob?.cancel()
        _pendingConfirmation.value = null
        _isProcessing.value = true
        _connectionStatus.value = ConnectionStatus.PROCESSING
        streamingMessageId = null
        streamingText.clear()
        currentToolActivities.clear()
        toolHostMessageId = null
        lastAssistantMessageId = null

        agentJob = viewModelScope.launch {
            try {
                val conversationHistory = buildClaudeHistory(_messages.value)
                val systemPrompt = systemPromptBuilder.build()

                agentRuntime.run(userMessage, conversationHistory, systemPrompt)
                    .collect { event ->
                        handleAgentEvent(event)
                    }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Agent error", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = "Error: ${e.message ?: "Unknown error occurred"}",
                    timestamp = System.currentTimeMillis(),
                    conversationId = currentConversationId,
                )
                chatMessageDao.insert(errorMessage.toEntity())
            } finally {
                _isProcessing.value = false
                _isThinking.value = false
                _connectionStatus.value = ConnectionStatus.CONNECTED
                streamingMessageId = null
                streamingText.clear()

                // Notify if app is in background
                if (!isAppInForeground()) {
                    showCompletionNotification()
                }

                // Process queued messages
                if (messageQueue.isNotEmpty()) {
                    val next = messageQueue.removeAt(0)
                    if (next.attachmentUri != null && next.attachmentMimeType != null) {
                        sendMessageWithAttachment(next.text, next.attachmentUri, next.attachmentMimeType)
                    } else {
                        sendMessage(next.text)
                    }
                }
            }
        }
    }

    /**
     * Run agent with structured content (e.g., image + text).
     * The content is used as the user message in the conversation history.
     */
    private fun runAgentWithContent(userText: String, content: ClaudeContent) {
        agentJob?.cancel()
        _pendingConfirmation.value = null
        _isProcessing.value = true
        _connectionStatus.value = ConnectionStatus.PROCESSING
        streamingMessageId = null
        streamingText.clear()
        currentToolActivities.clear()
        toolHostMessageId = null
        lastAssistantMessageId = null

        agentJob = viewModelScope.launch {
            try {
                val conversationHistory = buildClaudeHistory(_messages.value).toMutableList()
                // Replace last user message (plain text) with the rich content version
                if (conversationHistory.lastOrNull()?.role == "user") {
                    conversationHistory.removeLastOrNull()
                }
                conversationHistory.add(ClaudeMessage(role = "user", content = content))

                val systemPrompt = systemPromptBuilder.build()

                agentRuntime.runWithHistory(conversationHistory, systemPrompt)
                    .collect { event ->
                        handleAgentEvent(event)
                    }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Agent error", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = "Error: ${e.message ?: "Unknown error occurred"}",
                    timestamp = System.currentTimeMillis(),
                    conversationId = currentConversationId,
                )
                chatMessageDao.insert(errorMessage.toEntity())
            } finally {
                _isProcessing.value = false
                _isThinking.value = false
                _connectionStatus.value = ConnectionStatus.CONNECTED
                streamingMessageId = null
                streamingText.clear()

                // Notify if app is in background
                if (!isAppInForeground()) {
                    showCompletionNotification()
                }

                if (messageQueue.isNotEmpty()) {
                    val next = messageQueue.removeAt(0)
                    if (next.attachmentUri != null && next.attachmentMimeType != null) {
                        sendMessageWithAttachment(next.text, next.attachmentUri, next.attachmentMimeType)
                    } else {
                        sendMessage(next.text)
                    }
                }
            }
        }
    }

    private fun buildClaudeHistory(messages: List<ChatMessage>): List<ClaudeMessage> {
        val result = mutableListOf<ClaudeMessage>()

        for (msg in messages) {
            // Skip system messages — they're local UI only
            if (msg.role == MessageRole.SYSTEM) continue

            // If we have the full serialized ClaudeMessage, use it directly
            val storedJson = msg.claudeMessageJson
            if (storedJson != null) {
                try {
                    result.add(json.decodeFromString<ClaudeMessage>(storedJson))
                    continue
                } catch (e: Exception) {
                    android.util.Log.w("ChatViewModel", "Failed to deserialize claude_message_json, falling back", e)
                }
            }

            // Legacy fallback: only include pure text user/assistant turns.
            // Tool-use turns without stored JSON cannot be faithfully reconstructed.
            if (msg.toolName != null) continue
            if (msg.role != MessageRole.USER && msg.role != MessageRole.ASSISTANT) continue

            result.add(
                ClaudeMessage(
                    role = when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        else -> "user"
                    },
                    content = ClaudeContent.Text(msg.content),
                )
            )
        }

        // Drop trailing user message — AgentRuntime will re-add it
        if (result.lastOrNull()?.role == "user") {
            result.removeLastOrNull()
        }

        return result
    }

    private suspend fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                if (streamingMessageId == null) {
                    // First delta: reuse tool host message if it exists, otherwise insert new
                    val hostId = toolHostMessageId
                    streamingText.clear()
                    streamingText.append(event.delta)

                    if (hostId != null) {
                        // Reuse the tool host message — append streaming text after tool markers
                        streamingMessageId = hostId
                        lastAssistantMessageId = hostId
                        val existing = chatMessageDao.getMessageById(hostId)
                        if (existing != null) {
                            val toolMarkers = currentToolActivities.joinToString("\n") { it.toMarker() }
                            chatMessageDao.update(existing.copy(content = "$toolMarkers\n${streamingText}"))
                        }
                    } else {
                        val id = UUID.randomUUID().toString()
                        streamingMessageId = id
                        lastAssistantMessageId = id
                        val message = ChatMessage(
                            id = id,
                            role = MessageRole.ASSISTANT,
                            content = streamingText.toString(),
                            timestamp = System.currentTimeMillis(),
                            conversationId = currentConversationId,
                        )
                        chatMessageDao.insert(message.toEntity())
                    }
                } else {
                    // Subsequent delta: update the existing message
                    streamingText.append(event.delta)
                    val msgId = streamingMessageId ?: return
                    val existing = chatMessageDao.getMessageById(msgId)
                    if (existing != null) {
                        val toolMarkers = if (currentToolActivities.isNotEmpty()) {
                            currentToolActivities.joinToString("\n") { it.toMarker() } + "\n"
                        } else ""
                        chatMessageDao.update(existing.copy(content = "$toolMarkers${streamingText}"))
                    }
                }
            }

            is AgentEvent.Thinking -> {
                _isThinking.value = true
                _connectionStatus.value = ConnectionStatus.THINKING
            }

            is AgentEvent.Text -> {
                // Build the final content: tool markers (if any) + text
                val toolMarkers = if (currentToolActivities.isNotEmpty()) {
                    currentToolActivities.joinToString("\n") { it.toMarker() }
                } else null

                val finalContent = if (toolMarkers != null) {
                    "$toolMarkers\n${event.text}"
                } else {
                    event.text
                }

                val existingId = streamingMessageId
                val hostId = toolHostMessageId

                when {
                    // Was streaming text deltas — finalize with tools prepended
                    existingId != null -> {
                        val existing = chatMessageDao.getMessageById(existingId)
                        if (existing != null) {
                            chatMessageDao.update(existing.copy(content = finalContent))
                        }
                        // If there was a separate tool host message, delete it (merged into streaming msg)
                        if (hostId != null && hostId != existingId) {
                            val hostMsg = chatMessageDao.getMessageById(hostId)
                            if (hostMsg != null) chatMessageDao.delete(hostMsg)
                        }
                    }
                    // Had tool activities with a host message — update it with the final text
                    hostId != null -> {
                        val existing = chatMessageDao.getMessageById(hostId)
                        if (existing != null) {
                            chatMessageDao.update(existing.copy(content = finalContent))
                        }
                    }
                    // No streaming, no tools — insert fresh
                    else -> {
                        val id = UUID.randomUUID().toString()
                        lastAssistantMessageId = id
                        val message = ChatMessage(
                            id = id,
                            role = MessageRole.ASSISTANT,
                            content = finalContent,
                            timestamp = System.currentTimeMillis(),
                            conversationId = currentConversationId,
                        )
                        chatMessageDao.insert(message.toEntity())
                    }
                }

                streamingMessageId = null
                streamingText.clear()
                currentToolActivities.clear()
                toolHostMessageId = null
                _isThinking.value = false
                _connectionStatus.value = ConnectionStatus.PROCESSING
            }

            is AgentEvent.ToolCalling -> {
                // Reset streaming state — tool calls start a new turn
                streamingMessageId = null
                streamingText.clear()

                // Add a pending tool activity
                val activity = ToolActivity(
                    toolName = event.toolName,
                    input = event.input.entries.joinToString(", ") { "${it.key}: ${it.value}" },
                    isPending = true,
                )
                currentToolActivities.add(activity)

                // Ensure we have a host message for tool markers
                val hostId = toolHostMessageId
                if (hostId == null) {
                    // Create a new assistant message to hold tool activities
                    val id = UUID.randomUUID().toString()
                    toolHostMessageId = id
                    lastAssistantMessageId = id
                    val content = currentToolActivities.joinToString("\n") { it.toMarker() }
                    val message = ChatMessage(
                        id = id,
                        role = MessageRole.ASSISTANT,
                        content = content,
                        timestamp = System.currentTimeMillis(),
                        conversationId = currentConversationId,
                    )
                    chatMessageDao.insert(message.toEntity())
                } else {
                    // Update existing host message with new tool marker
                    val existing = chatMessageDao.getMessageById(hostId)
                    if (existing != null) {
                        val content = currentToolActivities.joinToString("\n") { it.toMarker() }
                        chatMessageDao.update(existing.copy(content = content))
                    }
                }
            }

            is AgentEvent.ToolResultEvent -> {
                // Update the last matching tool activity with the result
                val idx = currentToolActivities.indexOfLast {
                    it.toolName == event.toolName && it.isPending
                }
                if (idx >= 0) {
                    val isError = event.result is ToolResult.Error
                    val resultText = when (val r = event.result) {
                        is ToolResult.Success -> r.data
                        is ToolResult.Error -> r.message
                        is ToolResult.NeedsConfirmation -> r.preview
                    }
                    currentToolActivities[idx] = currentToolActivities[idx].copy(
                        result = resultText,
                        isError = isError,
                        isPending = false,
                    )

                    // Update the host message
                    val hostId = toolHostMessageId
                    if (hostId != null) {
                        val existing = chatMessageDao.getMessageById(hostId)
                        if (existing != null) {
                            val content = currentToolActivities.joinToString("\n") { it.toMarker() }
                            chatMessageDao.update(existing.copy(content = content))
                        }
                    }
                }
            }

            is AgentEvent.RawAssistantTurn -> {
                // Update the known assistant message by ID with full Claude JSON for history reconstruction
                val targetId = lastAssistantMessageId
                if (targetId != null) {
                    val existing = chatMessageDao.getMessageById(targetId)
                    if (existing != null) {
                        chatMessageDao.update(
                            existing.copy(
                                claudeMessageJson = json.encodeToString(ClaudeMessage.serializer(), event.message),
                            )
                        )
                    }
                }
            }

            is AgentEvent.RawToolResultTurn -> {
                // Store tool result JSON on the tool host assistant message for API history reconstruction.
                // We create a hidden TOOL_RESULT row so buildClaudeHistory can find it.
                val toolResultMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.TOOL_RESULT,
                    content = "", // empty — not displayed; only claudeMessageJson matters
                    toolName = "__raw_turn__",
                    timestamp = System.currentTimeMillis(),
                    conversationId = currentConversationId,
                    claudeMessageJson = json.encodeToString(ClaudeMessage.serializer(), event.message),
                )
                chatMessageDao.insert(toolResultMsg.toEntity())
            }

            is AgentEvent.Error -> {
                _connectionStatus.value = ConnectionStatus.ERROR
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.ASSISTANT,
                    content = "Error: ${event.message}",
                    timestamp = System.currentTimeMillis(),
                    conversationId = currentConversationId,
                )
                chatMessageDao.insert(message.toEntity())
            }

            is AgentEvent.NeedsConfirmation -> {
                _pendingConfirmation.value = ConfirmationState(
                    requestId = event.requestId,
                    toolName = event.toolName,
                    preview = event.preview,
                )
            }

            is AgentEvent.Usage -> {
                usageTracker.recordUsage(event.inputTokens, event.outputTokens, event.model)
            }

            is AgentEvent.StreamComplete -> {
                // Handled internally by AgentRuntime; nothing to do here.
            }
        }
    }

    private fun ChatMessageEntity.toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = content,
        toolName = toolName,
        toolInput = toolInput,
        timestamp = timestamp,
        conversationId = conversationId,
        claudeMessageJson = claudeMessageJson,
    )

    private fun ChatMessage.toEntity(): ChatMessageEntity = ChatMessageEntity(
        id = id,
        role = role,
        content = content,
        toolName = toolName,
        toolInput = toolInput,
        timestamp = timestamp,
        conversationId = conversationId,
        claudeMessageJson = claudeMessageJson,
    )
}
