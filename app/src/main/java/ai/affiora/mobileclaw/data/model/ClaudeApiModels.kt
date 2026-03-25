package ai.affiora.mobileclaw.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ClaudeRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    val system: String? = null,
    val tools: List<ClaudeTool>? = null,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val stream: Boolean = false,
)

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: ClaudeContent,
)

@Serializable
sealed interface ClaudeContent {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ClaudeContent

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id")
        val toolUseId: String,
        val content: String,
    ) : ClaudeContent

    @Serializable
    @SerialName("content_list")
    data class ContentList(val blocks: List<ContentBlock>) : ClaudeContent
}

@Serializable
sealed interface ContentBlock {

    @Serializable
    @SerialName("text")
    data class TextBlock(val text: String) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUseBlock(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResultBlock(
        @SerialName("tool_use_id")
        val toolUseId: String,
        val content: String,
    ) : ContentBlock

    @Serializable
    @SerialName("image")
    data class ImageBlock(
        val source: ImageSource,
    ) : ContentBlock
}

@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type")
    val mediaType: String,
    val data: String,
)

@Serializable
data class ClaudeTool(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: JsonObject,
)

@Serializable
data class ClaudeResponse(
    val id: String,
    val model: String,
    val role: String,
    val content: List<ContentBlock>,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Thinking text extracted from thinking blocks (not serialized to API). */
    val thinkingText: String? = null,
)
