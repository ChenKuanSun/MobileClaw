package ai.affiora.mobileclaw.tools

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.TimeZone
import java.util.UUID

class CalendarTool(
    private val context: Context
) : AndroidTool {

    override val name: String = "calendar"

    override val description: String =
        "Query calendar events by date range or add a new calendar event."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("events"))
                    add(JsonPrimitive("add"))
                })
                put("description", JsonPrimitive("Action: 'events' to query or 'add' to create a new event."))
            })
            put("start", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Start of date range as Unix timestamp in millis (required for 'events')."))
            })
            put("end", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("End of date range as Unix timestamp in millis (required for 'events')."))
            })
            put("title", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Event title (required for 'add')."))
            })
            put("dtstart", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Event start time as Unix timestamp in millis (required for 'add')."))
            })
            put("dtend", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Event end time as Unix timestamp in millis (required for 'add')."))
            })
            put("location", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Event location (optional, for 'add')."))
            })
            put("description", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Event description (optional, for 'add')."))
            })
            put("calendar_id", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Calendar ID to add event to. Uses primary calendar if omitted."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val readPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val writePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)

        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        if (action == "events" && readPerm != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Error("Calendar read permission not granted. Please grant Calendar permission in Settings.")
        }
        if (action == "add" && writePerm != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Error("Calendar write permission not granted. Please grant Calendar permission in Settings.")
        }

        return withContext(Dispatchers.IO) {
            when (action) {
                "events" -> executeEvents(params)
                "add" -> executeAdd(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'events' or 'add'.")
            }
        }
    }

    private fun executeEvents(params: Map<String, JsonElement>): ToolResult {
        val start = params["start"]?.jsonPrimitive?.long
            ?: return ToolResult.Error("Missing required parameter: start")
        val end = params["end"]?.jsonPrimitive?.long
            ?: return ToolResult.Error("Missing required parameter: end")

        val resolver: ContentResolver = context.contentResolver

        val cursor = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            ),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(start.toString(), end.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        ) ?: return ToolResult.Error("Failed to query calendar events")

        val results = buildJsonArray {
            cursor.use { c ->
                while (c.moveToNext()) {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(c.getLong(0)))
                        put("title", JsonPrimitive(c.getString(1) ?: ""))
                        put("dtstart", JsonPrimitive(c.getLong(2)))
                        put("dtend", JsonPrimitive(c.getLong(3)))
                        put("location", JsonPrimitive(c.getString(4) ?: ""))
                        put("description", JsonPrimitive(c.getString(5) ?: ""))
                    })
                }
            }
        }

        return ToolResult.Success(results.toString())
    }

    private fun executeAdd(params: Map<String, JsonElement>): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: title")
        val dtstart = params["dtstart"]?.jsonPrimitive?.long
            ?: return ToolResult.Error("Missing required parameter: dtstart")
        val dtend = params["dtend"]?.jsonPrimitive?.long
            ?: return ToolResult.Error("Missing required parameter: dtend")
        val location = params["location"]?.jsonPrimitive?.content ?: ""
        val eventDescription = params["description"]?.jsonPrimitive?.content ?: ""
        val calendarId = params["calendar_id"]?.jsonPrimitive?.long

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            return confirmAdd(title, dtstart, dtend, location, eventDescription, calendarId)
        }

        val requestId = UUID.randomUUID().toString()
        val preview = buildString {
            appendLine("Add calendar event:")
            appendLine("  Title: $title")
            appendLine("  Start: $dtstart")
            appendLine("  End: $dtend")
            if (location.isNotEmpty()) appendLine("  Location: $location")
            if (eventDescription.isNotEmpty()) appendLine("  Description: $eventDescription")
        }

        return ToolResult.NeedsConfirmation(preview = preview.trim(), requestId = requestId)
    }

    /**
     * Actually inserts the event after user confirmation.
     */
    fun confirmAdd(
        title: String,
        dtstart: Long,
        dtend: Long,
        location: String,
        eventDescription: String,
        calendarId: Long?
    ): ToolResult {
        val resolver: ContentResolver = context.contentResolver

        val resolvedCalendarId = calendarId ?: getPrimaryCalendarId(resolver)
            ?: return ToolResult.Error("No calendar found on device")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, resolvedCalendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, dtstart)
            put(CalendarContract.Events.DTEND, dtend)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DESCRIPTION, eventDescription)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return try {
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val eventId = uri.lastPathSegment
                ToolResult.Success("Event created with ID: $eventId")
            } else {
                ToolResult.Error("Failed to insert calendar event")
            }
        } catch (e: Exception) {
            ToolResult.Error("Failed to insert calendar event: ${e.message}")
        }
    }

    private fun getPrimaryCalendarId(resolver: ContentResolver): Long? {
        val cursor = resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null,
            null
        )

        cursor?.use { c ->
            if (c.moveToFirst()) {
                return c.getLong(0)
            }
        }

        // Fallback: pick the first calendar
        val fallback = resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null,
            null,
            null
        )

        fallback?.use { c ->
            if (c.moveToFirst()) {
                return c.getLong(0)
            }
        }

        return null
    }
}
