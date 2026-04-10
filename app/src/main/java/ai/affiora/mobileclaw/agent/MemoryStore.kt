package ai.affiora.mobileclaw.agent

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenClaw-style memory storage:
 * - `MEMORY.md` for durable facts (auto-loaded every session)
 * - `daily/YYYY-MM-DD.md` for running daily notes
 * - `DREAMS.md` for promotion log / reflection diary
 *
 * Migrates from legacy `memories.json` on first use.
 */
@Singleton
class MemoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "MemoryStore"
        private const val MAX_MEMORY_CHARS = 8_000 // cap what's injected into prompt
        private const val MAX_DAILY_CHARS = 2_000
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val memoryDir: File by lazy {
        File(context.filesDir, "memory").also { it.mkdirs() }
    }
    private val dailyDir: File by lazy {
        File(memoryDir, "daily").also { it.mkdirs() }
    }

    val memoryFile: File get() = File(memoryDir, "MEMORY.md")
    val dreamsFile: File get() = File(memoryDir, "DREAMS.md")

    private val legacyFile: File get() = File(memoryDir, "memories.json")

    init {
        migrateLegacyIfNeeded()
    }

    // ── Durable facts (MEMORY.md) ─────────────────────────────────────────

    suspend fun appendDurableFact(fact: String, tags: List<String> = emptyList()): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = memoryFile
                if (!file.exists()) file.writeText("# Memory\n\nDurable facts and preferences.\n\n")
                val tagLine = if (tags.isNotEmpty()) " `${tags.joinToString(",")}`" else ""
                val timestamp = LocalDate.now().format(DATE_FORMAT)
                file.appendText("- [$timestamp]$tagLine $fact\n")
            }
        }

    suspend fun readDurableFacts(): String = withContext(Dispatchers.IO) {
        if (!memoryFile.exists()) return@withContext ""
        val text = memoryFile.readText()
        if (text.length <= MAX_MEMORY_CHARS) text
        else "...[truncated]...\n" + text.takeLast(MAX_MEMORY_CHARS)
    }

    suspend fun deleteDurableFactContaining(substring: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!memoryFile.exists()) return@withLock false
                val lines = memoryFile.readLines()
                val filtered = lines.filterNot { line ->
                    line.trim().startsWith("-") && line.contains(substring, ignoreCase = true)
                }
                if (filtered.size == lines.size) return@withLock false
                memoryFile.writeText(filtered.joinToString("\n") + "\n")
                true
            }
        }

    suspend fun listDurableFacts(): List<String> = withContext(Dispatchers.IO) {
        if (!memoryFile.exists()) return@withContext emptyList()
        memoryFile.readLines().filter { it.trim().startsWith("- ") }
    }

    // ── Daily notes ───────────────────────────────────────────────────────

    suspend fun appendDailyNote(note: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val today = LocalDate.now().format(DATE_FORMAT)
            val file = File(dailyDir, "$today.md")
            if (!file.exists()) file.writeText("# Daily Notes — $today\n\n")
            val time = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            file.appendText("- [$time] $note\n")
        }
    }

    /** Read today's + yesterday's notes. Returns pair of (today, yesterday) content. */
    suspend fun readRecentDailyNotes(): String = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val sb = StringBuilder()
        for (date in listOf(today, yesterday)) {
            val file = File(dailyDir, "${date.format(DATE_FORMAT)}.md")
            if (file.exists()) {
                sb.append(file.readText())
                sb.append("\n")
            }
        }
        val text = sb.toString()
        if (text.length <= MAX_DAILY_CHARS) text
        else text.takeLast(MAX_DAILY_CHARS)
    }

    // ── Search across all memory files ────────────────────────────────────

    suspend fun search(query: String): List<SearchMatch> = withContext(Dispatchers.IO) {
        val matches = mutableListOf<SearchMatch>()
        val q = query.lowercase()

        // Durable facts
        if (memoryFile.exists()) {
            memoryFile.readLines().forEach { line ->
                if (line.lowercase().contains(q)) {
                    matches.add(SearchMatch("MEMORY.md", line.trim(), 1.0))
                }
            }
        }

        // Daily notes
        dailyDir.listFiles()?.sortedByDescending { it.name }?.take(7)?.forEach { file ->
            file.readLines().forEach { line ->
                if (line.lowercase().contains(q)) {
                    matches.add(SearchMatch("daily/${file.name}", line.trim(), 0.7))
                }
            }
        }

        // Dreams diary
        if (dreamsFile.exists()) {
            dreamsFile.readLines().forEach { line ->
                if (line.lowercase().contains(q)) {
                    matches.add(SearchMatch("DREAMS.md", line.trim(), 0.5))
                }
            }
        }

        matches.sortedByDescending { it.score }
    }

    // ── Dreams diary ──────────────────────────────────────────────────────

    suspend fun readDreamsDiary(): String = withContext(Dispatchers.IO) {
        if (!dreamsFile.exists()) "No dreams recorded yet." else dreamsFile.readText()
    }

    suspend fun appendDreamsEntry(section: String, content: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!dreamsFile.exists()) dreamsFile.writeText("# Dreams Diary\n\n")
                val timestamp = LocalDate.now().format(DATE_FORMAT)
                dreamsFile.appendText("\n## $section — $timestamp\n\n$content\n")
            }
        }

    // ── Legacy migration ──────────────────────────────────────────────────

    private fun migrateLegacyIfNeeded() {
        if (!legacyFile.exists()) return
        if (memoryFile.exists()) {
            // Already migrated — just delete legacy
            legacyFile.delete()
            return
        }

        try {
            val entries = json.decodeFromString<List<LegacyMemoryEntry>>(legacyFile.readText())
            val sb = StringBuilder("# Memory\n\nDurable facts and preferences (migrated from v1).\n\n")
            val timestamp = LocalDate.now().format(DATE_FORMAT)
            for (entry in entries) {
                val tags = if (entry.tags.isNotEmpty()) " `${entry.tags.joinToString(",")}`" else ""
                sb.append("- [$timestamp]$tags **${entry.key}**: ${entry.content}\n")
            }
            memoryFile.writeText(sb.toString())
            legacyFile.delete()
            Log.i(TAG, "Migrated ${entries.size} legacy memory entries to MEMORY.md")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy memory file", e)
        }
    }

    @Serializable
    private data class LegacyMemoryEntry(
        val key: String,
        val content: String,
        val tags: List<String> = emptyList(),
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
    )
}

data class SearchMatch(
    val source: String,
    val line: String,
    val score: Double,
)
