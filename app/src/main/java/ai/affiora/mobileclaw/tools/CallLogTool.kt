package ai.affiora.mobileclaw.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

class CallLogTool(
    private val context: Context
) : AndroidTool {

    override val name: String = "call_log"

    override val description: String =
        "Search the device call log. Filter by call type (MISSED, INCOMING, OUTGOING, ALL) and time range."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray { add(JsonPrimitive("search")) })
                put("description", JsonPrimitive("The action to perform. Currently only 'search'."))
            })
            put("type", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("MISSED"))
                    add(JsonPrimitive("INCOMING"))
                    add(JsonPrimitive("OUTGOING"))
                    add(JsonPrimitive("ALL"))
                })
                put("description", JsonPrimitive("Filter by call type. Default ALL."))
            })
            put("since", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Unix timestamp in millis. Only return calls after this time."))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Max number of results. Default 20."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Error("Call log permission not granted. Go to Android Settings > Apps > MobileClaw > Permissions > Call Log to grant it.")
        }

        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        if (action != "search") {
            return ToolResult.Error("Unknown action: $action. Must be 'search'.")
        }

        return withContext(Dispatchers.IO) { executeSearch(params) }
    }

    private fun executeSearch(params: Map<String, JsonElement>): ToolResult {
        val callType = params["type"]?.jsonPrimitive?.content ?: "ALL"
        val since = params["since"]?.jsonPrimitive?.long
        val limit = params["limit"]?.jsonPrimitive?.int ?: 20

        val resolver: ContentResolver = context.contentResolver

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        val callTypeInt = callTypeStringToInt(callType)
        if (callTypeInt != null) {
            selectionParts.add("${CallLog.Calls.TYPE} = ?")
            selectionArgs.add(callTypeInt.toString())
        }

        if (since != null) {
            selectionParts.add("${CallLog.Calls.DATE} > ?")
            selectionArgs.add(since.toString())
        }

        val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            selection,
            args,
            "${CallLog.Calls.DATE} DESC"
        ) ?: return ToolResult.Error("Failed to query call log content provider")

        val results = buildJsonArray {
            var count = 0
            cursor.use { c ->
                while (c.moveToNext() && count < limit) {
                    add(buildJsonObject {
                        put("number", JsonPrimitive(c.getString(0) ?: ""))
                        put("name", JsonPrimitive(c.getString(1) ?: ""))
                        put("type", JsonPrimitive(callTypeIntToString(c.getInt(2))))
                        put("date", JsonPrimitive(c.getLong(3)))
                        put("duration", JsonPrimitive(c.getLong(4)))
                    })
                    count++
                }
            }
        }

        return ToolResult.Success(results.toString())
    }

    private fun callTypeStringToInt(type: String): Int? = when (type.uppercase()) {
        "MISSED" -> CallLog.Calls.MISSED_TYPE
        "INCOMING" -> CallLog.Calls.INCOMING_TYPE
        "OUTGOING" -> CallLog.Calls.OUTGOING_TYPE
        "ALL" -> null
        else -> null
    }

    private fun callTypeIntToString(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        else -> "UNKNOWN"
    }
}
