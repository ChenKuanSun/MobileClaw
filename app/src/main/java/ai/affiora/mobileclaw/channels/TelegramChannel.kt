package ai.affiora.mobileclaw.channels

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import ai.affiora.mobileclaw.connectors.ConnectorManager
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TelegramChannel(
    private val connectorManager: ConnectorManager,
    private val httpClient: HttpClient,
    private val context: Context,
    private val userPreferences: UserPreferences,
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
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var cachedBotUsername: String? = null

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
            isRunning = false
            return
        }
        if (pollingJob?.isActive == true) return

        isRunning = true
        registerNetworkCallback()
        pollingJob = scope.launch {
            registerBotCommands(token)

            // Outer restart loop — survives inner pollLoop crashes
            while (isActive) {
                try {
                    pollLoop(token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Polling loop crashed, restarting in 5s: ${e.message}")
                    isRunning = false
                    delay(5000)
                    isRunning = true
                }
            }
        }
    }

    private suspend fun pollLoop(token: String) {
        var offset = loadOffset()
        var backoff = 1000L

        while (currentCoroutineContext().isActive) {
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
                    if (apiResponse.result.isNotEmpty()) {
                        Log.i(TAG, "Received ${apiResponse.result.size} updates")
                    }
                    for (update in apiResponse.result) {
                        val msg = update.message ?: continue

                        var text = msg.text ?: msg.caption ?: ""
                        val photoFileId = msg.photo?.lastOrNull()?.fileId
                        val docFileId = msg.document?.fileId
                        val voiceFileId = msg.voice?.fileId

                        // Group message filtering — only respond when @mentioned
                        val isGroup = msg.chat.type in listOf("group", "supergroup")
                        if (isGroup) {
                            val botUsername = getBotUsername(token)
                            if (botUsername.isNotEmpty()) {
                                val mentioned = text.contains("@$botUsername", ignoreCase = true) ||
                                    msg.entities?.any { entity ->
                                        entity.type == "mention" &&
                                            text.substring(entity.offset, (entity.offset + entity.length).coerceAtMost(text.length))
                                                .equals("@$botUsername", ignoreCase = true)
                                    } == true

                                if (!mentioned) {
                                    offset = update.updateId + 1
                                    saveOffset(offset)
                                    continue
                                }

                                // Strip the @mention from the text
                                text = text.replace("@$botUsername", "", ignoreCase = true).trim()
                            }
                        }

                        // Skip completely empty messages
                        if (text.isBlank() && photoFileId == null && docFileId == null && voiceFileId == null) {
                            offset = update.updateId + 1
                            saveOffset(offset)
                            continue
                        }

                        // Handle bot commands before passing to AI
                        if (text.startsWith("/")) {
                            handleBotCommand(token, msg, text)
                            offset = update.updateId + 1
                            saveOffset(offset)
                            continue
                        }

                        // Build message text with media context
                        var messageText = text
                        var imageBase64: String? = null
                        val mediaDescriptions = mutableListOf<String>()

                        // Download and encode photo
                        if (photoFileId != null) {
                            val bytes = downloadFile(token, photoFileId)
                            if (bytes != null) {
                                imageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                if (text.isBlank()) {
                                    messageText = "Describe what you see in this image."
                                }
                            }
                        }

                        // Note document
                        if (docFileId != null) {
                            mediaDescriptions.add("Document: ${msg.document?.fileName ?: "unknown"}")
                        }

                        // Transcribe voice via Whisper or fall back to description
                        if (voiceFileId != null) {
                            val transcript = transcribeVoice(token, voiceFileId)
                            if (transcript != null) {
                                messageText += if (messageText.isBlank()) transcript else "\n$transcript"
                                mediaDescriptions.add("Voice message transcribed, ${msg.voice?.duration}s")
                            } else {
                                mediaDescriptions.add("Voice message, ${msg.voice?.duration}s — transcription unavailable")
                            }
                        }

                        val mediaDesc = mediaDescriptions.joinToString("; ").ifBlank { null }

                        channelManager.onMessageReceived(
                            IncomingMessage(
                                channelId = "telegram",
                                chatId = msg.chat.id.toString(),
                                senderId = msg.chat.id.toString(),
                                senderName = msg.from?.firstName ?: "User",
                                text = messageText,
                                timestamp = msg.date * 1000L,
                                imageBase64 = imageBase64,
                                mediaDescription = mediaDesc,
                            ),
                        )

                        offset = update.updateId + 1
                        saveOffset(offset)
                    }
                }

                delay(500)
            } catch (_: java.net.SocketTimeoutException) {
                // Normal for long polling — no messages arrived within timeout
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Poll error: ${e.javaClass.simpleName}: ${e.message}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    override fun stop() {
        unregisterNetworkCallback()
        pollingJob?.cancel()
        pollingJob = null
        isRunning = false
    }

    override suspend fun sendMessage(chatId: String, text: String, threadId: String?) {
        val token = connectorManager.getToken("telegram") ?: return
        // Split at 4096 char Telegram limit
        text.chunked(4096).forEach { chunk ->
            var retries = 3
            while (retries > 0) {
                try {
                    httpClient.post("https://api.telegram.org/bot$token/sendMessage") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("chat_id", JsonPrimitive(chatId.toLong()))
                                put("text", JsonPrimitive(chunk))
                                // Forum topic / thread routing — Telegram supergroups with topics
                                threadId?.toIntOrNull()?.let {
                                    put("message_thread_id", JsonPrimitive(it))
                                }
                            }.toString(),
                        )
                    }
                    break // Success
                } catch (e: Exception) {
                    retries--
                    if (retries > 0) {
                        Log.w(TAG, "Send retry ($retries left): ${e.message}")
                        delay(1000)
                    } else {
                        Log.e(TAG, "Failed to send after retries to $chatId", e)
                    }
                }
            }
        }
    }

    /** Send a "typing" chat action so user sees the bot is working. */
    suspend fun sendTyping(chatId: String) {
        val token = connectorManager.getToken("telegram") ?: return
        try {
            httpClient.post("https://api.telegram.org/bot$token/sendChatAction") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("chat_id", JsonPrimitive(chatId.toLong()))
                        put("action", JsonPrimitive("typing"))
                    }.toString(),
                )
            }
        } catch (_: Exception) {
            // Best-effort — don't fail if typing indicator fails
        }
    }

    override suspend fun sendPhoto(chatId: String, imageBytes: ByteArray, caption: String?): Boolean {
        val token = connectorManager.getToken("telegram") ?: return false
        return try {
            val url = "https://api.telegram.org/bot$token/sendPhoto"
            val body = buildMultipartBody(chatId, imageBytes, "photo.jpg", "photo", caption)
            val boundary = body.first
            httpClient.post(url) {
                headers.append("Content-Type", "multipart/form-data; boundary=$boundary")
                setBody(body.second)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send photo: ${e.message}")
            false
        }
    }

    override suspend fun sendDocument(chatId: String, fileBytes: ByteArray, fileName: String, caption: String?): Boolean {
        val token = connectorManager.getToken("telegram") ?: return false
        return try {
            val url = "https://api.telegram.org/bot$token/sendDocument"
            val body = buildMultipartBody(chatId, fileBytes, fileName, "document", caption)
            val boundary = body.first
            httpClient.post(url) {
                headers.append("Content-Type", "multipart/form-data; boundary=$boundary")
                setBody(body.second)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send document: ${e.message}")
            false
        }
    }

    /** Build multipart/form-data body. Returns (boundary, bodyBytes). */
    private fun buildMultipartBody(
        chatId: String, fileBytes: ByteArray,
        fileName: String, fieldName: String, caption: String?,
    ): Pair<String, ByteArray> {
        val boundary = "----MobileClaw${System.currentTimeMillis()}"
        val sb = StringBuilder()
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n")
        if (caption != null) {
            sb.append("--$boundary\r\n")
            sb.append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n$caption\r\n")
        }
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
        sb.append("Content-Type: application/octet-stream\r\n\r\n")
        val prefix = sb.toString().toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        return boundary to (prefix + fileBytes + suffix)
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

    // ── Network Reconnection ──

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available — ensuring polling is active")
                scope.launch {
                    if (pollingJob?.isActive != true) {
                        start()
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost")
            }
        }
        cm.registerDefaultNetworkCallback(connectivityCallback!!)
    }

    private fun unregisterNetworkCallback() {
        connectivityCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
            connectivityCallback = null
        }
    }

    // ── Bot Commands ──

    private suspend fun registerBotCommands(token: String) {
        try {
            val commands = listOf(
                mapOf("command" to "start", "description" to "Start chatting with MobileClaw"),
                mapOf("command" to "help", "description" to "Show available commands"),
                mapOf("command" to "status", "description" to "Check bot status"),
                mapOf("command" to "unpair", "description" to "Disconnect from this device"),
            )

            httpClient.post("https://api.telegram.org/bot$token/setMyCommands") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(mapOf("commands" to commands)))
            }
            Log.i(TAG, "Bot commands registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register bot commands: ${e.message}")
        }
    }

    private suspend fun getBotUsername(token: String): String {
        cachedBotUsername?.let { return it }
        return try {
            val response = httpClient.get("https://api.telegram.org/bot$token/getMe")
            val result = json.decodeFromString<TgGetMeResponse>(response.bodyAsText())
            val username = result.result?.username ?: ""
            cachedBotUsername = username
            username
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get bot username: ${e.message}")
            ""
        }
    }

    private suspend fun handleBotCommand(token: String, msg: TgMessage, text: String) {
        val chatId = msg.chat.id.toString()
        val senderId = msg.chat.id.toString()
        val command = text.split(" ", "@").first().lowercase()

        when (command) {
            "/start" -> {
                if (isAllowed(senderId)) {
                    sendMessage(chatId, "You're already connected! Send any message to chat with the AI.")
                } else {
                    // Delegate to ChannelManager to create pairing request
                    channelManager.onMessageReceived(
                        IncomingMessage(
                            channelId = "telegram",
                            chatId = chatId,
                            senderId = senderId,
                            senderName = msg.from?.firstName ?: "User",
                            text = "/start",
                            timestamp = msg.date * 1000L,
                        ),
                    )
                }
            }
            "/help" -> {
                sendMessage(
                    chatId,
                    """
                    |MobileClaw Bot Commands:
                    |/start - Start chatting with MobileClaw
                    |/help - Show this help message
                    |/status - Check bot and device status
                    |/unpair - Disconnect from this device
                    |
                    |You can also send photos, documents, and voice messages.
                    """.trimMargin(),
                )
            }
            "/status" -> {
                if (!isAllowed(senderId)) {
                    sendMessage(chatId, "You're not paired. Send /start to request pairing.")
                    return
                }
                val deviceModel = android.os.Build.MODEL
                val androidVersion = android.os.Build.VERSION.RELEASE
                sendMessage(
                    chatId,
                    """
                    |MobileClaw Status:
                    |Device: $deviceModel (Android $androidVersion)
                    |Bot: Running
                    |Paired: Yes
                    """.trimMargin(),
                )
            }
            "/unpair" -> {
                if (!isAllowed(senderId)) {
                    sendMessage(chatId, "You're not paired.")
                    return
                }
                unpair(senderId)
                channelManager.refreshStatuses()
                sendMessage(chatId, "Disconnected. Send /start to pair again.")
            }
            else -> {
                // Unknown command — ignore silently
            }
        }
    }

    // ── Voice Transcription via Whisper ──

    private suspend fun transcribeVoice(token: String, fileId: String): String? {
        val audioBytes = downloadFile(token, fileId) ?: return null

        val openaiKey = userPreferences.getTokenForProvider("openai")
        if (openaiKey.isBlank()) return null

        return try {
            val boundary = "----Whisper${System.currentTimeMillis()}"
            val body = buildWhisperMultipart(boundary, audioBytes)
            val response = httpClient.post("https://api.openai.com/v1/audio/transcriptions") {
                header("Authorization", "Bearer $openaiKey")
                header("Content-Type", "multipart/form-data; boundary=$boundary")
                setBody(body)
            }
            val result = json.decodeFromString<kotlinx.serialization.json.JsonObject>(response.bodyAsText())
            result["text"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription failed: ${e.message}")
            null
        }
    }

    private fun buildWhisperMultipart(boundary: String, audioBytes: ByteArray): ByteArray {
        val sb = StringBuilder()
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n")
        sb.append("--$boundary\r\n")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"voice.ogg\"\r\n")
        sb.append("Content-Type: audio/ogg\r\n\r\n")
        val prefix = sb.toString().toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        return prefix + audioBytes + suffix
    }

    // ── Media Download ──

    private suspend fun downloadFile(token: String, fileId: String): ByteArray? {
        return try {
            val fileResponse = httpClient.get("https://api.telegram.org/bot$token/getFile") {
                parameter("file_id", fileId)
            }
            val fileResult = json.decodeFromString<TgFileResponse>(fileResponse.bodyAsText())
            val filePath = fileResult.result?.filePath ?: return null

            val fileBytes = httpClient.get("https://api.telegram.org/file/bot$token/$filePath")
            fileBytes.readRawBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file: ${e.message}")
            null
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
private data class TgFileResponse(
    val ok: Boolean,
    val result: TgFile? = null,
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
    val caption: String? = null,
    val photo: List<TgPhotoSize>? = null,
    val document: TgDocument? = null,
    val voice: TgVoice? = null,
    val entities: List<TgMessageEntity>? = null,
)

@Serializable
private data class TgMessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
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
    val username: String? = null,
)

@Serializable
private data class TgGetMeResponse(
    val ok: Boolean,
    val result: TgUser? = null,
)

@Serializable
private data class TgPhotoSize(
    @SerialName("file_id") val fileId: String,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("file_size") val fileSize: Long = 0,
)

@Serializable
private data class TgDocument(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
)

@Serializable
private data class TgVoice(
    @SerialName("file_id") val fileId: String,
    val duration: Int = 0,
    @SerialName("file_size") val fileSize: Long = 0,
)

@Serializable
private data class TgFile(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_path") val filePath: String? = null,
)
