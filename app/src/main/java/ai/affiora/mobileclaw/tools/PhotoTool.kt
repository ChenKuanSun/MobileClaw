package ai.affiora.mobileclaw.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class PhotoTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "photos"

    override val description: String =
        "Access device photos via MediaStore. Actions: 'search' to find photos by name or date, 'get_info' for full metadata, 'share' to share a photo via the Android share sheet."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("get_info"))
                    add(JsonPrimitive("share"))
                })
                put("description", JsonPrimitive("Action: 'search', 'get_info', or 'share'."))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Search term to match against photo display name (optional, for 'search')."))
            })
            put("since", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Only return photos taken after this Unix timestamp in millis (optional, for 'search')."))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Maximum number of results (default: 10, for 'search')."))
            })
            put("id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Photo ID from MediaStore (required for 'get_info' and 'share')."))
            })
        })
    }

    private fun hasReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        if (!hasReadPermission()) {
            val permName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "READ_MEDIA_IMAGES"
            } else {
                "READ_EXTERNAL_STORAGE"
            }
            return ToolResult.Error("Photo access permission ($permName) not granted. Go to Android Settings > Apps > MobileClaw > Permissions > Photos to grant it.")
        }

        return withContext(Dispatchers.IO) {
            when (action) {
                "search" -> executeSearch(params)
                "get_info" -> executeGetInfo(params)
                "share" -> executeShare(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'search', 'get_info', or 'share'.")
            }
        }
    }

    private fun executeSearch(params: Map<String, JsonElement>): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
        val since = params["since"]?.jsonPrimitive?.content?.toLongOrNull()
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10

        val selection = buildString {
            val conditions = mutableListOf<String>()
            if (query != null) {
                conditions.add("${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?")
            }
            if (since != null) {
                // DATE_ADDED is in seconds
                conditions.add("${MediaStore.Images.Media.DATE_ADDED} >= ?")
            }
            if (conditions.isNotEmpty()) {
                append(conditions.joinToString(" AND "))
            }
        }.ifEmpty { null }

        val selectionArgs = buildList {
            if (query != null) add("%$query%")
            if (since != null) add((since / 1000).toString())
        }.toTypedArray().ifEmpty { null }

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATA,
                ),
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            ) ?: return ToolResult.Error("Failed to query photos")

            val results = buildJsonArray {
                cursor.use { c ->
                    var count = 0
                    while (c.moveToNext() && count < limit) {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(c.getLong(0).toString()))
                            put("displayName", JsonPrimitive(c.getString(1) ?: ""))
                            put("dateTaken", JsonPrimitive(c.getLong(2) * 1000)) // convert to millis
                            put("size", JsonPrimitive(c.getLong(3)))
                            put("path", JsonPrimitive(c.getString(4) ?: ""))
                        })
                        count++
                    }
                }
            }

            ToolResult.Success(results.toString())
        } catch (e: Exception) {
            ToolResult.Error("Photo search failed: ${e.message}")
        }
    }

    private fun executeGetInfo(params: Map<String, JsonElement>): ToolResult {
        val id = params["id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: id")

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.DATA,
                ),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(id),
                null,
            ) ?: return ToolResult.Error("Failed to query photo info")

            cursor.use { c ->
                if (!c.moveToFirst()) {
                    return ToolResult.Error("Photo not found with ID: $id")
                }

                val result = buildJsonObject {
                    put("id", JsonPrimitive(c.getLong(0).toString()))
                    put("displayName", JsonPrimitive(c.getString(1) ?: ""))
                    put("dateAdded", JsonPrimitive(c.getLong(2) * 1000))
                    put("dateModified", JsonPrimitive(c.getLong(3) * 1000))
                    put("size", JsonPrimitive(c.getLong(4)))
                    put("width", JsonPrimitive(c.getInt(5)))
                    put("height", JsonPrimitive(c.getInt(6)))
                    put("mimeType", JsonPrimitive(c.getString(7) ?: ""))
                    put("path", JsonPrimitive(c.getString(8) ?: ""))
                }
                ToolResult.Success(result.toString())
            }
        } catch (e: Exception) {
            ToolResult.Error("Failed to get photo info: ${e.message}")
        }
    }

    private fun executeShare(params: Map<String, JsonElement>): ToolResult {
        val id = params["id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: id")

        return try {
            val contentUri = Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id,
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Photo").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            ToolResult.Success("Share sheet opened for photo ID: $id")
        } catch (e: Exception) {
            ToolResult.Error("Failed to share photo: ${e.message}")
        }
    }
}
