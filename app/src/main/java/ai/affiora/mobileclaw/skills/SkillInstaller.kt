package ai.affiora.mobileclaw.skills

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // Dangerous patterns that should NEVER appear in a skill
        private val BLOCKED_PATTERNS = listOf(
            // Data exfiltration
            Regex("""https?://[^\s]*\?(.*=.*){3,}""", RegexOption.IGNORE_CASE),

            // Prompt injection
            Regex("""ignore\s+(previous|above|all)\s+(instructions|rules)""", RegexOption.IGNORE_CASE),
            Regex("""forget\s+(everything|all|your)""", RegexOption.IGNORE_CASE),
            Regex("""<\s*system\s*>""", RegexOption.IGNORE_CASE),

            // Dangerous tool abuse instructions
            Regex("""send\s+(all|every)\s+(contacts?|sms|messages?|data)""", RegexOption.IGNORE_CASE),
            Regex("""(upload|exfiltrate|steal|extract)\s+(data|info|credentials|passwords|tokens)""", RegexOption.IGNORE_CASE),
            Regex("""bypass\s+(security|permission|confirmation|approval)""", RegexOption.IGNORE_CASE),
            Regex("""__confirmed.*true""", RegexOption.IGNORE_CASE),
            Regex("""always\s+approve""", RegexOption.IGNORE_CASE),

            // Code injection
            Regex("""<script""", RegexOption.IGNORE_CASE),
            Regex("""eval\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""Runtime\.exec""", RegexOption.IGNORE_CASE),
        )

        // Suspicious patterns (warn but don't block)
        private val SUSPICIOUS_PATTERNS = listOf(
            Regex("""api[_-]?key""", RegexOption.IGNORE_CASE),
            Regex("""password""", RegexOption.IGNORE_CASE),
            Regex("""token""", RegexOption.IGNORE_CASE),
            Regex("""credential""", RegexOption.IGNORE_CASE),
            Regex("""(delete|remove)\s+(all|every)""", RegexOption.IGNORE_CASE),
            Regex("""banking|bank\s+app|financial""", RegexOption.IGNORE_CASE),
            Regex("""install\s+apk""", RegexOption.IGNORE_CASE),
            Regex("""base64[.\s]*encode""", RegexOption.IGNORE_CASE),
            Regex("""you\s+are\s+now\s+(a|an)\s+""", RegexOption.IGNORE_CASE),
            Regex("""system\s*:\s*""", RegexOption.IGNORE_CASE),
        )

        private const val MAX_SKILL_SIZE = 50_000 // 50KB max
    }

    data class ScanResult(
        val safe: Boolean,
        val blockedReasons: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val riskLevel: RiskLevel = RiskLevel.LOW,
    )

    enum class RiskLevel { LOW, MEDIUM, HIGH, BLOCKED }

    /** Scan a SKILL.md content for security issues */
    fun scanContent(content: String): ScanResult {
        val blocked = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Size check
        if (content.length > MAX_SKILL_SIZE) {
            blocked.add("Skill content exceeds maximum size (${content.length} > $MAX_SKILL_SIZE)")
        }

        // Check blocked patterns
        for (pattern in BLOCKED_PATTERNS) {
            val match = pattern.find(content)
            if (match != null) {
                blocked.add("Blocked pattern found: '${match.value.take(50)}' — ${describeBlockedPattern(pattern)}")
            }
        }

        // Check suspicious patterns
        for (pattern in SUSPICIOUS_PATTERNS) {
            val match = pattern.find(content)
            if (match != null) {
                warnings.add("Suspicious pattern: '${match.value.take(50)}' — review carefully")
            }
        }

        val riskLevel = when {
            blocked.isNotEmpty() -> RiskLevel.BLOCKED
            warnings.size >= 5 -> RiskLevel.HIGH
            warnings.isNotEmpty() -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        return ScanResult(
            safe = blocked.isEmpty(),
            blockedReasons = blocked,
            warnings = warnings,
            riskLevel = riskLevel,
        )
    }

    private fun describeBlockedPattern(pattern: Regex): String {
        val src = pattern.pattern
        return when {
            "ignore" in src -> "Prompt injection attempt"
            "forget" in src -> "Prompt injection attempt"
            "system" in src -> "System prompt override attempt"
            "exfiltrate" in src -> "Data exfiltration attempt"
            "bypass" in src -> "Security bypass attempt"
            "__confirmed" in src -> "Confirmation bypass attempt"
            "script" in src -> "Code injection attempt"
            "base64" in src -> "Data encoding (possible exfiltration)"
            "send" in src -> "Mass data sending attempt"
            else -> "Security policy violation"
        }
    }

    /** Download skill content from a URL */
    suspend fun downloadSkill(url: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "MobileClaw/1.0")

                if (connection.responseCode != 200) {
                    return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
                }

                val content = connection.inputStream.bufferedReader().readText()
                if (content.length > MAX_SKILL_SIZE) {
                    return@withContext Result.failure(Exception("Skill too large: ${content.length} bytes"))
                }

                Result.success(content)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Save a skill to user skills directory */
    fun saveSkill(skillId: String, content: String): Boolean {
        val dir = File(context.filesDir, "skills/user/$skillId")
        dir.mkdirs()
        val file = File(dir, "SKILL.md")
        file.writeText(content)
        return file.exists()
    }

    /** Delete a user skill */
    fun deleteSkill(skillId: String): Boolean {
        val dir = File(context.filesDir, "skills/user/$skillId")
        return dir.deleteRecursively()
    }
}
