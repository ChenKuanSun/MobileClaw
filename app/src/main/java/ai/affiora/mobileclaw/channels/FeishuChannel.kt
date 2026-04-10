package ai.affiora.mobileclaw.channels

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.connectors.ConnectorManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Feishu / Lark channel. Polls `im/v1/messages` for each paired chat.
 *
 * Credentials stored as JSON blob under "feishu":
 *   {"app_id":"cli_...","app_secret":"...","domain":"open.feishu.cn"}
 *
 * domain is "open.feishu.cn" (Feishu, CN) or "open.larksuite.com" (Lark, international).
 * Default: open.feishu.cn.
 *
 * Pairing workflow: the user pairs a chat by providing the chat_id (from Feishu bot).
 * Once paired, messages in that chat are polled every 30s.
 */
class FeishuChannel(
    private val connectorManager: ConnectorManager,
    private val httpClient: HttpClient,
    private val context: Context,
) : Channel {

    override val id = "feishu"
    override val displayName = "Feishu"
    override var isRunning = false
        private set

    lateinit var channelManager: ChannelManager

    private var pollJob: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pairedChats = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val lastMsgTs = ConcurrentHashMap<String, Long>()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    private val tokenMutex = Mutex()
    private var startTimestampMs: Long = 0L

    companion object {
        private const val TAG = "FeishuChannel"
        private const val PREFS = "feishu_channel"
        private const val KEY_PAIRED = "paired_chats"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val DEFAULT_DOMAIN = "open.feishu.cn"
    }

    init { loadPaired() }

    // ── Credentials ─────────────────────────────────────────────────────

    private data class Creds(val appId: String, val appSecret: String, val domain: String)

    private fun loadCreds(): Creds? {
        val blob = connectorManager.getToken("feishu") ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(blob).jsonObject
            val appId = (obj["app_id"] as? JsonPrimitive)?.content ?: return@runCatching null
            val secret = (obj["app_secret"] as? JsonPrimitive)?.content ?: return@runCatching null
            val domain = (obj["domain"] as? JsonPrimitive)?.content ?: DEFAULT_DOMAIN
            Creds(appId, secret, domain)
        }.getOrNull()
    }

    private suspend fun getTenantAccessToken(creds: Creds): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiresAt - 60_000) return it }

        return tokenMutex.withLock {
            // Double-check after acquiring lock
            cachedToken?.let { if (System.currentTimeMillis() < tokenExpiresAt - 60_000) return@withLock it }

            val resp = httpClient.post("https://${creds.domain}/open-apis/auth/v3/tenant_access_token/internal") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("app_id", JsonPrimitive(creds.appId))
                        put("app_secret", JsonPrimitive(creds.appSecret))
                    }.toString(),
                )
            }
            val body = resp.bodyAsText()
            val obj = json.parseToJsonElement(body).jsonObject
            val code = obj["code"]?.jsonPrimitive?.content
            if (code != "0") {
                Log.w(TAG, "tenant_access_token failed: $body")
                return@withLock null
            }
            val token = obj["tenant_access_token"]?.jsonPrimitive?.content ?: return@withLock null
            val expire = obj["expire"]?.jsonPrimitive?.content?.toLongOrNull() ?: 7200L
            cachedToken = token
            tokenExpiresAt = System.currentTimeMillis() + expire * 1000
            token
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun start() {
        val creds = loadCreds() ?: run {
            Log.w(TAG, "Feishu not configured (need app_id + app_secret)")
            isRunning = false
            return
        }
        synchronized(this) {
            if (pollJob?.isActive == true) return
            isRunning = true
            startTimestampMs = System.currentTimeMillis()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            pollJob = scope.launch {
                while (isActive) {
                    try {
                        val token = getTenantAccessToken(creds)
                        if (token != null) {
                            pollOnce(creds, token)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Poll error: ${e.message}")
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        isRunning = false
    }

    // ── Polling ─────────────────────────────────────────────────────────

    private suspend fun pollOnce(creds: Creds, token: String) {
        for (chatId in pairedChats.toList()) {
            val lastTs = lastMsgTs[chatId] ?: startTimestampMs
            val url = "https://${creds.domain}/open-apis/im/v1/messages" +
                "?container_id_type=chat" +
                "&container_id=$chatId" +
                "&sort_type=ByCreateTimeDesc" +
                "&page_size=20"

            val resp = httpClient.get(url) {
                header("Authorization", "Bearer $token")
            }
            if (resp.status.value !in 200..299) continue
            val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            if (obj["code"]?.jsonPrimitive?.content != "0") continue

            val itemsArr = (obj["data"]?.jsonObject?.get("items") as? kotlinx.serialization.json.JsonArray) ?: continue

            var maxTs = lastTs
            // Items are newest-first; walk them and keep only new messages
            for (i in itemsArr.indices.reversed()) {
                val item = itemsArr[i].jsonObject
                val msgType = item["msg_type"]?.jsonPrimitive?.content ?: continue
                if (msgType != "text") continue
                val createTimeStr = item["create_time"]?.jsonPrimitive?.content ?: continue
                val createTime = createTimeStr.toLongOrNull() ?: continue  // ms
                if (createTime <= lastTs) continue

                val senderType = item["sender"]?.jsonObject?.get("sender_type")?.jsonPrimitive?.content
                if (senderType == "app") continue  // ignore own bot messages

                val senderId = item["sender"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""

                val bodyContent = item["body"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: continue
                // body.content is a JSON string: {"text":"actual text"}
                val text = runCatching {
                    json.parseToJsonElement(bodyContent).jsonObject["text"]?.jsonPrimitive?.content
                }.getOrNull() ?: continue

                channelManager.onMessageReceived(
                    IncomingMessage(
                        channelId = id,
                        chatId = chatId,
                        senderId = chatId,  // pair by chat
                        senderName = senderId,
                        text = text,
                        timestamp = createTime,
                    ),
                )
                if (createTime > maxTs) maxTs = createTime
            }
            lastMsgTs[chatId] = maxTs
        }
    }

    // ── Sending ─────────────────────────────────────────────────────────

    override suspend fun sendMessage(chatId: String, text: String, threadId: String?) {
        // Feishu wasn't covered by OpenClaw 110782a2 thread routing fix; no upstream
        // basis for using the reply endpoint here. threadId is accepted but ignored.
        val creds = loadCreds() ?: return
        val token = getTenantAccessToken(creds) ?: return
        try {
            val contentJson = buildJsonObject { put("text", JsonPrimitive(text)) }.toString()
            httpClient.post("https://${creds.domain}/open-apis/im/v1/messages?receive_id_type=chat_id") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("receive_id", JsonPrimitive(chatId))
                        put("msg_type", JsonPrimitive("text"))
                        put("content", JsonPrimitive(contentJson))
                    }.toString(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
        }
    }

    // ── Pairing (by chat_id) ────────────────────────────────────────────

    override fun isAllowed(senderId: String) = senderId in pairedChats
    override fun pair(senderId: String, senderName: String) { pairedChats.add(senderId); savePaired() }
    override fun unpair(senderId: String) {
        pairedChats.remove(senderId)
        lastMsgTs.remove(senderId)
        savePaired()
    }
    override fun getPairedSenders() = pairedChats.map {
        PairedSender(id = it, name = it, channelId = id)
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun loadPaired() {
        pairedChats.clear()
        pairedChats.addAll(prefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
    }
    private fun savePaired() { prefs().edit().putStringSet(KEY_PAIRED, pairedChats.toSet()).apply() }
}
