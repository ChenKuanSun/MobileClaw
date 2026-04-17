package ai.affiora.mobileclaw.channels

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.connectors.ConnectorManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Slack Socket Mode channel. WebSocket-based — no public URL needed.
 *
 * Credentials stored as JSON blob under "slack_app":
 *   {"app_token":"xapp-1-...","bot_token":"xoxb-..."}
 *
 * The existing "slack" connector remains the OAuth2 bot token for HTTP tools.
 * This channel uses a separate credential so the user can have one without the other.
 */
class SlackChannel(
    private val connectorManager: ConnectorManager,
    private val httpClient: HttpClient,
    private val context: Context,
) : Channel {

    override val id = "slack_app"
    override val displayName = "Slack"
    override var isRunning = false
        private set

    lateinit var channelManager: ChannelManager

    // SlackChannel needs its own WebSocket-capable client because Ktor's
    // WebSockets pipeline interceptor interferes with regular HTTP long-polling
    // (breaks TelegramChannel getUpdates). The shared HttpClient from
    // ToolsModule deliberately omits the WebSockets plugin for this reason.
    private val wsHttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        install(io.ktor.client.plugins.websocket.WebSockets)
    }

    private var wsJob: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pairedChannels = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var botUserId: String? = null
    private var startTimestampMs: Long = 0L

    companion object {
        private const val TAG = "SlackChannel"
        private const val PREFS = "slack_channel"
        private const val KEY_PAIRED = "paired_channels"
    }

    init { loadPaired() }

    // ── Credentials ─────────────────────────────────────────────────────

    private data class Creds(val appToken: String, val botToken: String)

    private fun loadCreds(): Creds? {
        val blob = connectorManager.getToken("slack_app") ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(blob).jsonObject
            val app = (obj["app_token"] as? JsonPrimitive)?.content ?: return@runCatching null
            val bot = (obj["bot_token"] as? JsonPrimitive)?.content ?: return@runCatching null
            if (!app.startsWith("xapp-")) return@runCatching null
            if (!bot.startsWith("xoxb-")) return@runCatching null
            Creds(app, bot)
        }.getOrNull()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun start() {
        val creds = loadCreds() ?: run {
            Log.w(TAG, "Slack not configured (need xapp- app token + xoxb- bot token)")
            isRunning = false
            return
        }

        // Resolve bot user id so we can ignore our own messages
        runCatching { botUserId = fetchAuthTestUserId(creds.botToken) }
            .onFailure { Log.w(TAG, "auth.test failed: ${it.message}") }

        synchronized(this) {
            if (wsJob?.isActive == true) return
            isRunning = true
            startTimestampMs = System.currentTimeMillis()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            wsJob = scope.launch {
                var backoff = 1_000L
                while (isActive) {
                    try {
                        runSocketMode(creds)
                        backoff = 1_000L
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Socket Mode loop crashed: ${e.message}, retry in ${backoff}ms")
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    }
                }
            }
        }
    }

    override fun stop() {
        wsJob?.cancel()
        wsJob = null
        scope.cancel()
        isRunning = false
    }

    // ── Socket Mode ─────────────────────────────────────────────────────

    private suspend fun runSocketMode(creds: Creds) {
        // 1. Open a new Socket Mode connection
        val openResp = httpClient.post("https://slack.com/api/apps.connections.open") {
            header("Authorization", "Bearer ${creds.appToken}")
            contentType(ContentType.Application.FormUrlEncoded)
        }
        val openBody = openResp.bodyAsText()
        val open = json.decodeFromString<SlackOpenResp>(openBody)
        if (!open.ok || open.url.isNullOrBlank()) {
            throw IllegalStateException("apps.connections.open failed: $openBody")
        }

        Log.i(TAG, "Slack Socket Mode connected")

        // 2. Connect WebSocket (Slack URL already includes the auth; append debug_reconnects)
        val wsUrl = if (open.url.contains("?")) "${open.url}&debug_reconnects=true" else "${open.url}?debug_reconnects=true"

        wsHttpClient.webSocket(urlString = wsUrl) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val payload = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val envelope = payload["envelope_id"]?.jsonPrimitive?.content
                val type = payload["type"]?.jsonPrimitive?.content

                when (type) {
                    "hello" -> Log.d(TAG, "Slack hello")
                    "disconnect" -> {
                        Log.i(TAG, "Slack requested disconnect — reopening")
                        return@webSocket  // block exits, webSocket closes, outer retry reconnects
                    }
                    "events_api" -> {
                        if (envelope != null) {
                            runCatching {
                                send(buildJsonObject { put("envelope_id", JsonPrimitive(envelope)) }.toString())
                            }
                        }
                        handleEventsApi(payload, creds)
                    }
                    "slash_commands", "interactive" -> {
                        // ACK only — dispatch is intentionally not implemented. If
                        // dispatch is ever added here (block_actions, modal submit,
                        // slash command handlers), per OpenClaw #66028 the handler
                        // MUST call isAllowed(channelId) on the inferred channel id
                        // before routing to channelManager, otherwise unpaired users
                        // can trigger side effects via interaction callbacks.
                        if (envelope != null) {
                            runCatching {
                                send(buildJsonObject { put("envelope_id", JsonPrimitive(envelope)) }.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleEventsApi(payload: JsonObject, creds: Creds) {
        val event = payload["payload"]?.jsonObject?.get("event")?.jsonObject ?: return
        val type = event["type"]?.jsonPrimitive?.content ?: return
        if (type != "message") return
        // Skip subtype messages (edits, bot messages, etc.) except normal user messages
        val subtype = event["subtype"]?.jsonPrimitive?.content
        if (subtype != null) return

        val userId = event["user"]?.jsonPrimitive?.content ?: return
        if (botUserId != null && userId == botUserId) return

        val channelId = event["channel"]?.jsonPrimitive?.content ?: return
        val text = event["text"]?.jsonPrimitive?.content ?: return
        val tsStr = event["ts"]?.jsonPrimitive?.content ?: ""
        val ts = tsStr.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: System.currentTimeMillis()
        if (ts < startTimestampMs) return

        // Thread routing: if message is in a thread, reply in same thread.
        // If message is a top-level message in a channel that should become a thread, use ts.
        val threadTs = event["thread_ts"]?.jsonPrimitive?.content ?: tsStr

        channelManager.onMessageReceived(
            IncomingMessage(
                channelId = id,
                chatId = channelId,
                senderId = channelId, // pair by Slack channel
                senderName = userId,
                text = text,
                timestamp = ts,
                threadId = threadTs,
            ),
        )
    }

    // ── Web API ─────────────────────────────────────────────────────────

    private suspend fun fetchAuthTestUserId(botToken: String): String? {
        val resp = httpClient.post("https://slack.com/api/auth.test") {
            header("Authorization", "Bearer $botToken")
            contentType(ContentType.Application.FormUrlEncoded)
        }
        val body = resp.bodyAsText()
        val obj = json.parseToJsonElement(body).jsonObject
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        return obj["user_id"]?.jsonPrimitive?.content
    }

    override suspend fun sendMessage(chatId: String, text: String, threadId: String?) {
        val creds = loadCreds() ?: return
        try {
            httpClient.post("https://slack.com/api/chat.postMessage") {
                header("Authorization", "Bearer ${creds.botToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("channel", JsonPrimitive(chatId))
                        put("text", JsonPrimitive(text))
                        // Slack thread reply — thread_ts is the parent message timestamp
                        if (threadId != null) {
                            put("thread_ts", JsonPrimitive(threadId))
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
            // files.upload is deprecated; use files.getUploadURLExternal + completeUploadExternal
            val getUrl = httpClient.post("https://slack.com/api/files.getUploadURLExternal") {
                header("Authorization", "Bearer ${creds.botToken}")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("filename=photo.jpg&length=${imageBytes.size}")
            }
            val up = json.parseToJsonElement(getUrl.bodyAsText()).jsonObject
            val uploadUrl = up["upload_url"]?.jsonPrimitive?.content ?: return false
            val fileId = up["file_id"]?.jsonPrimitive?.content ?: return false

            val uploadResp = httpClient.post(uploadUrl) {
                header("Content-Type", "image/jpeg")
                setBody(imageBytes)
            }
            if (!uploadResp.status.isSuccess()) {
                Log.e(TAG, "Photo upload failed: ${uploadResp.status}")
                return false
            }

            val completeResp = httpClient.post("https://slack.com/api/files.completeUploadExternal") {
                header("Authorization", "Bearer ${creds.botToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("files", buildJsonArray {
                            add(buildJsonObject {
                                put("id", JsonPrimitive(fileId))
                                if (caption != null) put("title", JsonPrimitive(caption))
                            })
                        })
                        put("channel_id", JsonPrimitive(chatId))
                        if (caption != null) put("initial_comment", JsonPrimitive(caption))
                    }.toString(),
                )
            }
            val completeOk = runCatching {
                json.parseToJsonElement(completeResp.bodyAsText())
                    .jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
            }.getOrElse { false }
            if (!completeOk) {
                Log.e(TAG, "completeUploadExternal failed: ${completeResp.bodyAsText()}")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto failed: ${e.message}")
            false
        }
    }

    // ── Pairing (by Slack channel id) ───────────────────────────────────

    override fun isAllowed(senderId: String) = senderId in pairedChannels
    override fun pair(senderId: String, senderName: String) { pairedChannels.add(senderId); savePaired() }
    override fun unpair(senderId: String) { pairedChannels.remove(senderId); savePaired() }
    override fun getPairedSenders() = pairedChannels.map {
        PairedSender(id = it, name = it, channelId = id)
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun loadPaired() {
        pairedChannels.clear()
        pairedChannels.addAll(prefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
    }
    private fun savePaired() { prefs().edit().putStringSet(KEY_PAIRED, pairedChannels.toSet()).apply() }

    // ── Wire types ──────────────────────────────────────────────────────

    @Serializable
    private data class SlackOpenResp(
        val ok: Boolean,
        val url: String? = null,
        val error: String? = null,
    )
}
