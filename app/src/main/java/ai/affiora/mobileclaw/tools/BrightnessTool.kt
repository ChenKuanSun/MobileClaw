package ai.affiora.mobileclaw.tools

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class BrightnessTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "brightness"

    override val description: String =
        "Get or set screen brightness. Actions: 'get' returns current brightness (0-100) and auto-brightness state, 'set' sets brightness level (0-100), 'auto' toggles auto-brightness on/off. Requires WRITE_SETTINGS permission."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("get"))
                    add(JsonPrimitive("set"))
                    add(JsonPrimitive("auto"))
                })
                put("description", JsonPrimitive("The action to perform: 'get', 'set', or 'auto'."))
            })
            put("level", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Brightness level 0-100. Required for 'set'."))
            })
            put("enabled", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Whether to enable auto-brightness. Required for 'auto'."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        // Check WRITE_SETTINGS permission for set/auto
        if (action in listOf("set", "auto") && !Settings.System.canWrite(context)) {
            return ToolResult.Error("WRITE_SETTINGS permission not granted. Please enable 'Modify system settings' for MobileClaw in Settings > Apps.")
        }

        return withContext(Dispatchers.IO) {
            when (action) {
                "get" -> executeGet()
                "set" -> executeSet(params)
                "auto" -> executeAuto(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'get', 'set', or 'auto'.")
            }
        }
    }

    private fun executeGet(): ToolResult {
        return try {
            val brightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val autoMode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val percent = (brightness * 100 / 255).coerceIn(0, 100)
            val isAuto = autoMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

            ToolResult.Success(buildJsonObject {
                put("brightness", JsonPrimitive(percent))
                put("auto_brightness", JsonPrimitive(isAuto))
            }.toString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to get brightness: ${e.message}")
        }
    }

    private fun executeSet(params: Map<String, JsonElement>): ToolResult {
        val level = params["level"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: level")

        return try {
            val brightnessValue = (level.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
            // Disable auto-brightness when setting manual level
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            ToolResult.Success("Brightness set to $level%.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to set brightness: ${e.message}")
        }
    }

    private fun executeAuto(params: Map<String, JsonElement>): ToolResult {
        val enabled = params["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: return ToolResult.Error("Missing required parameter: enabled (true/false)")

        return try {
            val mode = if (enabled) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode
            )
            ToolResult.Success("Auto-brightness ${if (enabled) "enabled" else "disabled"}.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to toggle auto-brightness: ${e.message}")
        }
    }
}
