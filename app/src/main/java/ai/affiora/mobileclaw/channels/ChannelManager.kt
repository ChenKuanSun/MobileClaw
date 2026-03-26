package ai.affiora.mobileclaw.channels

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.agent.AgentRuntime
import ai.affiora.mobileclaw.agent.SystemPromptBuilder
import ai.affiora.mobileclaw.data.model.AgentEvent
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private const val PREFS_NAME = "channel_pairing"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRING_EXPIRY = "pairing_code_expiry"
    }

    private val channels = mutableMapOf<String, Channel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thread-safe per-channel, per-chat conversation history (FIX 3)
    private val chatHistories = ConcurrentHashMap<String, MutableList<HistoryMessage>>()

    // Rate limiting for pairing attempts (FIX 1B)
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val lastAttemptTime = ConcurrentHashMap<String, Long>()

    // Rate limiting for paired user messages (FIX 2)
    private val messageTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    // Track senders who already received unpaired instructions on SMS (FIX 8)
    private val repliedUnpairedSenders = Collections.synchronizedSet(mutableSetOf<String>())

    // Channel status observable
    private val _channelStatuses = MutableStateFlow<List<ChannelStatus>>(emptyList())
    val channelStatuses: StateFlow<List<ChannelStatus>> = _channelStatuses.asStateFlow()

    // Pairing code with expiry (FIX 1A, 1C)
    private val _pairingCode = MutableStateFlow(loadOrGeneratePairingCode())
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()
    private var pairingCodeExpiry: Long = loadPairingExpiry()

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

        // Check pairing
        if (!channel.isAllowed(msg.senderId)) {
            // FIX 1D: Check rate limit before processing unpaired users
            if (isRateLimited(msg.senderId)) return // silently drop

            // Check if the message IS a pairing code
            if (isPairingCode(msg.text.trim())) {
                channel.pair(msg.senderId, msg.senderName)
                channel.sendMessage(
                    msg.chatId,
                    "Paired successfully! You can now chat with MobileClaw.",
                )
                // FIX 6: Regenerate code after successful pairing to prevent reuse
                generatePairingCode()
                // Clear rate limit state for newly paired sender
                failedAttempts.remove(msg.senderId)
                lastAttemptTime.remove(msg.senderId)
                refreshStatuses()
                return
            }

            // FIX 1D: Track failed pairing attempts
            failedAttempts[msg.senderId] = (failedAttempts[msg.senderId] ?: 0) + 1
            lastAttemptTime[msg.senderId] = System.currentTimeMillis()

            // FIX 8: Don't reply to unpaired SMS senders more than once (cost attack prevention)
            if (msg.channelId == "sms" && msg.senderId in repliedUnpairedSenders) return

            // Send pairing instructions
            channel.sendMessage(
                msg.chatId,
                "You're not paired with this MobileClaw instance.\n" +
                    "Send the pairing code shown in MobileClaw's Devices tab to pair.",
            )
            repliedUnpairedSenders.add(msg.senderId)
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

    // ── Rate limiting (FIX 1B) ──

    private fun isRateLimited(senderId: String): Boolean {
        val attempts = failedAttempts[senderId] ?: 0
        if (attempts >= 5) {
            val lastTime = lastAttemptTime[senderId] ?: 0
            val cooldown = 60_000L * (attempts - 4).coerceAtMost(10) // 1-10 min cooldown
            return System.currentTimeMillis() - lastTime < cooldown
        }
        return false
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

    // ── Pairing code management (FIX 1A, 1C, 6) ──

    fun generatePairingCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous 0/O/1/I
        val code = (1..8).map { chars.random() }.joinToString("")
        val expiry = System.currentTimeMillis() + 10 * 60 * 1000 // 10 minutes
        prefs.edit()
            .putString(KEY_PAIRING_CODE, code)
            .putLong(KEY_PAIRING_EXPIRY, expiry)
            .apply()
        _pairingCode.value = code
        pairingCodeExpiry = expiry
        return code
    }

    private fun loadOrGeneratePairingCode(): String {
        val existing = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAIRING_CODE, null)
        val expiry = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PAIRING_EXPIRY, 0L)
        // Return existing only if not expired
        if (existing != null && expiry > System.currentTimeMillis()) {
            return existing
        }
        // Generate fresh code
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..8).map { chars.random() }.joinToString("")
        val newExpiry = System.currentTimeMillis() + 10 * 60 * 1000
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRING_CODE, code)
            .putLong(KEY_PAIRING_EXPIRY, newExpiry)
            .apply()
        return code
    }

    private fun loadPairingExpiry(): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PAIRING_EXPIRY, 0L)
    }

    private fun isPairingCode(text: String): Boolean {
        // FIX 1C: Check expiry
        if (pairingCodeExpiry < System.currentTimeMillis()) return false
        return _pairingCode.value.isNotBlank() && text.trim().equals(_pairingCode.value, ignoreCase = true)
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

// FIX 7: Added timestamp field for LRU eviction
data class HistoryMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)
