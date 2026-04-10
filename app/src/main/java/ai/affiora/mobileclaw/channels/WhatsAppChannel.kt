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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * WhatsApp Cloud API channel — **outbound only**.
 *
 * Mobile devices cannot receive webhooks (no public URL), so this channel
 * only supports sending messages. The user can reply in the WhatsApp app.
 *
 * Credentials stored as JSON blob under "whatsapp":
 *   {"access_token":"EAA...","phone_number_id":"123456789012345"}
 *
 * Uses Graph API v22.0 (latest stable).
 * 24-hour customer service window: if outside window, falls back to template.
 */
class WhatsAppChannel(
    private val connectorManager: ConnectorManager,
    private val httpClient: HttpClient,
    private val context: Context,
) : Channel {

    override val id = "whatsapp"
    override val displayName = "WhatsApp"
    override val isOutboundOnly = true
    override var isRunning = false
        private set

    lateinit var channelManager: ChannelManager

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pairedNumbers = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    companion object {
        private const val TAG = "WhatsAppChannel"
        private const val PREFS = "whatsapp_channel"
        private const val KEY_PAIRED = "paired_numbers"
        private const val GRAPH_API = "https://graph.facebook.com/v22.0"
    }

    init { loadPaired() }

    // ── Credentials ─────────────────────────────────────────────────────

    private data class Creds(val accessToken: String, val phoneNumberId: String)

    private fun loadCreds(): Creds? {
        val blob = connectorManager.getToken("whatsapp") ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(blob).jsonObject
            val token = (obj["access_token"] as? JsonPrimitive)?.content ?: return@runCatching null
            val phoneId = (obj["phone_number_id"] as? JsonPrimitive)?.content ?: return@runCatching null
            Creds(token, phoneId)
        }.getOrNull()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun start() {
        val creds = loadCreds()
        if (creds == null) {
            Log.w(TAG, "WhatsApp not configured (need access_token + phone_number_id)")
            isRunning = false
            return
        }
        // No polling needed — outbound only
        isRunning = true
    }

    override fun stop() {
        isRunning = false
    }

    // ── Sending ─────────────────────────────────────────────────────────

    override suspend fun sendMessage(chatId: String, text: String, threadId: String?) {
        // WhatsApp Cloud API supports `context.message_id` for replies, but it's optional;
        // we don't track inbound message IDs (outbound only). threadId ignored.
        val creds = loadCreds() ?: return
        try {
            val resp = httpClient.post("$GRAPH_API/${creds.phoneNumberId}/messages") {
                header("Authorization", "Bearer ${creds.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("messaging_product", JsonPrimitive("whatsapp"))
                        put("to", JsonPrimitive(chatId))
                        put("type", JsonPrimitive("text"))
                        put("text", buildJsonObject {
                            put("body", JsonPrimitive(text))
                        })
                    }.toString(),
                )
            }
            val body = resp.bodyAsText()
            // Check for error 131047 = outside 24h customer service window
            val errorCode = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]
                    ?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull
            }.getOrNull()
            if (errorCode == 131047) {
                Log.w(TAG, "Outside 24h window, trying template fallback")
                sendTemplateFallback(creds, chatId, text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
        }
    }

    override suspend fun sendPhoto(chatId: String, imageBytes: ByteArray, caption: String?): Boolean {
        // WhatsApp Cloud API requires media to be hosted at a URL or uploaded first
        // For simplicity, skip photo support via bytes — would need media upload endpoint
        Log.w(TAG, "sendPhoto not supported for WhatsApp (requires hosted media URL)")
        return false
    }

    private suspend fun sendTemplateFallback(creds: Creds, to: String, text: String) {
        // hello_world is a default approved template in WhatsApp Business
        // For custom messages outside the window, a proper template should be configured
        try {
            httpClient.post("$GRAPH_API/${creds.phoneNumberId}/messages") {
                header("Authorization", "Bearer ${creds.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("messaging_product", JsonPrimitive("whatsapp"))
                        put("to", JsonPrimitive(to))
                        put("type", JsonPrimitive("template"))
                        put("template", buildJsonObject {
                            put("name", JsonPrimitive("hello_world"))
                            put("language", buildJsonObject {
                                put("code", JsonPrimitive("en_US"))
                            })
                        })
                    }.toString(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Template fallback failed: ${e.message}")
        }
    }

    // ── Pairing (by phone number) ───────────────────────────────────────

    override fun isAllowed(senderId: String) = senderId in pairedNumbers
    override fun pair(senderId: String, senderName: String) { pairedNumbers.add(senderId); savePaired() }
    override fun unpair(senderId: String) { pairedNumbers.remove(senderId); savePaired() }
    override fun getPairedSenders() = pairedNumbers.map {
        PairedSender(id = it, name = it, channelId = id)
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun loadPaired() {
        pairedNumbers.clear()
        pairedNumbers.addAll(prefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet())
    }
    private fun savePaired() { prefs().edit().putStringSet(KEY_PAIRED, pairedNumbers.toSet()).apply() }
}
