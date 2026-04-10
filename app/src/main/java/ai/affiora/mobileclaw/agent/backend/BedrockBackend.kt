package ai.affiora.mobileclaw.agent.backend

import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.BedrockSigner
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

class BedrockBackend(
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
        val creds = parseBedrockCreds(apiKey)
        val modelId = request.model

        // Honor the user's configured region. AWS Bedrock cross-region inference profiles
        // (us./eu./apac./global. prefixes) accept any source region within their geography;
        // routing happens server-side. Don't override the user's choice.
        val region = creds.region
        val url = "https://bedrock-runtime.$region.amazonaws.com/model/$modelId/converse"

        val body = buildBedrockConverseRequest(request)
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)

        val signed = BedrockSigner.sign(
            BedrockSigner.AwsCreds(creds.accessKey, creds.secretKey, region, creds.sessionToken),
            url,
            bodyBytes,
        )

        val response = httpClient.post(url) {
            for ((k, v) in signed.headers) {
                header(k, v)
            }
            contentType(ContentType.Application.Json)
            setBody(bodyBytes)
        }

        if (response.status.value !in 200..299) {
            throw ClaudeApiException(response.status.value, response.bodyAsText())
        }

        val respObj = jsonSerializer.decodeFromString<JsonObject>(response.bodyAsText())
        return convertBedrockToClaudeResponse(respObj, modelId)
    }

    private data class BedrockCreds(
        val accessKey: String,
        val secretKey: String,
        val region: String,
        val sessionToken: String? = null,
    )

    private fun parseBedrockCreds(json: String): BedrockCreds {
        val obj = jsonSerializer.parseToJsonElement(json) as JsonObject
        return BedrockCreds(
            accessKey = (obj["access_key"] as? JsonPrimitive)?.content
                ?: throw ClaudeApiException(401, "Missing access_key in Bedrock credentials"),
            secretKey = (obj["secret_key"] as? JsonPrimitive)?.content
                ?: throw ClaudeApiException(401, "Missing secret_key in Bedrock credentials"),
            region = (obj["region"] as? JsonPrimitive)?.content ?: "us-east-1",
            sessionToken = (obj["session_token"] as? JsonPrimitive)?.content,
        )
    }

    private fun buildBedrockConverseRequest(request: ClaudeRequest): JsonObject {
        return buildJsonObject {
            // System prompt
            if (!request.system.isNullOrBlank()) {
                put("system", buildJsonArray {
                    add(buildJsonObject { put("text", request.system) })
                })
            }

            // Messages
            put("messages", buildJsonArray {
                for (message in request.messages) {
                    add(buildBedrockMessage(message))
                }
            })

            // Inference config
            put("inferenceConfig", buildJsonObject {
                put("maxTokens", request.maxTokens)
            })

            // Tool config
            if (request.tools != null && request.tools.isNotEmpty()) {
                put("toolConfig", buildJsonObject {
                    put("tools", buildJsonArray {
                        for (tool in request.tools) {
                            add(buildJsonObject {
                                put("toolSpec", buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("inputSchema", buildJsonObject {
                                        put("json", tool.inputSchema)
                                    })
                                })
                            })
                        }
                    })
                })
            }
        }
    }

    private fun buildBedrockMessage(
        message: ai.affiora.mobileclaw.data.model.ClaudeMessage,
    ): JsonObject {
        return buildJsonObject {
            put("role", message.role)
            put("content", buildJsonArray {
                when (val content = message.content) {
                    is ClaudeContent.Text -> {
                        add(buildJsonObject { put("text", content.text) })
                    }
                    is ClaudeContent.ToolResult -> {
                        add(buildJsonObject {
                            put("toolResult", buildJsonObject {
                                put("toolUseId", content.toolUseId)
                                put("content", buildJsonArray {
                                    add(buildJsonObject { put("text", content.content) })
                                })
                            })
                        })
                    }
                    is ClaudeContent.ContentList -> {
                        for (block in content.blocks) {
                            when (block) {
                                is ContentBlock.TextBlock -> {
                                    add(buildJsonObject { put("text", block.text) })
                                }
                                is ContentBlock.ToolUseBlock -> {
                                    add(buildJsonObject {
                                        put("toolUse", buildJsonObject {
                                            put("toolUseId", block.id)
                                            put("name", block.name)
                                            put("input", block.input)
                                        })
                                    })
                                }
                                is ContentBlock.ToolResultBlock -> {
                                    add(buildJsonObject {
                                        put("toolResult", buildJsonObject {
                                            put("toolUseId", block.toolUseId)
                                            put("content", buildJsonArray {
                                                add(buildJsonObject { put("text", block.content) })
                                            })
                                        })
                                    })
                                }
                                is ContentBlock.ImageBlock -> {
                                    add(buildJsonObject {
                                        put("image", buildJsonObject {
                                            put("format", when {
                                                block.source.mediaType.contains("png") -> "png"
                                                block.source.mediaType.contains("gif") -> "gif"
                                                block.source.mediaType.contains("webp") -> "webp"
                                                else -> "jpeg"
                                            })
                                            put("source", buildJsonObject {
                                                put("bytes", block.source.data)
                                            })
                                        })
                                    })
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    private fun convertBedrockToClaudeResponse(json: JsonObject, modelId: String): ClaudeResponse {
        val output = json["output"] as? JsonObject ?: error("No output in Bedrock response")
        val message = output["message"] as? JsonObject ?: error("No message in Bedrock output")
        val contentArr = message["content"] as? JsonArray ?: JsonArray(emptyList())
        val stopReason = (json["stopReason"] as? JsonPrimitive)?.content

        val contentBlocks = mutableListOf<ContentBlock>()

        for (item in contentArr) {
            val obj = item as? JsonObject ?: continue
            val text = (obj["text"] as? JsonPrimitive)?.content
            val toolUse = obj["toolUse"] as? JsonObject

            if (text != null) {
                contentBlocks.add(ContentBlock.TextBlock(text))
            }
            if (toolUse != null) {
                contentBlocks.add(ContentBlock.ToolUseBlock(
                    id = (toolUse["toolUseId"] as? JsonPrimitive)?.content ?: "bedrock-${System.currentTimeMillis()}",
                    name = (toolUse["name"] as? JsonPrimitive)?.content ?: "",
                    input = toolUse["input"] as? JsonObject ?: buildJsonObject {},
                ))
            }
        }

        // Map Bedrock stop reasons to Claude/Anthropic equivalents.
        // Bedrock returns: end_turn, tool_use, max_tokens, stop_sequence,
        // guardrail_intervened, content_filtered, malformed_model_output,
        // malformed_tool_use, model_context_window_exceeded
        val mappedStop = when (stopReason) {
            "end_turn", "tool_use", "max_tokens", "stop_sequence" -> stopReason
            "guardrail_intervened", "content_filtered" -> "stop_sequence"
            "malformed_tool_use", "malformed_model_output" -> "end_turn"
            "model_context_window_exceeded" -> "max_tokens"
            else -> stopReason ?: "end_turn"
        }

        return ClaudeResponse(
            id = "bedrock-${System.currentTimeMillis()}",
            model = modelId,
            role = "assistant",
            content = contentBlocks,
            stopReason = mappedStop,
        )
    }
}
