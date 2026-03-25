package ai.affiora.mobileclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Phone call tool — dial or call a number.
 */
class PhoneCallTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "phone"

    override val description: String =
        "Make phone calls or open the dialer. Actions: 'call' to initiate a call (needs confirmation), 'dial' to open dialer with a number pre-filled."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
            add(JsonPrimitive("number"))
        })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("call"))
                    add(JsonPrimitive("dial"))
                })
                put("description", JsonPrimitive("'call' to initiate call (needs confirmation), 'dial' to open dialer."))
            })
            put("number", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Phone number to call or dial."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")
        val number = params["number"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: number")

        return when (action) {
            "call" -> {
                val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
                if (confirmed) {
                    try {
                        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        ToolResult.Success("Calling $number...")
                    } catch (e: Exception) {
                        ToolResult.Error("Failed to call $number: ${e.message}")
                    }
                } else {
                    ToolResult.NeedsConfirmation(
                        preview = "Call $number?",
                        requestId = "phone_call_${UUID.randomUUID()}",
                    )
                }
            }
            "dial" -> {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult.Success("Opened dialer with number: $number")
                } catch (e: Exception) {
                    ToolResult.Error("Failed to open dialer: ${e.message}")
                }
            }
            else -> ToolResult.Error("Unknown action: $action. Must be 'call' or 'dial'.")
        }
    }
}
