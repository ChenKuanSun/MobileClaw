package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.data.model.AgentEvent
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.model.ClaudeTool
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.data.model.ImageSource
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.ToolResult
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRuntime @Inject constructor(
    private val apiClient: ClaudeApiClient,
    private val toolRegistry: Map<String, @JvmSuppressWildcards AndroidTool>,
    private val userPreferences: UserPreferences,
    private val permissionManager: PermissionManager,
) {

    private val pendingConfirmations = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        // Pass tool registry to API client for local on-device inference
        apiClient.setLocalTools(toolRegistry)
    }

    fun run(
        userMessage: String,
        conversationHistory: List<ClaudeMessage>,
        systemPrompt: String,
        imageBase64: String? = null,
    ): Flow<AgentEvent> = flow {
        val model = userPreferences.selectedModel.first()

        val claudeTools = toolRegistry.values.map { tool ->
            ClaudeTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.parameters,
            )
        }

        val messages = conversationHistory.toMutableList()
        val userContent = if (imageBase64 != null) {
            ClaudeContent.ContentList(
                blocks = listOf(
                    ContentBlock.ImageBlock(
                        source = ImageSource(
                            mediaType = "image/jpeg",
                            data = imageBase64,
                        ),
                    ),
                    ContentBlock.TextBlock(text = userMessage),
                ),
            )
        } else {
            ClaudeContent.Text(userMessage)
        }
        messages.add(
            ClaudeMessage(
                role = "user",
                content = userContent,
            )
        )

        // Context length warning — server-side compaction handles actual trimming
        val historyChars = messages.sumOf { msg ->
            when (val c = msg.content) {
                is ClaudeContent.Text -> c.text.length
                else -> 500 // rough estimate for non-text
            }
        }
        when {
            historyChars > 200_000 -> emit(AgentEvent.Error("Approaching context limit. Use /compact or start a new conversation."))
            historyChars > 120_000 -> emit(AgentEvent.Error("Conversation getting long (~${historyChars / 4000}K tokens). Consider using /compact."))
        }

        var iterations = 0
        var consecutiveToolErrors = 0

        while (iterations < MAX_ITERATIONS) {
            iterations++

            val request = ClaudeRequest(
                model = model,
                messages = messages,
                system = systemPrompt,
                tools = claudeTools.ifEmpty { null },
                maxTokens = MAX_TOKENS,
            )

            val response = try {
                callApiStreaming(request)
            } catch (e: ClaudeApiException) {
                emit(AgentEvent.Error(formatApiError(e)))
                return@flow
            } catch (e: java.io.IOException) {
                // UnknownHostException / ConnectException / SocketTimeoutException all
                // extend IOException; formatNetworkError dispatches to the right hint.
                emit(AgentEvent.Error(formatNetworkError(e)))
                return@flow
            } catch (e: Exception) {
                emit(AgentEvent.Error("Unexpected error: ${e.message ?: "unknown"}"))
                return@flow
            }

            // Emit usage stats
            if (response.inputTokens > 0 || response.outputTokens > 0) {
                emit(AgentEvent.Usage(
                    inputTokens = response.inputTokens,
                    outputTokens = response.outputTokens,
                    model = model,
                ))
            }

            // Emit thinking text if present (full thinking for DB persistence)
            if (!response.thinkingText.isNullOrEmpty()) {
                emit(AgentEvent.Thinking(response.thinkingText))
            }

            val toolResultBlocks = mutableListOf<ContentBlock>()
            var hasToolUse = false

            for (block in response.content) {
                when (block) {
                    is ContentBlock.TextBlock -> {
                        // Text deltas were already emitted in real-time via streaming.
                        // Emit the full Text event for DB persistence.
                        emit(AgentEvent.Text(block.text))
                    }
                    is ContentBlock.ToolResultBlock -> {
                        // Server-echoed tool result — skip, we already processed it
                    }
                    is ContentBlock.ImageBlock -> {
                        // Server-echoed image block — skip
                    }
                    is ContentBlock.ToolUseBlock -> {
                        hasToolUse = true
                        val inputMap: Map<String, JsonElement> = block.input.toMap()
                            .filterKeys { it != "__confirmed" }  // STRIP — AI cannot set this
                        Log.d("AgentRuntime", "Tool call: ${block.name}")

                        emit(AgentEvent.ToolCalling(toolName = block.name, input = inputMap))

                        val tool = toolRegistry[block.name]
                        val rawResult: String

                        if (tool == null) {
                            rawResult = "Error: Unknown tool '${block.name}'"
                            emit(AgentEvent.ToolResultEvent(
                                toolName = block.name,
                                result = ToolResult.Error("Unknown tool: ${block.name}"),
                            ))
                            consecutiveToolErrors++
                        } else {
                            rawResult = executeAndEmit(tool, block.name, inputMap)
                            if (rawResult.startsWith("Error:") && !rawResult.contains("cancelled by user")) {
                                consecutiveToolErrors++
                            } else {
                                consecutiveToolErrors = 0
                            }
                        }

                        // FIX 6: Cap tool result size to prevent context overflow
                        val maxResultSize = 100_000 // 100KB
                        val resultContent = if (rawResult.length > maxResultSize) {
                            rawResult.take(maxResultSize) + "\n[Truncated: ${rawResult.length} chars total]"
                        } else {
                            rawResult
                        }

                        // Bail out if tools keep failing
                        if (consecutiveToolErrors >= 5) {
                            emit(AgentEvent.Error("Tools failed 5 times in a row. Stopping to prevent infinite loop."))
                            return@flow
                        }

                        toolResultBlocks.add(
                            ContentBlock.ToolResultBlock(
                                toolUseId = block.id,
                                content = resultContent,
                            )
                        )
                    }
                }
            }

            // Add assistant response
            val assistantMessage = ClaudeMessage(
                role = "assistant",
                content = ClaudeContent.ContentList(response.content),
            )
            messages.add(assistantMessage)
            emit(AgentEvent.RawAssistantTurn(assistantMessage))

            // Combine all tool results into a single user message (API requirement)
            if (toolResultBlocks.isNotEmpty()) {
                val toolResultMessage = ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.ContentList(toolResultBlocks),
                )
                messages.add(toolResultMessage)
                emit(AgentEvent.RawToolResultTurn(toolResultMessage))
            }

            if (response.stopReason == "end_turn" || !hasToolUse) {
                break
            }
        }

        if (iterations >= MAX_ITERATIONS) {
            emit(AgentEvent.Error("Agent reached maximum iteration limit ($MAX_ITERATIONS)"))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.executeAndEmit(
        tool: AndroidTool,
        toolName: String,
        inputMap: Map<String, JsonElement>,
    ): String {
        val result = try {
            tool.execute(inputMap)
        } catch (e: Exception) {
            ToolResult.Error("Tool execution failed: ${e.message ?: "unknown"}")
        }
        Log.d("AgentRuntime", "Tool $toolName result: $result")

        return handleResult(result, tool, toolName, inputMap)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.handleResult(
        result: ToolResult,
        tool: AndroidTool,
        toolName: String,
        inputMap: Map<String, JsonElement>,
    ): String {
        return when (result) {
            is ToolResult.Success -> {
                emit(AgentEvent.ToolResultEvent(toolName = toolName, result = result))
                result.data
            }
            is ToolResult.Error -> {
                emit(AgentEvent.ToolResultEvent(toolName = toolName, result = result))
                "Error: ${result.message}"
            }
            is ToolResult.NeedsConfirmation -> {
                val autoApproved = permissionManager.shouldAutoApprove(toolName)

                if (!autoApproved) {
                    val deferred = CompletableDeferred<Boolean>()
                    pendingConfirmations[result.requestId] = deferred

                    emit(AgentEvent.NeedsConfirmation(
                        toolName = toolName,
                        preview = result.preview,
                        requestId = result.requestId,
                    ))

                    val confirmed = try {
                        deferred.await()
                    } finally {
                        pendingConfirmations.remove(result.requestId)
                    }

                    if (!confirmed) {
                        val cancelResult = ToolResult.Error("Action cancelled by user")
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = cancelResult))
                        return "Action cancelled by user"
                    }
                } else {
                    Log.d("AgentRuntime", "Auto-approved tool: $toolName (mode=${permissionManager.mode.value})")
                }

                val confirmedParams = inputMap.toMutableMap()
                confirmedParams["__confirmed"] = JsonPrimitive(true)

                val confirmedResult = try {
                    tool.execute(confirmedParams)
                } catch (e: Exception) {
                    ToolResult.Error("Confirmed execution failed: ${e.message ?: "unknown"}")
                }

                when (confirmedResult) {
                    is ToolResult.Success -> {
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = confirmedResult))
                        confirmedResult.data
                    }
                    is ToolResult.Error -> {
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = confirmedResult))
                        "Error: ${confirmedResult.message}"
                    }
                    is ToolResult.NeedsConfirmation -> {
                        val errResult = ToolResult.Error("Tool requested confirmation again after being confirmed")
                        emit(AgentEvent.ToolResultEvent(toolName = toolName, result = errResult))
                        "Error: Tool requested confirmation again after being confirmed"
                    }
                }
            }
        }
    }

    /**
     * Run with pre-built conversation history (supports image content blocks).
     * The history must already include the final user message.
     */
    fun runWithHistory(
        conversationHistory: List<ClaudeMessage>,
        systemPrompt: String,
    ): Flow<AgentEvent> = flow {
        val model = userPreferences.selectedModel.first()

        val claudeTools = toolRegistry.values.map { tool ->
            ClaudeTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.parameters,
            )
        }

        val messages = conversationHistory.toMutableList()

        // Context length warning — server-side compaction handles actual trimming
        val historyChars = messages.sumOf { msg ->
            when (val c = msg.content) {
                is ClaudeContent.Text -> c.text.length
                else -> 500 // rough estimate for non-text
            }
        }
        when {
            historyChars > 200_000 -> emit(AgentEvent.Error("Approaching context limit. Use /compact or start a new conversation."))
            historyChars > 120_000 -> emit(AgentEvent.Error("Conversation getting long (~${historyChars / 4000}K tokens). Consider using /compact."))
        }

        var iterations = 0
        var consecutiveToolErrors = 0

        while (iterations < MAX_ITERATIONS) {
            iterations++

            val request = ClaudeRequest(
                model = model,
                messages = messages,
                system = systemPrompt,
                tools = claudeTools.ifEmpty { null },
                maxTokens = MAX_TOKENS,
            )

            val response = try {
                callApiStreaming(request)
            } catch (e: ClaudeApiException) {
                emit(AgentEvent.Error(formatApiError(e)))
                return@flow
            } catch (e: java.io.IOException) {
                // UnknownHostException / ConnectException / SocketTimeoutException all
                // extend IOException; formatNetworkError dispatches to the right hint.
                emit(AgentEvent.Error(formatNetworkError(e)))
                return@flow
            } catch (e: Exception) {
                emit(AgentEvent.Error("Unexpected error: ${e.message ?: "unknown"}"))
                return@flow
            }

            if (response.inputTokens > 0 || response.outputTokens > 0) {
                emit(AgentEvent.Usage(
                    inputTokens = response.inputTokens,
                    outputTokens = response.outputTokens,
                    model = model,
                ))
            }

            // Emit thinking text if present (full thinking for DB persistence)
            if (!response.thinkingText.isNullOrEmpty()) {
                emit(AgentEvent.Thinking(response.thinkingText))
            }

            val toolResultBlocks = mutableListOf<ContentBlock>()
            var hasToolUse = false

            for (block in response.content) {
                when (block) {
                    is ContentBlock.TextBlock -> {
                        // Text deltas were already emitted in real-time via streaming.
                        emit(AgentEvent.Text(block.text))
                    }
                    is ContentBlock.ToolResultBlock -> { /* skip */ }
                    is ContentBlock.ImageBlock -> { /* skip echoed image blocks */ }
                    is ContentBlock.ToolUseBlock -> {
                        hasToolUse = true
                        val inputMap: Map<String, JsonElement> = block.input.toMap()
                            .filterKeys { it != "__confirmed" }  // STRIP — AI cannot set this
                        emit(AgentEvent.ToolCalling(toolName = block.name, input = inputMap))

                        val tool = toolRegistry[block.name]
                        val rawResult: String

                        if (tool == null) {
                            rawResult = "Error: Unknown tool '${block.name}'"
                            emit(AgentEvent.ToolResultEvent(
                                toolName = block.name,
                                result = ToolResult.Error("Unknown tool: ${block.name}"),
                            ))
                            consecutiveToolErrors++
                        } else {
                            rawResult = executeAndEmit(tool, block.name, inputMap)
                            if (rawResult.startsWith("Error:")) {
                                consecutiveToolErrors++
                            } else {
                                consecutiveToolErrors = 0
                            }
                        }

                        // FIX 6: Cap tool result size to prevent context overflow
                        val maxResultSize = 100_000 // 100KB
                        val resultContent = if (rawResult.length > maxResultSize) {
                            rawResult.take(maxResultSize) + "\n[Truncated: ${rawResult.length} chars total]"
                        } else {
                            rawResult
                        }

                        if (consecutiveToolErrors >= 3) {
                            emit(AgentEvent.Error("Tools failed 3 times in a row. Stopping to prevent infinite loop."))
                            return@flow
                        }

                        toolResultBlocks.add(
                            ContentBlock.ToolResultBlock(
                                toolUseId = block.id,
                                content = resultContent,
                            )
                        )
                    }
                }
            }

            val assistantMessage = ClaudeMessage(
                role = "assistant",
                content = ClaudeContent.ContentList(response.content),
            )
            messages.add(assistantMessage)
            emit(AgentEvent.RawAssistantTurn(assistantMessage))

            if (toolResultBlocks.isNotEmpty()) {
                val toolResultMessage = ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.ContentList(toolResultBlocks),
                )
                messages.add(toolResultMessage)
                emit(AgentEvent.RawToolResultTurn(toolResultMessage))
            }

            if (response.stopReason == "end_turn" || !hasToolUse) {
                break
            }
        }

        if (iterations >= MAX_ITERATIONS) {
            emit(AgentEvent.Error("Agent reached maximum iteration limit ($MAX_ITERATIONS)"))
        }
    }

    /**
     * Call the API with real-time streaming: text deltas and thinking events are
     * emitted to the flow as they arrive from the SSE stream, instead of waiting
     * for the full response and then fake-chunking.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.callApiStreaming(
        request: ClaudeRequest,
    ): ClaudeResponse {
        // Channel bridges the IO-thread callbacks into this coroutine's flow context
        val deltaChannel = Channel<AgentEvent>(Channel.BUFFERED)

        return coroutineScope {
            val apiJob = launch {
                try {
                    val response = apiClient.sendMessage(
                        request,
                        onTextDelta = { text ->
                            deltaChannel.trySend(AgentEvent.TextDelta(text))
                        },
                        onThinkingStarted = {
                            deltaChannel.trySend(AgentEvent.Thinking(""))
                        },
                        onRetry = { attempt, statusCode, delayMs ->
                            val delaySec = delayMs / 1000
                            deltaChannel.trySend(AgentEvent.Error("Retrying (attempt $attempt) after error $statusCode — waiting ${delaySec}s..."))
                        },
                    )
                    // Signal completion by sending the response through the channel
                    deltaChannel.trySend(AgentEvent.StreamComplete(response))
                    deltaChannel.close()
                } catch (e: Exception) {
                    deltaChannel.close(e)
                }
            }

            var response: ClaudeResponse? = null
            for (event in deltaChannel) {
                when (event) {
                    is AgentEvent.StreamComplete -> {
                        response = event.response
                    }
                    else -> emit(event)
                }
            }

            // If channel was closed with an exception, the apiJob will throw
            apiJob.join()

            response ?: throw IllegalStateException("Stream ended without a response")
        }
    }

    fun confirmAction(requestId: String, confirmed: Boolean) {
        pendingConfirmations[requestId]?.complete(confirmed)
    }

    fun getToolNames(): List<String> = toolRegistry.keys.sorted()

    companion object {
        private const val MAX_ITERATIONS = 200
        private const val MAX_TOKENS = 8192
    }
}

/**
 * Format a ClaudeApiException into a user-facing error string, with hints for
 * known provider-specific failure modes (e.g. OpenRouter data-policy guardrails).
 *
 * Design notes on the string-matching gates:
 * - OpenRouter guardrail blocks return HTTP 404 with "guardrail" in the body.
 *   AWS Bedrock Guardrails (a separate product) return 400, not 404, so the
 *   status-code gate is the primary disambiguator; we also exclude bodies
 *   that mention "bedrock" to be safe.
 * - We match OpenRouter's exact phrase "insufficient credits" rather than the
 *   bare word "credit" to avoid misclassifying Stripe/billing errors from
 *   OpenAI/Mistral/Together that mention "credit card", "credit limit", etc.
 */
internal fun formatApiError(e: ClaudeApiException): String {
    val body = e.errorBody
    // OpenRouter: 404 "No endpoints available matching your guardrail restrictions and data policy"
    // is an account-side privacy setting, not a code bug. Surface the fix URL directly.
    if (e.statusCode == 404 &&
        body.contains("guardrail", ignoreCase = true) &&
        !body.contains("bedrock", ignoreCase = true)
    ) {
        return "OpenRouter rejected this model due to your data policy settings. " +
            "Open https://openrouter.ai/settings/privacy on the web, accept the data " +
            "policy for this model family, then retry. (Some free/China-hosted models " +
            "require explicit opt-in.)"
    }
    // OpenRouter: 402 "Insufficient credits" — exact phrase to avoid Stripe false positives
    if (e.statusCode == 402 && body.contains("insufficient credits", ignoreCase = true)) {
        return "Out of credits. Top up your provider account and retry."
    }
    // Bedrock cross-region: 403 AccessDeniedException with "is not authorized to perform: bedrock:InvokeModel"
    if (e.statusCode == 403 && body.contains("bedrock:InvokeModel", ignoreCase = true)) {
        return "Bedrock denied access to this model. Enable model access in the AWS " +
            "Bedrock console (Model access page) for your region, then retry."
    }
    return when (e.statusCode) {
        401 -> "API error (401): Unauthorized. Check your API key in Settings."
        429 -> "API error (429): $body Rate limited. Will retry automatically."
        else -> "API error (${e.statusCode}): $body"
    }
}

/**
 * Format a network-layer exception into a user-facing message that distinguishes
 * DNS failures (UnknownHostException) from connection refusals (ConnectException),
 * with extra hints for self-hosted endpoint scenarios.
 */
internal fun formatNetworkError(e: Throwable): String {
    val host = e.message?.substringBefore(":")?.trim().orEmpty()
    return when (e) {
        is java.net.UnknownHostException ->
            "Could not resolve the server hostname${if (host.isNotEmpty()) " ($host)" else ""}. " +
                "If using Tailscale, verify MagicDNS is enabled in the admin console " +
                "and the phone is connected to the tailnet. For LAN/Ollama, prefer " +
                "the raw IP (e.g. http://192.168.1.50:11434/v1) over a hostname."
        is java.net.ConnectException ->
            "Connection refused. The server isn't reachable. Common causes: " +
                "(1) the local server (Ollama / LM Studio / vLLM) isn't running; " +
                "(2) it's bound to 127.0.0.1 only — set OLLAMA_HOST=0.0.0.0:11434 or " +
                "enable LM Studio's network exposure; (3) the port is blocked by a firewall."
        is java.net.SocketTimeoutException ->
            "The server exceeded the ${HttpTimeouts.REQUEST_MINUTES}-minute response limit. " +
                "For reasoning models (DeepSeek R1, MiniMax M2, Nemotron Ultra, etc.) try " +
                "a simpler prompt or switch to a faster non-reasoning model. Also check " +
                "your network signal (if using Tailscale or another VPN, verify the " +
                "tunnel is up)."
        else ->
            "Network error: ${e.message ?: e.javaClass.simpleName}"
    }
}
