package ai.affiora.mobileclaw.ui.chat

import android.app.Application
import ai.affiora.mobileclaw.agent.AgentRuntime
import ai.affiora.mobileclaw.agent.ContextCompactor
import ai.affiora.mobileclaw.agent.PermissionManager
import ai.affiora.mobileclaw.agent.SystemPromptBuilder
import ai.affiora.mobileclaw.agent.UsageTracker
import ai.affiora.mobileclaw.data.db.ChatMessageDao
import ai.affiora.mobileclaw.data.db.ChatMessageEntity
import ai.affiora.mobileclaw.data.db.ConversationDao
import ai.affiora.mobileclaw.data.db.ConversationEntity
import ai.affiora.mobileclaw.data.model.AgentEvent
import ai.affiora.mobileclaw.data.model.MessageRole
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.skills.SkillInstaller
import ai.affiora.mobileclaw.tools.ToolResult
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var application: Application
    private lateinit var agentRuntime: AgentRuntime
    private lateinit var systemPromptBuilder: SystemPromptBuilder
    private lateinit var chatMessageDao: ChatMessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var userPreferences: UserPreferences
    private lateinit var usageTracker: UsageTracker
    private lateinit var contextCompactor: ContextCompactor
    private lateinit var ttsManager: TtsManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var skillInstaller: SkillInstaller

    private val messagesFlow = MutableStateFlow<List<ChatMessageEntity>>(emptyList())

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        application = mockk(relaxed = true)
        agentRuntime = mockk(relaxed = true)
        systemPromptBuilder = mockk()
        chatMessageDao = mockk(relaxed = true)
        conversationDao = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)
        usageTracker = mockk(relaxed = true)
        contextCompactor = mockk(relaxed = true)
        ttsManager = mockk(relaxed = true)
        permissionManager = mockk(relaxed = true)
        skillInstaller = mockk(relaxed = true)

        every { chatMessageDao.getMessagesByConversation(any()) } returns messagesFlow
        every { userPreferences.selectedModel } returns flowOf("claude-sonnet-4-6")
        coEvery { conversationDao.getConversationById(any()) } returns null
        coEvery { conversationDao.insert(any()) } just Runs
        coEvery { chatMessageDao.insert(any()) } just Runs
        coEvery { systemPromptBuilder.build() } returns "test system prompt"
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(
            application = application,
            agentRuntime = agentRuntime,
            systemPromptBuilder = systemPromptBuilder,
            chatMessageDao = chatMessageDao,
            conversationDao = conversationDao,
            userPreferences = userPreferences,
            usageTracker = usageTracker,
            contextCompactor = contextCompactor,
            ttsManager = ttsManager,
            permissionManager = permissionManager,
            skillInstaller = skillInstaller,
            networkMonitor = mockk(relaxed = true),
        )
    }

    @Test
    fun `sendMessage adds user message to DB and triggers agent`() = runTest {
        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.Text("Hello back!"))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        val capturedMessages = mutableListOf<ChatMessageEntity>()
        coVerify(atLeast = 1) { chatMessageDao.insert(capture(capturedMessages)) }

        val userMessage = capturedMessages.first { it.role == MessageRole.USER }
        assertThat(userMessage.content).isEqualTo("Hello")
        assertThat(userMessage.role).isEqualTo(MessageRole.USER)

        // Agent should have been called
        verify { agentRuntime.run(eq("Hello"), any(), any()) }
    }

    @Test
    fun `sendMessage calls systemPromptBuilder build`() = runTest {
        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.Text("Response"))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        coVerify(exactly = 1) { systemPromptBuilder.build() }
        verify { agentRuntime.run(eq("Hello"), any(), eq("test system prompt")) }
    }

    @Test
    fun `sendMessage stores assistant response from agent`() = runTest {
        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.Text("I can help with that."))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Help me")
        advanceUntilIdle()

        val capturedMessages = mutableListOf<ChatMessageEntity>()
        coVerify(atLeast = 2) { chatMessageDao.insert(capture(capturedMessages)) }

        val assistantMessage = capturedMessages.first { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.content).isEqualTo("I can help with that.")
    }

    @Test
    fun `sendMessage sets isProcessing during agent execution`() = runTest {
        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.Text("Done"))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.isProcessing.test {
            assertThat(awaitItem()).isFalse()

            viewModel.sendMessage("Process this")
            assertThat(awaitItem()).isTrue()

            advanceUntilIdle()
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `confirmAction forwards to agentRuntime`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.NeedsConfirmation(
                toolName = "send_sms",
                preview = "Send SMS to +1234567890",
                requestId = "req-123",
            ))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        viewModel.sendMessage("Send a text")
        advanceUntilIdle()

        // Verify confirmation state is set
        assertThat(viewModel.pendingConfirmation.value).isNotNull()
        assertThat(viewModel.pendingConfirmation.value?.requestId).isEqualTo("req-123")
        assertThat(viewModel.pendingConfirmation.value?.toolName).isEqualTo("send_sms")

        // Confirm the action
        viewModel.confirmAction(confirmed = true)
        advanceUntilIdle()

        verify { agentRuntime.confirmAction("req-123", true) }
        assertThat(viewModel.pendingConfirmation.value).isNull()
    }

    @Test
    fun `confirmAction with cancel forwards false to agentRuntime`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.NeedsConfirmation(
                toolName = "delete_contact",
                preview = "Delete contact John",
                requestId = "req-456",
            ))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        viewModel.sendMessage("Delete John")
        advanceUntilIdle()

        viewModel.confirmAction(confirmed = false)
        advanceUntilIdle()

        verify { agentRuntime.confirmAction("req-456", false) }
    }

    @Test
    fun `error from agent is stored as assistant message`() = runTest {
        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.Error("API rate limit exceeded"))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Do something")
        advanceUntilIdle()

        val capturedMessages = mutableListOf<ChatMessageEntity>()
        coVerify(atLeast = 2) { chatMessageDao.insert(capture(capturedMessages)) }

        val errorMessage = capturedMessages.first { it.content.startsWith("Error:") }
        assertThat(errorMessage.role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(errorMessage.content).contains("API rate limit exceeded")
    }

    @Test
    fun `agent exception is caught and stored as error message`() = runTest {
        val agentFlow = flow<AgentEvent> {
            throw RuntimeException("Network failure")
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Try this")
        advanceUntilIdle()

        val capturedMessages = mutableListOf<ChatMessageEntity>()
        coVerify(atLeast = 2) { chatMessageDao.insert(capture(capturedMessages)) }

        val errorMessage = capturedMessages.first { it.content.contains("Network failure") }
        assertThat(errorMessage.role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(errorMessage.content).contains("Error:")
    }

    @Test
    fun `sendMessage is ignored when blank`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("   ")
        advanceUntilIdle()

        // Only the init DB subscription, no message inserts
        coVerify(exactly = 0) { chatMessageDao.insert(any()) }
    }

    @Test
    fun `sendMessage is ignored when already processing`() = runTest {
        // Create a flow that never completes to keep isProcessing = true
        val neverEndingFlow = flow<AgentEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        every { agentRuntime.run(any(), any(), any()) } returns neverEndingFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("First message")
        advanceUntilIdle()

        assertThat(viewModel.isProcessing.value).isTrue()

        // Try to send another message while processing
        viewModel.sendMessage("Second message")
        advanceUntilIdle()

        // Should only have inserted the first user message, not the second
        val capturedMessages = mutableListOf<ChatMessageEntity>()
        coVerify(atLeast = 1) { chatMessageDao.insert(capture(capturedMessages)) }

        val userMessages = capturedMessages.filter { it.role == MessageRole.USER }
        assertThat(userMessages).hasSize(1)
        assertThat(userMessages.first().content).isEqualTo("First message")
    }

    @Test
    fun `tool calling and result events create assistant host message with tool markers`() = runTest {
        val agentFlow = flow<AgentEvent> {
            emit(AgentEvent.ToolCalling(
                toolName = "sms_reader",
                input = mapOf("action" to kotlinx.serialization.json.JsonPrimitive("search")),
            ))
            emit(AgentEvent.ToolResultEvent(
                toolName = "sms_reader",
                result = ToolResult.Success(data = "3 unread messages"),
            ))
        }
        every { agentRuntime.run(any(), any(), any()) } returns agentFlow

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("Check my texts")
        advanceUntilIdle()

        val capturedMessages = mutableListOf<ChatMessageEntity>()
        coVerify(atLeast = 2) { chatMessageDao.insert(capture(capturedMessages)) }

        // ToolCalling creates an assistant host message with tool markers
        val assistantMessage = capturedMessages.first { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.content).contains("sms_reader")
    }

    @Test
    fun `loadConversation switches to new conversation`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val newConversationId = "conv-new-123"
        viewModel.loadConversation(newConversationId)
        advanceUntilIdle()

        // Verify it subscribed to the new conversation's messages
        verify { chatMessageDao.getMessagesByConversation(newConversationId) }
    }

    @Test
    fun `conversation is created on first message`() = runTest {
        coEvery { conversationDao.getConversationById(any()) } returns null
        every { agentRuntime.run(any(), any(), any()) } returns flowOf()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sendMessage("First message in conversation")
        advanceUntilIdle()

        val conversationSlot = slot<ConversationEntity>()
        coVerify { conversationDao.insert(capture(conversationSlot)) }
        assertThat(conversationSlot.captured.title).isEqualTo("First message in conversation")
    }
}
