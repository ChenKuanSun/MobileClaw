package ai.affiora.mobileclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import ai.affiora.mobileclaw.agent.AiModel
import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.PermissionManager
import ai.affiora.mobileclaw.connectors.ConnectorConfig
import ai.affiora.mobileclaw.connectors.ConnectorManager
import ai.affiora.mobileclaw.connectors.ConnectorStatus
import ai.affiora.mobileclaw.connectors.ConnectorAuthType
import ai.affiora.mobileclaw.data.db.ChatMessageDao
import ai.affiora.mobileclaw.data.db.ConversationDao
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderTokenState(
    val provider: AiProvider,
    val token: String,
    val hasToken: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val chatMessageDao: ChatMessageDao,
    private val conversationDao: ConversationDao,
    private val permissionManager: PermissionManager,
    private val connectorManager: ConnectorManager,
) : ViewModel() {

    val selectedProvider: StateFlow<AiProvider> = userPreferences.selectedProvider
        .map { AiProvider.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiProvider.ANTHROPIC)

    val selectedModel: StateFlow<String> = userPreferences.selectedModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences.DEFAULT_MODEL)

    val deviceName: StateFlow<String> = userPreferences.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // Per-provider token states
    private val _providerTokens = MutableStateFlow(loadProviderTokens())
    val providerTokens: StateFlow<List<ProviderTokenState>> = _providerTokens.asStateFlow()

    val permissionMode: StateFlow<PermissionManager.PermissionMode> = permissionManager.mode

    val allowedTools: StateFlow<Set<String>> = permissionManager.allowedTools

    private val _clearHistoryCompleted = MutableStateFlow(false)
    val clearHistoryCompleted: StateFlow<Boolean> = _clearHistoryCompleted.asStateFlow()

    private fun loadProviderTokens(): List<ProviderTokenState> {
        return AiProvider.entries.map { provider ->
            val token = userPreferences.getTokenForProvider(provider.id)
            ProviderTokenState(
                provider = provider,
                token = token,
                hasToken = token.isNotBlank(),
            )
        }
    }

    fun updateTokenForProvider(providerId: String, token: String) {
        viewModelScope.launch {
            userPreferences.setTokenForProvider(providerId, token)
            _providerTokens.value = loadProviderTokens()
        }
    }

    fun addKey(providerId: String, token: String) {
        viewModelScope.launch {
            userPreferences.setTokenForProvider(providerId, token)
            // Auto-select this provider if current provider has no key
            val currentProvider = userPreferences.selectedProvider.first()
            if (userPreferences.getTokenForProvider(currentProvider).isBlank()) {
                val provider = AiProvider.fromId(providerId)
                userPreferences.setSelectedProvider(provider.id)
                userPreferences.setSelectedModel(provider.models.first().id)
            }
            _providerTokens.value = loadProviderTokens()
        }
    }

    fun removeKey(providerId: String) {
        viewModelScope.launch {
            userPreferences.setTokenForProvider(providerId, "")
            // If removing the active provider's key, switch to first provider with a key
            val currentProvider = userPreferences.selectedProvider.first()
            if (currentProvider == providerId) {
                val updated = loadProviderTokens()
                val fallback = updated.firstOrNull { it.hasToken }
                if (fallback != null) {
                    userPreferences.setSelectedProvider(fallback.provider.id)
                    userPreferences.setSelectedModel(fallback.provider.models.first().id)
                }
            }
            _providerTokens.value = loadProviderTokens()
        }
    }

    /** Models only from providers with configured keys. */
    fun getAvailableModels(): List<Pair<AiProvider, AiModel>> {
        return _providerTokens.value
            .filter { it.hasToken }
            .flatMap { state -> state.provider.models.map { state.provider to it } }
    }

    fun updateProvider(provider: AiProvider) {
        viewModelScope.launch {
            userPreferences.setSelectedProvider(provider.id)
            userPreferences.setSelectedModel(provider.models.first().id)
        }
    }

    fun updateSelectedModel(model: String) {
        viewModelScope.launch {
            userPreferences.setSelectedModel(model)
        }
    }

    fun updateDeviceName(name: String) {
        viewModelScope.launch {
            userPreferences.setDeviceName(name)
        }
    }

    fun clearConversationHistory() {
        viewModelScope.launch {
            val conversations = conversationDao.getAllConversations().first()
            conversations.forEach { conversation ->
                chatMessageDao.deleteByConversation(conversation.id)
                conversationDao.deleteById(conversation.id)
            }
            _clearHistoryCompleted.value = true
        }
    }

    fun dismissClearHistoryConfirmation() {
        _clearHistoryCompleted.value = false
    }

    fun setPermissionMode(mode: PermissionManager.PermissionMode) {
        permissionManager.setMode(mode)
    }

    fun toggleToolAllowed(toolName: String, allowed: Boolean) {
        if (allowed) {
            permissionManager.allowTool(toolName)
        } else {
            permissionManager.denyTool(toolName)
        }
    }

    // ── Connectors ──────────────────────────────────────────────────

    val connectorStatuses: StateFlow<List<Pair<ConnectorConfig, ConnectorStatus>>> =
        connectorManager.connectorStatuses

    fun startOAuthFlow(connector: ConnectorConfig): Intent? {
        return connectorManager.startOAuthFlow(connector)
    }

    fun handleOAuthCallback(intent: Intent) {
        viewModelScope.launch {
            connectorManager.handleOAuthCallback(intent)
        }
    }

    fun disconnectConnector(connectorId: String) {
        viewModelScope.launch {
            connectorManager.disconnect(connectorId)
        }
    }

    fun saveConnectorToken(connectorId: String, token: String) {
        viewModelScope.launch {
            connectorManager.saveToken(connectorId, token)
        }
    }

    fun saveConnectorClientId(connectorId: String, clientId: String) {
        viewModelScope.launch {
            connectorManager.saveClientId(connectorId, clientId)
        }
    }

    fun getConnectorClientId(connectorId: String): String {
        return connectorManager.getClientId(connectorId)
    }

    /** All available tool names for the allowlist UI. */
    val allToolNames: List<String> = listOf(
        "alarm_timer", "app", "brightness", "calendar", "call_log", "clipboard",
        "contacts", "filesystem", "flashlight", "media_control", "notifications",
        "phone_call", "screen_capture", "skills_author", "sms", "system_info",
        "ui_automation", "volume", "web",
    )
}
