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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentRuntimeTest {

    private lateinit var apiClient: ClaudeApiClient
    private lateinit var userPreferences: UserPreferences
    private lateinit var permissionManager: PermissionManager
    private lateinit var runtime: AgentRuntime

    private val emptyHistory = emptyList<ClaudeMessage>()
    private val systemPrompt = "You are a test agent."
    private val testModel = "claude-sonnet-4-6"

    @BeforeEach
    fun setup() {
        apiClient = mockk()
        userPreferences = mockk()
        permissionManager = mockk()
        every { userPreferences.selectedModel } returns flowOf(testModel)
        every { permissionManager.shouldAutoApprove(any()) } returns false
    }

    private fun buildRuntime(tools: Map<String, AndroidTool> = emptyMap()): AgentRuntime {
        runtime = AgentRuntime(
            apiClient = apiClient,
            toolRegistry = tools,
            userPreferences = userPreferences,
            permissionManager = permissionManager,
        )
        return runtime
    }

    private fun textResponse(
        id: String = "msg_001",
        text: String = "Hello",
        stopReason: String = "end_turn",
    ) = ClaudeResponse(
        id = id,
        model = testModel,
        role = "assistant",
        content = listOf(ContentBlock.TextBlock(text = text)),
        stopReason = stopReason,
    )

    private fun toolUseResponse(
        id: String = "msg_002",
        toolUseId: String = "toolu_001",
        toolName: String = "search",
        input: JsonObject = JsonObject(emptyMap()),
        stopReason: String = "tool_use",
    ) = ClaudeResponse(
        id = id,
        model = testModel,
        role = "assistant",
        content = listOf(
            ContentBlock.ToolUseBlock(
                id = toolUseId,
                name = toolName,
                input = input,
            ),
        ),
        stopReason = stopReason,
    )

    private fun mockTool(
        name: String,
        description: String = "A test tool",
    ): AndroidTool = mockk<AndroidTool>().also {
        every { it.name } returns name
        every { it.description } returns description
        every { it.parameters } returns JsonObject(emptyMap())
    }

    // -----------------------------------------------------------------------
    // Simple text response
    // -----------------------------------------------------------------------

    @Test
    fun `simple text response emits single Text event then completes`() = runTest {
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returns
            textResponse(text = "Hello, world!")

        val rt = buildRuntime()
        rt.run("Hi", emptyHistory, systemPrompt).test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(AgentEvent.Text::class.java)
            assertThat((event as AgentEvent.Text).text).isEqualTo("Hello, world!")
            awaitComplete()
        }
    }

    // -----------------------------------------------------------------------
    // Single tool use -> ToolCalling + ToolResult, then loop continues
    // -----------------------------------------------------------------------

    @Test
    fun `tool use emits ToolCalling and ToolResultEvent then continues loop`() = runTest {
        val toolInput = JsonObject(mapOf("query" to JsonPrimitive("weather")))

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returnsMany listOf(
            toolUseResponse(toolName = "search", input = toolInput),
            textResponse(text = "The weather is sunny."),
        )

        val tool = mockTool("search", "Search the web")
        coEvery { tool.execute(any()) } returns ToolResult.Success("Sunny, 25C")

        val rt = buildRuntime(mapOf("search" to tool))
        rt.run("What's the weather?", emptyHistory, systemPrompt).test {
            // 1) ToolCalling
            val calling = awaitItem()
            assertThat(calling).isInstanceOf(AgentEvent.ToolCalling::class.java)
            assertThat((calling as AgentEvent.ToolCalling).toolName).isEqualTo("search")
            assertThat(calling.input).containsEntry("query", JsonPrimitive("weather"))

            // 2) ToolResultEvent with Success
            val result = awaitItem()
            assertThat(result).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
            val tre = result as AgentEvent.ToolResultEvent
            assertThat(tre.toolName).isEqualTo("search")
            assertThat(tre.result).isInstanceOf(ToolResult.Success::class.java)
            assertThat((tre.result as ToolResult.Success).data).isEqualTo("Sunny, 25C")

            // 3) Text from second API call
            val text = awaitItem()
            assertThat(text).isInstanceOf(AgentEvent.Text::class.java)
            assertThat((text as AgentEvent.Text).text).isEqualTo("The weather is sunny.")

            awaitComplete()
        }
    }

    // -----------------------------------------------------------------------
    // NeedsConfirmation -> approve -> re-execute with __confirmed
    // -----------------------------------------------------------------------

    @Test
    fun `NeedsConfirmation approved re-executes with __confirmed and emits ToolResult`() = runTest {
        val toolInput = JsonObject(mapOf("to" to JsonPrimitive("+1234567890")))
        val requestId = "confirm_001"

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returnsMany listOf(
            toolUseResponse(toolName = "sms", input = toolInput),
            textResponse(text = "Message sent."),
        )

        val tool = mockTool("sms", "Send SMS")
        coEvery { tool.execute(match { !it.containsKey("__confirmed") }) } returns
            ToolResult.NeedsConfirmation(
                preview = "Send SMS to +1234567890",
                requestId = requestId,
            )
        coEvery { tool.execute(match { it.containsKey("__confirmed") }) } returns
            ToolResult.Success("SMS sent successfully")

        val rt = buildRuntime(mapOf("sms" to tool))
        rt.run("Send a text", emptyHistory, systemPrompt).test {
            // 1) ToolCalling
            val calling = awaitItem()
            assertThat(calling).isInstanceOf(AgentEvent.ToolCalling::class.java)
            assertThat((calling as AgentEvent.ToolCalling).toolName).isEqualTo("sms")

            // 2) NeedsConfirmation
            val confirmation = awaitItem()
            assertThat(confirmation).isInstanceOf(AgentEvent.NeedsConfirmation::class.java)
            val nc = confirmation as AgentEvent.NeedsConfirmation
            assertThat(nc.requestId).isEqualTo(requestId)
            assertThat(nc.preview).isEqualTo("Send SMS to +1234567890")
            assertThat(nc.toolName).isEqualTo("sms")

            // Approve from another coroutine
            launch { rt.confirmAction(requestId, true) }

            // 3) ToolResultEvent with Success after confirmed re-execution
            val result = awaitItem()
            assertThat(result).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
            val tre = result as AgentEvent.ToolResultEvent
            assertThat(tre.toolName).isEqualTo("sms")
            assertThat(tre.result).isInstanceOf(ToolResult.Success::class.java)
            assertThat((tre.result as ToolResult.Success).data).isEqualTo("SMS sent successfully")

            // 4) Text from second API call
            val text = awaitItem()
            assertThat(text).isInstanceOf(AgentEvent.Text::class.java)
            assertThat((text as AgentEvent.Text).text).isEqualTo("Message sent.")

            awaitComplete()
        }
    }

    // -----------------------------------------------------------------------
    // NeedsConfirmation -> deny -> emits cancelled
    // -----------------------------------------------------------------------

    @Test
    fun `NeedsConfirmation denied emits cancelled ToolResultEvent`() = runTest {
        val toolInput = JsonObject(mapOf("number" to JsonPrimitive("+1234567890")))
        val requestId = "confirm_002"

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returnsMany listOf(
            toolUseResponse(toolName = "call", input = toolInput),
            textResponse(text = "Okay, I won't make the call."),
        )

        val tool = mockTool("call", "Make a phone call")
        coEvery { tool.execute(any()) } returns ToolResult.NeedsConfirmation(
            preview = "Call +1234567890",
            requestId = requestId,
        )

        val rt = buildRuntime(mapOf("call" to tool))
        rt.run("Call this number", emptyHistory, systemPrompt).test {
            // 1) ToolCalling
            val calling = awaitItem()
            assertThat(calling).isInstanceOf(AgentEvent.ToolCalling::class.java)
            assertThat((calling as AgentEvent.ToolCalling).toolName).isEqualTo("call")

            // 2) NeedsConfirmation
            val confirmation = awaitItem()
            assertThat(confirmation).isInstanceOf(AgentEvent.NeedsConfirmation::class.java)
            assertThat((confirmation as AgentEvent.NeedsConfirmation).requestId).isEqualTo(requestId)

            // Deny from another coroutine
            launch { rt.confirmAction(requestId, false) }

            // 3) ToolResultEvent with Error containing cancellation message
            val result = awaitItem()
            assertThat(result).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
            val tre = result as AgentEvent.ToolResultEvent
            assertThat(tre.toolName).isEqualTo("call")
            assertThat(tre.result).isInstanceOf(ToolResult.Error::class.java)
            assertThat((tre.result as ToolResult.Error).message).isEqualTo("Action cancelled by user")

            // 4) Text from second API call (Claude responds to cancellation)
            val text = awaitItem()
            assertThat(text).isInstanceOf(AgentEvent.Text::class.java)
            assertThat((text as AgentEvent.Text).text).isEqualTo("Okay, I won't make the call.")

            awaitComplete()
        }
    }

    // -----------------------------------------------------------------------
    // Max iterations safety limit
    // -----------------------------------------------------------------------

    @Test
    fun `max iterations safety limit emits Error after 10 loops`() = runTest {
        val toolInput = JsonObject(mapOf("q" to JsonPrimitive("loop")))

        // Every API call returns tool_use so the loop never breaks naturally
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returns
            toolUseResponse(
                id = "msg_loop",
                toolUseId = "toolu_loop",
                toolName = "echo",
                input = toolInput,
                stopReason = "tool_use",
            )

        val tool = mockTool("echo", "Echo input")
        coEvery { tool.execute(any()) } returns ToolResult.Success("echoed")

        val rt = buildRuntime(mapOf("echo" to tool))
        rt.run("Loop forever", emptyHistory, systemPrompt).test {
            // 10 iterations x (ToolCalling + ToolResultEvent) = 20 events
            repeat(10) { i ->
                val calling = awaitItem()
                assertThat(calling).isInstanceOf(AgentEvent.ToolCalling::class.java)
                assertThat((calling as AgentEvent.ToolCalling).toolName).isEqualTo("echo")

                val result = awaitItem()
                assertThat(result).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
                assertThat((result as AgentEvent.ToolResultEvent).result)
                    .isInstanceOf(ToolResult.Success::class.java)
            }

            // Final: max iterations error
            val error = awaitItem()
            assertThat(error).isInstanceOf(AgentEvent.Error::class.java)
            assertThat((error as AgentEvent.Error).message)
                .contains("maximum iteration limit")
            assertThat(error.message).contains("10")

            awaitComplete()
        }
    }

    // -----------------------------------------------------------------------
    // API error -> emits Error event
    // -----------------------------------------------------------------------

    @Test
    fun `API error emits Error event with status code and body`() = runTest {
        val errorBody = """{"error":{"type":"rate_limit_error","message":"Too many requests"}}"""
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } throws
            ClaudeApiException(429, errorBody)

        val rt = buildRuntime()
        rt.run("Hello", emptyHistory, systemPrompt).test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(AgentEvent.Error::class.java)
            val error = event as AgentEvent.Error
            assertThat(error.message).contains("429")
            assertThat(error.message).contains("rate_limit_error")
            awaitComplete()
        }
    }

    @Test
    fun `unexpected exception emits Error event`() = runTest {
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } throws
            RuntimeException("Connection refused")

        val rt = buildRuntime()
        rt.run("Hello", emptyHistory, systemPrompt).test {
            val event = awaitItem()
            assertThat(event).isInstanceOf(AgentEvent.Error::class.java)
            val error = event as AgentEvent.Error
            assertThat(error.message).contains("Connection refused")
            awaitComplete()
        }
    }

    // -----------------------------------------------------------------------
    // Unknown tool -> emits Error for that tool
    // -----------------------------------------------------------------------

    @Test
    fun `unknown tool emits ToolCalling then error ToolResultEvent`() = runTest {
        val toolInput = JsonObject(mapOf("x" to JsonPrimitive("y")))

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), anyOrNull(), anyOrNull()) } returnsMany listOf(
            toolUseResponse(
                toolName = "nonexistent_tool",
                toolUseId = "toolu_unknown",
                input = toolInput,
            ),
            textResponse(text = "Sorry, I couldn't do that."),
        )

        // Empty registry — no tools registered
        val rt = buildRuntime()
        rt.run("Do something", emptyHistory, systemPrompt).test {
            // 1) ToolCalling still emitted for the unknown tool
            val calling = awaitItem()
            assertThat(calling).isInstanceOf(AgentEvent.ToolCalling::class.java)
            assertThat((calling as AgentEvent.ToolCalling).toolName).isEqualTo("nonexistent_tool")

            // 2) ToolResultEvent with Error about unknown tool
            val result = awaitItem()
            assertThat(result).isInstanceOf(AgentEvent.ToolResultEvent::class.java)
            val tre = result as AgentEvent.ToolResultEvent
            assertThat(tre.toolName).isEqualTo("nonexistent_tool")
            assertThat(tre.result).isInstanceOf(ToolResult.Error::class.java)
            assertThat((tre.result as ToolResult.Error).message).contains("Unknown tool")
            assertThat((tre.result as ToolResult.Error).message).contains("nonexistent_tool")

            // 3) Text from second API call
            val text = awaitItem()
            assertThat(text).isInstanceOf(AgentEvent.Text::class.java)
            assertThat((text as AgentEvent.Text).text).isEqualTo("Sorry, I couldn't do that.")

            awaitComplete()
        }
    }
}
