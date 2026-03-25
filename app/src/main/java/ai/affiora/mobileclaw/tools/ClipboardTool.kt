package ai.affiora.mobileclaw.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClipboardTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "clipboard"

    override val description: String =
        "Read or write the device clipboard. Actions: 'read' to get current clipboard text, 'write' to set clipboard text. Note: Android 13+ restricts background clipboard reads."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("read"))
                    add(JsonPrimitive("write"))
                })
                put("description", JsonPrimitive("The action to perform: 'read' or 'write'"))
            })
            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Text to write to clipboard (required for 'write')."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.Main) {
            when (action) {
                "read" -> executeRead()
                "write" -> executeWrite(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'read' or 'write'.")
            }
        }
    }

    private fun executeRead(): ToolResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) {
                return ToolResult.Success(buildJsonObject {
                    put("has_content", JsonPrimitive(false))
                    put("text", JsonPrimitive(""))
                }.toString())
            }
            val clip = clipboard.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            ToolResult.Success(buildJsonObject {
                put("has_content", JsonPrimitive(true))
                put("text", JsonPrimitive(text))
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to read clipboard: ${e.message}")
        }
    }

    private fun executeWrite(params: Map<String, JsonElement>): ToolResult {
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: text")

        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("MobileClaw", text)
            clipboard.setPrimaryClip(clip)
            ToolResult.Success("Text copied to clipboard.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to write clipboard: ${e.message}")
        }
    }
}
