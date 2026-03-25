package ai.affiora.mobileclaw.tools

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class MediaControlTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "media"

    override val description: String =
        "Control media playback on the device. Actions: 'play', 'pause', 'next', 'previous', 'get_now_playing'. Controls the active media session from any app."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("play"))
                    add(JsonPrimitive("pause"))
                    add(JsonPrimitive("next"))
                    add(JsonPrimitive("previous"))
                    add(JsonPrimitive("get_now_playing"))
                })
                put("description", JsonPrimitive("The media action to perform."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "play" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                "pause" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                "next" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "previous" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "get_now_playing" -> getNowPlaying()
                else -> ToolResult.Error("Unknown action: $action.")
            }
        }
    }

    private fun getActiveController(): MediaController? {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(
                context,
                "ai.affiora.mobileclaw.tools.ClawNotificationListener"
            )
            val controllers = msm.getActiveSessions(listenerComponent)
            controllers.firstOrNull()
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun dispatchMediaKey(keyCode: Int): ToolResult {
        val controller = getActiveController()
        return if (controller != null) {
            try {
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                val actionName = when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                    KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
                    else -> "unknown"
                }
                ToolResult.Success("Media $actionName sent to ${controller.packageName}.")
            } catch (e: Exception) {
                ToolResult.Error("Failed to send media command: ${e.message}")
            }
        } else {
            ToolResult.Error("No active media session found. Notification listener access may be required.")
        }
    }

    private fun getNowPlaying(): ToolResult {
        val controller = getActiveController()
            ?: return ToolResult.Success(buildJsonObject {
                put("playing", JsonPrimitive(false))
                put("message", JsonPrimitive("No active media session found."))
            }.toString())

        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val result = buildJsonObject {
            put("playing", JsonPrimitive(
                playbackState?.state == PlaybackState.STATE_PLAYING
            ))
            put("app", JsonPrimitive(controller.packageName ?: "unknown"))
            put("title", JsonPrimitive(
                metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
            ))
            put("artist", JsonPrimitive(
                metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            ))
            put("album", JsonPrimitive(
                metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            ))
            val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0
            put("duration_ms", JsonPrimitive(duration))
            put("position_ms", JsonPrimitive(playbackState?.position ?: 0))
        }

        return ToolResult.Success(result.toString())
    }
}
