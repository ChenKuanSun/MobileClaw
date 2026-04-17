package ai.affiora.mobileclaw.channels

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.connectors.ConnectorManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Matrix client-server REST channel.
 * Long-polls /sync for incoming messages — no webhook or public URL required.
 *
 * Credentials stored in ConnectorManager under connector_matrix with a JSON blob:
 * {"homeserver":"https://matrix.org","user_id":"@alice:matrix.org","access_token":"syt_..."}
 */
class MatrixChannel(
    private val connectorManager: ConnectorManager,
    private val httpClient: HttpClient,
    private val context: Context,
) : Channel {

    override val id = "matrix"
    override val displayName = "Matrix"
    override var isRunning = false
        private set

    lateinit var channelManager: ChannelManager

    @Volatile private var syncJob: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pairedRooms = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var startTimestampMs: Long = 0L

    companion object {
        private const val TAG = "MatrixChannel"
        private const val PREFS = "matrix_channel"
        private const val KEY_NEXT_BATCH = "next_batch"
        private const val KEY_PAIRED = "paired_rooms"
        private const val SYNC_TIMEOUT_MS = 30_000
        private const val FILTER_JSON =
            """{"room":{"timeline":{"limit":20,"types":["m.room.message"]}},"presence":{"not_types":["*"]}}"""
    }

    init { loadPaired() }

    // ── Credentials from JSON blob ─────────────────────────────────────

    private data class Creds(val homeserver: String, val userId: String, val accessToken: String)

    private fun loadCreds(): Creds? {
        val blob = connectorManager.getToken("matrix") ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(blob).jsonObject
            val homeserver = (obj["homeserver"] as? JsonPrimitive)?.content?.trimEnd('/')
                ?: return@runCatching null
            val userId = (obj["user_id"] as? JsonPrimitive)?.content
                ?: return@runCatching null
            val accessToken = (obj["access_token"] as? JsonPrimitive)?.content
                ?: return@runCatching null
            Creds(homeserver, userId, accessToken)
        }.getOrNull()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun start() {
        val creds = loadCreds() ?: run {
            Log.w(TAG, "Matrix not configured")
            isRunning = false
            return
        }
        synchronized(this) {
            if (syncJob?.isActive == true) return
            isRunning = true
            startTimestampMs = System.currentTimeMillis()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            syncJob = scope.launch {
                while (isActive) {
                    try {
                        syncLoop(creds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Sync loop crashed, restart in 5s: ${e.message}")
                        delay(5_000)
                    }
                }
            }
        }
    }

    override fun stop() {
        syncJob?.cancel()
        syncJob = null
        scope.cancel()
        isRunning = false
    }

    // ── Sync loop ───────────────────────────────────────────────────────

    private suspend fun syncLoop(creds: Creds) {
        var since = loadNextBatch()
        var backoff = 1_000L

        while (currentCoroutineContext().isActive) {
            try {
                val url = buildString {
                    append(creds.homeserver).append("/_matrix/client/v3/sync")
                    append("?timeout=").append(SYNC_TIMEOUT_MS)
                    append("&filter=").append(URLEncoder.encode(FILTER_JSON, "UTF-8"))
                    if (since.isNotBlank()) append("&since=").append(URLEncoder.encode(since, "UTF-8"))
                }
                val resp = httpClient.get(url) {
                    header("Authorization", "Bearer ${creds.accessToken}")
                    timeout { requestTimeoutMillis = (SYNC_TIMEOUT_MS + 30_000).toLong() }
                }
                if (resp.status.value == 401 || resp.status.value == 403) {
                    Log.e(TAG, "Matrix auth failed (${resp.status.value}), stopping")
                    isRunning = false
                    return  // exits syncLoop; outer catch won't restart
                }
                if (!resp.status.isSuccess()) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                    continue
                }
                val syncResp = json.decodeFromString<MxSync>(resp.bodyAsText())

                // Auto-join invites
                syncResp.rooms?.invite?.keys?.forEach { roomId ->
                    runCatching { joinRoom(creds, roomId) }
                        .onFailure { Log.w(TAG, "Join $roomId failed: ${it.message}") }
                }

                // Process timeline events
                syncResp.rooms?.join?.forEach { (roomId, room) ->
                    room.timeline?.events?.forEach { evt ->
                        if (evt.type != "m.room.message") return@forEach
                        if (evt.sender == creds.userId) return@forEach
                        if (since.isBlank() && evt.originServerTs < startTimestampMs) return@forEach

                        val content = evt.content ?: return@forEach
                        val msgtype = (content["msgtype"] as? JsonPrimitive)?.content
                        if (msgtype != "m.text" && msgtype != "m.notice") return@forEach
                        val text = (content["body"] as? JsonPrimitive)?.content ?: return@forEach

                        // Fire-and-forget: don't block the sync loop while
                        // the AI generates a response (same fix as Telegram).
                        scope.launch {
                            channelManager.onMessageReceived(
                                IncomingMessage(
                                    channelId = id,
                                    chatId = roomId,
                                    senderId = roomId,
                                    senderName = evt.sender,
                                    text = text,
                                    timestamp = evt.originServerTs,
                                    threadId = evt.eventId.takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }
                }

                since = syncResp.nextBatch
                saveNextBatch(since)
                backoff = 1_000L
            } catch (_: java.net.SocketTimeoutException) {
                // Normal long-poll timeout — loop
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync error: ${e.message}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    // ── Sending ─────────────────────────────────────────────────────────

    override suspend fun sendMessage(chatId: String, text: String, threadId: String?) {
        val creds = loadCreds() ?: return
        val txnId = UUID.randomUUID().toString()
        val roomPath = URLEncoder.encode(chatId, "UTF-8")
        try {
            httpClient.put("${creds.homeserver}/_matrix/client/v3/rooms/$roomPath/send/m.room.message/$txnId") {
                header("Authorization", "Bearer ${creds.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("msgtype", JsonPrimitive("m.text"))
                        put("body", JsonPrimitive(text))
                        // Matrix reply: m.relates_to with m.in_reply_to.event_id
                        if (threadId != null) {
                            put("m.relates_to", buildJsonObject {
                                put("m.in_reply_to", buildJsonObject {
                                    put("event_id", JsonPrimitive(threadId))
                                })
                            })
                        }
                    }.toString(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
        }
    }

    override suspend fun sendPhoto(chatId: String, imageBytes: ByteArray, caption: String?): Boolean {
        val creds = loadCreds() ?: return false
        return try {
            // Upload media
            val uploadResp = httpClient.post("${creds.homeserver}/_matrix/media/v3/upload?filename=photo.jpg") {
                header("Authorization", "Bearer ${creds.accessToken}")
                header("Content-Type", "image/jpeg")
                setBody(imageBytes)
            }
            val mxc = json.decodeFromString<MxUpload>(uploadResp.bodyAsText()).contentUri

            // Send m.image event
            val txnId = UUID.randomUUID().toString()
            val roomPath = URLEncoder.encode(chatId, "UTF-8")
            httpClient.put("${creds.homeserver}/_matrix/client/v3/rooms/$roomPath/send/m.room.message/$txnId") {
                header("Authorization", "Bearer ${creds.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("msgtype", JsonPrimitive("m.image"))
                        put("body", JsonPrimitive(caption ?: "photo.jpg"))
                        put("url", JsonPrimitive(mxc))
                    }.toString(),
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto failed: ${e.message}")
            false
        }
    }

    private suspend fun joinRoom(creds: Creds, roomIdOrAlias: String) {
        val enc = URLEncoder.encode(roomIdOrAlias, "UTF-8")
        httpClient.post("${creds.homeserver}/_matrix/client/v3/join/$enc") {
            header("Authorization", "Bearer ${creds.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
    }

    // ── Pairing (by room) ───────────────────────────────────────────────

    override fun isAllowed(senderId: String) = senderId in pairedRooms
    override fun pair(senderId: String, senderName: String) { pairedRooms.add(senderId); savePaired() }
    override fun unpair(senderId: String) { pairedRooms.remove(senderId); savePaired() }
    override fun getPairedSenders() = pairedRooms.map {
        PairedSender(id = it, name = it, channelId = id)
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun loadNextBatch() = prefs().getString(KEY_NEXT_BATCH, "") ?: ""
    private fun saveNextBatch(v: String) { prefs().edit().putString(KEY_NEXT_BATCH, v).apply() }
    private fun loadPaired() {
        pairedRooms.clear()
        pairedRooms.addAll(prefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
    }
    private fun savePaired() { prefs().edit().putStringSet(KEY_PAIRED, pairedRooms.toSet()).apply() }

    // ── Wire types ──────────────────────────────────────────────────────

    @Serializable
    private data class MxSync(
        @SerialName("next_batch") val nextBatch: String,
        val rooms: MxRooms? = null,
    )
    @Serializable
    private data class MxRooms(
        val join: Map<String, MxJoinedRoom>? = null,
        val invite: Map<String, JsonObject>? = null,
    )
    @Serializable
    private data class MxJoinedRoom(val timeline: MxTimeline? = null)
    @Serializable
    private data class MxTimeline(val events: List<MxEvent>? = null)
    @Serializable
    private data class MxEvent(
        val type: String,
        val sender: String = "",
        @SerialName("event_id") val eventId: String = "",
        @SerialName("origin_server_ts") val originServerTs: Long = 0,
        val content: JsonObject? = null,
    )
    @Serializable
    private data class MxUpload(@SerialName("content_uri") val contentUri: String)
}
