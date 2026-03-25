package ai.affiora.mobileclaw.tools

import android.content.Context
import ai.affiora.mobileclaw.channels.ChannelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Tool that lets the AI send messages, photos, and files via channels (Telegram, SMS, etc.).
 */
class ChannelTool(
    private val channelManagerLazy: dagger.Lazy<ChannelManager>,
    private val context: Context,
) : AndroidTool {

    private val channelManager: ChannelManager get() = channelManagerLazy.get()

    override val name: String = "channel"

    override val description: String =
        "Send messages, photos, or files via messaging channels (Telegram, SMS). " +
            "Actions: 'send_message' (send text), 'send_photo' (send image file), " +
            "'send_document' (send file), 'list_channels' (list active channels), " +
            "'list_paired' (list paired users for a channel)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("send_message"))
                    add(JsonPrimitive("send_photo"))
                    add(JsonPrimitive("send_document"))
                    add(JsonPrimitive("list_channels"))
                    add(JsonPrimitive("list_paired"))
                })
                put("description", JsonPrimitive("The action to perform."))
            })
            put("channel_id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Channel ID: 'telegram' or 'sms'. Required for send/list_paired actions."))
            })
            put("chat_id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Chat/user ID to send to. Required for send actions."))
            })
            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Message text (required for send_message)."))
            })
            put("image_path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Absolute file path to an image on device (required for send_photo)."))
            })
            put("file_path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Absolute file path to a file on device (required for send_document)."))
            })
            put("caption", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional caption for photo or document."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return when (action) {
            "send_message" -> executeSendMessage(params)
            "send_photo" -> executeSendPhoto(params)
            "send_document" -> executeSendDocument(params)
            "list_channels" -> executeListChannels()
            "list_paired" -> executeListPaired(params)
            else -> ToolResult.Error(
                "Unknown action: $action. Must be 'send_message', 'send_photo', 'send_document', 'list_channels', or 'list_paired'.",
            )
        }
    }

    private suspend fun executeSendMessage(params: Map<String, JsonElement>): ToolResult {
        val channelId = params["channel_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: channel_id")
        val chatId = params["chat_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: chat_id")
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: text")

        val channel = channelManager.getChannel(channelId)
            ?: return ToolResult.Error("Channel '$channelId' not found. Use 'list_channels' to see available channels.")

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!confirmed) {
            return ToolResult.NeedsConfirmation(
                preview = "Send message via ${channel.displayName} to $chatId:\n\"$text\"",
                requestId = "channel_msg_${UUID.randomUUID()}",
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                channel.sendMessage(chatId, text)
                ToolResult.Success("Message sent via ${channel.displayName} to $chatId.")
            } catch (e: Exception) {
                ToolResult.Error("Failed to send message: ${e.message}")
            }
        }
    }

    private suspend fun executeSendPhoto(params: Map<String, JsonElement>): ToolResult {
        val channelId = params["channel_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: channel_id")
        val chatId = params["chat_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: chat_id")
        val imagePath = params["image_path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: image_path")
        val caption = params["caption"]?.jsonPrimitive?.content

        val channel = channelManager.getChannel(channelId)
            ?: return ToolResult.Error("Channel '$channelId' not found. Use 'list_channels' to see available channels.")

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!confirmed) {
            return ToolResult.NeedsConfirmation(
                preview = "Send photo via ${channel.displayName} to $chatId:\nFile: $imagePath" +
                    if (caption != null) "\nCaption: $caption" else "",
                requestId = "channel_photo_${UUID.randomUUID()}",
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val file = File(imagePath)
                if (!file.exists()) {
                    return@withContext ToolResult.Error("File not found: $imagePath")
                }
                val bytes = file.readBytes()
                val sent = channel.sendPhoto(chatId, bytes, caption)
                if (sent) {
                    ToolResult.Success("Photo sent via ${channel.displayName} to $chatId.")
                } else {
                    ToolResult.Error("${channel.displayName} does not support sending photos.")
                }
            } catch (e: Exception) {
                ToolResult.Error("Failed to send photo: ${e.message}")
            }
        }
    }

    private suspend fun executeSendDocument(params: Map<String, JsonElement>): ToolResult {
        val channelId = params["channel_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: channel_id")
        val chatId = params["chat_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: chat_id")
        val filePath = params["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: file_path")
        val caption = params["caption"]?.jsonPrimitive?.content

        val channel = channelManager.getChannel(channelId)
            ?: return ToolResult.Error("Channel '$channelId' not found. Use 'list_channels' to see available channels.")

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!confirmed) {
            return ToolResult.NeedsConfirmation(
                preview = "Send document via ${channel.displayName} to $chatId:\nFile: $filePath" +
                    if (caption != null) "\nCaption: $caption" else "",
                requestId = "channel_doc_${UUID.randomUUID()}",
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext ToolResult.Error("File not found: $filePath")
                }
                val bytes = file.readBytes()
                val sent = channel.sendDocument(chatId, bytes, file.name, caption)
                if (sent) {
                    ToolResult.Success("Document sent via ${channel.displayName} to $chatId.")
                } else {
                    ToolResult.Error("${channel.displayName} does not support sending documents.")
                }
            } catch (e: Exception) {
                ToolResult.Error("Failed to send document: ${e.message}")
            }
        }
    }

    private fun executeListChannels(): ToolResult {
        val channels = channelManager.getAllChannels()
        if (channels.isEmpty()) {
            return ToolResult.Success("No channels registered.")
        }

        val result = buildJsonArray {
            for (ch in channels) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(ch.id))
                    put("name", JsonPrimitive(ch.displayName))
                    put("running", JsonPrimitive(ch.isRunning))
                    put("paired_count", JsonPrimitive(ch.getPairedSenders().size))
                })
            }
        }
        return ToolResult.Success(result.toString())
    }

    private fun executeListPaired(params: Map<String, JsonElement>): ToolResult {
        val channelId = params["channel_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: channel_id")

        val channel = channelManager.getChannel(channelId)
            ?: return ToolResult.Error("Channel '$channelId' not found. Use 'list_channels' to see available channels.")

        val paired = channel.getPairedSenders()
        if (paired.isEmpty()) {
            return ToolResult.Success("No paired users for ${channel.displayName}.")
        }

        val result = buildJsonArray {
            for (sender in paired) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(sender.id))
                    put("name", JsonPrimitive(sender.name))
                    put("channel", JsonPrimitive(sender.channelId))
                })
            }
        }
        return ToolResult.Success(result.toString())
    }
}
