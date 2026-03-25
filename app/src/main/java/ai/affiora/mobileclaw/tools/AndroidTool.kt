package ai.affiora.mobileclaw.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Core interface that ALL Android tools implement.
 * Each tool exposes a JSON Schema so Claude knows how to call it.
 */
interface AndroidTool {
    /** Unique tool name sent to Claude (e.g. "sms", "call_log"). */
    val name: String

    /** Human-readable description for Claude's tool definition. */
    val description: String

    /** JSON Schema describing the parameters Claude can pass. */
    val parameters: JsonObject

    /** Execute the tool with the given parameters from Claude. */
    suspend fun execute(params: Map<String, JsonElement>): ToolResult
}

sealed class ToolResult {
    /** Unified string output for display/logging, regardless of result type. */
    abstract val output: String

    data class Success(val data: String) : ToolResult() {
        override val output: String get() = data
    }
    data class Error(val message: String) : ToolResult() {
        override val output: String get() = "Error: $message"
    }
    data class NeedsConfirmation(val preview: String, val requestId: String) : ToolResult() {
        override val output: String get() = preview
    }
}
