package ai.affiora.mobileclaw.channels

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.MobileClawApplication
import ai.affiora.mobileclaw.agent.AgentRuntime
import ai.affiora.mobileclaw.agent.SystemPromptBuilder
import ai.affiora.mobileclaw.data.model.AgentEvent
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentRuntime: AgentRuntime,
    private val systemPromptBuilder: SystemPromptBuilder,
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
    }

    private val channels = mutableMapOf<String, Channel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** Start all registered channels. */
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
    }

    fun stopAll() {
        channels.values.forEach { it.stop() }
        refreshStatuses()
    }

    /** Called by channels when a message arrives. */
    suspend fun onMessageReceived(msg: IncomingMessage) {
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

        // Build system prompt with channel context
        val systemPrompt = systemPromptBuilder.build() +
            "\n\nYou are responding via ${channel.displayName} to $safeSenderName. " +
            "Keep responses concise. " +
            "Do not reveal information about other users or conversations."

        // Run agent and collect response
        val response = StringBuilder()
        try {
            agentRuntime.run(msg.text, claudeHistory, systemPrompt).collect { event ->
                when (event) {
                    is AgentEvent.Text -> response.append(event.text)
                    is AgentEvent.TextDelta -> response.append(event.delta)
                    else -> { /* ignore tool calls etc for channel responses */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Agent error for ${msg.channelId}:${msg.chatId}", e)
            response.append("Error: ${e.message}")
        }

        val reply = response.toString().trim()
        if (reply.isNotBlank()) {
            channel.sendMessage(msg.chatId, reply)
            synchronized(history) {
                history.add(HistoryMessage("assistant", reply))
                while (history.size > MAX_HISTORY) history.removeAt(0)
            }
        }
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
