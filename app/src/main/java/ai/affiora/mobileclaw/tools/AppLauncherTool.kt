package ai.affiora.mobileclaw.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppLauncherTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "app"

    override val description: String =
        "List installed apps, launch apps, or share text to other apps. Actions: 'list_apps' to list launchable apps, 'launch' to open an app by package name, 'share_text' to share text via Intent."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list_apps"))
                    add(JsonPrimitive("launch"))
                    add(JsonPrimitive("share_text"))
                })
                put("description", JsonPrimitive("The action to perform: 'list_apps', 'launch', or 'share_text'"))
            })
            put("package_name", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Package name of the app to launch (required for 'launch', optional for 'share_text')."))
            })
            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Text content to share (required for 'share_text')."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "list_apps" -> executeListApps()
                "launch" -> executeLaunch(params)
                "share_text" -> executeShareText(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'list_apps', 'launch', or 'share_text'.")
            }
        }
    }

    private fun executeListApps(): ToolResult {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_DEFAULT_ONLY)

        val results = buildJsonArray {
            for (app in apps.sortedBy { it.loadLabel(pm).toString().lowercase() }) {
                add(buildJsonObject {
                    put("packageName", JsonPrimitive(app.activityInfo.packageName))
                    put("appName", JsonPrimitive(app.loadLabel(pm).toString()))
                })
            }
        }

        return ToolResult.Success(results.toString())
    }

    private fun executeLaunch(params: Map<String, JsonElement>): ToolResult {
        val packageName = params["package_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: package_name")

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult.Error("App not found or not launchable: $packageName")

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ToolResult.Success("Launched $packageName")
        } catch (e: Exception) {
            ToolResult.Error("Failed to launch $packageName: ${e.message}")
        }
    }

    private fun executeShareText(params: Map<String, JsonElement>): ToolResult {
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: text")
        val targetPackage = params["package_name"]?.jsonPrimitive?.content

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (targetPackage != null) {
                setPackage(targetPackage)
            }
        }

        return try {
            if (targetPackage != null) {
                context.startActivity(shareIntent)
            } else {
                val chooser = Intent.createChooser(shareIntent, "Share via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
            ToolResult.Success("Share intent sent.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to share text: ${e.message}")
        }
    }
}
