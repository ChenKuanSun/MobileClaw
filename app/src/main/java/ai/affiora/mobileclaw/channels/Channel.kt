package ai.affiora.mobileclaw.channels

/** A messaging channel that can receive and send messages. */
interface Channel {
    val id: String
    val displayName: String
    val isRunning: Boolean

    /** Start listening for incoming messages. */
    suspend fun start()

    /** Stop listening. */
    fun stop()

    /** Send a text message to a specific chat/user. */
    suspend fun sendMessage(chatId: String, text: String)

    /** Send a photo to a specific chat/user. Returns true if supported. */
    suspend fun sendPhoto(chatId: String, imageBytes: ByteArray, caption: String? = null): Boolean = false

    /** Send a file/document to a specific chat/user. Returns true if supported. */
    suspend fun sendDocument(chatId: String, fileBytes: ByteArray, fileName: String, caption: String? = null): Boolean = false

    /** Check if a sender is paired/allowed. */
    fun isAllowed(senderId: String): Boolean

    /** Pair a new sender. */
    fun pair(senderId: String, senderName: String)

    /** Unpair a sender. */
    fun unpair(senderId: String)

    /** Get all paired senders. */
    fun getPairedSenders(): List<PairedSender>
}

data class PairedSender(
    val id: String,
    val name: String,
    val channelId: String,
    val pairedAt: Long = System.currentTimeMillis(),
)

data class IncomingMessage(
    val channelId: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val imageBase64: String? = null,
    val mediaDescription: String? = null,
)
