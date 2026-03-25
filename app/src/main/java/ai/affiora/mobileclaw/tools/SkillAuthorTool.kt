package ai.affiora.mobileclaw.tools

import android.content.Context
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.skills.SkillInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class SkillAuthorTool(
    private val context: Context,
    private val userPreferences: UserPreferences,
    private val skillInstaller: SkillInstaller,
) : AndroidTool {

    companion object {
        private const val USER_SKILLS_DIR = "skills/user"
        private const val SKILL_FILE = "SKILL.md"
    }

    override val name: String = "skills_author"

    override val description: String =
        "Create, read, update, delete, and list user-created skills. Actions: 'list' to list all user skills, 'read' to read a skill, 'create' to create a new skill, 'update' to update a skill, 'delete' to delete a skill."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("read"))
                    add(JsonPrimitive("create"))
                    add(JsonPrimitive("update"))
                    add(JsonPrimitive("delete"))
                })
                put("description", JsonPrimitive("The action to perform: 'list', 'read', 'create', 'update', or 'delete'"))
            })
            put("skill_id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The skill identifier (required for 'read', 'create', 'update', 'delete')."))
            })
            put("content", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Full SKILL.md content including YAML frontmatter (required for 'create' and 'update')."))
            })
        })
    }

    private fun getUserSkillsDir(): File {
        return File(context.filesDir, USER_SKILLS_DIR)
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return withContext(Dispatchers.IO) {
            when (action) {
                "list" -> executeList()
                "read" -> executeRead(params)
                "create" -> executeCreate(params)
                "update" -> executeUpdate(params)
                "delete" -> executeDelete(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'list', 'read', 'create', 'update', or 'delete'.")
            }
        }
    }

    private fun executeList(): ToolResult {
        val skillsDir = getUserSkillsDir()
        if (!skillsDir.exists()) {
            return ToolResult.Success(buildJsonArray {}.toString())
        }

        val skills = buildJsonArray {
            val dirs = skillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (dir in dirs) {
                val skillFile = File(dir, SKILL_FILE)
                if (!skillFile.exists()) continue

                val raw = skillFile.readText()
                val meta = extractFrontmatter(raw)

                add(buildJsonObject {
                    put("id", JsonPrimitive(dir.name))
                    put("name", JsonPrimitive(meta["name"] ?: dir.name))
                    put("description", JsonPrimitive(meta["description"] ?: ""))
                })
            }
        }

        return ToolResult.Success(skills.toString())
    }

    private fun executeRead(params: Map<String, JsonElement>): ToolResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")

        val skillFile = File(getUserSkillsDir(), "$skillId/$SKILL_FILE")
        if (!skillFile.exists()) {
            return ToolResult.Error("Skill not found: $skillId")
        }

        val content = skillFile.readText()
        val result = buildJsonObject {
            put("skill_id", JsonPrimitive(skillId))
            put("content", JsonPrimitive(content))
        }
        return ToolResult.Success(result.toString())
    }

    private suspend fun executeCreate(params: Map<String, JsonElement>): ToolResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")

        val skillDir = File(getUserSkillsDir(), skillId)
        if (skillDir.exists()) {
            return ToolResult.Error("Skill already exists: $skillId. Use 'update' to modify it.")
        }

        // FIX 7: Security scan the content before saving
        val scanResult = skillInstaller.scanContent(content)
        if (!scanResult.safe) {
            return ToolResult.Error(
                "Skill content blocked by security scanner: ${scanResult.blockedReasons.joinToString("; ")}"
            )
        }

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            skillDir.mkdirs()
            File(skillDir, SKILL_FILE).writeText(content)

            // FIX 6: Auto-enable the newly created skill
            val currentActive = userPreferences.activeSkillIds.first()
            userPreferences.setActiveSkillIds(currentActive + skillId)

            return ToolResult.Success("Skill '$skillId' created and auto-enabled successfully.")
        }

        val meta = extractFrontmatter(content)
        val preview = buildString {
            appendLine("Create new skill:")
            appendLine("  ID: $skillId")
            appendLine("  Name: ${meta["name"] ?: skillId}")
            appendLine("  Description: ${meta["description"] ?: "(none)"}")
            appendLine("  Tools required: ${meta["tools_required"] ?: "(none)"}")
            appendLine()
            appendLine("Content preview:")
            appendLine(content.take(500))
            if (content.length > 500) appendLine("...[truncated]")
        }

        return ToolResult.NeedsConfirmation(
            preview = preview,
            requestId = UUID.randomUUID().toString(),
        )
    }

    private fun executeUpdate(params: Map<String, JsonElement>): ToolResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: content")

        val skillFile = File(getUserSkillsDir(), "$skillId/$SKILL_FILE")
        if (!skillFile.exists()) {
            return ToolResult.Error("Skill not found: $skillId. Use 'create' to make a new skill.")
        }

        // FIX 7: Security scan updated content before saving
        val scanResult = skillInstaller.scanContent(content)
        if (!scanResult.safe) {
            return ToolResult.Error(
                "Skill content blocked by security scanner: ${scanResult.blockedReasons.joinToString("; ")}"
            )
        }

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            skillFile.writeText(content)
            return ToolResult.Success("Skill '$skillId' updated successfully.")
        }

        val oldContent = skillFile.readText()
        val preview = buildString {
            appendLine("Update skill '$skillId':")
            appendLine()
            appendLine("--- OLD ---")
            appendLine(oldContent.take(300))
            if (oldContent.length > 300) appendLine("...[truncated]")
            appendLine()
            appendLine("--- NEW ---")
            appendLine(content.take(300))
            if (content.length > 300) appendLine("...[truncated]")
        }

        return ToolResult.NeedsConfirmation(
            preview = preview,
            requestId = UUID.randomUUID().toString(),
        )
    }

    private fun executeDelete(params: Map<String, JsonElement>): ToolResult {
        val skillId = params["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")

        val skillDir = File(getUserSkillsDir(), skillId)
        if (!skillDir.exists()) {
            return ToolResult.Error("Skill not found: $skillId")
        }

        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (confirmed) {
            skillDir.deleteRecursively()
            return ToolResult.Success("Skill '$skillId' deleted successfully.")
        }

        val skillFile = File(skillDir, SKILL_FILE)
        val meta = if (skillFile.exists()) extractFrontmatter(skillFile.readText()) else emptyMap()

        val preview = buildString {
            appendLine("Delete skill:")
            appendLine("  ID: $skillId")
            appendLine("  Name: ${meta["name"] ?: skillId}")
            appendLine("  Description: ${meta["description"] ?: "(none)"}")
        }

        return ToolResult.NeedsConfirmation(
            preview = preview,
            requestId = UUID.randomUUID().toString(),
        )
    }

    /**
     * Extracts YAML frontmatter fields from a SKILL.md string.
     */
    private fun extractFrontmatter(raw: String): Map<String, String> {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap()

        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex == -1) return emptyMap()

        val frontmatter = trimmed.substring(3, endIndex).trim()
        val result = mutableMapOf<String, String>()
        for (line in frontmatter.lines()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty() || !trimmedLine.contains(':')) continue
            val colonIndex = trimmedLine.indexOf(':')
            val key = trimmedLine.substring(0, colonIndex).trim()
            val value = trimmedLine.substring(colonIndex + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }
}
