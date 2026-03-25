package ai.affiora.mobileclaw.skills

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillsManager: SkillsManager,
) {

    companion object {
        private const val SKILLS_BASE_PATH = "skills"
        private val CATEGORIES = listOf("built-in", "vertical", "user")
        private val CATEGORY_LABELS = mapOf(
            "built-in" to "Built-in",
            "vertical" to "Vertical",
            "user" to "User",
        )
    }

    fun getSkillsGrouped(): Map<String, List<Skill>> {
        val result = mutableMapOf<String, MutableList<Skill>>()
        val seenIds = mutableSetOf<String>()

        for (category in CATEGORIES) {
            val label = CATEGORY_LABELS[category] ?: category
            val categoryPath = "$SKILLS_BASE_PATH/$category"
            val skillDirs = try {
                context.assets.list(categoryPath)?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            for (dir in skillDirs) {
                val filePath = "$categoryPath/$dir/SKILL.md"
                val raw = try {
                    context.assets.open(filePath).bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    continue
                }
                val skill = skillsManager.parseSkillMd(dir, raw) ?: continue
                result.getOrPut(label) { mutableListOf() }.add(skill)
                seenIds.add(dir)
            }
        }

        // Include user-installed skills from internal storage
        val userSkillsDir = java.io.File(context.filesDir, "skills/user")
        if (userSkillsDir.exists() && userSkillsDir.isDirectory) {
            val dirs = userSkillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (dir in dirs) {
                if (dir.name in seenIds) continue
                val skillFile = java.io.File(dir, "SKILL.md")
                if (!skillFile.exists()) continue
                val raw = skillFile.readText()
                val skill = skillsManager.parseSkillMd(dir.name, raw) ?: continue
                result.getOrPut("Installed") { mutableListOf() }.add(skill)
            }
        }

        return result
    }

    fun getSkillById(id: String): Skill? {
        return skillsManager.loadSkill(id)
    }
}
