package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.data.model.AgentEvent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.ToolResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Integration-style tests that verify the full agentic loop of AgentRuntime
 * with mock tools and mock Claude API. Simulates real multi-turn conversations.
 */
class AgentRuntimeIntegrationTest {

    private lateinit var apiClient: ClaudeApiClient
    private lateinit var userPreferences: UserPreferences
    private lateinit var permissionManager: PermissionManager

    private val emptyHistory = emptyList<ClaudeMessage>()
    private val systemPrompt = "You are an Android assistant that can check call logs and send SMS."

    @BeforeEach
    fun setup() {
        apiClient = mockk()
        userPreferences = mockk()
        permissionManager = mockk()
        every { userPreferences.selectedModel } returns flowOf("claude-sonnet-4-6")
        every { permissionManager.shouldAutoApprove(any()) } returns false
    }

    private fun buildRuntime(tools: Map<String, AndroidTool>): AgentRuntime {
        return AgentRuntime(
            apiClient = apiClient,
            toolRegistry = tools,
            userPreferences = userPreferences,
            permissionManager = permissionManager,
        )
    }

    private fun List<AgentEvent>.withoutRaw(): List<AgentEvent> =
        filter { it !is AgentEvent.RawAssistantTurn && it !is AgentEvent.RawToolResultTurn }

    // -- Helpers to build mock tools --

    private fun buildCallLogTool(): AndroidTool {
        val tool = mockk<AndroidTool>()
        every { tool.name } returns "call_log"
        every { tool.description } returns "Search the device call log"
        every { tool.parameters } returns JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "type" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Call type filter: MISSED, INCOMING, OUTGOING"),
                            )
                        ),
                    )
                ),
            )
        )
        return tool
    }

    private fun buildSmsTool(): AndroidTool {
        val tool = mockk<AndroidTool>()
        every { tool.name } returns "sms"
        every { tool.description } returns "Send an SMS message"
        every { tool.parameters } returns JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "to" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Phone number to send SMS to"),
                            )
                        ),
                        "message" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("SMS body"),
                            )
                        ),
                    )
                ),
            )
        )
        return tool
    }

    // -- Helpers to build Claude API responses --

    private fun toolUseResponse(
        id: String,
        toolUseId: String,
        toolName: String,
        input: JsonObject,
    ): ClaudeResponse = ClaudeResponse(
        id = id,
        model = "claude-sonnet-4-6",
        role = "assistant",
        content = listOf(
            ContentBlock.ToolUseBlock(
                id = toolUseId,
                name = toolName,
                input = input,
            ),
        ),
        stopReason = "tool_use",
    )

    private fun textResponse(id: String, text: String): ClaudeResponse = ClaudeResponse(
        id = id,
        model = "claude-sonnet-4-6",
        role = "assistant",
        content = listOf(ContentBlock.TextBlock(text = text)),
        stopReason = "end_turn",
    )

    @Test
    @DisplayName("Full E2E: Check missed calls and send SMS to each caller")
    fun `check missed calls and send SMS flow`() = runTest {
        // -- Setup mock tools --
        val callLogTool = buildCallLogTool()
        val smsTool = buildSmsTool()

        coEvery { callLogTool.execute(any()) } returns ToolResult.Success(
            """[{"number":"0912345678","type":"MISSED"}]"""
        )

        val smsRequestId = "sms_confirm_001"
        coEvery { smsTool.execute(match { !it.containsKey("__confirmed") }) } returns
            ToolResult.NeedsConfirmation(
                preview = "Send SMS to 0912345678: \"Sorry I missed your call\"",
                requestId = smsRequestId,
            )
        coEvery { smsTool.execute(match { it.containsKey("__confirmed") }) } returns
            ToolResult.Success("SMS sent to 0912345678")

        val callLogInput = JsonObject(mapOf("type" to JsonPrimitive("MISSED")))
        val smsInput = JsonObject(
            mapOf(
                "to" to JsonPrimitive("0912345678"),
                "message" to JsonPrimitive("Sorry I missed your call"),
            )
        )

        val response1 = toolUseResponse("msg_001", "toolu_001", "call_log", callLogInput)
        val response2 = toolUseResponse("msg_002", "toolu_002", "sms", smsInput)
        val response3 = textResponse("msg_003", "Done! SMS sent to all missed callers.")

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any()) } returnsMany listOf(
            response1, response2, response3,
        )

        val tools = mapOf("call_log" to callLogTool, "sms" to smsTool)
        val runtime = buildRuntime(tools)

        val events = mutableListOf<AgentEvent>()
        val job = launch {
            runtime.run("Check missed calls and send SMS to each", emptyHistory, systemPrompt)
                .collect { event ->
                    events.add(event)
                    if (event is AgentEvent.NeedsConfirmation && event.requestId == smsRequestId) {
                        launch { runtime.confirmAction(smsRequestId, true) }
                    }
                }
        }
        job.join()

        val filtered = events.withoutRaw()

        // Verify call_log tool was called and returned results
        val callings = filtered.filterIsInstance<AgentEvent.ToolCalling>()
        assertThat(callings).hasSize(2)
        assertThat(callings[0].toolName).isEqualTo("call_log")
        assertThat(callings[0].input).containsEntry("type", JsonPrimitive("MISSED"))
        assertThat(callings[1].toolName).isEqualTo("sms")
        assertThat(callings[1].input).containsEntry("to", JsonPrimitive("0912345678"))

        // Verify tool results
        val results = filtered.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(2)
        assertThat(results[0].toolName).isEqualTo("call_log")
        assertThat((results[0].result as ToolResult.Success).data).contains("0912345678")
        assertThat(results[1].toolName).isEqualTo("sms")
        assertThat((results[1].result as ToolResult.Success).data).contains("SMS sent")

        // Verify NeedsConfirmation was emitted
        val confirmations = filtered.filterIsInstance<AgentEvent.NeedsConfirmation>()
        assertThat(confirmations).hasSize(1)
        assertThat(confirmations[0].toolName).isEqualTo("sms")
        assertThat(confirmations[0].requestId).isEqualTo(smsRequestId)

        // Verify final text response
        val texts = filtered.filterIsInstance<AgentEvent.Text>()
        assertThat(texts.last().text).isEqualTo("Done! SMS sent to all missed callers.")

        // Verify the API was called exactly 3 times
        coVerify(exactly = 3) { apiClient.sendMessage(any<ClaudeRequest>(), any(), any()) }
        coVerify(exactly = 1) { callLogTool.execute(any()) }
        coVerify(exactly = 2) { smsTool.execute(any()) }
    }

    @Test
    @DisplayName("API error recovery: 429 rate limit emits Error event")
    fun `API error recovery emits Error event with status and body`() = runTest {
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any()) } throws
            ClaudeApiException(429, "Rate limited")

        val runtime = buildRuntime(emptyMap())
        val events = runtime.run("Check my calls", emptyHistory, systemPrompt).toList()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(AgentEvent.Error::class.java)
        val error = events[0] as AgentEvent.Error
        assertThat(error.message).contains("429")
        assertThat(error.message).contains("Rate limited")

        coVerify(exactly = 1) { apiClient.sendMessage(any<ClaudeRequest>(), any(), any()) }
    }

    @Test
    @DisplayName("Max iterations safety: infinite tool_use loop is terminated after 200 iterations")
    fun `max iterations safety stops runaway agent loop`() = runTest {
        val echoTool = mockk<AndroidTool>()
        every { echoTool.name } returns "echo"
        every { echoTool.description } returns "Echo back input"
        every { echoTool.parameters } returns JsonObject(emptyMap())
        coEvery { echoTool.execute(any()) } returns ToolResult.Success("echoed")

        val infiniteToolUseResponse = toolUseResponse(
            id = "msg_loop",
            toolUseId = "toolu_loop",
            toolName = "echo",
            input = JsonObject(mapOf("text" to JsonPrimitive("ping"))),
        )
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any()) } returns infiniteToolUseResponse

        val runtime = buildRuntime(mapOf("echo" to echoTool))
        val events = runtime.run("Loop forever", emptyHistory, systemPrompt).toList().withoutRaw()

        val callings = events.filterIsInstance<AgentEvent.ToolCalling>()
        assertThat(callings).hasSize(200)

        val results = events.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(200)

        val errors = events.filterIsInstance<AgentEvent.Error>()
        assertThat(errors).hasSize(1)
        assertThat(errors[0].message).contains("maximum iteration limit")
        assertThat(errors[0].message).contains("200")

        coVerify(exactly = 200) { apiClient.sendMessage(any<ClaudeRequest>(), any(), any()) }
        coVerify(exactly = 200) { echoTool.execute(any()) }
    }
}
