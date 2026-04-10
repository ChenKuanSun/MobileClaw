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

class GoogleBackend(
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
        val url = "${provider.baseUrl}/v1beta/models/${request.model}:generateContent"
        val requestBody = buildGoogleRequestJson(request)

        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            header(provider.authHeader, apiKey)
            setBody(requestBody)
        }

        if (response.status.value !in 200..299) {
            throw ClaudeApiException(response.status.value, response.bodyAsText())
        }

        val googleResponse = jsonSerializer.decodeFromString<JsonObject>(response.bodyAsText())
        return convertGoogleToClaudeResponse(googleResponse)
    }

    private fun buildGoogleRequestJson(request: ClaudeRequest): JsonObject {
        return buildJsonObject {
            put("contents", buildJsonArray {
                if (request.system != null) {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", request.system) })
                        })
                    })
                    add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", "Understood.") })
                        })
                    })
                }
                for (message in request.messages) {
                    add(buildJsonObject {
                        put("role", if (message.role == "assistant") "model" else "user")
                        put("parts", buildJsonArray {
                            when (val content = message.content) {
                                is ClaudeContent.Text -> add(buildJsonObject { put("text", content.text) })
                                else -> add(buildJsonObject { put("text", content.toString()) })
                            }
                        })
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", request.maxTokens)
            })

            if (request.tools != null && request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            for (tool in request.tools) {
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.inputSchema)
                                })
                            }
                        })
                    })
                })
            }
        }
    }

    private fun convertGoogleToClaudeResponse(json: JsonObject): ClaudeResponse {
        val candidates = json["candidates"] as? JsonArray ?: error("No candidates")
        val candidate = candidates[0] as JsonObject
        val content = candidate["content"] as JsonObject
        val parts = content["parts"] as JsonArray

        val contentBlocks = mutableListOf<ContentBlock>()
        var hasToolUse = false

        for (part in parts) {
            val partObj = part as JsonObject
            val text = (partObj["text"] as? JsonPrimitive)?.content
            val functionCall = partObj["functionCall"] as? JsonObject

            if (text != null) {
                contentBlocks.add(ContentBlock.TextBlock(text))
            }
            if (functionCall != null) {
                hasToolUse = true
                contentBlocks.add(ContentBlock.ToolUseBlock(
                    id = "gemini-${System.currentTimeMillis()}",
                    name = (functionCall["name"] as JsonPrimitive).content,
                    input = functionCall["args"] as? JsonObject ?: buildJsonObject {},
                ))
            }
        }

        return ClaudeResponse(
            id = "gemini-${System.currentTimeMillis()}",
            model = "",
            role = "assistant",
            content = contentBlocks,
            stopReason = if (hasToolUse) "tool_use" else "end_turn",
        )
    }
}
