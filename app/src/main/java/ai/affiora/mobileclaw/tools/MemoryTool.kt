package ai.affiora.mobileclaw.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class MemoryTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "memory"

    override val description: String =
        "Store and retrieve persistent memories across conversations. " +
        "Use this to remember user preferences, names, common tasks, important facts, etc. " +
        "Actions: 'save' (store a memory), 'search' (find memories by query), " +
        "'get' (retrieve by key), 'list' (list all keys), 'delete' (remove a memory)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("save"))
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("get"))
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("delete"))
                })
                put("description", JsonPrimitive("The action to perform"))
            })
            put("key", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Memory key (required for save, get, delete)"))
            })
            put("content", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Memory content (required for save)"))
            })
            put("tags", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Comma-separated tags (optional, for save)"))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Search query (required for search)"))
            })
        })
    }

    companion object {
        private const val TAG = "MemoryTool"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private fun getMemoryFile(): File {
        val dir = File(context.filesDir, "memory")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "memories.json")
    }

    private fun loadMemories(): MutableList<MemoryEntry> {
        val file = getMemoryFile()
        if (!file.exists()) return mutableListOf()
        return try {
            json.decodeFromString<List<MemoryEntry>>(file.readText()).toMutableList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memories: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveMemories(memories: List<MemoryEntry>) {
        try {
            getMemoryFile().writeText(json.encodeToString(memories))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memories: ${e.message}")
        }
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "save" -> executeSave(params)
                "search" -> executeSearch(params)
                "get" -> executeGet(params)
                "list" -> executeList()
                "delete" -> executeDelete(params)
                else -> ToolResult.Error("Unknown action: $action")
            }
        }
    }

    private fun executeSave(params: Map<String, JsonElement>): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: key")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")
        val tags = params["tags"]?.jsonPrimitive?.content
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        val memories = loadMemories()
        val now = System.currentTimeMillis()

        val existing = memories.indexOfFirst { it.key == key }
        if (existing >= 0) {
            memories[existing] = memories[existing].copy(
                content = content,
                tags = tags,
                updatedAt = now,
            )
        } else {
            memories.add(
                MemoryEntry(
                    key = key,
                    content = content,
                    tags = tags,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }

        saveMemories(memories)
        val verb = if (existing >= 0) "Updated" else "Saved"
        return ToolResult.Success("$verb memory '$key'.")
    }

    private fun executeSearch(params: Map<String, JsonElement>): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: query")

        val memories = loadMemories()
        val queryLower = query.lowercase()

        val matches = memories.filter { entry ->
            entry.key.lowercase().contains(queryLower) ||
                entry.content.lowercase().contains(queryLower) ||
                entry.tags.any { it.lowercase().contains(queryLower) }
        }

        if (matches.isEmpty()) {
            return ToolResult.Success("No memories found matching '$query'.")
        }

        val sb = StringBuilder("Found ${matches.size} memor${if (matches.size == 1) "y" else "ies"}:\n\n")
        for (entry in matches) {
            sb.appendLine("**${entry.key}**")
            sb.appendLine(entry.content)
            if (entry.tags.isNotEmpty()) {
                sb.appendLine("Tags: ${entry.tags.joinToString(", ")}")
            }
            sb.appendLine()
        }
        return ToolResult.Success(sb.toString().trim())
    }

    private fun executeGet(params: Map<String, JsonElement>): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: key")

        val memories = loadMemories()
        val entry = memories.find { it.key == key }
            ?: return ToolResult.Success("Memory '$key' not found.")

        val sb = StringBuilder()
        sb.appendLine("**${entry.key}**")
        sb.appendLine(entry.content)
        if (entry.tags.isNotEmpty()) {
            sb.appendLine("Tags: ${entry.tags.joinToString(", ")}")
        }
        sb.appendLine("Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(entry.createdAt))}")
        sb.appendLine("Updated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(entry.updatedAt))}")
        return ToolResult.Success(sb.toString().trim())
    }

    private fun executeList(): ToolResult {
        val memories = loadMemories()
        if (memories.isEmpty()) {
            return ToolResult.Success("No memories stored yet.")
        }

        val sb = StringBuilder("${memories.size} memor${if (memories.size == 1) "y" else "ies"} stored:\n\n")
        for (entry in memories) {
            val preview = if (entry.content.length > 80) entry.content.take(80) + "..." else entry.content
            sb.appendLine("- **${entry.key}**: $preview")
        }
        return ToolResult.Success(sb.toString().trim())
    }

    private fun executeDelete(params: Map<String, JsonElement>): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: key")

        val memories = loadMemories()
        val removed = memories.removeAll { it.key == key }
        if (!removed) {
            return ToolResult.Success("Memory '$key' not found.")
        }

        saveMemories(memories)
        return ToolResult.Success("Deleted memory '$key'.")
    }
}

@Serializable
data class MemoryEntry(
    val key: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
