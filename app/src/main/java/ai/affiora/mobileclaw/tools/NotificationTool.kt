package ai.affiora.mobileclaw.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

class NotificationTool(
    private val notificationCache: NotificationCache
) : AndroidTool {

    override val name: String = "notifications"

    override val description: String =
        "Read recent device notifications. Optionally filter by time, package name, and limit results."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray { add(JsonPrimitive("read")) })
                put("description", JsonPrimitive("Action to perform. Currently only 'read'."))
            })
            put("since", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Unix timestamp in millis. Only return notifications after this time."))
            })
            put("package_name", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter notifications by app package name (e.g. 'com.whatsapp')."))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Max number of notifications to return. Default 20."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        if (action != "read") {
            return ToolResult.Error("Unknown action: $action. Must be 'read'.")
        }

        val since = params["since"]?.jsonPrimitive?.long
        val packageName = params["package_name"]?.jsonPrimitive?.content
        val limit = params["limit"]?.jsonPrimitive?.int ?: 20

        val notifications = notificationCache.getRecent(since, packageName, limit)

        val results = buildJsonArray {
            for (n in notifications) {
                add(buildJsonObject {
                    put("package_name", JsonPrimitive(n.packageName))
                    put("title", JsonPrimitive(n.title))
                    put("text", JsonPrimitive(n.text))
                    put("post_time", JsonPrimitive(n.postTime))
                })
            }
        }

        return ToolResult.Success(results.toString())
    }
}

/**
 * Interface for notification cache to allow easy testing/mocking.
 */
interface NotificationCache {
    fun getRecent(since: Long?, packageName: String?, limit: Int): List<CachedNotification>
    fun add(notification: CachedNotification)
}

data class CachedNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long
)
