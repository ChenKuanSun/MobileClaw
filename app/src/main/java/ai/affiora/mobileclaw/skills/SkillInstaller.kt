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
        // Dangerous patterns that should NEVER appear in a skill.
        //
        // Threat model: skill markdown is loaded into the system prompt. The only
        // realistic attack is PROMPT INJECTION — adversarial phrasing that coerces
        // the model to override instructions or exfiltrate data. Code-execution
        // patterns (eval, Runtime.exec, <script>) are demoted to SUSPICIOUS since
        // we never execute skill code — they're just markdown text in the prompt.
        //
        // Patterns here must be narrowly targeted at adversarial intent so that
        // legitimate documentation (prompt engineering tutorials, data-processing
        // skills, agent internals) doesn't false-positive.
        private val BLOCKED_PATTERNS = listOf(
            // URL with suspicious query params (data-in-URL exfiltration channel)
            Regex("""https?://[^\s]*\?(.*=.*){3,}""", RegexOption.IGNORE_CASE),

            // Prompt injection — require the full adversarial phrase
            Regex("""ignore\s+(previous|above|all)\s+(instructions|rules)""", RegexOption.IGNORE_CASE),
            Regex("""forget\s+(everything|all|your)\s+(instructions|rules|context|prior)""", RegexOption.IGNORE_CASE),
            // NOTE: we intentionally do NOT block bare `<system>` mentions — many
            // legitimate prompt-engineering tutorials and chat-template docs include
            // `<system>You are a helpful assistant</system>` examples. Adversarial
            // use would be `<system>ignore previous instructions</system>` which is
            // already caught by the `ignore...instructions` rule above.

            // Dangerous tool-abuse instructions — require explicit send-to-target
            Regex("""send\s+(all|every)\s+(contacts?|sms|messages?)\s+to""", RegexOption.IGNORE_CASE),
            // Keep only unambiguously adversarial verbs (exfiltrate/steal).
            // `.{0,30}` between verb and target tolerates natural language
            // articles ("all", "the", "every") without becoming an any-match.
            // "extract data" is deliberately not here — it's too common in
            // legitimate web scraping / OCR / parsing skills.
            Regex("""(exfiltrate|steal)\b.{0,30}\b(data|info|credentials|passwords|tokens)""", RegexOption.IGNORE_CASE),
            // Bypass must target a concrete mechanism (check/system/flag) not
            // just a generic noun — "don't bypass security" in docs shouldn't match
            Regex("""bypass\s+(security|permission|confirmation|approval)\s+(check|system|flag|mechanism)""", RegexOption.IGNORE_CASE),
            Regex("""always\s+approve""", RegexOption.IGNORE_CASE),

            // Script injection — require a script TAG boundary, not mid-word
            Regex("""<\s*script[>\s/]""", RegexOption.IGNORE_CASE),
        )

        // Suspicious patterns (warn but don't block). Hitting >=5 of these bumps
        // risk to HIGH in the install preview but still allows installation.
        private val SUSPICIOUS_PATTERNS = listOf(
            Regex("""api[_-]?key""", RegexOption.IGNORE_CASE),
            Regex("""password""", RegexOption.IGNORE_CASE),
            Regex("""\btoken\b""", RegexOption.IGNORE_CASE),
            Regex("""credential""", RegexOption.IGNORE_CASE),
            Regex("""(delete|remove)\s+(all|every)""", RegexOption.IGNORE_CASE),
            Regex("""banking|bank\s+app|financial""", RegexOption.IGNORE_CASE),
            Regex("""install\s+apk""", RegexOption.IGNORE_CASE),
            Regex("""base64[.\s]*encode""", RegexOption.IGNORE_CASE),
            Regex("""you\s+are\s+now\s+(a|an)\s+""", RegexOption.IGNORE_CASE),
            Regex("""^system\s*:""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
            // Demoted from BLOCKED — harmless in markdown text, only dangerous if executed
            Regex("""eval\s*\(""", RegexOption.IGNORE_CASE),
            Regex("""Runtime\.exec""", RegexOption.IGNORE_CASE),
            // Demoted — __confirmed is our internal tool parameter; a skill
            // documenting how tools work legitimately mentions it
            Regex("""__confirmed""", RegexOption.IGNORE_CASE),
            // Demoted — extracting data is common legitimate wording
            Regex("""extract\s+(credentials|passwords|tokens)""", RegexOption.IGNORE_CASE),
            Regex("""upload\s+(data|info|credentials|passwords|tokens)""", RegexOption.IGNORE_CASE),
        )

        private const val MAX_SKILL_SIZE = 500_000 // 500KB max — accommodates skills with embedded examples / large prompts
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
            "exfiltrate" in src || "steal" in src -> "Data exfiltration attempt"
            "bypass" in src -> "Security bypass attempt"
            "always\\s+approve" in src || "always approve" in src -> "Auto-approval bypass attempt"
            "script" in src -> "Script injection attempt"
            "send" in src -> "Mass data sending attempt"
            "https?" in src -> "URL with suspicious query parameters"
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
