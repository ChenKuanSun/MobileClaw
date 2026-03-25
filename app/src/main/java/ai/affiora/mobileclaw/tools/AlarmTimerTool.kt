package ai.affiora.mobileclaw.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class AlarmTimerTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "alarm"

    override val description: String =
        "Set alarms, timers, or show existing alarms. Actions: 'set_alarm' (hour 0-23, minute 0-59, label), 'set_timer' (seconds, label), 'show_alarms'. Delegates to the system clock app."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("set_alarm"))
                    add(JsonPrimitive("set_timer"))
                    add(JsonPrimitive("show_alarms"))
                })
                put("description", JsonPrimitive("The action to perform."))
            })
            put("hour", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Hour for alarm (0-23). Required for 'set_alarm'."))
            })
            put("minute", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Minute for alarm (0-59). Required for 'set_alarm'."))
            })
            put("seconds", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Timer duration in seconds. Required for 'set_timer'."))
            })
            put("label", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional label for the alarm or timer."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "set_alarm" -> executeSetAlarm(params)
                "set_timer" -> executeSetTimer(params)
                "show_alarms" -> executeShowAlarms()
                else -> ToolResult.Error("Unknown action: $action. Must be 'set_alarm', 'set_timer', or 'show_alarms'.")
            }
        }
    }

    private fun executeSetAlarm(params: Map<String, JsonElement>): ToolResult {
        val hour = params["hour"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: hour")
        val minute = params["minute"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: minute")
        val label = params["label"]?.jsonPrimitive?.content ?: ""

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Alarm set for %02d:%02d${if (label.isNotEmpty()) " ($label)" else ""}".format(hour, minute))
        } catch (e: Exception) {
            ToolResult.Error("Failed to set alarm: ${e.message}")
        }
    }

    private fun executeSetTimer(params: Map<String, JsonElement>): ToolResult {
        val seconds = params["seconds"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: seconds")
        val label = params["label"]?.jsonPrimitive?.content ?: ""

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Timer set for ${seconds}s${if (label.isNotEmpty()) " ($label)" else ""}")
        } catch (e: Exception) {
            ToolResult.Error("Failed to set timer: ${e.message}")
        }
    }

    private fun executeShowAlarms(): ToolResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Opened alarms view.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to show alarms: ${e.message}")
        }
    }
}
