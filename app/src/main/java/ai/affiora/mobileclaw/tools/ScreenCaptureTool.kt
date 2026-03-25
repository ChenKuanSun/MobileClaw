package ai.affiora.mobileclaw.tools

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Screen capture tool. Uses AccessibilityService's global screenshot action
 * and delegates screen reading to the accessibility node tree.
 */
class ScreenCaptureTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "screen"

    override val description: String =
        "Capture or read the current screen. Actions: 'capture' to take a screenshot, 'read' to get structured text of visible UI elements."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("capture"))
                    add(JsonPrimitive("read"))
                })
                put("description", JsonPrimitive("'capture' to take a screenshot, 'read' to get screen text content."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val service = ClawAccessibilityService.instance
            ?: return ToolResult.Error(
                "Accessibility Service not enabled. Go to Settings > Accessibility > MobileClaw and enable it."
            )

        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return when (action) {
            "capture" -> {
                service.takeScreenshot()
                ToolResult.Success("Screenshot captured via system screenshot.")
            }
            "read" -> {
                val content = service.getScreenContent()
                ToolResult.Success(content)
            }
            else -> ToolResult.Error("Unknown action: $action. Must be 'capture' or 'read'.")
        }
    }
}
