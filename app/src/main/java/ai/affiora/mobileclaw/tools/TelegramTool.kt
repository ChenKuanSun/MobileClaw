package ai.affiora.mobileclaw.tools

import ai.affiora.mobileclaw.connectors.ConnectorManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class TelegramTool(
    private val httpClient: HttpClient,
    private val connectorManager: ConnectorManager,
) : AndroidTool {

    override val name: String = "telegram"

    override val description: String =
        "Send and receive Telegram messages via a configured Bot. Actions: 'send_message' (requires chat_id and text), 'get_updates' (get recent messages sent to the bot), 'get_me' (get bot info). Requires Telegram Bot Token in Settings > Connectors."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("send_message"))
                    add(JsonPrimitive("get_updates"))
                    add(JsonPrimitive("get_me"))
                })
                put("description", JsonPrimitive("The action to perform."))
            })
            put("chat_id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Telegram chat ID to send the message to (required for send_message)."))
            })
            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The message text to send (required for send_message)."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val token = connectorManager.getToken("telegram")
        if (token.isNullOrBlank()) {
            return ToolResult.Error("Telegram not connected. Go to Settings > Connectors to add your bot token.")
        }

        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return when (action) {
            "send_message" -> sendMessage(token, params)
            "get_updates" -> getUpdates(token)
            "get_me" -> getMe(token)
            else -> ToolResult.Error("Unknown action: $action. Must be 'send_message', 'get_updates', or 'get_me'.")
        }
    }

    private suspend fun sendMessage(token: String, params: Map<String, JsonElement>): ToolResult {
        val chatId = params["chat_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: chat_id")
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: text")

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!confirmed) {
            return ToolResult.NeedsConfirmation(
                preview = "Send Telegram message to chat $chatId:\n$text",
                requestId = "telegram_send_${UUID.randomUUID()}",
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("chat_id", JsonPrimitive(chatId))
                    put("text", JsonPrimitive(text))
                }
                val response = httpClient.post("https://api.telegram.org/bot$token/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                val responseBody = response.bodyAsText()
                ToolResult.Success(responseBody)
            } catch (e: Exception) {
                ToolResult.Error("Telegram sendMessage failed: ${e.message}")
            }
        }
    }

    private suspend fun getUpdates(token: String): ToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.get("https://api.telegram.org/bot$token/getUpdates?limit=10")
                val responseBody = response.bodyAsText()
                val truncated = if (responseBody.length > 8192) {
                    responseBody.take(8192) + "\n...[truncated]"
                } else {
                    responseBody
                }
                ToolResult.Success(truncated)
            } catch (e: Exception) {
                ToolResult.Error("Telegram getUpdates failed: ${e.message}")
            }
        }
    }

    private suspend fun getMe(token: String): ToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.get("https://api.telegram.org/bot$token/getMe")
                val responseBody = response.bodyAsText()
                ToolResult.Success(responseBody)
            } catch (e: Exception) {
                ToolResult.Error("Telegram getMe failed: ${e.message}")
            }
        }
    }
}
