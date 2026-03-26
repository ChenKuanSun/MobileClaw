package ai.affiora.mobileclaw.tools

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import java.util.UUID

class SmsTool(
    private val context: Context
) : AndroidTool {

    override val name: String = "sms"

    override val description: String =
        "Search SMS messages or send an SMS. Actions: 'search' to query messages, 'send' to send a new message."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("send"))
                })
                put("description", JsonPrimitive("The action to perform: 'search' or 'send'"))
            })
            put("since", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Unix timestamp in millis. Only return messages after this time."))
            })
            put("from", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter messages by sender phone number (search only)."))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Max number of messages to return. Default 20."))
            })
            put("to", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Recipient phone number (required for send)."))
            })
            put("body", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Message body text (required for send)."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        if (action == "search" && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Error("SMS permission not granted. Please grant SMS permission in Settings.")
        }
        if (action == "send" && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Error("SMS send permission not granted. Please grant SMS permission in Settings.")
        }

        return withContext(Dispatchers.IO) {
            when (action) {
                "search" -> executeSearch(params)
                "send" -> executeSend(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'search' or 'send'.")
            }
        }
    }

    private fun executeSearch(params: Map<String, JsonElement>): ToolResult {
        val since = params["since"]?.jsonPrimitive?.long
        val from = params["from"]?.jsonPrimitive?.content
        val limit = params["limit"]?.jsonPrimitive?.int ?: 20

        val resolver: ContentResolver = context.contentResolver

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (since != null) {
            selectionParts.add("${Telephony.Sms.DATE} > ?")
            selectionArgs.add(since.toString())
        }
        if (from != null) {
            selectionParts.add("${Telephony.Sms.ADDRESS} LIKE ?")
            selectionArgs.add("%$from%")
        }

        val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            ),
            selection,
            args,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return ToolResult.Error("Failed to query SMS content provider")

        val results = buildJsonArray {
            var count = 0
            cursor.use { c ->
                while (c.moveToNext() && count < limit) {
                    add(buildJsonObject {
                        put("address", JsonPrimitive(c.getString(0) ?: ""))
                        put("body", JsonPrimitive(c.getString(1) ?: ""))
                        put("date", JsonPrimitive(c.getLong(2)))
                        put("type", JsonPrimitive(smsTypeToString(c.getInt(3))))
                        put("read", JsonPrimitive(c.getInt(4) == 1))
                    })
                    count++
                }
            }
        }

        return ToolResult.Success(results.toString())
    }

    private fun executeSend(params: Map<String, JsonElement>): ToolResult {
        val to = params["to"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: to")
        val body = params["body"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: body")

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            return confirmSend(to, body)
        }

        val requestId = UUID.randomUUID().toString()
        val preview = "Send SMS to $to:\n\"$body\""

        return ToolResult.NeedsConfirmation(preview = preview, requestId = requestId)
    }

    /**
     * Actually sends the SMS after user confirmation.
     */
    @Suppress("DEPRECATION")
    private fun confirmSend(to: String, body: String): ToolResult {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(to, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            }
            ToolResult.Success("SMS sent to $to")
        } catch (e: Exception) {
            ToolResult.Error("Failed to send SMS: ${e.message}")
        }
    }

    private fun smsTypeToString(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> "unknown"
    }
}
