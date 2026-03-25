package ai.affiora.mobileclaw.tools

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class VolumeTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "volume"

    override val description: String =
        "Get or set device volume levels. Actions: 'get' returns all stream volumes (0-100), 'set' changes a specific stream volume."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("get"))
                    add(JsonPrimitive("set"))
                })
                put("description", JsonPrimitive("The action to perform: 'get' or 'set'."))
            })
            put("stream", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("media"))
                    add(JsonPrimitive("ring"))
                    add(JsonPrimitive("alarm"))
                    add(JsonPrimitive("notification"))
                })
                put("description", JsonPrimitive("Audio stream to set. Required for 'set'."))
            })
            put("level", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Volume level 0-100. Required for 'set'."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "get" -> executeGet()
                "set" -> executeSet(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'get' or 'set'.")
            }
        }
    }

    private fun executeGet(): ToolResult {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val result = buildJsonObject {
                put("media", JsonPrimitive(volumePercent(audio, AudioManager.STREAM_MUSIC)))
                put("ring", JsonPrimitive(volumePercent(audio, AudioManager.STREAM_RING)))
                put("alarm", JsonPrimitive(volumePercent(audio, AudioManager.STREAM_ALARM)))
                put("notification", JsonPrimitive(volumePercent(audio, AudioManager.STREAM_NOTIFICATION)))
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to get volume: ${e.message}")
        }
    }

    private fun executeSet(params: Map<String, JsonElement>): ToolResult {
        val stream = params["stream"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: stream")
        val level = params["level"]?.jsonPrimitive?.int
            ?: return ToolResult.Error("Missing required parameter: level")

        val streamInt = streamNameToInt(stream)
            ?: return ToolResult.Error("Unknown stream: $stream. Must be 'media', 'ring', 'alarm', or 'notification'.")

        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audio.getStreamMaxVolume(streamInt)
            val targetVol = (level.coerceIn(0, 100) * maxVol / 100).coerceIn(0, maxVol)
            audio.setStreamVolume(streamInt, targetVol, 0)
            ToolResult.Success("$stream volume set to $level%.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to set volume: ${e.message}")
        }
    }

    private fun volumePercent(audio: AudioManager, stream: Int): Int {
        val current = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        return if (max > 0) (current * 100 / max) else 0
    }

    private fun streamNameToInt(name: String): Int? = when (name) {
        "media" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> null
    }
}
