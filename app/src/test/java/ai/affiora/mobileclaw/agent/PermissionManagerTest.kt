package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.agent.PermissionManager.PermissionMode
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var userPreferences: UserPreferences
    private lateinit var permissionManager: PermissionManager

    private val permissionModeFlow = MutableStateFlow("default")
    private val allowedToolsFlow = MutableStateFlow<Set<String>>(emptySet())

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        userPreferences = mockk(relaxed = true)
        every { userPreferences.permissionMode } returns permissionModeFlow
        every { userPreferences.allowedTools } returns allowedToolsFlow
        permissionManager = PermissionManager(userPreferences)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `DEFAULT mode shouldAutoApprove returns false for any tool`() {
        permissionModeFlow.value = "default"
        assertThat(permissionManager.shouldAutoApprove("sms")).isFalse()
    }

    @Test
    fun `DEFAULT mode with session allow returns true for allowed tool`() {
        permissionModeFlow.value = "default"
        permissionManager.sessionAllow("sms")
        assertThat(permissionManager.shouldAutoApprove("sms")).isTrue()
    }

    @Test
    fun `BYPASS_ALL mode shouldAutoApprove returns true for anything`() {
        permissionModeFlow.value = "bypass_all"
        assertThat(permissionManager.shouldAutoApprove("anything")).isTrue()
    }

    @Test
    fun `ALLOWLIST mode returns false then true after allowTool`() {
        permissionModeFlow.value = "allowlist"
        assertThat(permissionManager.shouldAutoApprove("sms")).isFalse()

        // Simulate persisted allowlist update
        allowedToolsFlow.value = setOf("sms")
        assertThat(permissionManager.shouldAutoApprove("sms")).isTrue()
    }

    @Test
    fun `resetSession clears session allows`() {
        permissionModeFlow.value = "default"
        permissionManager.sessionAllow("sms")
        assertThat(permissionManager.shouldAutoApprove("sms")).isTrue()

        permissionManager.resetSession()
        assertThat(permissionManager.shouldAutoApprove("sms")).isFalse()
    }

    @Test
    fun `mode changes update shouldAutoApprove behavior`() {
        permissionModeFlow.value = "default"
        assertThat(permissionManager.shouldAutoApprove("sms")).isFalse()

        permissionModeFlow.value = "bypass_all"
        assertThat(permissionManager.shouldAutoApprove("sms")).isTrue()

        permissionModeFlow.value = "default"
        assertThat(permissionManager.shouldAutoApprove("sms")).isFalse()
    }

    @Test
    fun `ALLOWLIST mode session allow also works`() {
        permissionModeFlow.value = "allowlist"
        permissionManager.sessionAllow("call")
        assertThat(permissionManager.shouldAutoApprove("call")).isTrue()
        // But a non-session, non-allowlisted tool is still false
        assertThat(permissionManager.shouldAutoApprove("sms")).isFalse()
    }
}
