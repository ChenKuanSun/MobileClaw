package ai.affiora.mobileclaw.agent.backend

import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.AuthType
import ai.affiora.mobileclaw.agent.ClaudeApiException
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.model.ContentBlock
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigEnabled
import com.anthropic.models.messages.ThinkingConfigParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class AnthropicBackend(
    private val jsonSerializer: Json,
) : AiBackend {

    companion object {
        private const val TAG = "AiApiClient"
        internal const val CLAUDE_CODE_SYSTEM_PREFIX =
            "You are Claude Code, Anthropic's official CLI for Claude."
    }

    override suspend fun send(
        request: ClaudeRequest,
        apiKey: String,
        provider: AiProvider,
        onTextDelta: ((String) -> Unit)?,
        onThinkingStarted: (() -> Unit)?,
        baseUrlOverride: String?,
    ): ClaudeResponse {
        val isAdaptiveModel = request.model.contains("4-6") || request.model.contains("4.6")
        val isOAuth = (provider.authType == AuthType.BEARER_TOKEN && provider.isAnthropic)
            || apiKey.startsWith("sk-ant-oat")
        val client = buildAnthropicClient(apiKey, provider, isAdaptiveModel, isOAuth)

        try {
            // Build system blocks
            val systemBlocks = mutableListOf<TextBlockParam>()

            if (isOAuth) {
                systemBlocks.add(
                    TextBlockParam.builder()
                        .text(CLAUDE_CODE_SYSTEM_PREFIX)
                        .build()
                )
            }

            if (!request.system.isNullOrBlank()) {
                systemBlocks.add(
                    TextBlockParam.builder()
                        .text(request.system)
                        .cacheControl(
                            CacheControlEphemeral.builder()
                                .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                                .build()
                        )
                        .build()
                )
            }

            // Build messages
            val messageParams = request.messages.map { msg -> toMessageParam(msg) }

            // Build tools
            val toolParams = request.tools?.map { tool -> toToolUnion(tool) } ?: emptyList()

            // Determine thinking budget
            val thinkingBudget = (request.maxTokens.toLong() / 2).coerceAtLeast(1024L)

            // Build params
            val paramsBuilder = MessageCreateParams.builder()
                .model(request.model)
                .maxTokens(request.maxTokens.toLong())
                .messages(messageParams)

            if (systemBlocks.isNotEmpty()) {
                paramsBuilder.systemOfTextBlockParams(systemBlocks)
            }

            // Inject tools as raw JSON — the SDK's typed Tool.InputSchema builder
            // loses nested property info (type, enum, description). Use raw JSON instead.
            if (request.tools != null && request.tools.isNotEmpty()) {
                val rawTools = request.tools.map { tool ->
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "input_schema" to kotlinxJsonToNative(tool.inputSchema),
                    )
                }
                paramsBuilder.putAdditionalBodyProperty("tools", JsonValue.from(rawTools))
            }

            // Enable server-side compaction — API automatically summarizes older context
            paramsBuilder.putAdditionalBodyProperty("context_management", JsonValue.from(mapOf(
                "edits" to listOf(mapOf("type" to "compact_20260112"))
            )))

            // Enable thinking — 4.6 models use adaptive, others use explicit budget
            if (isAdaptiveModel) {
                paramsBuilder.thinking(
                    ThinkingConfigParam.ofAdaptive(
                        ThinkingConfigAdaptive.builder().build()
                    )
                )
            } else {
                paramsBuilder.thinking(
                    ThinkingConfigParam.ofEnabled(
                        ThinkingConfigEnabled.builder()
                            .budgetTokens(thinkingBudget)
                            .build()
                    )
                )
            }

            val params = paramsBuilder.build()

            Log.d(TAG, "Anthropic SDK streaming request: model=${request.model}, messages=${request.messages.size}, tools=${request.tools?.size ?: 0}")

            // Stream the response — emit text deltas in real-time
            val contentBlocks = mutableListOf<ContentBlock>()
            var stopReason: String? = null
            var messageId = ""
            var model = ""
            var inputTokens = 0
            var outputTokens = 0
            val thinkingParts = StringBuilder()

            // Track current block being streamed
            val currentText = StringBuilder()
            val currentToolInput = StringBuilder()
            var currentToolId = ""
            var currentToolName = ""
            var currentBlockType = ""
            var thinkingEmitted = false

            withContext(Dispatchers.IO) {
                client.messages().createStreaming(params).use { stream ->
                    stream.stream().forEach { event ->
                        when {
                            event.isMessageStart() -> {
                                val msg = event.asMessageStart().message()
                                messageId = msg.id()
                                model = msg.model().toString()
                                val usage = msg.usage()
                                inputTokens = usage.inputTokens().toInt()
                            }
                            event.isContentBlockStart() -> {
                                val block = event.asContentBlockStart()
                                val contentBlock = block.contentBlock()
                                when {
                                    contentBlock.isText() -> currentBlockType = "text"
                                    contentBlock.isToolUse() -> {
                                        currentBlockType = "tool_use"
                                        currentToolId = contentBlock.asToolUse().id()
                                        currentToolName = contentBlock.asToolUse().name()
                                    }
                                    contentBlock.isThinking() -> {
                                        currentBlockType = "thinking"
                                        if (!thinkingEmitted) {
                                            thinkingEmitted = true
                                            onThinkingStarted?.invoke()
                                        }
                                    }
                                    else -> currentBlockType = "unknown"
                                }
                                currentText.clear()
                                currentToolInput.clear()
                            }
                            event.isContentBlockDelta() -> {
                                val delta = event.asContentBlockDelta().delta()
                                when {
                                    delta.isText() -> {
                                        val text = delta.asText().text()
                                        currentText.append(text)
                                        onTextDelta?.invoke(text)
                                    }
                                    delta.isInputJson() -> {
                                        currentToolInput.append(delta.asInputJson().partialJson())
                                    }
                                    delta.isThinking() -> {
                                        val thinking = delta.asThinking().thinking()
                                        thinkingParts.append(thinking)
                                    }
                                }
                            }
                            event.isContentBlockStop() -> {
                                when (currentBlockType) {
                                    "text" -> {
                                        val text = currentText.toString()
                                        if (text.isNotEmpty()) {
                                            contentBlocks.add(ContentBlock.TextBlock(text))
                                        }
                                    }
                                    "tool_use" -> {
                                        val inputJson = try {
                                            jsonSerializer.decodeFromString<JsonObject>(currentToolInput.toString())
                                        } catch (_: Exception) {
                                            buildJsonObject {}
                                        }
                                        contentBlocks.add(
                                            ContentBlock.ToolUseBlock(
                                                id = currentToolId,
                                                name = currentToolName,
                                                input = inputJson,
                                            )
                                        )
                                    }
                                    // thinking blocks: accumulated in thinkingParts, not added to content
                                }
                            }
                            event.isMessageDelta() -> {
                                val delta = event.asMessageDelta()
                                stopReason = delta.delta().stopReason()
                                    .map { it.toString().lowercase() }.orElse(null)
                                outputTokens = delta.usage().outputTokens().toInt()
                            }
                        }
                    }
                }
            }

            return ClaudeResponse(
                id = messageId,
                model = model,
                role = "assistant",
                content = contentBlocks,
                stopReason = stopReason,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                thinkingText = thinkingParts.toString().ifEmpty { null },
            )
        } catch (e: com.anthropic.errors.AnthropicServiceException) {
            Log.e(TAG, "Anthropic SDK error: ${e.statusCode()} ${e.message}")
            throw ClaudeApiException(e.statusCode(), e.message ?: "Unknown error")
        } catch (e: com.anthropic.errors.AnthropicIoException) {
            Log.e(TAG, "Anthropic SDK IO error: ${e.message}")
            throw ClaudeApiException(503, e.message ?: "Network error")
        } catch (e: com.anthropic.errors.AnthropicException) {
            Log.e(TAG, "Anthropic SDK exception: ${e.message}")
            throw ClaudeApiException(500, e.message ?: "SDK error")
        }
    }

    private fun buildAnthropicClient(apiKey: String, provider: AiProvider, isAdaptiveModel: Boolean, isOAuth: Boolean): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .baseUrl(provider.baseUrl)
            .maxRetries(0) // We handle retries ourselves

        // 4.6 models use adaptive thinking and don't need interleaved-thinking beta
        val interleavedBeta = if (isAdaptiveModel) "" else ",interleaved-thinking-2025-05-14"
        val compactBeta = ",compact-2026-01-12"

        if (isOAuth) {
            builder.authToken(apiKey)
            builder.putHeader("anthropic-beta", "claude-code-20250219,oauth-2025-04-20,fine-grained-tool-streaming-2025-05-14$interleavedBeta$compactBeta")
            builder.putHeader("user-agent", "claude-cli/2.1.75")
            builder.putHeader("x-app", "cli")
        } else {
            builder.apiKey(apiKey)
            builder.putHeader("anthropic-beta", "fine-grained-tool-streaming-2025-05-14$interleavedBeta$compactBeta")
        }
        builder.putHeader("anthropic-dangerous-direct-browser-access", "true")

        return builder.build()
    }

    /** Convert our ClaudeMessage to SDK MessageParam. */
    private fun toMessageParam(message: ai.affiora.mobileclaw.data.model.ClaudeMessage): MessageParam {
        val role = if (message.role == "assistant") MessageParam.Role.ASSISTANT else MessageParam.Role.USER

        return when (val content = message.content) {
            is ClaudeContent.Text -> {
                MessageParam.builder()
                    .role(role)
                    .content(MessageParam.Content.ofString(content.text))
                    .build()
            }
            is ClaudeContent.ToolResult -> {
                MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(
                        listOf(
                            ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(content.toolUseId)
                                    .content(content.content)
                                    .build()
                            )
                        )
                    )
                    .build()
            }
            is ClaudeContent.ContentList -> {
                val blockParams = content.blocks.map { block -> toContentBlockParam(block) }
                MessageParam.builder()
                    .role(role)
                    .contentOfBlockParams(blockParams)
                    .build()
            }
        }
    }

    /** Convert our ContentBlock to SDK ContentBlockParam. */
    private fun toContentBlockParam(block: ContentBlock): ContentBlockParam {
        return when (block) {
            is ContentBlock.TextBlock -> {
                ContentBlockParam.ofText(
                    TextBlockParam.builder()
                        .text(block.text)
                        .build()
                )
            }
            is ContentBlock.ToolUseBlock -> {
                val inputBuilder = ToolUseBlockParam.Input.builder()
                for ((key, value) in block.input) {
                    inputBuilder.putAdditionalProperty(key, kotlinxJsonToSdkJsonValue(value))
                }
                ContentBlockParam.ofToolUse(
                    ToolUseBlockParam.builder()
                        .id(block.id)
                        .name(block.name)
                        .input(inputBuilder.build())
                        .build()
                )
            }
            is ContentBlock.ToolResultBlock -> {
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(block.toolUseId)
                        .content(block.content)
                        .build()
                )
            }
            is ContentBlock.ImageBlock -> {
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(
                            ImageBlockParam.Source.ofBase64(
                                Base64ImageSource.builder()
                                    .mediaType(
                                        when (block.source.mediaType) {
                                            "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG
                                            "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF
                                            "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP
                                            else -> Base64ImageSource.MediaType.IMAGE_JPEG
                                        }
                                    )
                                    .data(block.source.data)
                                    .build()
                            )
                        )
                        .build()
                )
            }
        }
    }

    /**
     * Convert our ClaudeTool to SDK ToolUnion.
     *
     * We bypass the SDK's typed Tool.InputSchema builder because it doesn't
     * preserve nested JSON structure (type/enum/description inside properties
     * get lost). Instead we inject the raw JSON schema via putAdditionalBodyProperty.
     */
    private fun toToolUnion(tool: ai.affiora.mobileclaw.data.model.ClaudeTool): ToolUnion {
        val rawSchema = kotlinxJsonToNative(tool.inputSchema) as Map<*, *>
        Log.d(TAG, "Tool schema for '${tool.name}': $rawSchema")

        val toolBuilder = Tool.builder()
            .name(tool.name)
            .description(tool.description)
            // Use a minimal InputSchema to satisfy the builder requirement
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder().build())
                    .build()
            )

        // Override with raw JSON schema that preserves all nested structure
        toolBuilder.putAdditionalProperty("input_schema", JsonValue.from(rawSchema))

        return ToolUnion.ofTool(toolBuilder.build())
    }

    /** Convert kotlinx.serialization JsonElement to Anthropic SDK JsonValue. */
    private fun kotlinxJsonToSdkJsonValue(element: kotlinx.serialization.json.JsonElement): JsonValue {
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.isString -> JsonValue.from(element.content)
                    element.content == "true" -> JsonValue.from(true)
                    element.content == "false" -> JsonValue.from(false)
                    element.content == "null" -> JsonValue.from(null as String?)
                    element.content.contains('.') -> JsonValue.from(element.content.toDouble())
                    else -> JsonValue.from(element.content.toLongOrNull() ?: element.content)
                }
            }
            is JsonObject -> {
                val map = mutableMapOf<String, Any?>()
                for ((key, value) in element) {
                    map[key] = kotlinxJsonToNative(value)
                }
                JsonValue.from(map)
            }
            is JsonArray -> {
                val list = element.map { kotlinxJsonToNative(it) }
                JsonValue.from(list)
            }
        }
    }

    /** Convert kotlinx.serialization JsonElement to native Java types for JsonValue.from(). */
    private fun kotlinxJsonToNative(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content == "null" -> null
                    element.content.contains('.') -> element.content.toDouble()
                    else -> element.content.toLongOrNull() ?: element.content
                }
            }
            is JsonObject -> {
                val map = mutableMapOf<String, Any?>()
                for ((key, value) in element) {
                    map[key] = kotlinxJsonToNative(value)
                }
                map
            }
            is JsonArray -> element.map { kotlinxJsonToNative(it) }
        }
    }

    /** Convert Anthropic SDK JsonValue to kotlinx.serialization JsonObject. */
    private fun jsonValueToJsonObject(jsonValue: JsonValue): JsonObject {
        // SDK's JsonValue wraps a Jackson JsonNode internally.
        // Use Jackson's ObjectMapper to serialize to proper JSON string.
        return try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val jsonStr = mapper.writeValueAsString(jsonValue)
            jsonSerializer.decodeFromString<JsonObject>(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert JsonValue to JsonObject: $e")
            buildJsonObject {}
        }
    }
}
