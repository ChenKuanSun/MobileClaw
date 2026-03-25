package ai.affiora.mobileclaw.tools

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * File system tool — restricted to app internal storage and Downloads for security.
 */
class FileSystemTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "files"

    override val description: String =
        "Read, write, list, and manage files. Restricted to app storage and Downloads folder. " +
        "Actions: 'list' (list directory), 'read' (read file, max 10KB), 'write' (write file, needs confirmation), " +
        "'delete' (delete file, needs confirmation), 'info' (get file metadata)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("read"))
                    add(JsonPrimitive("write"))
                    add(JsonPrimitive("delete"))
                    add(JsonPrimitive("info"))
                })
                put("description", JsonPrimitive("The file operation to perform."))
            })
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("File or directory path. Relative paths resolve from app internal storage."))
            })
            put("content", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Content to write (required for 'write' action)."))
            })
        })
    }

    private val internalDir: File get() = context.filesDir
    private val downloadsDir: File get() =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "list" -> executeList(params)
                "read" -> executeRead(params)
                "write" -> executeWrite(params)
                "delete" -> executeDelete(params)
                "info" -> executeInfo(params)
                else -> ToolResult.Error("Unknown action: $action")
            }
        }
    }

    private fun resolvePath(pathStr: String?): File {
        if (pathStr.isNullOrBlank()) return internalDir
        val file = File(pathStr)
        return if (file.isAbsolute) file else File(internalDir, pathStr)
    }

    private fun isAllowedPath(file: File): Boolean {
        val canonical = file.canonicalPath
        return canonical.startsWith(internalDir.canonicalPath) ||
               canonical.startsWith(downloadsDir.canonicalPath) ||
               canonical.startsWith(context.cacheDir.canonicalPath) ||
               canonical.startsWith(context.getExternalFilesDir(null)?.canonicalPath ?: "___none___")
    }

    private fun executeList(params: Map<String, JsonElement>): ToolResult {
        val dir = resolvePath(params["path"]?.jsonPrimitive?.content)
        if (!isAllowedPath(dir)) return ToolResult.Error("Access denied: path outside allowed directories.")
        if (!dir.exists()) return ToolResult.Error("Directory not found: ${dir.path}")
        if (!dir.isDirectory) return ToolResult.Error("Not a directory: ${dir.path}")

        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val result = buildJsonArray {
            for (entry in entries) {
                add(buildJsonObject {
                    put("name", JsonPrimitive(entry.name))
                    put("type", JsonPrimitive(if (entry.isDirectory) "directory" else "file"))
                    put("size", JsonPrimitive(if (entry.isFile) entry.length() else 0))
                })
            }
        }
        return ToolResult.Success("${dir.path}:\n$result")
    }

    private fun executeRead(params: Map<String, JsonElement>): ToolResult {
        val pathStr = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: path")
        val file = resolvePath(pathStr)
        if (!isAllowedPath(file)) return ToolResult.Error("Access denied: path outside allowed directories.")
        if (!file.exists()) return ToolResult.Error("File not found: ${file.path}")
        if (!file.isFile) return ToolResult.Error("Not a file: ${file.path}")
        if (file.length() > 10 * 1024) return ToolResult.Error("File too large (${file.length()} bytes). Max 10KB.")

        return ToolResult.Success(file.readText())
    }

    private fun executeWrite(params: Map<String, JsonElement>): ToolResult {
        val pathStr = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: path")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")
        val file = resolvePath(pathStr)
        if (!isAllowedPath(file)) return ToolResult.Error("Access denied: path outside allowed directories.")

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            file.parentFile?.mkdirs()
            file.writeText(content)
            return ToolResult.Success("Wrote ${content.length} chars to ${file.path}")
        }

        val preview = "Write ${content.length} chars to ${file.path}?\n" +
                "Preview: ${content.take(200)}${if (content.length > 200) "..." else ""}"

        return ToolResult.NeedsConfirmation(
            preview = preview,
            requestId = "file_write_${UUID.randomUUID()}",
        )
    }

    private fun executeDelete(params: Map<String, JsonElement>): ToolResult {
        val pathStr = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: path")
        val file = resolvePath(pathStr)
        if (!isAllowedPath(file)) return ToolResult.Error("Access denied: path outside allowed directories.")
        if (!file.exists()) return ToolResult.Error("File not found: ${file.path}")

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            return if (deleted) {
                ToolResult.Success("Deleted ${file.path}")
            } else {
                ToolResult.Error("Failed to delete ${file.path}")
            }
        }

        return ToolResult.NeedsConfirmation(
            preview = "Delete ${file.path}? (${file.length()} bytes)",
            requestId = "file_delete_${UUID.randomUUID()}",
        )
    }

    private fun executeInfo(params: Map<String, JsonElement>): ToolResult {
        val pathStr = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: path")
        val file = resolvePath(pathStr)
        if (!isAllowedPath(file)) return ToolResult.Error("Access denied: path outside allowed directories.")
        if (!file.exists()) return ToolResult.Error("File not found: ${file.path}")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val info = buildJsonObject {
            put("path", JsonPrimitive(file.canonicalPath))
            put("name", JsonPrimitive(file.name))
            put("type", JsonPrimitive(if (file.isDirectory) "directory" else "file"))
            put("size", JsonPrimitive(file.length()))
            put("modified", JsonPrimitive(dateFormat.format(Date(file.lastModified()))))
            put("readable", JsonPrimitive(file.canRead()))
            put("writable", JsonPrimitive(file.canWrite()))
        }
        return ToolResult.Success(info.toString())
    }
}
