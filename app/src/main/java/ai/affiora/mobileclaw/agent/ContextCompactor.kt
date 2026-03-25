package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextCompactor @Inject constructor(
    private val apiClient: ClaudeApiClient,
    private val userPreferences: UserPreferences,
) {

    /**
     * Compacts a conversation history by sending it to the AI for summarization.
     * Returns a minimal message list containing just the summary, dramatically
     * reducing token count while preserving key context.
     */
    suspend fun compact(messages: List<ClaudeMessage>, systemPrompt: String): List<ClaudeMessage> {
        if (messages.isEmpty()) return emptyList()

        val model = userPreferences.selectedModel.first()

        // Build a text representation of the conversation for summarization
        val conversationText = buildString {
            for (msg in messages) {
                val role = msg.role.replaceFirstChar { it.uppercase() }
                when (val content = msg.content) {
                    is ClaudeContent.Text -> {
                        appendLine("$role: ${content.text}")
                    }
                    is ClaudeContent.ToolResult -> {
                        appendLine("$role (tool_result for ${content.toolUseId}): ${content.content}")
                    }
                    is ClaudeContent.ContentList -> {
                        for (block in content.blocks) {
                            when (block) {
                                is ContentBlock.TextBlock -> appendLine("$role: ${block.text}")
                                is ContentBlock.ToolUseBlock -> appendLine("$role [tool_use: ${block.name}]: ${block.input}")
                                is ContentBlock.ToolResultBlock -> appendLine("$role [tool_result]: ${block.content}")
                                is ContentBlock.ImageBlock -> appendLine("$role [image: ${block.source.mediaType}]")
                            }
                        }
                    }
                }
            }
        }

        val summaryRequest = ClaudeRequest(
            model = model,
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.Text(
                        "Summarize the following conversation concisely, preserving key decisions, " +
                            "code changes, and context needed to continue. Format as a brief conversation summary.\n\n" +
                            conversationText
                    ),
                ),
            ),
            system = "You are a conversation summarizer. Produce a concise summary that preserves " +
                "all important context: decisions made, actions taken, tool results, errors encountered, " +
                "and any state the assistant needs to continue helping the user. Be brief but complete.",
            tools = null,
            maxTokens = 2048,
        )

        val response = apiClient.sendMessage(summaryRequest)

        val summaryText = response.content
            .filterIsInstance<ContentBlock.TextBlock>()
            .joinToString("\n") { it.text }
            .ifBlank { "No summary available." }

        return listOf(
            ClaudeMessage(
                role = "user",
                content = ClaudeContent.Text("Previous conversation summary: $summaryText"),
            ),
        )
    }
}
