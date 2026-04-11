package ai.affiora.mobileclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import ai.affiora.mobileclaw.agent.AiModel
import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.DeviceCapability
import ai.affiora.mobileclaw.agent.LocalModelManager
import ai.affiora.mobileclaw.agent.ModelState
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
    private val toolRegistry: Map<String, @JvmSuppressWildcards ai.affiora.mobileclaw.tools.AndroidTool>,
    private val localModelManager: LocalModelManager,
) : ViewModel() {

    val selectedProvider: StateFlow<AiProvider> = userPreferences.selectedProvider
        .map { AiProvider.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiProvider.ANTHROPIC)

    val selectedModel: StateFlow<String> = userPreferences.selectedModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences.DEFAULT_MODEL)

    val deviceName: StateFlow<String> = userPreferences.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // Per-provider token states (loaded asynchronously in init)
    private val _providerTokens = MutableStateFlow<List<ProviderTokenState>>(emptyList())
    val providerTokens: StateFlow<List<ProviderTokenState>> = _providerTokens.asStateFlow()

    init {
        viewModelScope.launch {
            _providerTokens.value = loadProviderTokens()
        }
    }

    val permissionMode: StateFlow<PermissionManager.PermissionMode> = permissionManager.mode

    val allowedTools: StateFlow<Set<String>> = permissionManager.allowedTools

    /** True when no providers have a configured API key. */
    private val _noKeysConfigured = MutableStateFlow(false)
    val noKeysConfigured: StateFlow<Boolean> = _noKeysConfigured.asStateFlow()

    private val _clearHistoryCompleted = MutableStateFlow(false)
    val clearHistoryCompleted: StateFlow<Boolean> = _clearHistoryCompleted.asStateFlow()

    private suspend fun loadProviderTokens(): List<ProviderTokenState> {
        return AiProvider.entries
            .filter { !it.isLocal } // Local models don't use API tokens
            .map { provider ->
                val token = userPreferences.getTokenForProvider(provider.id)
                // CUSTOM providers are "configured" when baseUrl is set, not when token is set
                val configured = if (provider.requiresCustomBaseUrl) {
                    userPreferences.getBaseUrlForProvider(provider.id).isNotBlank()
                } else {
                    token.isNotBlank()
                }
                ProviderTokenState(
                    provider = provider,
                    token = token,
                    hasToken = configured,
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
                provider.models.firstOrNull()?.let { userPreferences.setSelectedModel(it.id) }
            }
            _providerTokens.value = loadProviderTokens()
            _noKeysConfigured.value = false
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
                    fallback.provider.models.firstOrNull()?.let { userPreferences.setSelectedModel(it.id) }
                }
            }
            val updated = loadProviderTokens()
            _providerTokens.value = updated
            _noKeysConfigured.value = updated.none { it.hasToken }
        }
    }

    /** Get the configured base URL for a provider (used by CUSTOM provider). */
    suspend fun getBaseUrlForProvider(providerId: String): String =
        userPreferences.getBaseUrlForProvider(providerId)

    /** Set the per-provider base URL override (used by CUSTOM provider). */
    fun updateBaseUrlForProvider(providerId: String, baseUrl: String) {
        viewModelScope.launch {
            userPreferences.setBaseUrlForProvider(providerId, baseUrl.trim())
            // Refresh hasToken state — for CUSTOM, hasToken depends on baseUrl
            _providerTokens.value = loadProviderTokens()
        }
    }

    /**
     * Fully clear a custom (self-hosted) provider's configuration.
     * Wipes base URL, token, and the selected model (if this provider was active),
     * then switches the active provider to the first other configured provider,
     * or to DEFAULT_PROVIDER if none are configured (so the "no keys" UI surfaces
     * correctly instead of silently leaving a broken CUSTOM active).
     */
    fun clearCustomProvider(providerId: String) {
        viewModelScope.launch {
            userPreferences.setBaseUrlForProvider(providerId, "")
            userPreferences.setTokenForProvider(providerId, "")
            // If the cleared provider was active, hand off to the first configured
            // fallback — or reset to DEFAULT_PROVIDER if nothing else is configured.
            val currentProvider = userPreferences.selectedProvider.first()
            if (currentProvider == providerId) {
                val updated = loadProviderTokens()
                val fallback = updated.firstOrNull { it.hasToken && it.provider.id != providerId }
                if (fallback != null) {
                    userPreferences.setSelectedProvider(fallback.provider.id)
                    fallback.provider.models.firstOrNull()?.let { userPreferences.setSelectedModel(it.id) }
                } else {
                    // No other provider configured — reset to defaults so the "no keys"
                    // onboarding UI surfaces instead of leaving CUSTOM active with a
                    // wiped config (which would throw "Base URL not configured" on send).
                    userPreferences.setSelectedProvider(UserPreferences.DEFAULT_PROVIDER)
                    userPreferences.setSelectedModel(UserPreferences.DEFAULT_MODEL)
                }
            }
            val updated = loadProviderTokens()
            _providerTokens.value = updated
            _noKeysConfigured.value = updated.none { it.hasToken }
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
            provider.models.firstOrNull()?.let { userPreferences.setSelectedModel(it.id) }
        }
    }

    fun updateProviderAndModel(provider: AiProvider, modelId: String) {
        viewModelScope.launch {
            userPreferences.setSelectedProvider(provider.id)
            userPreferences.setSelectedModel(modelId)
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

    /** All available tool names for the allowlist UI — derived from the actual tool registry. */
    val allToolNames: List<String> = toolRegistry.keys.sorted()

    // ── Local Models ────────────────────────────────────────────────

    val deviceCapability: StateFlow<DeviceCapability?> = MutableStateFlow(localModelManager.getDeviceCapability())

    private val _localModelStates = MutableStateFlow<Map<String, ModelState>>(
        localModelManager.getAllModelStates().mapValues { it.value.value }
    )
    val localModelStates: StateFlow<Map<String, ModelState>> = _localModelStates.asStateFlow()

    /** Number of downloaded local models (for settings subtitle). */
    val downloadedModelCount: Int
        get() = _localModelStates.value.count { it.value is ModelState.Downloaded }

    fun downloadModel(modelId: String) {
        val job = viewModelScope.launch {
            try {
                localModelManager.downloadModel(modelId).collect { progress ->
                    refreshLocalModelStates()
                }
            } catch (e: Exception) {
                refreshLocalModelStates()
            }
        }
        localModelManager.trackDownloadJob(modelId, job)
    }

    fun cancelDownload(modelId: String) {
        localModelManager.cancelDownload(modelId)
        refreshLocalModelStates()
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            localModelManager.deleteModel(modelId)
            refreshLocalModelStates()
        }
    }

    private fun refreshLocalModelStates() {
        _localModelStates.value = localModelManager.getAllModelStates().mapValues { it.value.value }
    }

    /** Include LOCAL_GEMMA in available models when a model is downloaded. */
    fun getAvailableModelsIncludingLocal(): List<Pair<AiProvider, AiModel>> {
        val cloudModels = getAvailableModels()
        val localModels = if (localModelManager.hasAnyDownloadedModel()) {
            AiProvider.LOCAL_GEMMA.models
                .filter { localModelManager.getModelPath(it.id) != null }
                .map { AiProvider.LOCAL_GEMMA to it }
        } else {
            emptyList()
        }
        return cloudModels + localModels
    }
}
