package ai.affiora.mobileclaw.channels

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.connectors.ConnectorManager
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Microsoft Teams channel — **outbound only**.
 *
 * Mobile devices cannot receive Bot Framework webhooks, so this channel
 * only supports sending messages via MS Graph API proactive messaging.
 *
 * Credentials stored as JSON blob under "teams":
 *   {"tenant_id":"...","client_id":"...","client_secret":"...","service_url":"https://smba.trafficmanager.net/..."}
 *
 * Requires an Azure AD app with Application permission: Teamwork.Migrate.All or
 * Chat.Create + ChatMessage.Send (or use Bot Framework service URL for proactive messaging).
 *
 * Pairing: user provides a conversation ID (from Teams bot interaction).
 */
class TeamsChannel(
    private val connectorManager: ConnectorManager,
    private val httpClient: HttpClient,
    private val context: Context,
) : Channel {

    override val id = "teams"
    override val displayName = "MS Teams (Outbound)"
    override val isOutboundOnly = true
    override var isRunning = false
        private set

    lateinit var channelManager: ChannelManager

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pairedConversations = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    private val tokenMutex = Mutex()

    companion object {
        private const val TAG = "TeamsChannel"
        private const val PREFS = "teams_channel"
        private const val KEY_PAIRED = "paired_conversations"
    }

    init { loadPaired() }

    // ── Credentials ─────────────────────────────────────────────────────

    private data class Creds(
        val tenantId: String,
        val clientId: String,
        val clientSecret: String,
        val serviceUrl: String,
    )

    private fun loadCreds(): Creds? {
        val blob = connectorManager.getToken("teams") ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(blob).jsonObject
            // tenant_id is optional — defaults to "botframework.com" for multi-tenant bots
            // (the standard Bot Framework registration). Single-tenant bots provide a real GUID.
            val tenantId = (obj["tenant_id"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() }
                ?: "botframework.com"
            val clientId = (obj["client_id"] as? JsonPrimitive)?.content ?: return@runCatching null
            val clientSecret = (obj["client_secret"] as? JsonPrimitive)?.content ?: return@runCatching null
            // Default Bot Framework service URL when not provided by an incoming activity.
            // The /teams/ path is the canonical Bot Connector endpoint per Microsoft docs;
            // /amer/ is a Microsoft Graph region (wrong endpoint, returns 404).
            val serviceUrl = (obj["service_url"] as? JsonPrimitive)?.content
                ?: "https://smba.trafficmanager.net/teams/"
            Creds(tenantId, clientId, clientSecret, serviceUrl.trimEnd('/'))
        }.getOrNull()
    }

    // ── OAuth2 client credentials flow for Bot Framework ────────────────

    private suspend fun getAccessToken(creds: Creds): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiresAt - 60_000) return it }

        return tokenMutex.withLock {
            // Double-check after acquiring lock
            cachedToken?.let { if (System.currentTimeMillis() < tokenExpiresAt - 60_000) return@withLock it }

            val encodedClientId = URLEncoder.encode(creds.clientId, "UTF-8")
            val encodedSecret = URLEncoder.encode(creds.clientSecret, "UTF-8")
            val resp = httpClient.post("https://login.microsoftonline.com/${creds.tenantId}/oauth2/v2.0/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=client_credentials&client_id=$encodedClientId&client_secret=$encodedSecret&scope=https%3A%2F%2Fapi.botframework.com%2F.default")
            }
            val body = resp.bodyAsText()
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withLock null
            val token = obj["access_token"]?.jsonPrimitive?.content ?: return@withLock null
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            cachedToken = token
            tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000
            token
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun start() {
        val creds = loadCreds()
        if (creds == null) {
            Log.w(TAG, "Teams not configured (need tenant_id + client_id + client_secret)")
            isRunning = false
            return
        }
        // Outbound only — no polling needed
        isRunning = true
    }

    override fun stop() {
        isRunning = false
    }

    // ── Sending via Bot Framework proactive messaging ───────────────────

    override suspend fun sendMessage(chatId: String, text: String, threadId: String?) {
        // Teams wasn't covered by OpenClaw 110782a2 thread routing fix; threadId accepted but ignored.
        val creds = loadCreds() ?: return
        val token = getAccessToken(creds) ?: run {
            Log.e(TAG, "Failed to get Bot Framework access token")
            return
        }
        try {
            // Bot Framework: POST to /v3/conversations/{conversationId}/activities
            httpClient.post("${creds.serviceUrl}/v3/conversations/$chatId/activities") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("type", JsonPrimitive("message"))
                        put("text", JsonPrimitive(text))
                    }.toString(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
        }
    }

    // ── Pairing (by conversation ID) ────────────────────────────────────

    override fun isAllowed(senderId: String) = senderId in pairedConversations
    override fun pair(senderId: String, senderName: String) { pairedConversations.add(senderId); savePaired() }
    override fun unpair(senderId: String) { pairedConversations.remove(senderId); savePaired() }
    override fun getPairedSenders() = pairedConversations.map {
        PairedSender(id = it, name = it, channelId = id)
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun loadPaired() {
        pairedConversations.clear()
        pairedConversations.addAll(prefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
    }
    private fun savePaired() { prefs().edit().putStringSet(KEY_PAIRED, pairedConversations.toSet()).apply() }
}
