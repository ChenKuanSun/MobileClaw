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
        private const val PREFS_NAME = "channel_pairing"
        private const val KEY_PAIRING_CODE = "pairing_code"
    }

    private val channels = mutableMapOf<String, Channel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Per-channel, per-chat conversation history (last 20 messages)
    private val chatHistories = mutableMapOf<String, MutableList<HistoryMessage>>()

    // Channel status observable
    private val _channelStatuses = MutableStateFlow<List<ChannelStatus>>(emptyList())
    val channelStatuses: StateFlow<List<ChannelStatus>> = _channelStatuses.asStateFlow()

    // Pairing code
    private val _pairingCode = MutableStateFlow(loadOrGeneratePairingCode())
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

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
            // Check if the message IS a pairing code
            if (isPairingCode(msg.text.trim())) {
                channel.pair(msg.senderId, msg.senderName)
                channel.sendMessage(
                    msg.chatId,
                    "Paired successfully! You can now chat with MobileClaw.",
                )
                refreshStatuses()
                return
            }

            // Send pairing instructions
            channel.sendMessage(
                msg.chatId,
                "You're not paired with this MobileClaw instance.\n" +
                    "Send the pairing code shown in MobileClaw's Devices tab to pair.",
            )
            return
        }

        // Build history key
        val historyKey = "${msg.channelId}:${msg.chatId}"
        val history = chatHistories.getOrPut(historyKey) { mutableListOf() }
        history.add(HistoryMessage("user", msg.text))
        while (history.size > MAX_HISTORY) history.removeAt(0)

        // Convert to Claude messages
        val claudeHistory = history.dropLast(1).map {
            ClaudeMessage(role = it.role, content = ClaudeContent.Text(it.content))
        }

        // Build system prompt with channel context
        val systemPrompt = systemPromptBuilder.build() +
            "\n\nYou are responding via ${channel.displayName} to ${msg.senderName}. " +
            "Keep responses concise."

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
            history.add(HistoryMessage("assistant", reply))
            while (history.size > MAX_HISTORY) history.removeAt(0)
        }
    }

    // ── Pairing code management ──

    fun generatePairingCode(): String {
        val code = (100000..999999).random().toString()
        prefs.edit().putString(KEY_PAIRING_CODE, code).apply()
        _pairingCode.value = code
        return code
    }

    private fun loadOrGeneratePairingCode(): String {
        val existing = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAIRING_CODE, null)
        return existing ?: run {
            val code = (100000..999999).random().toString()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PAIRING_CODE, code).apply()
            code
        }
    }

    private fun isPairingCode(text: String): Boolean {
        return text == _pairingCode.value
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

data class HistoryMessage(val role: String, val content: String)
