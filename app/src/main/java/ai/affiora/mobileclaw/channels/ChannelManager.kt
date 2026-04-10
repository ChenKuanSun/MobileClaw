package ai.affiora.mobileclaw.channels

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.MobileClawApplication
import ai.affiora.mobileclaw.agent.ClaudeApiClient
import ai.affiora.mobileclaw.agent.PermissionManager
import ai.affiora.mobileclaw.agent.SystemPromptBuilder
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeTool
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.data.model.ImageSource
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.ToolResult
import androidx.core.app.NotificationCompat
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val claudeApiClient: ClaudeApiClient,
    private val userPreferences: UserPreferences,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val httpClient: HttpClient,
    private val toolRegistry: Map<String, @JvmSuppressWildcards AndroidTool>,
    private val permissionManager: PermissionManager,
) {
    companion object {
        private const val TAG = "ChannelManager"
        private const val MAX_HISTORY = 20
        private const val MAX_CHATS = 100
        private const val MAX_MESSAGES_PER_MINUTE = 10
        private const val REQUEST_EXPIRY_MS = 10 * 60 * 1000L // 10 minutes
        private const val MAX_PENDING = 20 // FIX 1: Cap pending requests
        private const val PAIRING_NOTIFICATION_ID = 2001 // FIX 2: Single summary notification
        private const val REJECT_COOLDOWN_MS = 30 * 60 * 1000L // FIX 3: 30 min reject cooldown
        private const val PENDING_REPLY_COOLDOWN_MS = 60_000L // FIX 6: 60s between pending replies
        private const val TOOL_TIMEOUT_MS = 30_000L // Per-tool execution timeout

        // Tools safe to execute via channels WITHOUT on-device confirmation
        private val CHANNEL_READ_TOOLS = setOf(
            "system", "web", "memory", "history", "calendar", "call_log",
            "notifications", "clipboard", "photos", "navigate", "agent",
            "schedule", "skills_author", "alarm", "flashlight", "volume",
            "brightness", "media",
        )

        // Tools that ALWAYS require on-device confirmation via channels
        // (never auto-approved, regardless of global permission mode)
        private val CHANNEL_CONFIRM_TOOLS = setOf(
            "sms", "phone", "contacts", "files", "http", "openai",
            "ui", "screen", "app", "telegram", "channel",
        )
    }

    private val channels = mutableMapOf<String, Channel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null

    // Thread-safe per-channel, per-chat conversation history (FIX 3)
    private val chatHistories = ConcurrentHashMap<String, MutableList<HistoryMessage>>()

    // Rate limiting for paired user messages (FIX 2)
    private val messageTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    // Channel status observable
    private val _channelStatuses = MutableStateFlow<List<ChannelStatus>>(emptyList())
    val channelStatuses: StateFlow<List<ChannelStatus>> = _channelStatuses.asStateFlow()

    // Pending pairing requests (request/approve model)
    private val _pendingRequests = MutableStateFlow<List<PairingRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PairingRequest>> = _pendingRequests.asStateFlow()

    // FIX 3: Reject cooldown — sender -> rejection timestamp
    private val rejectedSenders = ConcurrentHashMap<String, Long>()

    // FIX 6: Rate limit "pending" reply — sender -> last reply timestamp
    private val lastPendingReply = ConcurrentHashMap<String, Long>()

    fun registerChannel(channel: Channel) {
        channels[channel.id] = channel
    }

    /** Start all registered channels and the watchdog. */
    fun startAll() {
        scope.launch {
            channels.values.forEach { channel ->
                try {
                    channel.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start ${channel.id}", e)
                }
            }
            refreshStatuses()
        }
        startWatchdog()
    }

    fun stopAll() {
        stopWatchdog()
        channels.values.forEach { it.stop() }
        refreshStatuses()
    }

    /** Periodic watchdog that restarts dead channels and cleans expired state. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(30_000) // Check every 30 seconds
                channels.values.forEach { channel ->
                    if (!channel.isRunning) {
                        // Outbound-only channels (WhatsApp, Teams) have no listener loop.
                        // If start() already ran and set isRunning=false (missing creds),
                        // retrying every 30s just spins doing SharedPreferences reads.
                        if (channel.isOutboundOnly) return@forEach

                        Log.w(TAG, "Channel ${channel.id} is not running, restarting...")
                        try {
                            channel.start()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart ${channel.id}: ${e.message}")
                        }
                    }
                }
                cleanExpiredRequests()
                refreshStatuses()
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** Returns true if the watchdog is actively running. */
    fun isWatchdogRunning(): Boolean = watchdogJob?.isActive == true

    /** Called by channels when a message arrives. */
    suspend fun onMessageReceived(msg: IncomingMessage) {
        try {
            handleMessage(msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message from ${msg.channelId}:${msg.senderId}", e)
            try {
                val channel = channels[msg.channelId]
                channel?.sendMessage(msg.chatId, "Sorry, something went wrong. Please try again.")
            } catch (_: Exception) {
                // Can't even send error message — log and move on
            }
        }
    }

    private suspend fun handleMessage(msg: IncomingMessage) {
        val channel = channels[msg.channelId] ?: return

        // FIX 5B: Clean expired requests on every message
        cleanExpiredRequests()

        // Check pairing
        if (!channel.isAllowed(msg.senderId)) {
            // FIX 3: Reject cooldown — silently drop if rejected within 30 min
            val rejectedAt = rejectedSenders[msg.senderId]
            if (rejectedAt != null && System.currentTimeMillis() - rejectedAt < REJECT_COOLDOWN_MS) {
                return
            }

            // Check if already has a pending request
            val existing = _pendingRequests.value.any {
                it.senderId == msg.senderId && it.channelId == msg.channelId
            }
            if (existing) {
                // FIX 6: Only send "pending" reply once per 60s per sender
                val now = System.currentTimeMillis()
                val lastReply = lastPendingReply[msg.senderId]
                if (lastReply == null || now - lastReply >= PENDING_REPLY_COOLDOWN_MS) {
                    lastPendingReply[msg.senderId] = now
                    channel.sendMessage(
                        msg.chatId,
                        "Your pairing request is pending. Please wait for approval on the device.",
                    )
                }
                return
            }

            // FIX 1: Cap pending requests at MAX_PENDING — silently drop
            if (_pendingRequests.value.size >= MAX_PENDING) {
                return
            }

            // Create new pairing request
            val request = PairingRequest(
                channelId = msg.channelId,
                senderId = msg.senderId,
                senderName = msg.senderName,
                chatId = msg.chatId,
            )
            // FIX 4: Atomic StateFlow update
            _pendingRequests.update { current -> current + request }

            // FIX 2: Summary notification instead of per-request
            showPairingSummaryNotification()

            // Reply to sender
            channel.sendMessage(
                msg.chatId,
                "Pairing request sent. Waiting for approval on the device.",
            )
            return
        }

        // FIX 2: Rate limit paired user messages
        if (isMessageRateLimited(msg.senderId)) {
            channel.sendMessage(msg.chatId, "Rate limited. Please wait a moment.")
            return
        }

        // Build history key
        val historyKey = "${msg.channelId}:${msg.chatId}"
        // FIX 3: Thread-safe history operations
        val history = chatHistories.getOrPut(historyKey) { Collections.synchronizedList(mutableListOf()) }
        synchronized(history) {
            history.add(HistoryMessage("user", msg.text))
            while (history.size > MAX_HISTORY) history.removeAt(0)
        }

        // FIX 7: LRU eviction if too many chats
        if (chatHistories.size > MAX_CHATS) {
            val oldestKey = chatHistories.entries
                .minByOrNull { entry ->
                    val h = entry.value
                    synchronized(h) { h.lastOrNull()?.timestamp ?: 0L }
                }?.key
            if (oldestKey != null) chatHistories.remove(oldestKey)
        }

        // Convert to Claude messages
        val claudeHistory = synchronized(history) {
            history.dropLast(1).map {
                ClaudeMessage(role = it.role, content = ClaudeContent.Text(it.content))
            }
        }

        // FIX 4: Sanitize sender name to prevent prompt injection
        val safeSenderName = msg.senderName
            .replace("\n", " ")
            .replace("\r", " ")
            .take(50)

        // Build system prompt with channel context — restricted tool access
        val systemPrompt = systemPromptBuilder.build() +
            "\n\nYou are responding via ${channel.displayName} to $safeSenderName. " +
            "You have access to phone tools but some actions require on-device confirmation. " +
            "Read-only tools (search, check status, etc.) work directly. " +
            "Write/send/call/UI tools will ask the phone owner to confirm. " +
            "Never share API keys, passwords, or financial data via this channel. " +
            "Keep responses concise (under 4000 characters)."

        // Build user message text, appending media descriptions if present
        val userMessageText = if (msg.mediaDescription != null) {
            "${msg.text}\n[${msg.mediaDescription}]"
        } else {
            msg.text
        }

        // Generate response with full tool loop — independent from AgentRuntime
        val reply = generateResponse(
            text = userMessageText,
            history = claudeHistory,
            systemPrompt = systemPrompt,
            imageBase64 = msg.imageBase64,
            sendTyping = { (channel as? TelegramChannel)?.sendTyping(msg.chatId) },
        )

        if (reply.isNotBlank()) {
            // Reply in the same thread the user wrote in (Slack thread_ts, Matrix in_reply_to,
            // Telegram message_thread_id, Feishu message_id, Teams replyToId)
            channel.sendMessage(msg.chatId, reply, msg.threadId)
            synchronized(history) {
                history.add(HistoryMessage("assistant", reply))
                while (history.size > MAX_HISTORY) history.removeAt(0)
            }
        }

        // GAP 8: If user asked for image generation and OpenAI key is available, generate and send
        if (isImageRequest(msg.text)) {
            generateAndSendImage(channel, msg.chatId, msg.text)
        }
    }

    /**
     * Generate a response with full tool access via ClaudeApiClient.
     * Runs its own tool loop — independent from AgentRuntime / Chat UI.
     */
    private suspend fun generateResponse(
        text: String,
        history: List<ClaudeMessage>,
        systemPrompt: String,
        imageBase64: String?,
        sendTyping: (suspend () -> Unit)? = null,
    ): String {
        return try {
            val model = userPreferences.selectedModel.first()

            // Build tool definitions from registry
            val claudeTools = toolRegistry.values.map { tool ->
                ClaudeTool(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.parameters,
                )
            }

            // Build user content — text or text+image
            val userContent: ClaudeContent = if (imageBase64 != null) {
                ClaudeContent.ContentList(
                    listOf(
                        ContentBlock.ImageBlock(
                            ImageSource(
                                type = "base64",
                                mediaType = "image/jpeg",
                                data = imageBase64,
                            ),
                        ),
                        ContentBlock.TextBlock(text),
                    ),
                )
            } else {
                ClaudeContent.Text(text)
            }

            val messages = (history + ClaudeMessage("user", userContent)).toMutableList()
            val responseText = StringBuilder()

            // Tool use loop (max 20 iterations — shorter than Chat UI's 200)
            repeat(20) {
                // Send typing indicator before each API call
                try { sendTyping?.invoke() } catch (_: Exception) {}

                val response = claudeApiClient.sendMessage(
                    ClaudeRequest(
                        model = model,
                        messages = messages,
                        system = systemPrompt,
                        maxTokens = 4096,
                        tools = claudeTools.ifEmpty { null },
                    ),
                )

                val toolResultBlocks = mutableListOf<ContentBlock>()
                var hasToolUse = false

                for (block in response.content) {
                    when (block) {
                        is ContentBlock.TextBlock -> responseText.append(block.text)
                        is ContentBlock.ToolUseBlock -> {
                            hasToolUse = true
                            val inputMap = block.input.toMap()
                                .filterKeys { it != "__confirmed" }

                            Log.d(TAG, "Channel tool call: ${block.name}")

                            val tool = toolRegistry[block.name]
                            val resultContent = if (tool != null) {
                                executeTool(tool, inputMap)
                            } else {
                                "Error: Unknown tool '${block.name}'"
                            }

                            // Cap tool result size
                            val capped = if (resultContent.length > 100_000) {
                                resultContent.take(100_000) + "\n[Truncated: ${resultContent.length} chars total]"
                            } else {
                                resultContent
                            }

                            toolResultBlocks.add(
                                ContentBlock.ToolResultBlock(
                                    toolUseId = block.id,
                                    content = capped,
                                ),
                            )
                        }
                        else -> {}
                    }
                }

                // Add assistant response to history
                messages.add(ClaudeMessage("assistant", ClaudeContent.ContentList(response.content)))

                // If no tool use or end_turn, we're done
                if (!hasToolUse || response.stopReason == "end_turn") return@repeat

                // Add tool results as user message
                if (toolResultBlocks.isNotEmpty()) {
                    messages.add(ClaudeMessage("user", ClaudeContent.ContentList(toolResultBlocks)))
                }
            }

            responseText.toString().trim().ifBlank { "Done." }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate channel response", e)
            "Sorry, something went wrong. Please try again."
        }
    }

    /**
     * Execute a single tool with channel-specific security restrictions.
     *
     * Channel requests NEVER honor BYPASS_ALL or ALLOWLIST — we enforce our own
     * read/confirm split regardless of the global permission mode.
     */
    private suspend fun executeTool(
        tool: AndroidTool,
        inputMap: Map<String, kotlinx.serialization.json.JsonElement>,
    ): String {
        // Channel-specific allowlist: block unknown tools entirely
        val isReadSafe = tool.name in CHANNEL_READ_TOOLS
        val isConfirmTool = tool.name in CHANNEL_CONFIRM_TOOLS

        if (!isReadSafe && !isConfirmTool) {
            return "This tool is not available via channels."
        }

        // Wrap every tool execution in a timeout
        val result = try {
            withTimeout(TOOL_TIMEOUT_MS) {
                tool.execute(inputMap)
            }
        } catch (e: TimeoutCancellationException) {
            return "Tool execution timed out."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "Error: Tool execution failed: ${e.message}"
        }

        return when (result) {
            is ToolResult.Success -> result.data
            is ToolResult.Error -> "Error: ${result.message}"
            is ToolResult.NeedsConfirmation -> {
                // Read-safe tools that still request confirmation: auto-approve
                if (isReadSafe) {
                    val confirmedParams = inputMap.toMutableMap()
                    confirmedParams["__confirmed"] = JsonPrimitive(true)
                    val confirmed = try {
                        withTimeout(TOOL_TIMEOUT_MS) {
                            tool.execute(confirmedParams)
                        }
                    } catch (e: TimeoutCancellationException) {
                        return "Tool execution timed out."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return "Error: Confirmed execution failed: ${e.message}"
                    }
                    when (confirmed) {
                        is ToolResult.Success -> confirmed.data
                        is ToolResult.Error -> "Error: ${confirmed.message}"
                        is ToolResult.NeedsConfirmation ->
                            "Error: Tool requested confirmation again after being confirmed"
                    }
                } else {
                    // Confirm-required tools: always deny via channel
                    "This action requires confirmation on the device. " +
                        "Open MobileClaw to approve, or ask me to do something else."
                }
            }
        }
    }

    // ── Image generation for channels ──

    private fun isImageRequest(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("generate image") || lower.contains("generate an image") ||
            lower.contains("create image") || lower.contains("create an image") ||
            lower.contains("make an image") || lower.contains("draw me") ||
            lower.contains("draw a ") || lower.contains("draw an ") ||
            lower.contains("生成圖") || lower.contains("畫一") || lower.contains("畫個")
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private suspend fun generateAndSendImage(channel: Channel, chatId: String, prompt: String) {
        val openaiKey = userPreferences.getTokenForProvider("openai")
        if (openaiKey.isBlank()) return

        try {
            val requestBody = """{"model":"dall-e-3","prompt":${jsonEscapeString(prompt)},"n":1,"size":"1024x1024"}"""
            val response = httpClient.post("https://api.openai.com/v1/images/generations") {
                header("Authorization", "Bearer $openaiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val json = jsonParser.decodeFromString<JsonObject>(response.bodyAsText())
            val imageUrl = json["data"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("url")?.jsonPrimitive?.content ?: return

            val imageBytes = httpClient.get(imageUrl).readRawBytes()
            channel.sendPhoto(chatId, imageBytes, prompt.take(200))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Image generation failed: ${e.message}")
            channel.sendMessage(chatId, "Sorry, image generation failed. Please try in the app.")
        }
    }

    /** Escape a string for safe JSON embedding. */
    private fun jsonEscapeString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    // ── Pairing request management ──

    fun approvePairing(request: PairingRequest) {
        val channel = channels[request.channelId] ?: return

        // FIX 5A: Check expiry before approving
        if (request.expiresAt < System.currentTimeMillis()) {
            _pendingRequests.update { it.filter { r -> r != request } }
            return // Expired, don't pair
        }

        channel.pair(request.senderId, request.senderName)

        // FIX 4: Atomic removal from pending
        _pendingRequests.update { current ->
            current.filter {
                !(it.senderId == request.senderId && it.channelId == request.channelId)
            }
        }

        refreshStatuses()

        // Notify sender
        scope.launch {
            channel.sendMessage(
                request.chatId,
                "You're now connected to MobileClaw! Send any message to chat with the AI.",
            )
        }
    }

    fun rejectPairing(request: PairingRequest) {
        val channel = channels[request.channelId] ?: return

        // FIX 3: Record rejection timestamp for cooldown
        rejectedSenders[request.senderId] = System.currentTimeMillis()

        // FIX 4: Atomic removal
        _pendingRequests.update { current ->
            current.filter {
                !(it.senderId == request.senderId && it.channelId == request.channelId)
            }
        }

        scope.launch {
            channel.sendMessage(request.chatId, "Pairing request denied.")
        }
    }

    private fun cleanExpiredRequests() {
        val now = System.currentTimeMillis()
        // FIX 4: Atomic StateFlow update
        _pendingRequests.update { current -> current.filter { it.expiresAt > now } }
    }

    // FIX 2: Single summary notification that updates in place
    private fun showPairingSummaryNotification() {
        val pending = _pendingRequests.value
        if (pending.isEmpty()) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            PAIRING_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = if (pending.size == 1) {
            "${pending.first().senderName} wants to connect"
        } else {
            "${pending.size} pending pairing requests"
        }

        val notification = NotificationCompat.Builder(context, MobileClawApplication.CHANNEL_AGENT_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pairing request")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(PAIRING_NOTIFICATION_ID, notification)
    }

    // ── Message rate limiting (FIX 2) ──

    private fun isMessageRateLimited(senderId: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = messageTimestamps.getOrPut(senderId) { Collections.synchronizedList(mutableListOf()) }
        synchronized(timestamps) {
            timestamps.removeAll { now - it > 60_000 }
            if (timestamps.size >= MAX_MESSAGES_PER_MINUTE) return true
            timestamps.add(now)
        }
        return false
    }

    /** Unpair a sender from a channel. */
    fun unpairSender(channelId: String, senderId: String) {
        channels[channelId]?.unpair(senderId)
        refreshStatuses()
    }

    /** Get all paired senders across all channels. */
    fun getAllPairedSenders(): List<PairedSender> {
        return channels.values.flatMap { it.getPairedSenders() }
    }

    /** Get a channel by ID. */
    fun getChannel(channelId: String): Channel? = channels[channelId]

    /** Get all registered channels. */
    fun getAllChannels(): List<Channel> = channels.values.toList()

    fun refreshStatuses() {
        _channelStatuses.value = channels.values.map { channel ->
            ChannelStatus(
                channelId = channel.id,
                displayName = channel.displayName,
                isRunning = channel.isRunning,
                pairedCount = channel.getPairedSenders().size,
            )
        }
    }
}

data class ChannelStatus(
    val channelId: String,
    val displayName: String,
    val isRunning: Boolean,
    val pairedCount: Int,
    val lastMessageTime: Long? = null,
)

data class PairingRequest(
    val channelId: String,
    val senderId: String,
    val senderName: String,
    val chatId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 10 * 60 * 1000,
)

// FIX 7: Added timestamp field for LRU eviction
data class HistoryMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)
