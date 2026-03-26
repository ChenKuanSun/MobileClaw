package ai.affiora.mobileclaw.tools

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * UI automation tool — the killer feature. Uses AccessibilityService to read
 * and interact with ANY app on the device.
 */
class UIAutomationTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "ui"

    override val description: String =
        "Control any app on the phone via UI automation. Actions: 'read_screen' to get visible UI elements, " +
        "'click' to tap an element (by index/text/id), 'type' to enter text, 'scroll' up/down, " +
        "'back'/'home'/'recents'/'notifications'/'quick_settings' for navigation, " +
        "'screenshot' to capture screen, 'wait' to pause N seconds, " +
        "'launch_and_wait' to open an app and return its screen content."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("read_screen"))
                    add(JsonPrimitive("click"))
                    add(JsonPrimitive("type"))
                    add(JsonPrimitive("scroll"))
                    add(JsonPrimitive("back"))
                    add(JsonPrimitive("home"))
                    add(JsonPrimitive("recents"))
                    add(JsonPrimitive("notifications"))
                    add(JsonPrimitive("quick_settings"))
                    add(JsonPrimitive("screenshot"))
                    add(JsonPrimitive("wait"))
                    add(JsonPrimitive("launch_and_wait"))
                })
                put("description", JsonPrimitive("The UI action to perform."))
            })
            put("index", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Node index from read_screen output (for 'click')."))
            })
            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Text to type (for 'type'), or text to find (for 'click')."))
            })
            put("id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Resource ID of the element to click (for 'click')."))
            })
            put("direction", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("up"))
                    add(JsonPrimitive("down"))
                })
                put("description", JsonPrimitive("Scroll direction (for 'scroll'). Default: 'down'."))
            })
            put("seconds", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Seconds to wait (for 'wait', max 10)."))
            })
            put("package_name", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Package name of the app to launch (for 'launch_and_wait')."))
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

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true

        return when (action) {
            "read_screen" -> {
                val content = service.getScreenContent()
                ToolResult.Success(content)
            }
            "click" -> {
                executeClick(params, service)
            }
            "type" -> {
                val text = params["text"]?.jsonPrimitive?.content
                    ?: return ToolResult.Error("Missing required parameter: text")
                if (service.typeText(text)) {
                    ToolResult.Success("Typed: \"$text\"")
                } else {
                    ToolResult.Error("Failed to type text. No focused editable field found.")
                }
            }
            "scroll" -> {
                val direction = params["direction"]?.jsonPrimitive?.content ?: "down"
                val ok = if (direction == "up") service.scrollUp() else service.scrollDown()
                if (ok) ToolResult.Success("Scrolled $direction.")
                else ToolResult.Error("No scrollable element found.")
            }
            "back" -> {
                service.pressBack()
                ToolResult.Success("Pressed back.")
            }
            "home" -> {
                service.pressHome()
                ToolResult.Success("Pressed home.")
            }
            "recents" -> {
                service.pressRecents()
                ToolResult.Success("Opened recent apps.")
            }
            "notifications" -> {
                service.openNotifications()
                ToolResult.Success("Opened notification shade.")
            }
            "quick_settings" -> {
                service.openQuickSettings()
                ToolResult.Success("Opened quick settings.")
            }
            "screenshot" -> {
                service.takeScreenshot()
                ToolResult.Success("Screenshot taken.")
            }
            "wait" -> {
                val seconds = (params["seconds"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 10)
                delay(seconds * 1000L)
                ToolResult.Success("Waited $seconds seconds.")
            }
            "launch_and_wait" -> executeLaunchAndWait(params, service)
            else -> ToolResult.Error("Unknown action: $action")
        }
    }

    private fun executeClick(
        params: Map<String, JsonElement>,
        service: ClawAccessibilityService,
    ): ToolResult {
        // Priority: index > id > text
        val index = params["index"]?.jsonPrimitive?.intOrNull
        if (index != null) {
            return if (service.clickNode(index)) {
                ToolResult.Success("Clicked element at index $index.")
            } else {
                ToolResult.Error("Failed to click element at index $index. Run read_screen first.")
            }
        }

        val id = params["id"]?.jsonPrimitive?.content
        if (id != null) {
            return if (service.clickById(id)) {
                ToolResult.Success("Clicked element with id: $id")
            } else {
                ToolResult.Error("No element found with id: $id")
            }
        }

        val text = params["text"]?.jsonPrimitive?.content
        if (text != null) {
            return if (service.clickByText(text)) {
                ToolResult.Success("Clicked element with text: \"$text\"")
            } else {
                ToolResult.Error("No element found with text: \"$text\"")
            }
        }

        return ToolResult.Error("click requires one of: index, text, or id")
    }

    private suspend fun executeLaunchAndWait(
        params: Map<String, JsonElement>,
        service: ClawAccessibilityService,
    ): ToolResult {
        val packageName = params["package_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: package_name")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult.Error("App not found or not launchable: $packageName")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            // Wait for app to load
            delay(2000)
            val content = service.getScreenContent()
            ToolResult.Success("Launched $packageName.\n\n$content")
        } catch (e: Exception) {
            ToolResult.Error("Failed to launch $packageName: ${e.message}")
        }
    }
}
