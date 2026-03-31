package ai.affiora.mobileclaw.tools

import ai.affiora.mobileclaw.agent.ClaudeApiClient
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubAgentTool(
    private val claudeApiClient: ClaudeApiClient,
    private val userPreferences: UserPreferences,
) : AndroidTool {

    override val name: String = "agent"

    override val description: String =
        "Spawn a sub-agent to handle a subtask. The sub-agent runs independently and returns its result. Use for research, analysis, or tasks that can be delegated."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
            add(JsonPrimitive("task"))
        })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray { add(JsonPrimitive("run")) })
                put("description", JsonPrimitive("Action to perform. Currently only 'run'."))
            })
            put("task", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The prompt/task for the sub-agent to complete."))
            })
            put("context", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional additional context for the sub-agent."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")
        if (action != "run") return ToolResult.Error("Unknown action: $action. Only 'run' is supported.")

        val task = params["task"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: task")
        val context = params["context"]?.jsonPrimitive?.content ?: ""

        return withContext(Dispatchers.IO) {
            try {
                val model = userPreferences.selectedModel.first()
                val systemPrompt =
                    "You are a sub-agent. Complete the following task concisely and return the result. " +
                        "Do not ask questions — just do the work."
                val userMessage = if (context.isNotBlank()) "$task\n\nContext: $context" else task

                val request = ClaudeRequest(
                    model = model,
                    messages = listOf(ClaudeMessage("user", ClaudeContent.Text(userMessage))),
                    system = systemPrompt,
                    maxTokens = 2048,
                    tools = null,
                )

                val response = claudeApiClient.sendMessage(request)
                val text = response.content
                    .filterIsInstance<ContentBlock.TextBlock>()
                    .joinToString("") { it.text }

                if (text.isBlank()) ToolResult.Error("Sub-agent returned empty response")
                else ToolResult.Success(text.take(100_000))
            } catch (e: Exception) {
                ToolResult.Error("Sub-agent failed: ${e.message}")
            }
        }
    }
}
