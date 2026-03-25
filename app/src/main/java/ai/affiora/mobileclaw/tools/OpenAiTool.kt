package ai.affiora.mobileclaw.tools

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class OpenAiTool(
    private val context: Context,
    private val httpClient: HttpClient,
    private val userPreferences: UserPreferences,
) : AndroidTool {

    override val name: String = "openai"

    override val description: String =
        "Access OpenAI services: image generation (DALL-E), text-to-speech, and speech-to-text (Whisper). Requires OpenAI API key configured for the 'openai' provider."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("generate_image"))
                    add(JsonPrimitive("text_to_speech"))
                    add(JsonPrimitive("transcribe"))
                })
                put("description", JsonPrimitive("Action: 'generate_image', 'text_to_speech', or 'transcribe'."))
            })
            put("prompt", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Image generation prompt (required for 'generate_image')."))
            })
            put("size", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("1024x1024"))
                    add(JsonPrimitive("1024x1792"))
                    add(JsonPrimitive("1792x1024"))
                })
                put("description", JsonPrimitive("Image size (default: 1024x1024). For 'generate_image' only."))
            })
            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Text to speak (required for 'text_to_speech')."))
            })
            put("voice", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("alloy"))
                    add(JsonPrimitive("echo"))
                    add(JsonPrimitive("fable"))
                    add(JsonPrimitive("onyx"))
                    add(JsonPrimitive("nova"))
                    add(JsonPrimitive("shimmer"))
                })
                put("description", JsonPrimitive("TTS voice (default: alloy). For 'text_to_speech' only."))
            })
            put("audio_path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Path to audio file (required for 'transcribe')."))
            })
        })
    }

    private fun getApiKey(): String {
        return userPreferences.getTokenForProvider("openai")
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return ToolResult.Error("OpenAI API key not configured. Set a token for the 'openai' provider in Settings.")
        }

        return withContext(Dispatchers.IO) {
            when (action) {
                "generate_image" -> executeGenerateImage(params, apiKey)
                "text_to_speech" -> executeTextToSpeech(params, apiKey)
                "transcribe" -> executeTranscribe(params, apiKey)
                else -> ToolResult.Error("Unknown action: $action. Must be 'generate_image', 'text_to_speech', or 'transcribe'.")
            }
        }
    }

    private suspend fun executeGenerateImage(params: Map<String, JsonElement>, apiKey: String): ToolResult {
        val prompt = params["prompt"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: prompt")
        val size = params["size"]?.jsonPrimitive?.content ?: "1024x1024"

        return try {
            val requestBody = buildJsonObject {
                put("model", JsonPrimitive("dall-e-3"))
                put("prompt", JsonPrimitive(prompt))
                put("n", JsonPrimitive(1))
                put("size", JsonPrimitive(size))
                put("response_format", JsonPrimitive("b64_json"))
            }

            val response = httpClient.post("https://api.openai.com/v1/images/generations") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }

            val responseText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val parsed = json.parseToJsonElement(responseText).jsonObject

            // Check for error
            val error = parsed["error"]
            if (error != null) {
                val errorMessage = error.jsonObject["message"]?.jsonPrimitive?.content ?: "Unknown error"
                return ToolResult.Error("OpenAI API error: $errorMessage")
            }

            val data = parsed["data"] as? kotlinx.serialization.json.JsonArray
                ?: return ToolResult.Error("Unexpected API response format")
            val b64 = data[0].jsonObject["b64_json"]?.jsonPrimitive?.content
                ?: return ToolResult.Error("No image data in response")

            val imageBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val fileName = "mobileclaw_${System.currentTimeMillis()}.png"

            val savedPath = saveImageToDownloads(fileName, imageBytes)
                ?: return ToolResult.Error("Failed to save image to Downloads")

            val result = buildJsonObject {
                put("file_path", JsonPrimitive(savedPath))
                put("prompt", JsonPrimitive(prompt))
                put("size", JsonPrimitive(size))
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Image generation failed: ${e.message}")
        }
    }

    private fun saveImageToDownloads(fileName: String, data: ByteArray): String? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MobileClaw")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        resolver.openOutputStream(uri)?.use { it.write(data) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        return "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/MobileClaw/$fileName"
    }

    private suspend fun executeTextToSpeech(params: Map<String, JsonElement>, apiKey: String): ToolResult {
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: text")
        val voice = params["voice"]?.jsonPrimitive?.content ?: "alloy"

        return try {
            val requestBody = buildJsonObject {
                put("model", JsonPrimitive("tts-1"))
                put("input", JsonPrimitive(text))
                put("voice", JsonPrimitive(voice))
            }

            val response = httpClient.post("https://api.openai.com/v1/audio/speech") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }

            val audioBytes = response.readRawBytes()

            // Save to cache and play
            val audioFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            audioFile.writeBytes(audioBytes)

            // Play audio
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()
            mediaPlayer.setOnCompletionListener {
                it.release()
                audioFile.delete()
            }

            val result = buildJsonObject {
                put("status", JsonPrimitive("playing"))
                put("voice", JsonPrimitive(voice))
                put("text_length", JsonPrimitive(text.length))
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Text-to-speech failed: ${e.message}")
        }
    }

    private suspend fun executeTranscribe(params: Map<String, JsonElement>, apiKey: String): ToolResult {
        val audioPath = params["audio_path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: audio_path")

        val audioFile = File(audioPath)
        if (!audioFile.exists()) {
            return ToolResult.Error("Audio file not found: $audioPath")
        }

        return try {
            val audioBytes = audioFile.readBytes()
            val fileName = audioFile.name

            val response = httpClient.post("https://api.openai.com/v1/audio/transcriptions") {
                header("Authorization", "Bearer $apiKey")
                setBody(MultiPartFormDataContent(formData {
                    append("model", "whisper-1")
                    append("file", audioBytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, guessAudioContentType(fileName))
                    })
                }))
            }

            val responseText = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val parsed = json.parseToJsonElement(responseText).jsonObject

            val error = parsed["error"]
            if (error != null) {
                val errorMessage = error.jsonObject["message"]?.jsonPrimitive?.content ?: "Unknown error"
                return ToolResult.Error("Whisper API error: $errorMessage")
            }

            val transcription = parsed["text"]?.jsonPrimitive?.content ?: ""

            val result = buildJsonObject {
                put("text", JsonPrimitive(transcription))
                put("audio_file", JsonPrimitive(audioPath))
            }
            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Transcription failed: ${e.message}")
        }
    }

    private fun guessAudioContentType(fileName: String): String {
        return when {
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            fileName.endsWith(".webm", ignoreCase = true) -> "audio/webm"
            fileName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
            else -> "audio/mpeg"
        }
    }
}
