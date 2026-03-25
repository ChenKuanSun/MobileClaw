package ai.affiora.mobileclaw.tools

import android.content.Context
import ai.affiora.mobileclaw.agent.ScheduleEngine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class ScheduleTool(
    private val context: Context,
    private val scheduleEngine: ScheduleEngine,
) : AndroidTool {

    override val name: String = "schedule"

    override val description: String =
        "Manage scheduled tasks that run automatically. Actions: 'create' a recurring task, 'list' all schedules, 'cancel' a schedule, 'run_now' to execute immediately."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("create"))
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("cancel"))
                    add(JsonPrimitive("run_now"))
                })
                put("description", JsonPrimitive("Action: 'create', 'list', 'cancel', or 'run_now'."))
            })
            put("name", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Schedule name (required for 'create', 'cancel', 'run_now')."))
            })
            put("prompt", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The prompt/action to execute on schedule (required for 'create')."))
            })
            put("interval_hours", buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive("Repeat interval in hours (required for 'create', minimum 1)."))
            })
            put("enabled", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Whether the schedule is enabled (default: true, for 'create')."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")
        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true

        return when (action) {
            "create" -> executeCreate(params, confirmed)
            "list" -> executeList()
            "cancel" -> executeCancel(params, confirmed)
            "run_now" -> executeRunNow(params)
            else -> ToolResult.Error("Unknown action: $action. Must be 'create', 'list', 'cancel', or 'run_now'.")
        }
    }

    private fun executeCreate(params: Map<String, JsonElement>, confirmed: Boolean): ToolResult {
        val name = params["name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: name")
        val prompt = params["prompt"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: prompt")
        val intervalHours = params["interval_hours"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return ToolResult.Error("Missing required parameter: interval_hours (must be a number)")

        if (intervalHours < 1) {
            return ToolResult.Error("interval_hours must be at least 1")
        }

        if (!confirmed) {
            val preview = buildString {
                appendLine("Create scheduled task:")
                appendLine("  Name: $name")
                appendLine("  Action: $prompt")
                appendLine("  Interval: every $intervalHours hour(s)")
            }
            return ToolResult.NeedsConfirmation(
                preview = preview.trim(),
                requestId = "schedule_create_${UUID.randomUUID()}",
            )
        }

        return try {
            scheduleEngine.scheduleRecurring(name, intervalHours, prompt)
            ToolResult.Success("Scheduled '$name' to run every $intervalHours hour(s).")
        } catch (e: Exception) {
            ToolResult.Error("Failed to create schedule: ${e.message}")
        }
    }

    private fun executeList(): ToolResult {
        return try {
            val schedules = scheduleEngine.listSchedules()
            if (schedules.isEmpty()) {
                return ToolResult.Success("No scheduled tasks.")
            }
            val result = buildJsonArray {
                for (schedule in schedules) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(schedule.name))
                        put("interval_hours", JsonPrimitive(schedule.intervalHours))
                        put("action", JsonPrimitive(schedule.skillAction))
                        put("next_run_time", JsonPrimitive(schedule.nextRunTime))
                    })
                }
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to list schedules: ${e.message}")
        }
    }

    private fun executeCancel(params: Map<String, JsonElement>, confirmed: Boolean): ToolResult {
        val name = params["name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: name")

        if (!confirmed) {
            return ToolResult.NeedsConfirmation(
                preview = "Cancel scheduled task: $name",
                requestId = "schedule_cancel_${UUID.randomUUID()}",
            )
        }

        return try {
            scheduleEngine.cancelSchedule(name)
            ToolResult.Success("Cancelled schedule '$name'.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to cancel schedule: ${e.message}")
        }
    }

    private fun executeRunNow(params: Map<String, JsonElement>): ToolResult {
        val name = params["name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: name")

        val schedules = scheduleEngine.listSchedules()
        val schedule = schedules.find { it.name == name }
            ?: return ToolResult.Error("No schedule found with name: $name")

        // Re-trigger by scheduling with same params — WorkManager will run immediately
        // if the work is already due, or we cancel and re-create.
        return try {
            scheduleEngine.cancelSchedule(name)
            scheduleEngine.scheduleRecurring(name, schedule.intervalHours, schedule.skillAction)
            ToolResult.Success("Triggered immediate run of '$name'. It will continue on its regular schedule.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to run schedule immediately: ${e.message}")
        }
    }
}
