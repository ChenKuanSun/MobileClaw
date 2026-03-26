package ai.affiora.mobileclaw.channels

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.connectors.ConnectorManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TelegramChannel(
    private val connectorManager: ConnectorManager,
    private val httpClient: HttpClient,
    private val context: Context,
) : Channel {

    override val id = "telegram"
    override val displayName = "Telegram"
    override var isRunning = false
        private set

    // Injected after construction to break circular dependency
    lateinit var channelManager: ChannelManager

    private var pollingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val pairedSenders = mutableSetOf<String>()

    companion object {
        private const val TAG = "TelegramChannel"
        private const val PREFS = "telegram_channel"
        private const val KEY_OFFSET = "update_offset"
        private const val KEY_PAIRED = "paired_chats"
    }

    init {
        loadPaired()
    }

    override suspend fun start() {
        val token = connectorManager.getToken("telegram")
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No Telegram bot token configured")
            return
        }
        if (pollingJob?.isActive == true) return

        isRunning = true
        pollingJob = scope.launch {
            var offset = loadOffset()
            var backoff = 1000L

            while (isActive) {
                try {
                    val response = httpClient.get("https://api.telegram.org/bot$token/getUpdates") {
                        parameter("offset", offset)
                        parameter("timeout", 30)
                        parameter("limit", 10)
                    }

                    val body = response.bodyAsText()
                    val apiResponse = json.decodeFromString<TgApiResponse>(body)

                    if (apiResponse.ok && apiResponse.result != null) {
                        backoff = 1000L
                        for (update in apiResponse.result) {
                            val msg = update.message ?: continue
                            val text = msg.text ?: continue

                            channelManager.onMessageReceived(
                                IncomingMessage(
                                    channelId = "telegram",
                                    chatId = msg.chat.id.toString(),
                                    senderId = msg.chat.id.toString(),
                                    senderName = msg.from?.firstName ?: "User",
                                    text = text,
                                    timestamp = msg.date * 1000L,
                                ),
                            )

                            offset = update.updateId + 1
                            saveOffset(offset)
                        }
                    }

                    delay(500)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }
    }

    override fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        isRunning = false
    }

    override suspend fun sendMessage(chatId: String, text: String) {
        val token = connectorManager.getToken("telegram") ?: return
        // Split at 4096 char Telegram limit
        text.chunked(4096).forEach { chunk ->
            try {
                httpClient.post("https://api.telegram.org/bot$token/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("chat_id", JsonPrimitive(chatId.toLong()))
                            put("text", JsonPrimitive(chunk))
                        }.toString(),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to $chatId", e)
            }
        }
    }

    override fun isAllowed(senderId: String) = senderId in pairedSenders

    override fun pair(senderId: String, senderName: String) {
        pairedSenders.add(senderId)
        savePaired()
    }

    override fun unpair(senderId: String) {
        pairedSenders.remove(senderId)
        savePaired()
    }

    override fun getPairedSenders(): List<PairedSender> {
        return pairedSenders.map {
            PairedSender(id = it, name = "Telegram User", channelId = "telegram")
        }
    }

    // ── Persistence ──

    private fun getPrefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadOffset(): Long = getPrefs().getLong(KEY_OFFSET, 0L)

    private fun saveOffset(offset: Long) {
        getPrefs().edit().putLong(KEY_OFFSET, offset).apply()
    }

    private fun loadPaired() {
        val set = getPrefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet()
        pairedSenders.clear()
        pairedSenders.addAll(set)
    }

    private fun savePaired() {
        getPrefs().edit().putStringSet(KEY_PAIRED, pairedSenders.toSet()).apply()
    }
}

// ── Telegram API models ──

@Serializable
private data class TgApiResponse(
    val ok: Boolean,
    val result: List<TgUpdate>? = null,
)

@Serializable
private data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
)

@Serializable
private data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val from: TgUser? = null,
    val date: Long,
    val text: String? = null,
)

@Serializable
private data class TgChat(
    val id: Long,
    val type: String = "private",
)

@Serializable
private data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String = "",
)
