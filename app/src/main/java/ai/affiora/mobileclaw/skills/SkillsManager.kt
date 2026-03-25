package ai.affiora.mobileclaw.skills

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val toolsRequired: List<String>,
    val content: String,
)

@Singleton
class SkillsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
) {

    companion object {
        private const val SKILLS_BASE_PATH = "skills"
        private const val SKILL_FILE = "SKILL.md"
        private const val USER_SKILLS_PATH = "skills/user"
        private val CATEGORIES = listOf("built-in", "vertical", "user")
    }

    fun loadSkill(skillId: String): Skill? {
        // Check asset-based skills first (built-in, vertical, user in assets)
        for (category in CATEGORIES) {
            val path = "$SKILLS_BASE_PATH/$category/$skillId/$SKILL_FILE"
            val raw = readAsset(path) ?: continue
            return parseSkillMd(skillId, raw)
        }
        // Check user-created skills in internal storage
        val userSkillFile = java.io.File(context.filesDir, "$USER_SKILLS_PATH/$skillId/$SKILL_FILE")
        if (userSkillFile.exists()) {
            val raw = userSkillFile.readText()
            return parseSkillMd(skillId, raw)
        }
        return null
    }

    suspend fun getActiveSkills(): List<Skill> {
        var activeIds = userPreferences.activeSkillIds.first()
        if (activeIds.isEmpty()) {
            // Auto-enable built-in skills on fresh install
            val builtInIds = getBuiltInSkillIds()
            if (builtInIds.isNotEmpty()) {
                userPreferences.setActiveSkillIds(builtInIds)
                activeIds = builtInIds
            }
        }
        return getAllSkills().filter { it.id in activeIds }
    }

    private fun getBuiltInSkillIds(): Set<String> {
        val path = "$SKILLS_BASE_PATH/built-in"
        return listAssetDir(path).toSet()
    }

    fun getAllSkills(): List<Skill> {
        val skills = mutableListOf<Skill>()
        val seenIds = mutableSetOf<String>()

        // Load asset-based skills (built-in, vertical, user in assets)
        for (category in CATEGORIES) {
            val categoryPath = "$SKILLS_BASE_PATH/$category"
            val skillDirs = listAssetDir(categoryPath)
            for (dir in skillDirs) {
                val filePath = "$categoryPath/$dir/$SKILL_FILE"
                val raw = readAsset(filePath) ?: continue
                val skill = parseSkillMd(dir, raw) ?: continue
                skills.add(skill)
                seenIds.add(dir)
            }
        }

        // Load user-created skills from internal storage
        val userSkillsDir = java.io.File(context.filesDir, USER_SKILLS_PATH)
        if (userSkillsDir.exists() && userSkillsDir.isDirectory) {
            val dirs = userSkillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (dir in dirs) {
                if (dir.name in seenIds) continue
                val skillFile = java.io.File(dir, SKILL_FILE)
                if (!skillFile.exists()) continue
                val raw = skillFile.readText()
                val skill = parseSkillMd(dir.name, raw) ?: continue
                skills.add(skill)
            }
        }

        return skills
    }

    suspend fun enableSkill(skillId: String) {
        val current = userPreferences.activeSkillIds.first().toMutableSet()
        current.add(skillId)
        userPreferences.setActiveSkillIds(current)
    }

    suspend fun disableSkill(skillId: String) {
        val current = userPreferences.activeSkillIds.first().toMutableSet()
        current.remove(skillId)
        userPreferences.setActiveSkillIds(current)
    }

    internal fun parseSkillMd(skillId: String, raw: String): Skill? {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("---")) return null

        val endIndex = trimmed.indexOf("---", startIndex = 3)
        if (endIndex == -1) return null

        val frontmatter = trimmed.substring(3, endIndex).trim()
        val body = trimmed.substring(endIndex + 3).trim()

        val fields = parseFrontmatter(frontmatter)

        val name = fields["name"] ?: return null
        val description = fields["description"] ?: ""
        val version = fields["version"] ?: "1.0"
        val author = fields["author"] ?: ""
        val toolsRequired = parseToolsRequired(fields["tools_required"] ?: "")

        return Skill(
            id = skillId,
            name = name,
            description = description,
            version = version,
            author = author,
            toolsRequired = toolsRequired,
            content = body,
        )
    }

    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
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

    private fun parseToolsRequired(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()

        // Handle YAML list format: [item1, item2] or comma-separated
        val cleaned = raw
            .removePrefix("[")
            .removeSuffix("]")

        return cleaned.split(",")
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.isNotEmpty() }
    }

    private fun readAsset(path: String): String? {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun listAssetDir(path: String): List<String> {
        return try {
            context.assets.list(path)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
