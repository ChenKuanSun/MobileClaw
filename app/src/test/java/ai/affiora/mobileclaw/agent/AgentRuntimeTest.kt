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
        every { apiClient.setLocalTools(any()) } returns Unit
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

    /** Collect all events, filtering out Raw* turn events for cleaner assertions. */
    private inline fun <reified T : AgentEvent> List<AgentEvent>.typed(): List<T> =
        filterIsInstance<T>()

    private fun List<AgentEvent>.withoutRaw(): List<AgentEvent> =
        filter { it !is AgentEvent.RawAssistantTurn && it !is AgentEvent.RawToolResultTurn }

    // -----------------------------------------------------------------------
    // Simple text response
    // -----------------------------------------------------------------------

    @Test
    fun `simple text response emits single Text event then completes`() = runTest {
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } returns
            textResponse(text = "Hello, world!")

        val rt = buildRuntime()
        val events = rt.run("Hi", emptyHistory, systemPrompt).toList().withoutRaw()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(AgentEvent.Text::class.java)
        assertThat((events[0] as AgentEvent.Text).text).isEqualTo("Hello, world!")
    }

    // -----------------------------------------------------------------------
    // Single tool use -> ToolCalling + ToolResult, then loop continues
    // -----------------------------------------------------------------------

    @Test
    fun `tool use emits ToolCalling and ToolResultEvent then continues loop`() = runTest {
        val toolInput = JsonObject(mapOf("query" to JsonPrimitive("weather")))

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } returnsMany listOf(
            toolUseResponse(toolName = "search", input = toolInput),
            textResponse(text = "The weather is sunny."),
        )

        val tool = mockTool("search", "Search the web")
        coEvery { tool.execute(any()) } returns ToolResult.Success("Sunny, 25C")

        val rt = buildRuntime(mapOf("search" to tool))
        val events = rt.run("What's the weather?", emptyHistory, systemPrompt).toList().withoutRaw()

        val calling = events.filterIsInstance<AgentEvent.ToolCalling>()
        assertThat(calling).hasSize(1)
        assertThat(calling[0].toolName).isEqualTo("search")
        assertThat(calling[0].input).containsEntry("query", JsonPrimitive("weather"))

        val results = events.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(1)
        assertThat(results[0].toolName).isEqualTo("search")
        assertThat(results[0].result).isInstanceOf(ToolResult.Success::class.java)
        assertThat((results[0].result as ToolResult.Success).data).isEqualTo("Sunny, 25C")

        val texts = events.filterIsInstance<AgentEvent.Text>()
        assertThat(texts).hasSize(1)
        assertThat(texts[0].text).isEqualTo("The weather is sunny.")
    }

    // -----------------------------------------------------------------------
    // NeedsConfirmation -> approve -> re-execute with __confirmed
    // -----------------------------------------------------------------------

    @Test
    fun `NeedsConfirmation approved re-executes with __confirmed and emits ToolResult`() = runTest {
        val toolInput = JsonObject(mapOf("to" to JsonPrimitive("+1234567890")))
        val requestId = "confirm_001"

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } returnsMany listOf(
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

        val events = mutableListOf<AgentEvent>()
        val job = launch {
            rt.run("Send a text", emptyHistory, systemPrompt).collect { event ->
                events.add(event)
                if (event is AgentEvent.NeedsConfirmation) {
                    launch { rt.confirmAction(requestId, true) }
                }
            }
        }
        job.join()

        val filtered = events.withoutRaw()
        val callings = filtered.filterIsInstance<AgentEvent.ToolCalling>()
        assertThat(callings).hasSize(1)
        assertThat(callings[0].toolName).isEqualTo("sms")

        val confirmations = filtered.filterIsInstance<AgentEvent.NeedsConfirmation>()
        assertThat(confirmations).hasSize(1)
        assertThat(confirmations[0].requestId).isEqualTo(requestId)
        assertThat(confirmations[0].preview).isEqualTo("Send SMS to +1234567890")

        val results = filtered.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(1)
        assertThat(results[0].toolName).isEqualTo("sms")
        assertThat(results[0].result).isInstanceOf(ToolResult.Success::class.java)
        assertThat((results[0].result as ToolResult.Success).data).isEqualTo("SMS sent successfully")

        val texts = filtered.filterIsInstance<AgentEvent.Text>()
        assertThat(texts).hasSize(1)
        assertThat(texts[0].text).isEqualTo("Message sent.")
    }

    // -----------------------------------------------------------------------
    // NeedsConfirmation -> deny -> emits cancelled
    // -----------------------------------------------------------------------

    @Test
    fun `NeedsConfirmation denied emits cancelled ToolResultEvent`() = runTest {
        val toolInput = JsonObject(mapOf("number" to JsonPrimitive("+1234567890")))
        val requestId = "confirm_002"

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } returnsMany listOf(
            toolUseResponse(toolName = "call", input = toolInput),
            textResponse(text = "Okay, I won't make the call."),
        )

        val tool = mockTool("call", "Make a phone call")
        coEvery { tool.execute(any()) } returns ToolResult.NeedsConfirmation(
            preview = "Call +1234567890",
            requestId = requestId,
        )

        val rt = buildRuntime(mapOf("call" to tool))

        val events = mutableListOf<AgentEvent>()
        val job = launch {
            rt.run("Call this number", emptyHistory, systemPrompt).collect { event ->
                events.add(event)
                if (event is AgentEvent.NeedsConfirmation) {
                    launch { rt.confirmAction(requestId, false) }
                }
            }
        }
        job.join()

        val filtered = events.withoutRaw()
        val callings = filtered.filterIsInstance<AgentEvent.ToolCalling>()
        assertThat(callings).hasSize(1)
        assertThat(callings[0].toolName).isEqualTo("call")

        val confirmations = filtered.filterIsInstance<AgentEvent.NeedsConfirmation>()
        assertThat(confirmations).hasSize(1)
        assertThat(confirmations[0].requestId).isEqualTo(requestId)

        val results = filtered.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(1)
        assertThat(results[0].toolName).isEqualTo("call")
        assertThat(results[0].result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((results[0].result as ToolResult.Error).message).isEqualTo("Action cancelled by user")

        val texts = filtered.filterIsInstance<AgentEvent.Text>()
        assertThat(texts).hasSize(1)
        assertThat(texts[0].text).isEqualTo("Okay, I won't make the call.")
    }

    // -----------------------------------------------------------------------
    // Max iterations safety limit
    // -----------------------------------------------------------------------

    @Test
    fun `max iterations safety limit emits Error after MAX_ITERATIONS loops`() = runTest {
        val toolInput = JsonObject(mapOf("q" to JsonPrimitive("loop")))

        // Every API call returns tool_use so the loop never breaks naturally
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } returns
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
        val events = rt.run("Loop forever", emptyHistory, systemPrompt).toList().withoutRaw()

        val callings = events.filterIsInstance<AgentEvent.ToolCalling>()
        assertThat(callings).hasSize(200)

        val results = events.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(200)

        val errors = events.filterIsInstance<AgentEvent.Error>()
        assertThat(errors).hasSize(1)
        assertThat(errors[0].message).contains("maximum iteration limit")
        assertThat(errors[0].message).contains("200")
    }

    // -----------------------------------------------------------------------
    // API error -> emits Error event
    // -----------------------------------------------------------------------

    @Test
    fun `API error emits Error event with status code and body`() = runTest {
        val errorBody = """{"error":{"type":"rate_limit_error","message":"Too many requests"}}"""
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } throws
            ClaudeApiException(429, errorBody)

        val rt = buildRuntime()
        val events = rt.run("Hello", emptyHistory, systemPrompt).toList()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(AgentEvent.Error::class.java)
        val error = events[0] as AgentEvent.Error
        assertThat(error.message).contains("429")
        assertThat(error.message).contains("rate_limit_error")
    }

    @Test
    fun `unexpected exception emits Error event`() = runTest {
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } throws
            RuntimeException("Connection refused")

        val rt = buildRuntime()
        val events = rt.run("Hello", emptyHistory, systemPrompt).toList()

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(AgentEvent.Error::class.java)
        val error = events[0] as AgentEvent.Error
        assertThat(error.message).contains("Connection refused")
    }

    // -----------------------------------------------------------------------
    // Unknown tool -> emits Error for that tool
    // -----------------------------------------------------------------------

    @Test
    fun `unknown tool emits ToolCalling then error ToolResultEvent`() = runTest {
        val toolInput = JsonObject(mapOf("x" to JsonPrimitive("y")))

        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } returnsMany listOf(
            toolUseResponse(
                toolName = "nonexistent_tool",
                toolUseId = "toolu_unknown",
                input = toolInput,
            ),
            textResponse(text = "Sorry, I couldn't do that."),
        )

        // Empty registry — no tools registered
        val rt = buildRuntime()
        val events = rt.run("Do something", emptyHistory, systemPrompt).toList().withoutRaw()

        val callings = events.filterIsInstance<AgentEvent.ToolCalling>()
        assertThat(callings).hasSize(1)
        assertThat(callings[0].toolName).isEqualTo("nonexistent_tool")

        val results = events.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(1)
        assertThat(results[0].toolName).isEqualTo("nonexistent_tool")
        assertThat(results[0].result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((results[0].result as ToolResult.Error).message).contains("Unknown tool")
        assertThat((results[0].result as ToolResult.Error).message).contains("nonexistent_tool")

        val texts = events.filterIsInstance<AgentEvent.Text>()
        assertThat(texts).hasSize(1)
        assertThat(texts[0].text).isEqualTo("Sorry, I couldn't do that.")
    }

    // -----------------------------------------------------------------------
    // RawAssistantTurn is emitted after each API response
    // -----------------------------------------------------------------------

    @Test
    fun `RawAssistantTurn is emitted after processing API response`() = runTest {
        coEvery { apiClient.sendMessage(any<ClaudeRequest>(), any(), any(), any()) } returns
            textResponse(text = "Hi!")

        val rt = buildRuntime()
        val events = rt.run("Hello", emptyHistory, systemPrompt).toList()

        val rawTurns = events.filterIsInstance<AgentEvent.RawAssistantTurn>()
        assertThat(rawTurns).hasSize(1)
    }
}
