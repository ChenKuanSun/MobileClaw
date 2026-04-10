package ai.affiora.mobileclaw.tools

import ai.affiora.mobileclaw.agent.MemoryStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenClaw-style memory tool backed by [MemoryStore].
 *
 * Three storage surfaces:
 * - **save** → durable `MEMORY.md` facts (loaded every session)
 * - **note** → daily `daily/YYYY-MM-DD.md` running notes
 * - **diary** → read `DREAMS.md` promotion log
 *
 * Durable facts are auto-injected into the system prompt by SystemPromptBuilder,
 * so the model rarely needs to call `search` or `get` — just `save` to remember things.
 */
class MemoryTool(
    private val store: MemoryStore,
) : AndroidTool {

    override val name: String = "memory"

    override val description: String =
        "Persistent memory across conversations. " +
        "Use 'save' to remember durable facts (preferences, names, decisions) — these load " +
        "into every future conversation automatically. " +
        "Use 'note' for running daily observations (what the user did today). " +
        "Use 'search' to find specific things across all memory. " +
        "Use 'diary' to see recent promotion entries."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("save"))
                    add(JsonPrimitive("note"))
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("delete"))
                    add(JsonPrimitive("diary"))
                })
                put("description", JsonPrimitive("The memory action to perform"))
            })
            put("content", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The fact or note text (required for save/note)"))
            })
            put("tags", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Comma-separated tags (optional, for save)"))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Search query (required for search)"))
            })
            put("match", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Substring of the fact to delete (required for delete)"))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return when (action) {
            "save" -> save(params)
            "note" -> note(params)
            "search" -> search(params)
            "list" -> list()
            "delete" -> delete(params)
            "diary" -> diary()
            else -> ToolResult.Error("Unknown action: $action")
        }
    }

    private suspend fun save(params: Map<String, JsonElement>): ToolResult {
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")
        val tags = params["tags"]?.jsonPrimitive?.content
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        store.appendDurableFact(content, tags)
        return ToolResult.Success("Saved to MEMORY.md.")
    }

    private suspend fun note(params: Map<String, JsonElement>): ToolResult {
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")
        store.appendDailyNote(content)
        return ToolResult.Success("Added to today's daily notes.")
    }

    private suspend fun search(params: Map<String, JsonElement>): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: query")
        val matches = store.search(query)
        if (matches.isEmpty()) return ToolResult.Success("No matches for '$query'.")

        val sb = StringBuilder("Found ${matches.size} match${if (matches.size == 1) "" else "es"}:\n\n")
        for (m in matches.take(20)) {
            sb.appendLine("• [${m.source}] ${m.line}")
        }
        if (matches.size > 20) sb.appendLine("... and ${matches.size - 20} more")
        return ToolResult.Success(sb.toString().trim())
    }

    private suspend fun list(): ToolResult {
        val facts = store.listDurableFacts()
        if (facts.isEmpty()) return ToolResult.Success("No durable facts stored yet.")
        return ToolResult.Success(
            "${facts.size} durable fact${if (facts.size == 1) "" else "s"} in MEMORY.md:\n\n" +
                facts.joinToString("\n")
        )
    }

    private suspend fun delete(params: Map<String, JsonElement>): ToolResult {
        val match = params["match"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: match")
        val removed = store.deleteDurableFactContaining(match)
        return if (removed) ToolResult.Success("Deleted fact containing '$match'.")
        else ToolResult.Success("No fact matched '$match'.")
    }

    private suspend fun diary(): ToolResult {
        return ToolResult.Success(store.readDreamsDiary())
    }
}
