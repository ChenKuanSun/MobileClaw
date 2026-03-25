package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.data.model.AgentEvent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.ToolResult
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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

        // call_log tool returns one missed call from 0912345678
        coEvery { callLogTool.execute(any()) } returns ToolResult.Success(
            """[{"number":"0912345678","type":"MISSED"}]"""
        )

        // sms tool: first call (no __confirmed) returns NeedsConfirmation;
        // second call (with __confirmed) returns Success
        val smsRequestId = "sms_confirm_001"
        coEvery { smsTool.execute(match { !it.containsKey("__confirmed") }) } returns
            ToolResult.NeedsConfirmation(
                preview = "Send SMS to 0912345678: \"Sorry I missed your call\"",
                requestId = smsRequestId,
            )
        coEvery { smsTool.execute(match { it.containsKey("__confirmed") }) } returns
            ToolResult.Success("SMS sent to 0912345678")

        // -- Setup mock Claude API responses (3 sequential turns) --
        val callLogInput = JsonObject(mapOf("type" to JsonPrimitive("MISSED")))
        val smsInput = JsonObject(
            mapOf(
                "to" to JsonPrimitive("0912345678"),
                "message" to JsonPrimitive("Sorry I missed your call"),
            )
        )

        val response1 = toolUseResponse(
            id = "msg_001",
            toolUseId = "toolu_001",
            toolName = "call_log",
            input = callLogInput,
        )
        val response2 = toolUseResponse(
            id = "msg_002",
            toolUseId = "toolu_002",
            toolName = "sms",
            input = smsInput,
        )
        val response3 = textResponse(
            id = "msg_003",
            text = "Done! SMS sent to all missed callers.",
        )

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returnsMany listOf(
            response1,
            response2,
            response3,
        )

        // -- Run the agent --
        val tools = mapOf("call_log" to callLogTool, "sms" to smsTool)
        val runtime = buildRuntime(tools)

        runtime.run(
            "Check missed calls and send SMS to each",
            emptyHistory,
            systemPrompt,
        ).test {
            // Iteration 1: call_log tool
            val event1 = awaitItem()
            assertThat(event1).isInstanceOf(AgentEvent.ToolCalling::class.java)
            val toolCalling1 = event1 as AgentEvent.ToolCalling
            assertThat(toolCalling1.toolName).isEqualTo("call_log")
            assertThat(toolCalling1.input).containsEntry("type", JsonPrimitive("MISSED"))

            val event2 = awaitItem()
            assertThat(event2).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
            val toolResult1 = event2 as AgentEvent.ToolResultEvent
            assertThat(toolResult1.toolName).isEqualTo("call_log")
            assertThat(toolResult1.result).isInstanceOf(ToolResult.Success::class.java)
            assertThat((toolResult1.result as ToolResult.Success).data).contains("0912345678")

            // Iteration 2: sms tool — triggers NeedsConfirmation
            val event3 = awaitItem()
            assertThat(event3).isInstanceOf(AgentEvent.ToolCalling::class.java)
            val toolCalling2 = event3 as AgentEvent.ToolCalling
            assertThat(toolCalling2.toolName).isEqualTo("sms")
            assertThat(toolCalling2.input).containsEntry("to", JsonPrimitive("0912345678"))

            val event4 = awaitItem()
            assertThat(event4).isInstanceOf(AgentEvent.NeedsConfirmation::class.java)
            val confirmation = event4 as AgentEvent.NeedsConfirmation
            assertThat(confirmation.toolName).isEqualTo("sms")
            assertThat(confirmation.requestId).isEqualTo(smsRequestId)
            assertThat(confirmation.preview).contains("0912345678")

            // Simulate user confirming the SMS from a separate coroutine
            launch { runtime.confirmAction(smsRequestId, true) }

            // After confirmation: ToolResultEvent with success
            val event5 = awaitItem()
            assertThat(event5).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
            val toolResult2 = event5 as AgentEvent.ToolResultEvent
            assertThat(toolResult2.toolName).isEqualTo("sms")
            assertThat(toolResult2.result).isInstanceOf(ToolResult.Success::class.java)
            assertThat((toolResult2.result as ToolResult.Success).data).contains("SMS sent")

            // Iteration 3: final text response
            val event6 = awaitItem()
            assertThat(event6).isInstanceOf(AgentEvent.Text::class.java)
            val finalText = event6 as AgentEvent.Text
            assertThat(finalText.text).isEqualTo("Done! SMS sent to all missed callers.")

            awaitComplete()
        }

        // Verify the API was called exactly 3 times (one per iteration)
        coVerify(exactly = 3) { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) }

        // Verify call_log was executed once
        coVerify(exactly = 1) { callLogTool.execute(any()) }

        // Verify sms was executed twice (once without __confirmed, once with)
        coVerify(exactly = 2) { smsTool.execute(any()) }
    }

    @Test
    @DisplayName("API error recovery: 429 rate limit emits Error event")
    fun `API error recovery emits Error event with status and body`() = runTest {
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } throws
            ClaudeApiException(429, "Rate limited")

        val runtime = buildRuntime(emptyMap())

        runtime.run("Check my calls", emptyHistory, systemPrompt).test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(AgentEvent.Error::class.java)

            val error = event as AgentEvent.Error
            assertThat(error.message).contains("429")
            assertThat(error.message).contains("Rate limited")

            awaitComplete()
        }

        coVerify(exactly = 1) { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) }
    }

    @Test
    @DisplayName("Max iterations safety: infinite tool_use loop is terminated after 10 iterations")
    fun `max iterations safety stops runaway agent loop`() = runTest {
        // A tool that always succeeds, paired with an API that always asks for more tool use
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
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returns infiniteToolUseResponse

        val runtime = buildRuntime(mapOf("echo" to echoTool))

        runtime.run("Loop forever", emptyHistory, systemPrompt).test {
            // Each of the 10 iterations emits: ToolCalling + ToolResultEvent = 2 events
            val events = mutableListOf<AgentEvent>()
            repeat(10) { i ->
                val calling = awaitItem()
                assertThat(calling).isInstanceOf(AgentEvent.ToolCalling::class.java)
                assertThat((calling as AgentEvent.ToolCalling).toolName).isEqualTo("echo")
                events.add(calling)

                val result = awaitItem()
                assertThat(result).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
                events.add(result)
            }

            // After 10 iterations, the agent emits a max-iteration error
            val errorEvent = awaitItem()
            assertThat(errorEvent).isInstanceOf(AgentEvent.Error::class.java)
            val error = errorEvent as AgentEvent.Error
            assertThat(error.message).contains("maximum iteration limit")
            assertThat(error.message).contains("10")

            awaitComplete()
        }

        // API was called exactly 10 times
        coVerify(exactly = 10) { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) }

        // Echo tool was executed exactly 10 times
        coVerify(exactly = 10) { echoTool.execute(any()) }
    }
}
