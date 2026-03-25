package ai.affiora.mobileclaw.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class FlashlightTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "flashlight"

    override val description: String =
        "Control the device flashlight (torch). Actions: 'on', 'off', 'toggle'."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("on"))
                    add(JsonPrimitive("off"))
                    add(JsonPrimitive("toggle"))
                })
                put("description", JsonPrimitive("The action to perform: 'on', 'off', or 'toggle'."))
            })
        })
    }

    @Volatile
    private var torchOn = false

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "on" -> setTorch(true)
                "off" -> setTorch(false)
                "toggle" -> setTorch(!torchOn)
                else -> ToolResult.Error("Unknown action: $action. Must be 'on', 'off', or 'toggle'.")
            }
        }
    }

    private fun setTorch(on: Boolean): ToolResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ToolResult.Error("No camera found on device.")
            cameraManager.setTorchMode(cameraId, on)
            torchOn = on
            ToolResult.Success("Flashlight ${if (on) "on" else "off"}.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to control flashlight: ${e.message}")
        }
    }
}
