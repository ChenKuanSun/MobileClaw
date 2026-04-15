package ai.affiora.mobileclaw.agent.backend

import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.ClaudeApiException
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.model.ContentBlock
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenAiCompatibleBackend(
    private val httpClient: HttpClient,
    private val jsonSerializer: Json,
) : AiBackend {

    override suspend fun send(
        request: ClaudeRequest,
        apiKey: String,
        provider: AiProvider,
        onTextDelta: ((String) -> Unit)?,
        onThinkingStarted: (() -> Unit)?,
        baseUrlOverride: String?,
    ): ClaudeResponse {
        val requestBody = buildOpenAiRequestJson(request)
        val effectiveBase = baseUrlOverride?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?: provider.baseUrl
        if (effectiveBase.isBlank()) {
            throw ClaudeApiException(0, "Base URL not configured for ${provider.displayName}.")
        }
        val url = "$effectiveBase${provider.chatCompletionsPath}"

        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(requestBody)
        }

        if (response.status.value !in 200..299) {
            throw ClaudeApiException(response.status.value, response.bodyAsText())
        }

        val openAiResponse = jsonSerializer.decodeFromString<JsonObject>(response.bodyAsText())
        return convertOpenAiToClaudeResponse(openAiResponse)
    }

    private fun buildOpenAiRequestJson(request: ClaudeRequest): JsonObject {
        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("stream", false)

            put("messages", buildJsonArray {
                if (request.system != null) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    })
                }
                for (message in request.messages) {
                    add(buildOpenAiMessageJson(message))
                }
            })

            if (request.tools != null && request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in request.tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.inputSchema)
                            })
                        })
                    }
                })
            }
        }
    }

    private fun buildOpenAiMessageJson(
        message: ai.affiora.mobileclaw.data.model.ClaudeMessage,
    ): JsonObject {
        return buildJsonObject {
            put("role", message.role)
            when (val content = message.content) {
                is ClaudeContent.Text -> put("content", content.text)
                is ClaudeContent.ToolResult -> {
                    put("role", "tool")
                    put("tool_call_id", content.toolUseId)
                    put("content", content.content)
                }
                is ClaudeContent.ContentList -> {
                    val textParts = content.blocks.filterIsInstance<ContentBlock.TextBlock>()
                    val toolCalls = content.blocks.filterIsInstance<ContentBlock.ToolUseBlock>()
                    val toolResults = content.blocks.filterIsInstance<ContentBlock.ToolResultBlock>()

                    if (toolResults.isNotEmpty()) {
                        put("role", "tool")
                        put("tool_call_id", toolResults.first().toolUseId)
                        put("content", toolResults.first().content)
                    } else {
                        if (textParts.isNotEmpty()) {
                            put("content", textParts.joinToString("\n") { it.text })
                        }
                        if (toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                for (tc in toolCalls) {
                                    add(buildJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", tc.name)
                                            put("arguments", tc.input.toString())
                                        })
                                    })
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    private fun convertOpenAiToClaudeResponse(json: JsonObject): ClaudeResponse {
        val choices = json["choices"] as? JsonArray ?: error("No choices")
        val choice = choices[0] as JsonObject
        val message = choice["message"] as JsonObject
        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.content

        val contentBlocks = mutableListOf<ContentBlock>()

        // JSON null becomes JsonNull (subclass of JsonPrimitive) — its .content
        // returns the literal string "null", not kotlin null. Filter it out.
        val textContent = (message["content"] as? JsonPrimitive)
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.content
        if (!textContent.isNullOrBlank()) {
            contentBlocks.add(ContentBlock.TextBlock(textContent))
        }

        val toolCalls = message["tool_calls"] as? JsonArray
        toolCalls?.forEach { tc ->
            val tcObj = tc as JsonObject
            val fn = tcObj["function"] as JsonObject
            contentBlocks.add(ContentBlock.ToolUseBlock(
                id = (tcObj["id"] as JsonPrimitive).content,
                name = (fn["name"] as JsonPrimitive).content,
                input = jsonSerializer.decodeFromString<JsonObject>(
                    (fn["arguments"] as JsonPrimitive).content
                ),
            ))
        }

        // Per OpenClaw #66167: reasoning models (MiniMax M2.x, DeepSeek R1, GLM
        // thinking variants, etc.) return chain-of-thought in a separate
        // `reasoning_content` field alongside `content`. Previously we silently
        // dropped it, causing reasoning-only turns (where content is empty but
        // reasoning_content is populated) to produce a blank chat bubble.
        val reasoningContent = (message["reasoning_content"] as? JsonPrimitive)
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.content
            ?.takeIf { it.isNotBlank() }

        // Reasoning-only recovery: if we have no visible output at all but the
        // model did think, surface the reasoning as the visible answer so the
        // user at least sees what the model was working on instead of a blank
        // bubble. This is a graceful degradation — the ideal is a bounded
        // continuation call (OpenClaw's approach) but requires a second round
        // trip; this gives the user something useful for now.
        if (contentBlocks.isEmpty() && reasoningContent != null) {
            contentBlocks.add(ContentBlock.TextBlock(reasoningContent))
        }

        val stopReason = when (finishReason) {
            "stop" -> "end_turn"
            "tool_calls" -> "tool_use"
            else -> finishReason ?: "end_turn"
        }

        return ClaudeResponse(
            id = (json["id"] as? JsonPrimitive)?.content ?: "",
            model = (json["model"] as? JsonPrimitive)?.content ?: "",
            role = "assistant",
            content = contentBlocks,
            stopReason = stopReason,
            thinkingText = reasoningContent,
        )
    }
}
