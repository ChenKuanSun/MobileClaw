package ai.affiora.mobileclaw.data.model

import ai.affiora.mobileclaw.tools.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

sealed interface AgentEvent {

    data class Text(val text: String) : AgentEvent

    /** Partial text delta for streaming display. */
    data class TextDelta(val delta: String) : AgentEvent

    /** AI is thinking (extended thinking mode). */
    data class Thinking(val thinking: String) : AgentEvent

    data class ToolCalling(
        val toolName: String,
        val input: Map<String, JsonElement>,
    ) : AgentEvent

    data class ToolResultEvent(
        val toolName: String,
        val result: ToolResult,
    ) : AgentEvent

    data class Error(val message: String) : AgentEvent

    data class NeedsConfirmation(
        val toolName: String,
        val preview: String,
        val requestId: String,
    ) : AgentEvent

    /** Raw assistant turn from Claude API — stored in DB for faithful history reconstruction. */
    data class RawAssistantTurn(val message: ClaudeMessage) : AgentEvent

    /** Raw tool-result turn sent back to Claude — stored in DB for faithful history reconstruction. */
    data class RawToolResultTurn(val message: ClaudeMessage) : AgentEvent

    /** Token usage from a single API call. */
    data class Usage(val inputTokens: Int, val outputTokens: Int, val model: String) : AgentEvent

    /** Internal event: streaming API call completed with final response. Not emitted to UI. */
    data class StreamComplete(val response: ClaudeResponse) : AgentEvent
}

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL_RESULT,
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val toolName: String? = null,
    val toolInput: String? = null,
    val timestamp: Long,
    val conversationId: String,
    val claudeMessageJson: String? = null,
    val actions: List<MessageAction>? = null,
)

@Serializable
data class MessageAction(
    val label: String,
    val type: ActionType,
    val value: String,
)

@Serializable
enum class ActionType {
    SEND_MESSAGE,
    SWITCH_MODEL,
    OPEN_URL,
    COPY,
}

// ── Tool Activity (inline tool execution markers) ────────────────────────────

/**
 * Represents a single tool invocation embedded within an assistant message.
 * Serialized as special markers inside message content so no DB schema change is needed.
 *
 * Format in content:
 *   ‹tool:toolName|input|status|result›
 *
 * status: "pending", "success", "error"
 */
@Serializable
data class ToolActivity(
    val toolName: String,
    val input: String,
    val result: String? = null,
    val isError: Boolean = false,
    val isPending: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toMarker(): String {
        val status = when {
            isPending -> "pending"
            isError -> "error"
            else -> "success"
        }
        val safeInput = input.replace("›", ">").replace("‹", "<").replace("|", "\\|")
        val safeResult = (result ?: "").replace("›", ">").replace("‹", "<").replace("|", "\\|")
        return "‹tool:$toolName|$safeInput|$status|$safeResult›"
    }

    companion object {
        private val MARKER_REGEX = Regex("‹tool:([^|]+)\\|([^|]*)\\|([^|]*)\\|([^›]*)›")

        fun parseMarkers(text: String): List<Pair<IntRange, ToolActivity>> {
            return MARKER_REGEX.findAll(text).map { match ->
                val toolName = match.groupValues[1]
                val input = match.groupValues[2].replace("\\|", "|").replace("<", "‹").replace(">", "›")
                val status = match.groupValues[3]
                val result = match.groupValues[4].replace("\\|", "|").replace("<", "‹").replace(">", "›")
                    .ifEmpty { null }
                val activity = ToolActivity(
                    toolName = toolName,
                    input = input,
                    result = result,
                    isError = status == "error",
                    isPending = status == "pending",
                )
                match.range to activity
            }.toList()
        }

        /** Split content into text segments and tool activities. */
        fun splitContent(text: String): List<ContentSegment> {
            val markers = parseMarkers(text)
            if (markers.isEmpty()) return listOf(ContentSegment.Text(text))

            val segments = mutableListOf<ContentSegment>()
            var lastEnd = 0
            for ((range, activity) in markers) {
                if (range.first > lastEnd) {
                    val before = text.substring(lastEnd, range.first).trim()
                    if (before.isNotEmpty()) segments.add(ContentSegment.Text(before))
                }
                segments.add(ContentSegment.Tool(activity))
                lastEnd = range.last + 1
            }
            if (lastEnd < text.length) {
                val after = text.substring(lastEnd).trim()
                if (after.isNotEmpty()) segments.add(ContentSegment.Text(after))
            }
            return segments
        }
    }
}

sealed interface ContentSegment {
    data class Text(val text: String) : ContentSegment
    data class Tool(val activity: ToolActivity) : ContentSegment
}

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
enum class ToolStatus {
    SUCCESS,
    ERROR,
    CONFIRMED,
    CANCELLED,
}

@Serializable
data class ToolExecutionRecord(
    val id: String,
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolStatus,
    val timestamp: Long,
    val conversationId: String,
)

@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val publicKey: String,
    val lastSeen: Long,
    val isOnline: Boolean,
)
